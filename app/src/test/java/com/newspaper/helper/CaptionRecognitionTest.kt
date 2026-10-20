package com.newspaper.helper

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CaptionRecognitionTest {
    private val base="https://www.qstheory.cn/20260930/caption/c.html"
    private fun article(html:String,url:String=base)=Article(Story(Paper.QIUSHI,url,""),ContentParser.blocks(html,url))
    private fun speech(a:Article)=Segmenter.split(a).joinToString("") {it.text}
    private val engine=object:SpeechEngine {
        override suspend fun synthesize(text:String,file:File)=Unit
        override fun cancel()=Unit
        override fun close()=Unit
    }
    @Test fun fiveSourceOriginalMarkupKeepsActualBodyAndAnnotatesOnlyVerifiedCaptions() {
        val cases=org.json.JSONArray(checkNotNull(javaClass.getResource("/captions/provenance.json")).readText())
        fun normalize(text:String)=text.filterNot {it.isWhitespace()||it=='\u00a0'||it=='\u3000'}
        for(index in 0 until cases.length()) {
            val item=cases.getJSONObject(index)
            val html=checkNotNull(javaClass.getResource("/captions/"+item.getString("file"))).readText()
            val a=Article(Story(Paper.valueOf(item.getString("paper")),item.getString("url"),""),ContentParser.blocks(html,item.getString("url")))
            val expected=item.getJSONArray("expectedOmitted").let {list->(0 until list.length()).map {normalize(list.getString(it))}}
            assertEquals(item.getString("paper"),expected,a.blocks.filter {it.omitFromSpeech&&it.kind!=BlockKind.IMAGE}.map {normalize(it.text)})
            expected.forEach {caption->
                assertTrue(a.blocks.any {normalize(it.text)==caption})
                assertFalse(normalize(speech(a)).contains(caption))
            }
            item.getJSONArray("expectedBody").let {list->for(i in 0 until list.length())assertTrue(item.getString("paper"),normalize(speech(a)).contains(normalize(list.getString(i))))}
        }
    }
    @Test fun explicitFigureCaptionIsVisibleWithUnchangedMappingButAbsentFromSpeech() {
        val a=article("<p>图前正文。</p><figure><img src='photo.jpg'><figcaption>在某地发表重要讲话。新华社记者 张三 摄</figcaption></figure><p>图后正文。</p>")
        assertEquals(listOf(BlockKind.PARAGRAPH,BlockKind.IMAGE,BlockKind.CAPTION,BlockKind.PARAGRAPH),a.blocks.map {it.kind})
        assertEquals(listOf("图前正文。","","在某地发表重要讲话。新华社记者 张三 摄","图后正文。"),a.blocks.map {it.text})
        assertEquals(a.blocks[1].parent,a.blocks[2].parent)
        assertTrue(a.blocks[2].omitFromSpeech)
        assertEquals("图前正文。图后正文。",speech(a))
        assertEquals(listOf(a.blocks[0].id,a.blocks[3].id),Segmenter.split(a).map {it.blockId})
    }
    @Test fun qiushiStyledDescriptionWithoutPhotographerNeedsImmediatePhotoAndFullParagraphStyle() {
        val html="<p><img src='a.jpg'></p><p><span style='font-family:FangSong, serif;color:#000080'>在某地发表重要讲话，现场听取工作汇报。</span></p><p>这是应当完整朗读的政策正文。</p>"
        val a=article(html)
        assertEquals(listOf("在某地发表重要讲话，现场听取工作汇报。"),a.blocks.filter {it.omitFromSpeech}.map {it.text})
        assertEquals("这是应当完整朗读的政策正文。",speech(a))
        val noPhoto=article(html.replace("<p><img src='a.jpg'></p>",""))
        assertFalse(noPhoto.blocks.any {it.omitFromSpeech})
        val otherSite=article(html,"https://paper.people.com.cn/content/news.html")
        assertFalse(otherSite.blocks.any {it.omitFromSpeech})
        val mixed=article("<p><img src='a.jpg'></p><p>正文前半。<span style='font-family:FangSong;color:#000080'>重要讲话</span>正文后半。</p>")
        assertFalse(mixed.blocks.any {it.omitFromSpeech})
        assertEquals("正文前半。重要讲话正文后半。",speech(mixed))
    }
    @Test fun ordinaryBodyAboutImportantSpeechesPhotographyAndPictureReportsIsNeverKeywordFiltered() {
        val text="在重要讲话中明确了发展目标。摄影记者介绍拍摄方法，图为只是报道中引用的措辞。记者张三摄制的作品将展出。"
        val a=article("<p><img src='a.jpg'></p><p>$text</p><p style='text-align:center'>这是正文中的重要讲话摘录。</p><p>后续正文。</p>")
        assertFalse(a.blocks.any {it.omitFromSpeech})
        assertEquals(text+"这是正文中的重要讲话摘录。后续正文。",speech(a))
    }
    @Test fun photographerCreditRequiresLayoutAndNearbyImageWithoutSwallowingInterveningNews() {
        val body="近年来持续改善民生。图为居民正在办理业务，这是应当保留的新闻正文。"
        val a=article("<p><img src='a.jpg'></p><p>$body</p><p style='text-align:right'>龙林智 摄</p><p>后续报道。</p>")
        assertEquals(listOf("龙林智 摄"),a.blocks.filter {it.omitFromSpeech}.map {it.text})
        assertEquals(body+"后续报道。",speech(a))
        assertFalse(article("<p style='text-align:right'>龙林智 摄</p>").blocks.single().omitFromSpeech)
        assertFalse(article("<p><img src='a.jpg'></p><p>龙林智 摄</p>").blocks.last().omitFromSpeech)
    }
    @Test fun explicitCaptionClassRequiresActualPictureRelationship() {
        val a=article("<p><img src='a.jpg'></p><div class='pictext'>这张配图的说明。</div><p>正常正文。</p><div class='caption'>没有对应图片的正文标签。</div>")
        assertEquals(listOf("这张配图的说明。"),a.blocks.filter {it.omitFromSpeech}.map {it.text})
        assertEquals("正常正文。没有对应图片的正文标签。",speech(a))
        assertFalse(article("<p><img src='a.jpg'></p><div class='caption-analysis'>普通分析正文。</div>").blocks.last().omitFromSpeech)
    }
    @Test fun captionsInsideMixedTableCannotSuppressRealTableData() {
        val a=article("<table><tr><td><img src='a.jpg'></td><td>实际统计数据：42</td></tr><tr><td class='pictext'>表内配图说明。</td><td>结论需要朗读。</td></tr></table>")
        assertTrue(speech(a).contains("实际统计数据：42"))
        assertTrue(speech(a).contains("结论需要朗读。"))
        assertEquals(BlockKind.TABLE,a.blocks.first().kind)
    }
    @Test fun customTextAlwaysReadsEveryCharacterEvenIfImportedWithCaptionFlag() {
        val text="新华社记者 张三 摄\n在某地发表重要讲话。\n这是我自己粘贴的文字。"
        val a=Segmenter.custom(text)
        assertEquals(text.replace("\n",""),speech(a))
        assertEquals(speech(a),speech(a.copy(blocks=a.blocks.map {it.copy(omitFromSpeech=true)})))
    }
    @Test fun positionInOldCaptionMovesToNextBodyAndPolicyDoesNotInvalidateBodyAudioKeys() {
        val a=article("<p>第一段正文。</p><figure><img src='a.jpg'><figcaption>图注。</figcaption></figure><p>第二段正文。</p>")
        val old=a.copy(blocks=a.blocks.map {it.copy(omitFromSpeech=false)})
        val fingerprint=SpeechPolicy.fingerprint(a,engine)
        assertNotEquals(SpeechPolicy.fingerprint(old,engine),fingerprint)
        val caption=a.blocks.single {it.kind==BlockKind.CAPTION}
        val point=ReadingPosition(a.story.url,segment=1,audioMs=8000,audioBlock=caption.id,audioFingerprint=SpeechPolicy.fingerprint(old,engine))
        val segments=Segmenter.split(a)
        val restored=SpeechPolicy.resume(point,a,segments,fingerprint)
        assertEquals(a.blocks.last().id,segments[restored.first].blockId)
        assertEquals(0L,restored.second)
        val end=a.copy(blocks=a.blocks.dropLast(1)+a.blocks.last()+caption.copy(id="tail-caption"))
        val tail=SpeechPolicy.resume(point.copy(audioBlock="tail-caption"),end,Segmenter.split(end),SpeechPolicy.fingerprint(end,engine))
        assertEquals(a.blocks.last().id,Segmenter.split(end)[tail.first].blockId)
        val plain=article("<p>没有图注的正文。</p>")
        val legacy=stableId("${Segmenter.VERSION}|${engine.cacheIdentity}|"+plain.blocks.joinToString("\n") {"${it.id}|${it.kind}|${it.text}"})
        assertEquals(legacy,SpeechPolicy.fingerprint(plain,engine))
    }
}
