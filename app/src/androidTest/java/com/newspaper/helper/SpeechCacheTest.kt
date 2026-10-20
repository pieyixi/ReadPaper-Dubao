package com.newspaper.helper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** 使用可解码 WAV 和禁止再次合成的引擎，验证磁盘复用，不把模拟音频称为真实新闻播音。 */
@RunWith(AndroidJUnit4::class)
class SpeechCacheTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun wave():ByteArray {
        val frames=6400;val size=frames*2
        return ByteBuffer.allocate(44+size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(36+size);put("WAVEfmt ".toByteArray());putInt(16)
            putShort(1.toShort());putShort(1.toShort());putInt(16000);putInt(32000)
            putShort(2.toShort());putShort(16.toShort());put("data".toByteArray());putInt(size)
            repeat(frames) {putShort((kotlin.math.sin(it*2*Math.PI*440/16000)*3000).toInt().toShort())}
        }.array()
    }
    private inner class CountingEngine(override val cacheIdentity:String="cache-test-voice"):SpeechEngine {
        var calls=0;var offline=false
        override suspend fun synthesize(text:String,file:File) {calls++;check(!offline){"A cache hit must not request synthesis"};file.writeBytes(wave())}
        override fun cancel()=Unit
        override fun close()=Unit
    }
    private fun part(article:String,index:Int=0,text:String="第${index}段完整正文。"):SpeechSegment = SpeechSegment(article,"block-$index",text,index)
    private fun temp()=File(app.cacheDir,"cache-regression-${UUID.randomUUID()}").apply {mkdirs()}

    @Test fun skippingCaptionsKeepsAlreadySynthesizedBodyAudioAvailableOffline() = runBlocking {
        val root=temp()
        try {
            val url="https://example.org/caption-cache-${UUID.randomUUID()}"
            val article=Article(Story(Paper.PEOPLE,url,""),ContentParser.blocks("<figure><img src='a.jpg'><figcaption>配图说明。张三 摄</figcaption></figure><p>"+"正文的每个已获取片段都应继续复用。".repeat(45)+"</p><p>第二段正文。</p>",url))
            val old=article.copy(blocks=article.blocks.map {it.copy(omitFromSpeech=false)})
            val engine=CountingEngine();val cache=SpeechAudioCache(root)
            val cached=Segmenter.split(old).associate {it.text to cache.render(it,engine,false).file.absolutePath}
            val generated=engine.calls;assertTrue(generated>3)
            engine.offline=true
            val now=Segmenter.split(article)
            assertEquals(generated-1,now.size)
            now.forEach {assertEquals(cached[it.text],cache.render(it,engine,false).file.absolutePath)}
            assertEquals("Changing speech selection invalidated cached body audio",generated,engine.calls)
            assertEquals(4,article.blocks.size)
            assertEquals(1,article.blocks.count {it.omitFromSpeech})
        } finally {root.deleteRecursively()}
    }

    @Test fun annotationUpgradePreservesReadingPositionAndKeepsRecentBodyReadableWhenOffline() = runBlocking {
        val url="https://www.qstheory.cn/20261020/${UUID.randomUUID()}/c.html"
        val story=Story(Paper.QIUSHI,url,"")
        val raw="<div id='detailContent'><p>完整正文。</p><figure><img src='a.jpg'><figcaption>配图说明。</figcaption></figure></div>"
        val current=ContentParser.article(story,raw)
        val old=current.copy(parserVersion=2,blocks=current.blocks.map {it.copy(omitFromSpeech=false)})
        val position=ReadingPosition(url,block=5,offset=17,audioBlock=current.blocks.first().id,audioOffset=3)
        var calls=0
        val online=ArticleRepository(app,NewsSources(object:Transport {override suspend fun load(url:String,json:String?):String {calls++;return raw}}))
        online.dao.saveArticle(ArticleRecord(url,jsonCodec.toJson(old),System.currentTimeMillis()))
        online.dao.savePosition(position)
        assertTrue(online.article(story).blocks.any {it.omitFromSpeech})
        assertEquals(position,online.dao.position(url))
        online.article(story);assertEquals(1,calls)
        online.dao.saveArticle(ArticleRecord(url,jsonCodec.toJson(old),System.currentTimeMillis()))
        val offline=ArticleRepository(app,NewsSources(object:Transport {override suspend fun load(url:String,json:String?):String {throw java.io.IOException("Controlled offline annotation upgrade")}}))
        assertEquals(old,offline.article(story))
        assertEquals(position,online.dao.position(url))
    }

    @Test fun completeActiveArticleSurvivesEvictionAndReplaysOfflineAfterCacheRecreation() = runBlocking {
        val root=temp()
        try {
            val cache=SpeechAudioCache(root,maxBytes=20000)
            val engine=CountingEngine();val article="https://example.org/long-article"
            val scope=SpeechAudioCache.owner(article,false);cache.activate(scope)
            val segments=(0..13).map {part(article,it)}
            val paths=segments.map {cache.render(it,engine,false).file.also {cache.prune(force=true)}}
            assertEquals(14,engine.calls);assertTrue(paths.all {it.isFile})
            // 十四段远大于播放器八段窗口和测试容量上限，保护的是整篇而非当前队列。
            assertTrue(paths.sumOf {it.length()}>20000)
            engine.offline=true
            val reopened=SpeechAudioCache(root,maxBytes=20000).apply {activate(scope)}
            repeat(3) {segments.forEachIndexed {i,s->assertEquals(paths[i],reopened.render(s,engine,false).file)}}
            assertEquals("Loop/reopen synthesized downloaded paragraphs again",14,engine.calls)
            val other=part("https://example.org/other")
            val fresh=CountingEngine();reopened.activate(SpeechAudioCache.owner(other.articleId,false))
            val active=reopened.render(other,fresh,false).file
            reopened.prune(force=true)
            assertTrue(active.exists());assertTrue(paths.none {it.exists()})
        } finally {root.deleteRecursively()}
    }
    @Test fun notesPersistAcrossStopStyleReopenAndDeletionDoesNotTouchOtherOwners() = runBlocking {
        val root=temp()
        try {
            val cache=SpeechAudioCache(root);val engine=CountingEngine()
            val s=part("custom:original")
            val note=cache.render(s,engine,true,"note-a").file
            assertTrue(note.name.startsWith("private-"))
            val second=cache.render(s,engine,true,"note-b").file
            val news=cache.render(part("https://example.org/news"),engine,false).file
            assertNotEquals(note,second)
            engine.offline=true
            assertEquals(note,SpeechAudioCache(root).render(s,engine,true,"note-a").file)
            // 其他段落发生编辑，未改动的正文片段仍可复用；改动的文本不会命中旧声音。
            assertEquals(note,cache.render(s.copy(articleId="custom:edited"),engine,true,"note-a").file)
            engine.offline=false
            val changed=cache.render(s.copy(text="改动后的原文。"),engine,true,"note-a").file
            assertNotEquals(note,changed)
            cache.deleteNotes(listOf("note-a"))
            assertFalse(note.exists());assertFalse(changed.exists());assertTrue(second.exists());assertTrue(news.exists())
        } finally {root.deleteRecursively()}
    }
    @Test fun corruptAudioAndVoiceChangesRegenerateOnlyTheAffectedClip() = runBlocking {
        val root=temp()
        try {
            val cache=SpeechAudioCache(root);val engine=CountingEngine();val a=part("https://example.org/corrupt")
            val file=cache.render(a,engine,false).file
            val intact=cache.render(a.copy(index=1,text="另一个完整段落。"),engine,false).file
            file.writeBytes(byteArrayOf(1,2,3))
            assertTrue(cache.render(a,engine,false).durationMs>0);assertEquals(3,engine.calls)
            engine.offline=true
            assertEquals(intact,cache.render(a.copy(index=1,text="另一个完整段落。"),engine,false).file)
            val newVoice=CountingEngine("another-voice")
            assertNotEquals(file,cache.render(a,newVoice,false).file);assertEquals(1,newVoice.calls)
        } finally {root.deleteRecursively()}
    }
    @Test fun canceledSynthesisNeverLeavesAPublishedOrPartialClip() = runBlocking {
        val root=temp()
        try {
            val entered=CompletableDeferred<Unit>()
            val engine=object:SpeechEngine {
                override suspend fun synthesize(text:String,file:File) {file.writeBytes(wave().take(100).toByteArray());entered.complete(Unit);awaitCancellation()}
                override fun cancel()=Unit
                override fun close()=Unit
            }
            val job=launch {SpeechAudioCache(root).render(part("custom:cancel"),engine,true,"cancel")}
            entered.await();job.cancelAndJoin()
            assertTrue(root.walkTopDown().none {it.isFile})
        } finally {root.deleteRecursively()}
    }
    @Test fun oldPublicAudioMigratesWithoutRequestingSynthesis() = runBlocking {
        val root=temp()
        try {
            val legacy=File(root,"legacy").apply {mkdirs()};val engine=CountingEngine().apply {offline=true}
            val s=part("https://example.org/legacy")
            val key=stableId("${Segmenter.VERSION}|${engine.cacheIdentity}|${s.text}")
            val old=File(legacy,"$key.wav").apply {writeBytes(wave())}
            val migrated=SpeechAudioCache(File(root,"new"),legacy).render(s,engine,false)
            assertTrue(migrated.file.isFile);assertFalse(old.exists());assertEquals(0,engine.calls)
        } finally {root.deleteRecursively()}
    }
}
