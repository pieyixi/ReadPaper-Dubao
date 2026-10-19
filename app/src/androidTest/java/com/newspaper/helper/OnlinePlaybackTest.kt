package com.newspaper.helper

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
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

/** 使用真实 Edge 合成和 Media3 解码。目录/正文来自原站样本，不冒充五来源实时抓取。 */
@RunWith(AndroidJUnit4::class)
class OnlinePlaybackTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext
    private fun <T> main(block:()->T):T {
        var result:Result<T>?=null
        instrumentation.runOnMainSync { result=runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
    private fun waitFor(message:String,predicate:()->Boolean) {
        val end=System.currentTimeMillis()+90000
        while(System.currentTimeMillis()<end) { if(predicate()) return;Thread.sleep(150) }
        fail("$message: ${PlaybackSignals.state.value}")
    }
    private fun shell(command:String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    @Test fun fivePaperSamplesPlayOnlineAndSurvivePauseSwitchAndScreenOff() {
        val assets=instrumentation.context.assets
        val urls=JSONObject(assets.open("sources/provenance.json").bufferedReader().readText())
        val cases=listOf("study" to Paper.STUDY,"organization" to Paper.ORGANIZATION,"people" to Paper.PEOPLE,"south" to Paper.SOUTH,"qiushi" to Paper.QIUSHI)
        val articles=runBlocking(Dispatchers.IO) {
            cases.map { (name,paper) ->
                val story=Story(paper,urls.getString("$name-article"),"")
                val body=assets.open("sources/$name-article.${if(paper==Paper.ORGANIZATION) "json" else "html"}").bufferedReader().readText()
                val source=NewsSources(object:Transport { override suspend fun load(url:String,json:String?)=body })
                source.article(story).also { article ->
                    val dao=ReaderDatabase.get(app).reader()
                    dao.saveArticle(ArticleRecord(story.url,jsonCodec.toJson(article),System.currentTimeMillis()))
                    dao.savePosition(ReadingPosition(story.url))
                }
            }
        }
        val future=main { MediaController.Builder(app,SessionToken(app,ComponentName(app,ReadAloudService::class.java))).buildAsync() }
        val player=future.get(30,TimeUnit.SECONDS)
        fun command(name:String,bundle:Bundle=Bundle.EMPTY)=main { player.sendCustomCommand(SessionCommand(name,Bundle.EMPTY),bundle) }.get(10,TimeUnit.SECONDS)
        val evidence=File(app.getExternalFilesDir(null),"online-evidence").apply { mkdirs() }
        val results=mutableListOf<Map<String,Any>>()
        try {
            for(article in articles) {
                val started=System.currentTimeMillis()
                command("speak",Bundle().apply { putString("request",jsonCodec.toJson(SpeechRequest(stories=listOf(article.story)))) })
                waitFor("No online playback for ${article.story.paper}") {
                    PlaybackSignals.state.value.article==article.story.url && main { player.isPlaying && player.currentPosition>500 }
                }
                val uri=main { player.currentMediaItem!!.localConfiguration!!.uri }
                assertTrue("Offline fallback is not online evidence",uri.path!!.endsWith(".mp3"))
                File(uri.path!!).copyTo(File(evidence,"${article.story.paper.name.lowercase()}.mp3"),overwrite=true)
                results+=mapOf("paper" to article.story.paper.name,"url" to article.story.url,"blocks" to article.blocks.size,
                    "first_play_ms" to System.currentTimeMillis()-started,"media_position_ms" to main { player.currentPosition })
            }
            main { player.pause() }
            waitFor("Pause not acknowledged") { main { !player.isPlaying && !player.playWhenReady } }
            Thread.sleep(300)
            val paused=main { player.currentPosition }
            Thread.sleep(500)
            assertTrue(kotlin.math.abs(main { player.currentPosition }-paused)<100)
            main { player.play() }
            waitFor("Resume did not advance") { main { player.isPlaying && player.currentPosition>paused+150 } }
            val old=main { player.currentMediaItem!!.mediaId }
            command("step",Bundle().apply { putInt("delta",1) })
            waitFor("Next segment did not play") { main { player.currentMediaItem?.mediaId!=old && player.isPlaying } }
            command("step",Bundle().apply { putInt("delta",-1) })
            waitFor("Previous segment did not play") { main { player.currentMediaItem?.mediaId==old && player.isPlaying } }
            app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val before=main { player.currentMediaItem?.mediaId to player.currentPosition }
            shell("input keyevent 223")
            waitFor("Screen off stopped playback") {
                main { player.isPlaying && (player.currentMediaItem?.mediaId!=before.first || player.currentPosition>before.second+500) }
            }
            shell("input keyevent 224");shell("wm dismiss-keyguard")
            File(evidence,"playback.json").writeText(jsonCodec.toJson(results))
            command("stop")
            waitFor("Stop retained old media") { main { player.mediaItemCount==0 } }
            Thread.sleep(2000)
            assertEquals("Cancelled prefetch restarted after stop",0,main { player.mediaItemCount })
            assertFalse(PlaybackSignals.state.value.playing)
            // AGP 测试结束会卸载应用，提前将公开新闻样本证据导出到模拟器共享目录。
            shell("mkdir -p /sdcard/Download/dubao-online-evidence")
            shell("cp -r /sdcard/Android/data/com.newspaper.helper/files/online-evidence/. /sdcard/Download/dubao-online-evidence/")
        } finally {
            shell("input keyevent 224");shell("wm dismiss-keyguard")
            runCatching { command("stop") };main { player.release() }
        }
    }
}
