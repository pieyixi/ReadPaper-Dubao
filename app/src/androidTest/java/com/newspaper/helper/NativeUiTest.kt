package com.newspaper.helper

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val assets get() = instrumentation.context.assets
    private val settings get() = SettingsStore(app)
    @Before fun home() = runBlocking { settings.route(jsonCodec.toJson(Route()));settings.appearance(0,2,2) }
    private fun waitFor(message: String, check: () -> Boolean) {
        val end=System.currentTimeMillis()+30000
        while(System.currentTimeMillis()<end) { if(check()) return; Thread.sleep(100) }
        fail(message)
    }
    private fun shot(name:String) {
        instrumentation.waitForIdleSync()
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        val directory=File(app.getExternalFilesDir(null),"evidence").apply { mkdirs() }
        File(directory,name+".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun homeCardsThreeThemesAndNoBusinessWebView() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Paper.entries.forEach { paper -> onView(withText(paper.label)).check(matches(isDisplayed())) }
            fun check(view:View) {
                assertFalse(view.javaClass.name.contains("WebView"))
                if(view is ViewGroup) for(i in 0 until view.childCount) check(view.getChildAt(i))
            }
            for(mode in 0..2) {
                runBlocking { settings.appearance(mode,2,2) }
                Thread.sleep(300)
                scenario.onActivity { check(it.window.decorView) }
                shot("home-${app.resources.configuration.screenWidthDp}-$mode")
            }
            onView(withId(R.id.settings)).perform(click())
            onView(withText(app.getString(R.string.ui_eye))).check(matches(isDisplayed()))
            onView(withId(R.id.settings)).perform(click())
            onView(withText(Paper.PEOPLE.label)).perform(click())
            for(mode in 0..2) {
                runBlocking { settings.appearance(mode,2,2) };Thread.sleep(300)
                shot("calendar-${app.resources.configuration.screenWidthDp}-$mode")
            }
        }
    }
    @Test fun articleRestoresScrollSettingsAndNativeBody() {
        val urls=JSONObject(assets.open("sources/provenance.json").bufferedReader().readText())
        val story=Story(Paper.PEOPLE,urls.getString("people-article"),"")
        val article=ContentParser.article(story,assets.open("sources/people-article.html").bufferedReader().readText())
        val dao=ReaderDatabase.get(app).reader()
        runBlocking(Dispatchers.IO) {
            dao.saveArticle(ArticleRecord(story.url,jsonCodec.toJson(article),System.currentTimeMillis()))
            dao.savePosition(ReadingPosition(story.url))
            settings.route(jsonCodec.toJson(Route(Page.READER,story=article.story)))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("reader did not load") {
                var ready=false
                scenario.onActivity { ready=it.findViewById<RecyclerView>(R.id.list).adapter!!.itemCount>5 }
                ready
            }
            onView(withText(article.story.title)).check(matches(isDisplayed()))
            for(mode in 0..2) {
                runBlocking { settings.appearance(mode,3,3) }; Thread.sleep(300)
                shot("reader-${app.resources.configuration.screenWidthDp}-$mode")
            }
            scenario.onActivity { it.findViewById<RecyclerView>(R.id.list).smoothScrollToPosition(8) }
            waitFor("scroll did not move before recreation") {
                var moved=false
                scenario.onActivity { moved=(it.findViewById<RecyclerView>(R.id.list).layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()>0 && it.findViewById<RecyclerView>(R.id.list).scrollState==RecyclerView.SCROLL_STATE_IDLE }
                moved
            }
            scenario.recreate()
            waitFor("scroll not restored") {
                var restored=false
                scenario.onActivity { restored=(it.findViewById<RecyclerView>(R.id.list).layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()>0 }
                restored
            }
            assertEquals(3,runBlocking { settings.flow.first().font })
        }
    }
    @Test fun customStartsEmptyAcceptsLongTextAndSurvivesRotation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.paste)).perform(click())
            var initial="x"
            scenario.onActivity { initial=it.findViewById<EditText>(R.id.editor).text.toString() }
            assertEquals("",initial)
            val pasted="粘贴文本。English 😀。"
            scenario.onActivity {
                val clipboard=it.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("",pasted))
                val editor=it.findViewById<EditText>(R.id.editor)
                editor.requestFocus()
                editor.onTextContextMenuItem(android.R.id.paste)
                assertEquals(pasted,editor.text.toString())
            }
            val text="广告与债权都应完整保留。English 😀。\n".repeat(1000)
            scenario.onActivity { it.findViewById<EditText>(R.id.editor).setText(text) }
            scenario.recreate()
            waitFor("custom text lost") {
                var actual=""
                scenario.onActivity { actual=it.findViewById<EditText>(R.id.editor).text.toString() }
                actual==text
            }
            shot("custom-${app.resources.configuration.screenWidthDp}")
        }
    }
}
