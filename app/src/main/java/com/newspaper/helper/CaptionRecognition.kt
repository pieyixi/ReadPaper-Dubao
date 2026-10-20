package com.newspaper.helper

import org.jsoup.nodes.Element
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap

/** 只标记有图片关系的独立图注，不改写正文或按“摄、重要讲话”等词删除段落。 */
internal object CaptionRecognition {
    private val captionClasses=setOf("caption","figcaption","pictext","imgtext","photo-caption","picture-caption","image-caption","img-caption","pic-caption","pic-desc","img-desc","photo-desc","tuzhu")
    private val credit=Regex("^(?:(?:新华社(?:记者|发)?|本报(?:记者|通讯员)?|人民图片|南方日报(?:记者)?|通讯员|特约摄影|摄影)[\\s　]*)?(?:[\u3400-\u9fff·]{2,6}[\\s　]*){1,3}(?:[/／][\\s　]*)?(?:摄|摄影|拍摄|供图|制图|绘)[。.]?$")
    private val creditedTail=Regex("(?:新华社(?:记者|发)?|本报(?:记者|通讯员)|人民图片|南方日报记者|通讯员)[\\s　]*[\u3400-\u9fff·]{2,4}(?:[/／][\\s　]*)?(?:摄|摄影|拍摄|供图|制图|绘)[。.]?$")
    private fun normalized(text:String)=text.filterNot {it.isWhitespace() || it=='\u00a0' || it=='\u3000'}
    private fun style(node:Element)=node.attr("style").lowercase().replace(Regex("\\s+"),"")
    private fun centered(node:Element)=node.attr("align").equals("center",true) || "text-align:center" in style(node)
    private fun rightAligned(node:Element)=node.attr("align").equals("right",true) || "text-align:right" in style(node)
    private fun hasImage(node:Element,base:String)=node.select("img").any {
        val raw=it.attr("src").ifBlank {it.attr("data-src")}
        raw.isNotBlank() && ContentParser.safeUrl(base,raw)!=null
    }
    private fun imageOnly(node:Element,base:String)=hasImage(node,base) && node.text().isBlank()
    private fun previous(node:Element):Element? {
        var candidate=node.previousElementSibling()
        while(candidate!=null && candidate.text().isBlank() && candidate.select("img").isEmpty())candidate=candidate.previousElementSibling()
        return candidate
    }
    private fun imageBefore(node:Element,base:String,bridge:Int=0):Boolean {
        var current=node
        repeat(3) {
            var sibling=previous(current)
            var left=bridge
            while(sibling!=null) {
                if(imageOnly(sibling,base))return true
                if(left--<=0 || sibling.tagName() !in setOf("p","figcaption","div") || hasImage(sibling,base))return false
                sibling=previous(sibling)
            }
            current=current.parent() ?: return false
        }
        return false
    }
    fun identify(root:Element,base:String):Set<Element> {
        val marked=Collections.newSetFromMap(IdentityHashMap<Element,Boolean>())
        val qiushi=runCatching {URI(base).host?.lowercase() in setOf("www.qstheory.cn","qstheory.cn")}.getOrDefault(false)
        root.getAllElements().forEach {node->
            if(node.tagName() !in setOf("p","div","figcaption","td") || node.text().isBlank())return@forEach
            val text=node.text().trim();val full=normalized(text)
            // 行内样式仅在覆盖整段文字时才是证据，不能把同段其他正文连带标记。
            val covering=node.getAllElements().toList().filter {normalized(it.text())==full}
            val semantic=node.tagName()=="figcaption" || covering.any {e->e.classNames().any {it.lowercase() in captionClasses} || e.attr("role")=="caption"}
            val figure=node.parents().firstOrNull {it.tagName()=="figure"}
            val associated=imageBefore(node,base) || figure?.let {hasImage(it,base)}==true
            if(semantic && (associated || hasImage(node,base))) {marked+=node;return@forEach}
            if(hasImage(node,base))return@forEach
            val qiushiStyle=qiushi && covering.any {
                val css=style(it)
                ("fangsong" in css || "仿宋" in css || "kaiti" in css || "楷体" in css) &&
                    ("color:#000080" in css || "color:navy" in css || "color:rgb(0,0,128)" in css)
            }
            if(qiushiStyle && imageBefore(node,base)) {marked+=node;return@forEach}
            // 独立署名还需专门排版和近邻图片。跨过的新闻说明段保留，不猜测它也是图注。
            if(text.length<=100 && credit.matches(text) && (centered(node)||rightAligned(node)) && imageBefore(node,base,1)) {
                marked+=node;return@forEach
            }
            if(centered(node) && text.length<=1200 && creditedTail.containsMatchIn(text) && imageBefore(node,base))marked+=node
        }
        return marked
    }
}
