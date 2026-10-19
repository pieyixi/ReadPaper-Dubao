package com.newspaper.helper

import android.graphics.Bitmap
import android.view.View
import android.widget.EditText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
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
        File(directory,"${app.resources.configuration.screenWidthDp}-$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)
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
            // 日期、年份、期号、目录四种中间页在加载中和返回后均不得显示功能胶囊。
            for(page in listOf(Page.CALENDAR,Page.YEARS,Page.ISSUES,Page.STORIES)) {
                navigate(Route(page,Paper.QIUSHI,label="2026"))
                waitFor("Actions leaked on $page") { !visible(R.id.actions) && visible(R.id.back) }
                assertFalse(visible(R.id.sheet));assertFalse(visible(R.id.paste));assertFalse(visible(R.id.speech))
                shot("intermediate-${page.name.lowercase()}")
            }
            navigate(Route(Page.HOME));waitFor("Home did not return") { visible(R.id.paste) }
            click(R.id.paste);waitFor("Custom reader missing") { visible(R.id.editor) && visible(R.id.speech) }
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
