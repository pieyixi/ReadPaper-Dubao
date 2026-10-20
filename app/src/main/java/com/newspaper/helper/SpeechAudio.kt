package com.newspaper.helper

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal object SpeechPolicy {
    fun privateText(article:Article) = article.story.url.startsWith("custom:")
    fun fingerprint(article:Article,engine:SpeechEngine):String {
        val original="${Segmenter.VERSION}|${engine.cacheIdentity}|"+article.blocks.joinToString("\n") { "${it.id}|${it.kind}|${it.text}" }
        // 仅队列选择变化时失效片内时间；分段/声音相同的正文磁盘音频保持原键。
        val omitted=if(privateText(article) || article.blocks.none {it.omitFromSpeech})""
            else "|omitted:"+article.blocks.filter {it.omitFromSpeech}.joinToString(",") {it.id}
        return stableId(original+omitted)
    }
    fun resume(position:ReadingPosition?,article:Article,segments:List<SpeechSegment>,fingerprint:String):Pair<Int,Long> {
        if(position==null || segments.isEmpty()) return 0 to 0L
        if(position.audioFingerprint==fingerprint && position.segment in segments.indices)
            return position.segment to position.audioMs.coerceAtLeast(0)
        val anchor=if(position.audioBlock.isNotBlank()) position.audioBlock to position.audioOffset
            else Segmenter.legacyAnchor(article,position.segment)
        val index=anchor?.let { (block,offset) ->
            segments.indexOfLast { it.blockId==block && it.start<=offset }.takeIf { it>=0 }
                ?: article.blocks.indexOfFirst {it.id==block}.takeIf {it>=0}?.let {removed->
                    // 旧位置恰在图注时，接到之后的正文；末尾图注则回到最后一个可朗读自然段。
                    val order=article.blocks.mapIndexed {index,b->b.id to index}.toMap()
                    segments.indexOfFirst {(order[it.blockId] ?: -1)>removed}.takeIf {it>=0}
                        ?: segments.lastOrNull()?.blockId?.let {last->segments.indexOfFirst {it.blockId==last}}
                }
        } ?: 0
        return index to 0L
    }
    fun prefetch(current:Int,generated:Int,total:Int,bufferedMs:Long,speed:Float):Boolean =
        generated<total && generated-current<8 && (generated<=current || bufferedMs<45000L*speed.coerceAtLeast(.5f))
}

internal data class SpeechAudio(val file:File,val durationMs:Long)

/** 缓存按正文与声音参数复用；半成品永不进入播放器，取消只删除本次临时文件。 */
internal class SpeechAudioCache(
    private val directory:File,
    private val legacyDirectory:File?=null,
    private val maxBytes:Long=256L*1024*1024
) {
    companion object {
        // 服务与便签删除共用锁，取消后的半成品不能与删除/下一次合成争抢同一个文件。
        private val lock=Mutex()
        fun root(context:android.content.Context)=File(context.noBackupFilesDir,"speech-audio")
        fun owner(articleId:String,privateText:Boolean,noteId:String?=null)=
            (if(privateText)"private-" else "article-")+stableId(if(privateText)noteId ?: articleId else articleId)
    }
    @Volatile private var activeOwner:String?=null
    private var lastPrune=0L
    fun activate(owner:String?) {activeOwner=owner}
    suspend fun render(segment:SpeechSegment,engine:SpeechEngine,privateText:Boolean,noteId:String?=null):SpeechAudio = withContext(Dispatchers.IO) {lock.withLock {
        val scope=File(directory,owner(segment.articleId,privateText,noteId)).apply {mkdirs()}
        val key=stableId("${Segmenter.VERSION}|${engine.cacheIdentity}|${segment.text}")
        val prefix=if(privateText) "private-$key" else key
        val target=File(scope,"$prefix.${engine.extension}")
        fun touch(){val now=System.currentTimeMillis();target.setLastModified(now);scope.setLastModified(now)}
        if(target.isFile) {
            probe(target,engine.extension)?.let {touch();return@withLock SpeechAudio(target,it)}
            target.delete()
        }
        val temporary=File(scope,"$prefix-${UUID.randomUUID()}.part.${engine.extension}")
        try {
            // 老版本公共音频可以直接迁入，避免升级后让已经下载的新闻再次联网。
            val legacy=if(privateText)null else legacyDirectory?.let {File(it,"$key.${engine.extension}")}
            if(legacy!=null && probe(legacy,engine.extension)!=null)legacy.copyTo(temporary)
            else engine.synthesize(segment.text,temporary)
            coroutineContext.ensureActive()
            val duration=checkNotNull(probe(temporary,engine.extension)) { "Invalid speech audio" }
            coroutineContext.ensureActive()
            check(temporary.renameTo(target)) { "Cannot publish speech audio" }
            touch()
            legacy?.delete()
            SpeechAudio(target,duration)
        } finally { temporary.delete() }
    } }
    suspend fun deleteNotes(ids:Collection<String>) = withContext(Dispatchers.IO) {lock.withLock {
        ids.forEach {File(directory,owner("",true,it)).deleteRecursively()}
    } }
    suspend fun prune(force:Boolean=false) = withContext(Dispatchers.IO) {lock.withLock {
        val now=System.currentTimeMillis()
        if(!force && now-lastPrune<60000)return@withLock
        lastPrune=now
        val scopes=directory.listFiles()?.filter {it.isDirectory}.orEmpty()
        scopes.forEach {scope->scope.listFiles()?.filter {".part." in it.name}?.forEach {it.delete()}}
        val sizes=scopes.associateWith {scope->scope.listFiles()?.sumOf {it.length()} ?: 0L}
        var bytes=sizes.values.sum()
        // 保护当前整篇，不能只保护播放器八段窗口；无按天过期，容量不足才按篇回收闲置缓存。
        scopes.sortedBy {it.lastModified()}.forEach {scope->
            if(bytes>maxBytes && scope.name!=activeOwner && scope.deleteRecursively())bytes-=sizes.getValue(scope)
        }
    } }
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
