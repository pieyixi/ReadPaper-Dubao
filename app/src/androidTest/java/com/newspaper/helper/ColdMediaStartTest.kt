package com.newspaper.helper

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColdMediaStartTest {
    @Test fun foregroundMediaButtonStartsBeforeSynthesisAndRestoresQueue() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext
        val assets=instrumentation.context.assets
        val urls=JSONObject(assets.open("sources/provenance.json").bufferedReader().readText())
        val articles=listOf("people" to Paper.PEOPLE,"study" to Paper.STUDY).map { (name,paper) ->
            ContentParser.article(Story(paper,urls.getString("$name-article"),""),assets.open("sources/$name-article.html").bufferedReader().readText())
        }
        runBlocking(Dispatchers.IO) {
            val dao=ReaderDatabase.get(app).reader()
            articles.forEach { dao.saveArticle(ArticleRecord(it.story.url,jsonCodec.toJson(it),System.currentTimeMillis()));dao.savePosition(ReadingPosition(it.story.url)) }
            dao.saveQueue(QueueRecord(payload=jsonCodec.toJson(QueueSnapshot(listOf(articles[0].story),0))))
        }
        val service=Intent(app,ReadAloudService::class.java)
        instrumentation.runOnMainSync { app.stopService(service) }
        Thread.sleep(500)
        val intent=Intent(app,ReadAloudService::class.java).setAction(Intent.ACTION_MEDIA_BUTTON)
            .putExtra(Intent.EXTRA_KEY_EVENT,KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_PLAY))
        try {
            instrumentation.runOnMainSync { ContextCompat.startForegroundService(app,intent) }
            val end=System.currentTimeMillis()+60000
            while(System.currentTimeMillis()<end && !(PlaybackSignals.state.value.article==articles[0].story.url && PlaybackSignals.state.value.playing)) Thread.sleep(150)
            assertTrue("Cold media button did not decode audio: ${PlaybackSignals.state.value}",PlaybackSignals.state.value.playing)
            @Suppress("DEPRECATION")
            val running=(app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(50)
            assertTrue(running.any { it.service.className==ReadAloudService::class.java.name && it.foreground })
        } finally { instrumentation.runOnMainSync { app.stopService(service) } }
    }
}
