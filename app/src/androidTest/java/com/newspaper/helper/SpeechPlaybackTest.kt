package com.newspaper.helper

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** 使用安装的真实系统 TTS 和 ExoPlayer；不注入合成回调或假播放器。 */
@RunWith(AndroidJUnit4::class)
class SpeechPlaybackTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext
    private fun waitFor(message:String,seconds:Int=60,predicate:()->Boolean) {
        val end=System.currentTimeMillis()+seconds*1000
        while(System.currentTimeMillis()<end) { if(predicate()) return;Thread.sleep(150) }
        fail(message+": "+PlaybackSignals.state.value)
    }
    private fun <T> main(block:()->T):T {
        var result:Result<T>?=null
        instrumentation.runOnMainSync { result=runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
    private fun shell(command:String):String = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    @Test fun realChineseWaveHasPcmSamples() {
        val engine=main { AndroidSpeechEngine(app) }
        val file=File(app.getExternalFilesDir(null),"evidence/real-chinese.wav").apply { parentFile!!.mkdirs() }
        try {
            runBlocking { engine.synthesize("读报。以更高标准、更实举措推进全面从严治党。",file) }
            val bytes=file.readBytes()
            assertTrue(bytes.size>1000)
            assertEquals("RIFF",String(bytes,0,4))
            assertTrue(bytes.drop(44).any { it.toInt()!=0 })
        } finally { engine.close() }
    }
    @Test fun realPlayerPauseResumeSkipCancelBackgroundAndQueue() {
        val assets=instrumentation.context.assets
        val urls=JSONObject(assets.open("sources/provenance.json").bufferedReader().readText())
        val articles=listOf("people" to Paper.PEOPLE,"study" to Paper.STUDY).map { (name,paper) ->
            ContentParser.article(Story(paper,urls.getString("$name-article"),""),assets.open("sources/$name-article.html").bufferedReader().readText())
        }
        runBlocking(Dispatchers.IO) {
            val dao=ReaderDatabase.get(app).reader()
            articles.forEach { dao.saveArticle(ArticleRecord(it.story.url,jsonCodec.toJson(it),System.currentTimeMillis()));dao.savePosition(ReadingPosition(it.story.url)) }
        }
        val future=main { MediaController.Builder(app,SessionToken(app,ComponentName(app,ReadAloudService::class.java))).buildAsync() }
        val player=future.get(30,TimeUnit.SECONDS)
        fun command(name:String,bundle:Bundle=Bundle.EMPTY) = main { player.sendCustomCommand(SessionCommand(name,Bundle.EMPTY),bundle) }.get(10,TimeUnit.SECONDS)
        fun speak(request:SpeechRequest) { command("speak",Bundle().apply { putString("request",jsonCodec.toJson(request)) }) }
        try {
            speak(SpeechRequest(stories=articles.map { it.story }))
            waitFor("No real playback") { main { player.isPlaying && player.currentPosition>150 } }
            shell("cmd statusbar expand-notifications")
            Thread.sleep(500)
            val screen=instrumentation.uiAutomation.takeScreenshot()
            File(app.getExternalFilesDir(null),"evidence/media-notification.png").outputStream().use { screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            shell("cmd statusbar collapse")
            main { player.pause() }
            waitFor("Pause was not acknowledged") { main { !player.isPlaying && !player.playWhenReady } && !PlaybackSignals.state.value.playing }
            var lastPause=-1L
            var stable=0
            waitFor("Paused position did not stabilize",10) {
                val now=main { player.currentPosition }
                stable=if(lastPause>=0 && kotlin.math.abs(now-lastPause)<10) stable+1 else 0
                lastPause=now
                stable>=3 && main { !player.isPlaying && !player.playWhenReady }
            }
            val paused=main { player.currentPosition }
            val pausedId=main { player.currentMediaItem?.mediaId }
            Thread.sleep(500)
            val afterPause=main { player.currentPosition }
            assertTrue("Paused position advanced: $pausedId $paused -> ${main { player.currentMediaItem?.mediaId }} $afterPause; ${PlaybackSignals.state.value}",kotlin.math.abs(afterPause-paused)<100)
            main { player.setPlaybackSpeed(1.25f);player.play() }
            waitFor("Resume did not advance") { main { player.currentPosition>paused+150 } }
            val previousBlock=PlaybackSignals.state.value.block
            val previousSegment=main { player.currentMediaItem?.mediaId }
            command("step",Bundle().apply { putInt("delta",1) })
            waitFor("Next segment did not change audio item") { main { player.currentMediaItem?.mediaId }!=previousSegment && PlaybackSignals.state.value.block.isNotEmpty() }
            command("step",Bundle().apply { putInt("delta",-1) })
            waitFor("Previous segment did not restore audio item") { main { player.currentMediaItem?.mediaId }==previousSegment && PlaybackSignals.state.value.block==previousBlock }
            // 真正离开 Activity 后仍由媒体服务播放；不以模拟的播放事件代替解码进度。
            app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(500)
            assertTrue(main { player.playWhenReady })
            val audio=app.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val listener=android.media.AudioManager.OnAudioFocusChangeListener { }
            @Suppress("DEPRECATION")
            val focus=audio.requestAudioFocus(listener,android.media.AudioManager.STREAM_MUSIC,android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED,focus)
            waitFor("Transient audio focus did not pause speech") { main { !player.isPlaying } }
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(listener)
            waitFor("Audio focus recovery did not resume speech") { main { player.isPlaying } }
            val beforeSleep=main { Pair(player.currentMediaItem?.mediaId,player.currentPosition) }
            shell("input keyevent 223")
            waitFor("Screen off prevented further audio progress") {
                main { player.isPlaying && (player.currentMediaItem?.mediaId!=beforeSleep.first || player.currentPosition>beforeSleep.second+150) }
            }
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
            shell("input keyevent 127")
            waitFor("Media pause key not handled") { main { !player.playWhenReady } }
            shell("input keyevent 126")
            waitFor("Media play key not handled") { main { player.isPlaying } }
            speak(SpeechRequest(stories=listOf(articles[1].story)))
            waitFor("Article switch retained old audio") { PlaybackSignals.state.value.article==articles[1].story.url && main { player.isPlaying } }
            assertTrue(main { player.currentMediaItem?.mediaMetadata?.title }==articles[1].story.title)
            command("stop")
            waitFor("Stop retained media") { main { player.mediaItemCount==0 } }
            val text="自定义文本。广告和债权原样保留。English reading.\n第二段。"
            runBlocking { ReaderDatabase.get(app).reader().savePosition(ReadingPosition("custom:"+stableId(text))) }
            val name=stableId(text)+".txt"
            File(File(app.cacheDir,"speech-input").apply { mkdirs() },name).writeText(text)
            speak(SpeechRequest(custom=name))
            waitFor("Custom text did not play") { PlaybackSignals.state.value.article.startsWith("custom:") && main { player.isPlaying } }
            command("stop")
            runBlocking(Dispatchers.IO) {
                articles.forEach { a ->
                    val short=a.copy(blocks=listOf(a.blocks.first { it.text.isNotBlank() }.copy(text=a.blocks.first { it.text.isNotBlank() }.text.take(8))))
                    ReaderDatabase.get(app).reader().saveArticle(ArticleRecord(a.story.url,jsonCodec.toJson(short),System.currentTimeMillis()))
                    ReaderDatabase.get(app).reader().savePosition(ReadingPosition(a.story.url))
                }
            }
            speak(SpeechRequest(stories=articles.map { it.story }))
            waitFor("Queue did not advance automatically") { PlaybackSignals.state.value.article==articles[1].story.url && main { player.isPlaying } }
            command("stop")
        } finally {
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
            runCatching { command("stop") }
            main { player.release() }
        }
    }
}
