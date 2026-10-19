package com.newspaper.helper

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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
    @Test fun realNavigationWithParsedFixturesReusesEveryDirectoryOnBackAndCancelsOldLoads() {
        runBlocking {SettingsStore(app).route(jsonCodec.toJson(Route()))}
        val requests=AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            lateinit var model:ReaderViewModel
            fun ready(page:Page) {
                val until=System.currentTimeMillis()+15000
                while(System.currentTimeMillis()<until) {
                    ins.waitForIdleSync()
                    var ok=false
                    scenario.onActivity {val s=model.screen.value;ok=s.route.page==page&&!s.loading;assertFalse("Fixture navigation failed: $s",s.error)}
                    if(ok)return
                    Thread.sleep(25)
                }
                fail("Navigation did not settle on $page")
            }
            scenario.onActivity {activity ->
                model=ViewModelProvider(activity)[ReaderViewModel::class.java]
                val fixtureTransport=object:Transport {
                    override suspend fun load(url:String,json:String?):String {
                        val route=model.screen.value.route
                        requests.incrementAndGet()
                        delay(if("interrupted" in url)600 else 120)
                        val stem=when(route.paper) {Paper.STUDY->"study";Paper.ORGANIZATION->"organization";Paper.PEOPLE->"people";Paper.SOUTH->"south";Paper.QIUSHI->"qiushi"}
                        val fixture=when(route.page) {
                            Page.YEARS->"qiushi-years.html"
                            Page.ISSUES->"qiushi-2026.html"
                            Page.CALENDAR->if(route.paper==Paper.STUDY)"study-calendar.json" else "organization-issues.json"
                            Page.STORIES->if(route.paper==Paper.QIUSHI)"qiushi-issue.html" else "$stem-layout.${if(route.paper==Paper.ORGANIZATION)"json" else "html"}"
                            Page.READER->"$stem-article.${if(route.paper==Paper.ORGANIZATION)"json" else "html"}"
                            else->error("Unexpected fixture request $route $url")
                        }
                        return ins.context.assets.open("sources/$fixture").bufferedReader().use {it.readText()}
                    }
                }
                ArticleRepository::class.java.getDeclaredField("sources").apply {isAccessible=true}.set(model.repository,NewsSources(fixtureTransport))
            }
            ready(Page.HOME)
            Paper.entries.forEach {paper ->
                scenario.onActivity {model.navigate(Route(if(paper==Paper.QIUSHI)Page.YEARS else Page.CALENDAR,paper,month="2026-10"))}
                ready(if(paper==Paper.QIUSHI)Page.YEARS else Page.CALENDAR)
                if(paper==Paper.QIUSHI) {
                    scenario.onActivity {val year=model.screen.value.issues.first();model.navigate(Route(Page.ISSUES,paper,year.label,year.url))}
                    ready(Page.ISSUES)
                }
                scenario.onActivity {
                    val issue=model.screen.value.issues.first {paper==Paper.QIUSHI || it.date==if(paper==Paper.STUDY || paper==Paper.ORGANIZATION)"2026-10-16" else "2026-10-18"}
                    model.navigate(Route(Page.STORIES,paper,issue.label,issue.url,issue.date,month="2026-10"))
                }
                ready(Page.STORIES)
                scenario.onActivity {
                    val story=model.screen.value.stories.first()
                    model.navigate(Route(Page.READER,paper,story.title,story.url,story.date,story=story))
                }
                ready(Page.READER)
                shot("parsed-${paper.name.lowercase()}-${app.resources.configuration.screenWidthDp}")
                val beforeBack=requests.get()
                scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()};ready(Page.STORIES)
                scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()};ready(if(paper==Paper.QIUSHI)Page.ISSUES else Page.CALENDAR)
                assertEquals("Back unexpectedly fetched an already loaded directory",beforeBack,requests.get())
                // 等请求确实开始后再退出，不能只验证尚未开始请求的同帧返回。
                var restored:Route?=null
                scenario.onActivity {activity ->
                    restored=model.screen.value.route
                    model.navigate(Route(Page.STORIES,paper,url="https://example.invalid/interrupted/${paper.name}"))
                }
                val until=System.currentTimeMillis()+10000
                while(requests.get()==beforeBack && System.currentTimeMillis()<until)Thread.sleep(10)
                assertEquals(beforeBack+1,requests.get())
                scenario.onActivity {activity ->
                    activity.onBackPressedDispatcher.onBackPressed()
                    assertEquals(restored,model.screen.value.route)
                }
                Thread.sleep(650);ready(if(paper==Paper.QIUSHI)Page.ISSUES else Page.CALENDAR)
                assertEquals("Canceled request unexpectedly continued fetching",beforeBack+1,requests.get())
                if(paper==Paper.QIUSHI) {scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()};ready(Page.YEARS)}
                scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()};ready(Page.HOME)
            }
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
