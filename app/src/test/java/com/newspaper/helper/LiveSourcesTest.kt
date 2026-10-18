package com.newspaper.helper

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.YearMonth

/** 仅 DUBAO_LIVE=1 时运行；报告明确记录真实日期/URL/标题/块数，不以 200 代替正文验证。 */
class LiveSourcesTest {
    private val directory=File("/workspace/native-evidence/network").apply { mkdirs() }
    private val http=NewsHttp()
    private val captured=object:Transport {
        override suspend fun load(url:String,json:String?):String {
            val body=http.load(url,json)
            File(directory,"${stableId(url+(json?:""))}.txt").writeText(body)
            return body
        }
    }
    private suspend fun daily(paper:Paper,date:String) {
        val source=NewsSources(captured)
        val issues=source.issues(paper,YearMonth.parse(date.take(7)))
        val issue=issues.items.firstOrNull { it.date==date } ?: Issue(date,source.layoutUrl(paper,date),date)
        val stories=source.stories(paper,issue)
        check(stories.complete) { "Partial: ${stories.failures}" }
        assertTrue(stories.items.isNotEmpty())
        val article=source.article(stories.items.first())
        assertTrue(article.blocks.sumOf { it.text.length }>100)
        val imageStory=stories.items.drop(1).take(5).firstNotNullOfOrNull { candidate ->
            val a=source.article(candidate)
            a.takeIf { it.blocks.any { b -> b.kind==BlockKind.IMAGE } }
        }
        val evidence=mapOf("paper" to paper.label,"date" to date,"issueUrl" to issue.url,"articleCount" to stories.items.size,
            "article" to article,"imageArticle" to imageStory,"failures" to stories.failures)
        File(directory,"${paper.name}-$date.json").writeText(jsonCodec.toJson(evidence))
        println("${paper.label}: ${stories.items.size} articles; ${article.story.title}; ${article.story.url}; ${article.blocks.size} blocks; imageSample=${imageStory?.story?.url}")
    }
    @Test fun study()=runBlocking { daily(Paper.STUDY,"2026-10-16") }
    @Test fun organization()=runBlocking { daily(Paper.ORGANIZATION,"2026-10-16") }
    @Test fun people()=runBlocking { daily(Paper.PEOPLE,"2026-10-18") }
    @Test fun south()=runBlocking { daily(Paper.SOUTH,"2026-10-18") }
    @Test fun history()=runBlocking { daily(Paper.SOUTH,"2026-10-01") }
    @Test fun qiushi()=runBlocking {
        val source=NewsSources(captured)
        val years=source.years()
        val records=mutableListOf<Any>()
        for(year in listOf("2019","2022","2026")) {
            val url=years.first { it.label==year }.url
            val issues=source.qiushiIssues(year,url)
            if(year!="2026") assertEquals(24,issues.size)
            val issue=if(year=="2026") issues.first { it.label=="2026年第19期" } else issues.first()
            val articles=source.stories(Paper.QIUSHI,issue)
            assertTrue(articles.items.isNotEmpty())
            val article=source.article(articles.items.first())
            assertTrue(article.blocks.sumOf { it.text.length }>100)
            records+=mapOf("year" to year,"issues" to issues.size,"issue" to issue,"articles" to articles.items.size,"article" to article)
            println("求是 $year: ${issues.size} issues; ${articles.items.size} articles; ${article.story.title}; ${article.story.url}; ${article.blocks.size} blocks")
        }
        File(directory,"QIUSHI.json").writeText(jsonCodec.toJson(records))
    }
}
