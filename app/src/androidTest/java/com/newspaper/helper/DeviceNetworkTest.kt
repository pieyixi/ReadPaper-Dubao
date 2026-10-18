package com.newspaper.helper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class DeviceNetworkTest {
    @Test fun fiveSourcesOnAndroidWithRealTls(): Unit = runBlocking {
        val source=NewsSources()
        val records=mutableListOf<Any>()
        for(paper in Paper.entries) {
            val date=if(paper in listOf(Paper.STUDY,Paper.ORGANIZATION)) "2026-10-16" else "2026-10-18"
            val issue=if(paper==Paper.QIUSHI) {
                val year=source.years().first { it.label=="2026" }
                source.qiushiIssues("2026",year.url).first { it.label=="2026年第19期" }
            } else source.issues(paper,YearMonth.of(2026,10)).items.first { it.date==date }
            val stories=source.stories(paper,issue)
            assertTrue(stories.failures.toString(),stories.complete)
            assertTrue(stories.items.isNotEmpty())
            val article=source.article(stories.items.first())
            assertTrue(article.blocks.sumOf { it.text.length }>100)
            records+=mapOf("paper" to paper.label,"issue" to issue,"count" to stories.items.size,"article" to article)
        }
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        File(target.getExternalFilesDir(null),"evidence/device-network.json").apply { parentFile!!.mkdirs();writeText(jsonCodec.toJson(records)) }
        val article=source.article(Story(Paper.PEOPLE,"https://paper.people.com.cn/rmrb/pc/content/202610/18/content_30186272.html","",date="2026-10-18"))
        val dao=ReaderDatabase.get(target).reader()
        dao.saveArticle(ArticleRecord(article.story.url,jsonCodec.toJson(article),System.currentTimeMillis()))
        dao.savePosition(ReadingPosition(article.story.url))
        SettingsStore(target).route(jsonCodec.toJson(Route(Page.READER,story=article.story)))
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val end=System.currentTimeMillis()+45000
            var decoded=false
            while(!decoded && System.currentTimeMillis()<end) {
                scenario.onActivity { activity ->
                    val list=activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list)
                    val rows=list.adapter as NativeRows
                    val image=rows.items.indexOfFirst { it.kind==RowKind.IMAGE }
                    if(image>=0) {
                        list.scrollToPosition(image)
                        val holder=list.findViewHolderForAdapterPosition(image) as? NativeRows.Holder
                        val view=holder?.box?.getChildAt(0) as? android.widget.ImageView
                        decoded=view?.drawable!=null
                    }
                }
                Thread.sleep(150)
            }
            assertTrue("Real newspaper image did not decode",decoded)
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(target.getExternalFilesDir(null),"evidence/real-image.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        }
        Unit
    }
}
