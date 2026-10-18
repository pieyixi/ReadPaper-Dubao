package com.newspaper.helper

import java.security.MessageDigest

enum class Paper(val label: String, val subtitle: String, val colors: IntArray) {
    STUDY("学习时报", "中共中央党校主办", intArrayOf(0xff1a5276.toInt(), 0xff2c6a96.toInt())),
    ORGANIZATION("组织人事报", "中国人事报刊社", intArrayOf(0xff6b3a2e.toInt(), 0xff8b5545.toInt())),
    PEOPLE("人民日报", "中共中央机关报", intArrayOf(0xffc0392b.toInt(), 0xffe0513e.toInt())),
    SOUTH("南方日报", "中共广东省委机关报", intArrayOf(0xff2c7a3a.toInt(), 0xff3d9e4e.toInt())),
    QIUSHI("求是", "中共中央主办", intArrayOf(0xff8b1a1a.toInt(), 0xffb22222.toInt()))
}
data class Issue(val label: String, val url: String, val date: String = label)
data class Story(val paper: Paper, val url: String, val title: String, val section: String = "", val date: String = "", val author: String = "")
enum class BlockKind { TITLE, PARAGRAPH, IMAGE, CAPTION, LIST, QUOTE, TABLE }
data class BodyBlock(val id: String, val kind: BlockKind, val text: String = "", val url: String = "", val parent: String = "")
data class Article(val story: Story, val blocks: List<BodyBlock>)
data class SourceResult<T>(val items: List<T>, val failures: List<String> = emptyList()) {
    val complete get() = failures.isEmpty()
}
fun stableId(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

data class SpeechSegment(val articleId: String, val blockId: String, val text: String, val index: Int)
object Segmenter {
    // 显示和朗读共用正文块。图注朗读，图片和空白不生成音频；用户文本不经过新闻清洗。
    fun split(article: Article, limit: Int = 1800): List<SpeechSegment> {
        require(limit >= 2)
        val out = mutableListOf<SpeechSegment>()
        article.blocks.filter { it.kind != BlockKind.IMAGE }.forEach { block ->
            var start = 0
            while (start < block.text.length) {
                var end = minOf(start + limit, block.text.length)
                if (end < block.text.length) {
                    val boundary = (end - 1 downTo start + limit / 2).firstOrNull { block.text[it] in "。！？；.!?;\n" }
                    if (boundary != null) end = boundary + 1
                    if (end < block.text.length && Character.isHighSurrogate(block.text[end - 1])) end--
                }
                val text = block.text.substring(start, end)
                if (text.isNotBlank()) out += SpeechSegment(article.story.url, block.id, text, out.size)
                start = end
            }
        }
        return out
    }
    fun custom(text: String): Article = Article(Story(Paper.STUDY, "custom:" + stableId(text), ""),
        text.split('\n').mapIndexedNotNull { i, line -> if (line.isBlank()) null else BodyBlock("custom-$i", BlockKind.PARAGRAPH, line) })
}
