package com.newspaper.helper

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/** 真正点入口，并检查过渡中的每次绘制及屏幕像素，不能只直接 navigate 或拍最终页。 */
@RunWith(AndroidJUnit4::class)
class NavigationColorFlashTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=ins.targetContext
    private fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap {descendants(view.getChildAt(it))} else emptyList()
    private fun waitFor(message:String,check:()->Boolean) {
        val until=SystemClock.uptimeMillis()+10000
        while(SystemClock.uptimeMillis()<until) {if(check())return;Thread.sleep(20)}
        fail(message)
    }
    private fun tap(view:View,scenario:ActivityScenario<MainActivity>) {
        val xy=IntArray(2)
        scenario.onActivity {assertTrue(view.isShown);view.getLocationOnScreen(xy);xy[0]+=view.width/2;xy[1]+=view.height/2}
        val down=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,xy[0].toFloat(),xy[1].toFloat(),0)
            event.source=InputDevice.SOURCE_TOUCHSCREEN
            try {assertTrue(ins.uiAutomation.injectInputEvent(event,true))} finally {event.recycle()}
            if(action==MotionEvent.ACTION_DOWN)Thread.sleep(24)
        }
    }
    private fun export(file:File) {
        listOf("mkdir -p /sdcard/Download/dubao-topbar-evidence","cp ${file.absolutePath} /sdcard/Download/dubao-topbar-evidence/").forEach {command->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(command)).use {it.readBytes()}
        }
    }
    @Test fun actualPaperAndNoteClicksNeverCoverDestinationWithSourceColor() {
        val settings=SettingsStore(app)
        runBlocking {settings.route(jsonCodec.toJson(Route()));settings.appearance(0,2,2)}
        val results=mutableListOf<Map<String,Any>>()
        val evidence=File(app.getExternalFilesDir(null),"color-flash-evidence").apply {mkdirs()}
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            lateinit var model:ReaderViewModel
            lateinit var frame:ViewGroup
            lateinit var listener:ViewTreeObserver.OnPreDrawListener
            var frames=0
            val unexpectedLayers=java.util.concurrent.CopyOnWriteArrayList<String>()
            var originalChildren=emptySet<View>()
            scenario.onActivity {activity->
                assertTrue("Run this regression with Android animations enabled",activity.motionEnabled())
                model=ViewModelProvider(activity)[ReaderViewModel::class.java]
                frame=activity.findViewById(R.id.readerFrame)
                originalChildren=(0 until frame.childCount).map {frame.getChildAt(it)}.toSet()
                listener=ViewTreeObserver.OnPreDrawListener {
                    frames++
                    (0 until frame.childCount).map {frame.getChildAt(it)}.filter {it !in originalChildren}.forEach {
                        unexpectedLayers+="${model.screen.value.route.page}:${it.javaClass.name}"
                    }
                    true
                }
                frame.viewTreeObserver.addOnPreDrawListener(listener)
                // 慢加载扩大可观察窗口；不依赖官网快慢，错误页也绝不能被入口颜色盖住。
                val transport=object:Transport {override suspend fun load(url:String,json:String?):String {
                    delay(500);throw IOException("Controlled slow navigation for color-flash regression")
                }}
                ArticleRepository::class.java.getDeclaredField("sources").apply {isAccessible=true}.set(model.repository,NewsSources(transport))
            }
            fun ready(page:Page) = waitFor("Page did not settle on $page") {
                var ok=false
                scenario.onActivity {ok=model.screen.value.route.page==page&&!model.screen.value.loading}
                ok
            }
            fun open(route:Route) {scenario.onActivity {model.navigate(route)};ready(route.page);Thread.sleep(260)}
            fun find(predicate:(View)->Boolean):View {
                var result:View?=null
                scenario.onActivity {result=descendants(it.window.decorView).firstOrNull(predicate)}
                return checkNotNull(result){"Clickable entry not found"}
            }
            fun checkClick(label:String,source:View,destination:Page,theme:Int) {
                val at=IntArray(2);var width=0;var height=0;var background=0;val before=frames
                scenario.onActivity {activity->frame.getLocationOnScreen(at);width=frame.width;height=frame.height;background=activity.palette.bg}
                val started=SystemClock.uptimeMillis()
                tap(source,scenario)
                scenario.onActivity {
                    assertEquals(destination,model.screen.value.route.page)
                    assertEquals("Click inserted a full-frame overlay: $label",originalChildren,(0 until frame.childCount).map {frame.getChildAt(it)}.toSet())
                }
                val samples=mutableListOf<Map<String,Any>>()
                for(ms in listOf(60L,140L,220L)) {
                    val remaining=started+ms-SystemClock.uptimeMillis()
                    if(remaining>0)Thread.sleep(remaining)
                    val bitmap=checkNotNull(ins.uiAutomation.takeScreenshot())
                    var tinted=0;var count=0
                    // 目标页下四分之一是留白；允许角落加号，禁止大块颜色覆盖留白。
                    for(y in 0 until 12)for(x in 0 until 24) {
                        val px=(at[0]+width*(x+.5)/24).toInt().coerceIn(0,bitmap.width-1)
                        val py=(at[1]+height*(.76+.23*(y+.5)/12)).toInt().coerceIn(0,bitmap.height-1)
                        val color=bitmap.getPixel(px,py)
                        val distance=maxOf(kotlin.math.abs(Color.red(color)-Color.red(background)),kotlin.math.abs(Color.green(color)-Color.green(background)),kotlin.math.abs(Color.blue(color)-Color.blue(background)))
                        if(distance>12)tinted++
                        count++
                    }
                    val ratio=tinted.toDouble()/count
                    samples+=mapOf("elapsed_ms" to SystemClock.uptimeMillis()-started,"tinted_ratio" to ratio)
                    if(ms==140L && (theme==0 || label=="new-note")) {
                        val shot=File(evidence,"click-$theme-$label.png")
                        shot.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};export(shot)
                    }
                    bitmap.recycle()
                    assertTrue("Source color covered destination: theme=$theme $label $samples",ratio<.30)
                }
                ready(destination);Thread.sleep(260)
                assertTrue("Unexpected overlay during animation: $unexpectedLayers",unexpectedLayers.isEmpty())
                assertTrue("No actual transition frames observed",frames>before)
                results+=mapOf("theme" to theme,"entry" to label,"frames" to frames-before,"samples" to samples)
            }
            try {
                for(theme in 0..2) {
                    runBlocking {settings.appearance(theme,2,2)}
                    waitFor("Theme not applied") {var ok=false;scenario.onActivity {ok=it.palette.bg==Palette.forTheme(theme).bg};ok}
                    for(paper in Paper.entries) {
                        open(Route())
                        checkClick(paper.name.lowercase(),find {it.isClickable&&it.contentDescription==paper.label},if(paper==Paper.QIUSHI)Page.YEARS else Page.CALENDAR,theme)
                        scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()};ready(Page.HOME)
                    }
                    open(Route(Page.NOTES))
                    checkClick("new-note",find {it.id==R.id.newNote},Page.CUSTOM,theme)
                    scenario.onActivity {
                        it.findViewById<EditText>(R.id.noteTitle).setText("转场回归")
                        it.findViewById<EditText>(R.id.editor).setText("便签内容。")
                        model.flushDraft()
                        it.onBackPressedDispatcher.onBackPressed()
                    }
                    ready(Page.NOTES);Thread.sleep(300)
                    checkClick("saved-note",find {it.isClickable&&it.tag?.toString()?.startsWith("note-")==true},Page.CUSTOM,theme)
                    scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()};ready(Page.NOTES)
                }
                // 加号刚按下就返回，等待超过旧转场时长后不能重新打开编辑页。
                open(Route(Page.NOTES));tap(find {it.id==R.id.newNote},scenario)
                Thread.sleep(35);scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
                Thread.sleep(650);scenario.onActivity {assertEquals(Page.NOTES,model.screen.value.route.page)}
                assertTrue(unexpectedLayers.toString(),unexpectedLayers.isEmpty())
                val report=File(evidence,"click-color-flash.json")
                report.writeText(jsonCodec.toJson(mapOf("actual_touch_clicks" to true,"unexpected_layers" to unexpectedLayers,"checks" to results,"rapid_back_stable" to true)))
                export(report)
            } finally {scenario.onActivity {frame.viewTreeObserver.removeOnPreDrawListener(listener)}}
        }
    }
}
