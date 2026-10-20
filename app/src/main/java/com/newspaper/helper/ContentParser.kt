package com.newspaper.helper

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI

object ContentParser {
    const val VERSION=3
    fun safeUrl(base: String, raw: String): String? = runCatching {
        val u = URI(base).resolve(raw.trim()).normalize()
        if (u.scheme !in listOf("http", "https") || u.host.isNullOrBlank() || u.userInfo != null) null
        else u.toASCIIString().let { if (it.startsWith("http://www.qstheory.cn")) it.replaceFirst("http:", "https:") else it }
    }.getOrNull()

    fun blocks(html: String, base: String): List<BodyBlock> {
        val root = Jsoup.parseBodyFragment(html, base).body()
        // 只移除明确的外围结构，不按新闻中的“广告、公告、债权”等词删正文。
        root.select("script,style,iframe,nav,header,footer,aside,form,button,.font_change,.share,.tool,.tools,.toolbar,.prev-next,.related,.copyright").remove()
        val captions=CaptionRecognition.identify(root,base)
        val result = mutableListOf<BodyBlock>()
        fun add(kind: BlockKind, text: String = "", url: String = "", parent: String = "",omit:Boolean=false) {
            val clean = text.trim(' ', '\t', '\n', '\r', '\u00a0', '\u3000')
            if (clean.isEmpty() && url.isEmpty()) return
            result += BodyBlock(stableId("$base|${result.size}|$kind|$clean|$url"), kind, clean, url, parent,omit)
        }
        fun visit(node: Node, inherited: BlockKind = BlockKind.PARAGRAPH, parent: String = "",inheritedOmit:Boolean=false) {
            if (node is TextNode) { add(inherited, node.text(), parent = parent,omit=inheritedOmit); return }
            if (node !is Element) return
            val omit=inheritedOmit || node in captions
            if (node.tagName() == "img") {
                val url = safeUrl(base, node.attr("src").ifBlank { node.attr("data-src") }) ?: return
                add(BlockKind.IMAGE, url = url, parent = parent,omit=omit)
                return
            }
            if (node.tagName() == "table") {
                val rows = node.select("tr").map { row -> row.select("th,td").joinToString("\t") { it.text() } }
                add(BlockKind.TABLE, rows.joinToString("\n"), parent = parent,omit=omit)
                node.select("img").forEach { visit(it, parent = result.lastOrNull()?.id.orEmpty()) }
                return
            }
            val kind = when {
                node.tagName().matches(Regex("h[1-6]")) -> BlockKind.TITLE
                node.tagName() == "figcaption" || node.className().contains("caption") || node.className().contains("pictext") -> BlockKind.CAPTION
                node.tagName() == "li" -> BlockKind.LIST
                node.tagName() == "blockquote" -> BlockKind.QUOTE
                else -> inherited
            }
            val group = if (node.tagName() in listOf("figure", "ul", "ol", "blockquote")) stableId("$base|group|${result.size}") else parent
            // 合并内联文本，但在图片、表格和块级边界立即刷新，保留原始交错顺序。
            val text = StringBuilder()
            fun flush() { add(kind, text.toString(), parent = group,omit=omit); text.clear() }
            fun inline(child: Node) {
                if (child is TextNode) text.append(child.text())
                else if (child is Element) {
                    if (child.tagName() == "br") text.append('\n')
                    else if (child.isBlock || child.tagName() == "img") { flush(); visit(child, kind, group,omit) }
                    else child.childNodes().forEach { inline(it) }
                }
            }
            node.childNodes().forEach { inline(it) }
            flush()
        }
        visit(root)
        return result
    }

    fun article(story: Story, html: String): Article {
        val doc = Jsoup.parse(html, story.url)
        val title = listOf("#news_content h1", ".article-title", ".article h1", ".inner h1", "h1").firstNotNullOfOrNull { doc.selectFirst(it)?.text()?.takeIf(String::isNotBlank) }
            ?: story.title.ifBlank { doc.title() }
        val author = listOfNotNull(
            Regex("<author>([^<]+)</author>").find(html)?.groupValues?.get(1),
            doc.select(".datesource").firstOrNull { it.text().contains("字数") }?.text()?.substringBefore("字数"),
            doc.select(".appellation").firstOrNull { it.text().startsWith("作者") }?.text()?.replace(Regex("^作者[：:]\\s*"),""),
            doc.selectFirst("meta[name=author]")?.attr("content"), story.author
        ).map(String::trim).firstOrNull { it.isNotBlank() && !it.matches(Regex("第\\s*\\d+\\s*版")) && it != story.paper.label }.orEmpty()
        val published = Regex("\\d{4}-\\d{2}-\\d{2}").find(doc.select(".pubtime").text())?.value
        val selector = when (story.paper) {
            Paper.STUDY -> "cms-content#content, #content"
            Paper.PEOPLE -> "#ozoom"
            Paper.SOUTH -> "#content"
            Paper.QIUSHI -> "#detailContent, .clipboard_text, .inner .text"
            Paper.ORGANIZATION -> "body"
        }
        // 逗号选择器按 DOM 顺序返回，不按书写优先级；求是外层 .text 会把二维码、编辑栏一起选中。
        val content = selector.split(',').firstNotNullOfOrNull {doc.selectFirst(it.trim())}?.clone()
        content?.select("h1,.sec,.datesource,.article-title,.article-meta,.appellation,.pubtime")?.remove()
        if(story.paper==Paper.QIUSHI)content?.select(".xl_ewm,.sharebox,.fs-text,.fs-line,.fs-line_b,.fs-pinglun,#videoArea")?.remove()
        val raw = content?.html() ?: Regex("<!--enpcontent-->([\\s\\S]*?)<!--/enpcontent-->").find(html)?.groupValues?.get(1)
            ?: error("Body selector missing: ${story.url}")
        val body = blocks(raw, story.url)
        check(body.isNotEmpty()) { "Empty body: ${story.url}" }
        return Article(story.copy(title = title, author = author, date = published ?: story.date), body)
    }
}
