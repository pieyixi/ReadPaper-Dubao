package com.newspaper.helper

import org.junit.Assert.*
import org.junit.Test

class PlaybackModeTest {
    @Test fun longOriginalParagraphIncludesEveryAudioChunk() {
        val article=Article(Story(Paper.PEOPLE,"https://example.org/modes",""),listOf(
            BodyBlock("first",BlockKind.PARAGRAPH,"原文第一句。"+"同一自然段的后续内容必须一起循环。".repeat(200)),
            BodyBlock("photo",BlockKind.IMAGE,url="https://example.org/image.png"),
            BodyBlock("second",BlockKind.PARAGRAPH,"下一自然段。")))
        val segments=Segmenter.split(article)
        val indices=segments.indices.filter { segments[it].blockId=="first" }
        assertTrue(indices.size>8)
        indices.forEach { assertEquals(indices.first()..indices.last(),PlaybackBounds.paragraph(segments,it)) }
        assertEquals(segments.lastIndex..segments.lastIndex,PlaybackBounds.paragraph(segments,segments.lastIndex))
    }
    @Test fun adjacentIdenticalParagraphsRemainSeparateAndCustomUsesOriginalNewlines() {
        val segments=Segmenter.split(Segmenter.custom("相同段落。\n\n相同段落。"))
        assertEquals(2,segments.size)
        assertEquals(0..0,PlaybackBounds.paragraph(segments,0))
        assertEquals(1..1,PlaybackBounds.paragraph(segments,1))
        assertTrue(PlaybackBounds.paragraph(emptyList(),0).isEmpty())
    }
    @Test fun modesCycleAndMissingOrUnknownSettingsPlayOnce() {
        assertEquals(PlaybackMode.FULL_LOOP,PlaybackMode.FULL_ONCE.next())
        assertEquals(PlaybackMode.PARAGRAPH_LOOP,PlaybackMode.FULL_LOOP.next())
        assertEquals(PlaybackMode.FULL_ONCE,PlaybackMode.PARAGRAPH_LOOP.next())
        assertEquals(PlaybackMode.FULL_ONCE,PlaybackMode.restore(null))
        assertEquals(PlaybackMode.FULL_ONCE,PlaybackMode.restore("invalid"))
        PlaybackMode.entries.forEach { assertEquals(it,PlaybackMode.restore(it.name)) }
    }
}
