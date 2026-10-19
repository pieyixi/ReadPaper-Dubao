package com.newspaper.helper

import java.security.MessageDigest

internal object NoteText {
    // 不依赖 API 24 的 String.codePoints；表情按完整码点统计，空白不计入字数。
    fun count(value:String):Int {
        var count=0;var offset=0
        while(offset<value.length) {
            val point=Character.codePointAt(value,offset)
            if(!Character.isWhitespace(point)&&!Character.isSpaceChar(point))count++
            offset+=Character.charCount(point)
        }
        return count
    }
}

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
data class Article(val story: Story, val blocks: List<BodyBlock>,val parserVersion:Int=ContentParser.VERSION)
data class SourceResult<T>(val items: List<T>, val failures: List<String> = emptyList()) {
    val complete get() = failures.isEmpty()
}
fun stableId(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

data class SpeechSegment(val articleId: String, val blockId: String, val text: String, val index: Int,
    val start: Int = 0, val end: Int = start + text.length)
object Segmenter {
    const val VERSION = "sentences-v2"
    // 显示和朗读共用正文块。图注朗读，图片和空白不生成音频；用户文本不经过新闻清洗。
    fun split(article: Article, limit: Int = 320): List<SpeechSegment> {
        require(limit >= 2)
        val out = mutableListOf<SpeechSegment>()
        article.blocks.filter { it.kind != BlockKind.IMAGE }.forEach { block ->
            var start = 0
            while (start < block.text.length) {
                val end = boundary(block.text,start,limit,out.isEmpty())
                val text = block.text.substring(start, end)
                if (text.isNotBlank()) out += SpeechSegment(article.story.url, block.id, text, out.size,start,end)
                start = end
            }
        }
        return out
    }
    private fun boundary(text:String,start:Int,limit:Int,first:Boolean):Int {
        var hard=minOf(start+limit,text.length)
        if(hard<text.length && Character.isHighSurrogate(text[hard-1]) && Character.isLowSurrogate(text[hard])) hard--
        val goal=minOf(start+(if(first) 80 else 220),hard)
        if(text.length<=goal) return text.length
        val sentences=mutableListOf<Int>()
        for(i in start until hard) {
            val ch=text[i]
            val period=ch=='.' && (i+1==text.length || text[i+1].isWhitespace()) &&
                !text.substring(start,i).takeLastWhile { it.isLetter() || it=='.' }.let {
                    it.length==1 || it.lowercase() in setOf("mr","mrs","ms","dr","prof","e.g","i.e")
                }
            if(ch in "。！？!?\n" || period) {
                var end=i+1
                while(end<hard && text[end] in "”’\"')）】》") end++
                sentences+=end
            }
        }
        // 优先保留完整句群；首段较短以便尽快出声。小数点、版本号不作为句界。
        if(first) sentences.firstOrNull()?.let { return it }
        sentences.lastOrNull { it<=goal }?.let { return it }
        sentences.firstOrNull { it>goal }?.let { return it }
        (goal-1 downTo start+(goal-start)/2).firstOrNull { text[it] in "，,；;：:\t " }?.let { return it+1 }
        if(text.length<=hard) return text.length
        var end=hard
        fun word(c:Char)=c.isLetterOrDigit() && c.code<128 || c in ".%:_-/"
        while(end>start && end<text.length && word(text[end-1]) && word(text[end])) end--
        return if(end>start) end else hard
    }
    // 仅为 2.0.0 的片段序号恢复原文锚点；不再沿用旧的片内毫秒。
    fun legacyAnchor(article:Article,index:Int):Pair<String,Int>? {
        var count=0
        article.blocks.filter { it.kind!=BlockKind.IMAGE }.forEach { block ->
            var start=0
            while(start<block.text.length) {
                var end=minOf(start+1800,block.text.length)
                if(end<block.text.length) {
                    (end-1 downTo start+900).firstOrNull { block.text[it] in "。！？；.!?;\n" }?.let { end=it+1 }
                    if(Character.isHighSurrogate(block.text[end-1])) end--
                }
                if(block.text.substring(start,end).isNotBlank() && count++==index) return block.id to start
                start=end
            }
        }
        return null
    }
    fun custom(text: String): Article = Article(Story(Paper.STUDY, "custom:" + stableId(text), ""),
        text.split('\n').mapIndexedNotNull { i, line -> if (line.isBlank()) null else BodyBlock("custom-$i", BlockKind.PARAGRAPH, line) })
}
