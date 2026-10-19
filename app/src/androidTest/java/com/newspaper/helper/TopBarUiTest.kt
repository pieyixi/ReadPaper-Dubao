package com.newspaper.helper

import android.graphics.Bitmap
import android.view.View
import android.widget.EditText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click as tap
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 验证页面操作边界、弹层不挤压正文及空白粘贴页；真实音频另由 OnlinePlaybackTest 验证。 */
@RunWith(AndroidJUnit4::class)
class TopBarUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private fun shell(command:String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun waitFor(message:String,check:()->Boolean) {
        val end=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<end) { instrumentation.waitForIdleSync(); if(check())return;Thread.sleep(100) }
        fail(message)
    }
    private fun shot(name:String) {
        instrumentation.waitForIdleSync();Thread.sleep(250)
        val directory=File(app.getExternalFilesDir(null),"topbar-evidence").apply { mkdirs() }
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        assertEquals("Screenshot framebuffer differs from app display",app.resources.displayMetrics.widthPixels,bitmap.width)
        File(directory,"${app.resources.configuration.screenWidthDp}-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
    @Test fun actionsFollowPageAndSheetsOverlayWithoutBottomBar() {
        val store=SettingsStore(app)
        runBlocking { store.route(jsonCodec.toJson(Route()));store.appearance(0,2,2) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun visible(id:Int):Boolean { var result=false;scenario.onActivity { result=it.findViewById<View>(id).isShown };return result }
            fun click(id:Int) { scenario.onActivity { it.findViewById<View>(id).performClick() };instrumentation.waitForIdleSync() }
            fun navigate(route:Route) { scenario.onActivity { ViewModelProvider(it)[ReaderViewModel::class.java].navigate(route) };instrumentation.waitForIdleSync() }
            waitFor("Home actions missing") { visible(R.id.paste) && visible(R.id.settings) }
            val expected=InstrumentationRegistry.getArguments().getString("expectedWidth")?.toInt()
            scenario.onActivity {
                expected?.let { width -> assertEquals("Wrong activity screen width",width,it.resources.configuration.screenWidthDp) }
                assertEquals("App context differs from activity",app.resources.configuration.screenWidthDp,it.resources.configuration.screenWidthDp)
                val metrics=it.resources.displayMetrics
                assertEquals("Window does not fill display",metrics.widthPixels,it.findViewById<View>(R.id.root).width)
            }
            assertFalse(visible(R.id.speech))
            var height=0
            scenario.onActivity {
                height=it.findViewById<View>(R.id.list).height
                assertEquals("Content still reserves a bottom bar",it.findViewById<View>(R.id.root).height,
                    height+it.findViewById<View>(R.id.toolbar).height)
            }
            for(theme in 0..2) {
                runBlocking { store.appearance(theme,2,2) };Thread.sleep(250)
                shot("home-$theme")
                click(R.id.settings)
                waitFor("Settings sheet missing") { visible(R.id.sheet) }
                scenario.onActivity { assertEquals("Sheet moved the content",height,it.findViewById<View>(R.id.list).height) }
                shot("type-$theme")
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                assertFalse(visible(R.id.sheet)); assertTrue(visible(R.id.paste))
            }
            // 使用实际触摸手势与选项点击，检查新设置面板确实保存设置。
            click(R.id.settings)
            onView(withContentDescription(app.getString(R.string.ui_font))).perform(swipeLeft())
            waitFor("Font gesture was not saved") { runBlocking { store.flow.first().font==0 } }
            onView(withContentDescription(app.getString(R.string.ui_wider))).perform(tap())
            waitFor("Line choice was not saved") { runBlocking { store.flow.first().line==3 } }
            onView(withContentDescription(app.getString(R.string.ui_day))).perform(tap())
            waitFor("Theme choice was not saved") { runBlocking { store.flow.first().theme==0 } }
            click(R.id.dismissPanel)
            scenario.recreate()
            waitFor("Home actions lost after recreation") { visible(R.id.paste) && visible(R.id.settings) }
            assertEquals(0,runBlocking { store.flow.first().font })
            assertEquals(3,runBlocking { store.flow.first().line })
            // 日期、年份、期号、目录四种中间页在加载中和返回后均不得显示功能胶囊。
            for(page in listOf(Page.CALENDAR,Page.YEARS,Page.ISSUES,Page.STORIES)) {
                navigate(Route(page,Paper.QIUSHI,label="2026"))
                waitFor("Actions leaked on $page") { !visible(R.id.actions) && visible(R.id.back) }
                assertFalse(visible(R.id.sheet));assertFalse(visible(R.id.paste));assertFalse(visible(R.id.speech))
                shot("intermediate-${page.name.lowercase()}")
            }
            navigate(Route(Page.HOME));waitFor("Home did not return") { visible(R.id.paste) }
            click(R.id.paste);waitFor("Note history missing") { visible(R.id.noteList) && visible(R.id.newNote) }
            assertFalse(visible(R.id.actions))
            click(R.id.newNote);waitFor("Custom reader missing") { visible(R.id.editor) && visible(R.id.speech) }
            assertTrue(visible(R.id.settings));assertFalse(visible(R.id.paste))
            scenario.onActivity { assertEquals("",it.findViewById<EditText>(R.id.editor).text.toString()) }
            for(theme in 0..2) {
                runBlocking { store.appearance(theme,2,2) };Thread.sleep(250)
                click(R.id.speech);waitFor("Speech sheet missing") { visible(R.id.sheet) }
                assertFalse(visible(R.id.paste))
                shot("speech-empty-$theme")
                click(R.id.settings)
                scenario.onActivity { assertTrue(it.findViewById<View>(R.id.settings).isSelected);assertFalse(it.findViewById<View>(R.id.speech).isSelected) }
                click(R.id.dismissPanel);assertFalse(visible(R.id.sheet))
            }
        }
        // 测试结束卸载前导出界面证据，不包含自定义文本或用户数据。
        shell("mkdir -p /sdcard/Download/dubao-topbar-evidence")
        shell("cp -r /sdcard/Android/data/com.newspaper.helper/files/topbar-evidence/. /sdcard/Download/dubao-topbar-evidence/")
        shell("cp -r /sdcard/Android/data/com.newspaper.helper/files/evidence/. /sdcard/Download/dubao-topbar-evidence/")
    }
}
