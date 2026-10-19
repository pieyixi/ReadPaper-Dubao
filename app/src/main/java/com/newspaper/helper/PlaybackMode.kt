package com.newspaper.helper

enum class PlaybackMode {
    FULL_LOOP, PARAGRAPH_LOOP, FULL_ONCE;
    fun next() = when(this) {
        FULL_LOOP -> PARAGRAPH_LOOP
        PARAGRAPH_LOOP -> FULL_ONCE
        FULL_ONCE -> FULL_LOOP
    }
    companion object {
        fun restore(value:String?) = entries.firstOrNull { it.name==value } ?: FULL_ONCE
    }
}

/** 原文块是循环边界：同一自然段拆出的全部音频仍属于同一次循环。 */
internal object PlaybackBounds {
    fun paragraph(segments:List<SpeechSegment>,index:Int):IntRange {
        if(segments.isEmpty()) return IntRange.EMPTY
        val current=index.coerceIn(segments.indices)
        val block=segments[current].blockId
        var first=current;var last=current
        while(first>0 && segments[first-1].blockId==block) first--
        while(last<segments.lastIndex && segments[last+1].blockId==block) last++
        return first..last
    }
}
