package com.newspaper.helper

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal object SpeechPolicy {
    fun online(article:Article) = !article.story.url.startsWith("custom:")
    fun fingerprint(article:Article,engine:SpeechEngine) = stableId(
        "${Segmenter.VERSION}|${engine.cacheIdentity}|"+article.blocks.joinToString("\n") { "${it.id}|${it.kind}|${it.text}" })
    fun resume(position:ReadingPosition?,article:Article,segments:List<SpeechSegment>,fingerprint:String):Pair<Int,Long> {
        if(position==null || segments.isEmpty()) return 0 to 0L
        if(position.audioFingerprint==fingerprint && position.segment in segments.indices)
            return position.segment to position.audioMs.coerceAtLeast(0)
        val anchor=if(position.audioBlock.isNotBlank()) position.audioBlock to position.audioOffset
            else Segmenter.legacyAnchor(article,position.segment)
        val index=anchor?.let { (block,offset) ->
            segments.indexOfLast { it.blockId==block && it.start<=offset }.takeIf { it>=0 }
        } ?: 0
        return index to 0L
    }
    fun prefetch(current:Int,generated:Int,total:Int,bufferedMs:Long,speed:Float):Boolean =
        generated<total && generated-current<8 && (generated<=current || bufferedMs<45000L*speed.coerceAtLeast(.5f))
}

internal data class SpeechAudio(val file:File,val durationMs:Long)

/** 缓存按正文与声音参数复用；半成品永不进入播放器，取消只删除本次临时文件。 */
internal class SpeechAudioCache(private val directory:File) {
    suspend fun render(segment:SpeechSegment,engine:SpeechEngine,privateText:Boolean):SpeechAudio = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val key=stableId("${Segmenter.VERSION}|${engine.cacheIdentity}|${segment.text}")
        val prefix=if(privateText) "private-${UUID.randomUUID()}" else key
        val target=File(directory,"$prefix.${engine.extension}")
        if(!privateText && target.isFile) {
            probe(target,engine.extension)?.let { target.setLastModified(System.currentTimeMillis());return@withContext SpeechAudio(target,it) }
            target.delete()
        }
        val temporary=File(directory,"$prefix-${UUID.randomUUID()}.part.${engine.extension}")
        try {
            engine.synthesize(segment.text,temporary)
            coroutineContext.ensureActive()
            val duration=checkNotNull(probe(temporary,engine.extension)) { "Invalid speech audio" }
            coroutineContext.ensureActive()
            check(temporary.renameTo(target)) { "Cannot publish speech audio" }
            SpeechAudio(target,duration)
        } finally { temporary.delete() }
    }
    private fun probe(file:File,extension:String):Long? = runCatching {
        check(file.length() in 45..(4L*1024*1024))
        val header=file.inputStream().use { stream -> ByteArray(12).also { check(stream.read(it)==it.size) } }
        check(if(extension=="mp3") EdgeSpeechEngine.validHeader(header)
            else String(header,0,4,Charsets.US_ASCII)=="RIFF" && String(header,8,4,Charsets.US_ASCII)=="WAVE")
        val retriever=MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            checkNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()).also { check(it>0) }
        } finally { retriever.release() }
    }.getOrNull()
}
