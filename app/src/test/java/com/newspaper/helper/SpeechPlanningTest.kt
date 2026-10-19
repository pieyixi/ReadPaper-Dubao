package com.newspaper.helper

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SpeechPlanningTest {
    @Test fun noteCharacterCountTreatsEmojiAsOneAndExcludesUnicodeSpaces() {
        assertEquals(3,NoteText.count("中😀A\n\t \u00a0\u3000"))
        assertEquals(0,NoteText.count(""))
    }
    private fun article(text:String)=Article(Story(Paper.PEOPLE,"https://example.org/news",""),listOf(BodyBlock("body",BlockKind.PARAGRAPH,text=text)))
    private val engine=object:SpeechEngine {
        override suspend fun synthesize(text:String,file:File)=Unit
        override fun cancel()=Unit
        override fun close()=Unit
    }
    @Test fun customTextKeepsPrivateAudioWhileAllFivePapersUseSharedCache() {
        assertTrue(SpeechPolicy.privateText(Segmenter.custom("我的私人文本。")))
        Paper.entries.forEach { assertFalse(SpeechPolicy.privateText(article("正文。").copy(story=Story(it,"https://example.org/${it.name}","")))) }
    }
    @Test fun firstChunkIsACompleteShortSentenceAndQuotesStayAttached() {
        val text="负责人表示：“先核实数据，再作出判断。”"+"完善公共服务，持续改进工作方法。".repeat(80)
        val pieces=Segmenter.split(article(text))
        assertEquals("负责人表示：“先核实数据，再作出判断。”",pieces.first().text)
        assertTrue(pieces.first().text.length<80)
        assertEquals(text,pieces.joinToString("") { it.text })
        assertTrue(pieces.all { text.substring(it.start,it.end)==it.text && it.text.length<=320 })
    }
    @Test fun decimalsVersionsAndAbbreviationsStayWhole() {
        val text="Dr. Li 使用 AI，系统版本为2.0.0，同比增长5.2%。".repeat(30)
        val pieces=Segmenter.split(article(text),90)
        assertEquals(text,pieces.joinToString("") { it.text })
        assertTrue(pieces.none { it.text.endsWith("2.") || it.text.endsWith("5.") || it.text=="Dr." })
    }
    @Test fun newVoiceOrSegmentationDiscardsOldMillisecondsButKeepsSourceAnchor() {
        val a=article("正文。".repeat(600));val segments=Segmenter.split(a)
        val fingerprint=SpeechPolicy.fingerprint(a,engine)
        val old=ReadingPosition(a.story.url,block=4,offset=19,segment=3,audioMs=9000,audioBlock="body",audioOffset=700,audioFingerprint="old")
        val restored=SpeechPolicy.resume(old,a,segments,fingerprint)
        assertTrue(segments[restored.first].start<=700 && segments[restored.first].end>700)
        assertEquals(0L,restored.second)
        val compatible=old.copy(segment=2,audioFingerprint=fingerprint)
        assertEquals(2 to 9000L,SpeechPolicy.resume(compatible,a,segments,fingerprint))
        assertNotEquals(fingerprint,SpeechPolicy.fingerprint(article("正文已更新。"),engine))
    }
    @Test fun versionOnePositionsMapThroughOriginal1800CharacterSegments() {
        val a=article("甲".repeat(4000));val segments=Segmenter.split(a)
        val point=SpeechPolicy.resume(ReadingPosition(a.story.url,segment=1,audioMs=8000),a,segments,"new")
        assertTrue(segments[point.first].start<=1800 && segments[point.first].end>1800)
        assertEquals(0L,point.second)
    }
    @Test fun durationBufferScalesWithPlaybackSpeedAndIsBounded() {
        assertTrue(SpeechPolicy.prefetch(0,3,100,10000,1f))
        assertFalse(SpeechPolicy.prefetch(0,3,100,50000,1f))
        assertTrue(SpeechPolicy.prefetch(0,3,100,50000,2f))
        assertFalse(SpeechPolicy.prefetch(0,8,100,10000,2f))
        assertFalse(SpeechPolicy.prefetch(8,10,10,10000,1f))
    }
}
