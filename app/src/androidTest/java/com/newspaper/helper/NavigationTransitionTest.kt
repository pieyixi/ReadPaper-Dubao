package com.newspaper.helper

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 在渲染边界注入慢加载/快返回，逐帧检查真实 holder，避免联网速度把串页缺陷掩盖。 */
@RunWith(AndroidJUnit4::class)
class NavigationTransitionTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=ins.targetContext
    private val current=MainActivity::class.java.getDeclaredField("current").apply {isAccessible=true}
    private val render=MainActivity::class.java.getDeclaredMethod("render").apply {isAccessible=true}
    private val returning=MainActivity::class.java.getDeclaredField("returning").apply {isAccessible=true}
    private val markers=mutableSetOf<String>()
    private fun texts(view:View):List<String> = when(view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap {texts(view.getChildAt(it))}
        else -> emptyList()
    }
    private fun shot(name:String) {
        ins.waitForIdleSync()
        val file=File(app.getExternalFilesDir(null),"navigation-$name.png")
        file.outputStream().use {ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
        listOf("mkdir -p /sdcard/Download/dubao-topbar-evidence","cp ${file.absolutePath} /sdcard/Download/dubao-topbar-evidence/").forEach {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(it)).use {p->p.readBytes()}
        }
    }
    @Test fun allFiveSourcesForwardBackAndInterruptedLoadsNeverDrawPreviousRows() {
        runBlocking {SettingsStore(app).route(jsonCodec.toJson(Route()))}
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            ins.waitForIdleSync();Thread.sleep(500);ins.waitForIdleSync()
            var wanted:Set<String> = emptySet()
            val badFrames=mutableListOf<String>()
            var frames=0
            lateinit var listener:ViewTreeObserver.OnPreDrawListener
            scenario.onActivity {activity ->
                val list=activity.findViewById<RecyclerView>(R.id.list)
                assertNull("Shared page list must not retain disappearing holders",list.itemAnimator)
                listener=ViewTreeObserver.OnPreDrawListener {
                    frames++
                    val stale=texts(list).filter {value->markers.any {it in value && it !in wanted}}
                    if(stale.isNotEmpty())badFrames+=stale.joinToString()
                    true
                }
                list.viewTreeObserver.addOnPreDrawListener(listener)
            }
            fun show(screen:Screen,allowed:Set<String>,back:Boolean=false) {
                scenario.onActivity {activity ->
                    wanted=allowed
                    returning.setBoolean(activity,back)
                    current.set(activity,screen);render.invoke(activity)
                    val rows=activity.findViewById<RecyclerView>(R.id.list).adapter as NativeRows
                    // 同步边界就必须失效旧 diff，不等待后台 diff 恰好跑完。
                    assertTrue("Old page remained after route commit",rows.currentList.none {row->markers.any {it in row.title && it !in allowed}})
                }
                Thread.sleep(45);ins.waitForIdleSync()
            }
            Paper.entries.forEach {paper ->
                val prefix="NAV_${paper.name}_"
                val year=prefix+"YEAR";val issue=prefix+"ISSUE";val storyTitle=prefix+"STORY";val body=prefix+"BODY"
                markers+=listOf(year,issue,storyTitle,body)
                val story=Story(paper,"https://example.invalid/${paper.name}/article",storyTitle,date="2026-10-19")
                val directory=if(paper==Paper.QIUSHI)Route(Page.ISSUES,paper,label="2026",url="https://example.invalid/2026") else Route(Page.CALENDAR,paper)
                val stories=Route(Page.STORIES,paper,label="2026-10-19",url="https://example.invalid/${paper.name}/issue")
                val reader=Route(Page.READER,paper,story=story,date=story.date)
                val first=if(paper==Paper.QIUSHI)Screen(Route(Page.YEARS,paper),issues=listOf(Issue(year,"https://example.invalid/year"))) else Screen(directory,issues=listOf(Issue("2026-10-19","https://example.invalid/day")))
                val second=Screen(directory,issues=if(paper==Paper.QIUSHI)listOf(Issue(issue,"https://example.invalid/issue")) else first.issues)
                val readyStories=Screen(stories,stories=listOf(story))
                val readyReader=Screen(reader,article=Article(story,List(60){BodyBlock("b$it",BlockKind.PARAGRAPH,"$body $it")}))
                val path=listOf(first,second,readyStories,readyReader)
                fun allowed(screen:Screen)=when(screen.route.page) {Page.YEARS->setOf(year);Page.ISSUES->setOf(issue);Page.STORIES->setOf(storyTitle);Page.READER->setOf(storyTitle,body);else->emptySet()}
                path.forEach {screen ->show(Screen(screen.route,loading=true),emptySet());show(screen,allowed(screen))}
                shot("${paper.name.lowercase()}-${app.resources.configuration.screenWidthDp}-reader")
                path.dropLast(1).reversed().forEach {screen->show(screen,allowed(screen),true)}
                // 入场尚未结束立即跨页/返回；旧加载与 diff 都不能复活。
                repeat(4) {show(Screen(reader,loading=true),emptySet());show(first,allowed(first),true)}
                show(Screen(),emptySet(),true)
            }
            scenario.onActivity {it.findViewById<RecyclerView>(R.id.list).viewTreeObserver.removeOnPreDrawListener(listener)}
            assertTrue("No actual frames were observed",frames>20)
            assertEquals("Previous-page text was drawn",emptyList<String>(),badFrames)
        }
    }
}
