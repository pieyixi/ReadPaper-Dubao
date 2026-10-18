package com.newspaper.helper

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth

class SourceRegressionTest {
    private val source = NewsSources(object : Transport { override suspend fun load(url: String, json: String?) = error("Unexpected network: $url") })
    private fun sample(name: String): String = checkNotNull(javaClass.getResource("/sources/$name")).readText()
    private val urls get() = JSONObject(sample("provenance.json"))
    @Test fun allDailyDirectoriesUseActualDateAndAllSections() {
        val study = "https://paper.studytimes.cn/cntheory/2026-10/16/node_1.html"
        val people = "https://paper.people.com.cn/rmrb/pc/layout/202610/18/node_01.html"
        val south = "https://epaper.nfnews.com/nfdaily/html/202610/18/node_A01.html"
        assertEquals(12, source.parseSections(sample("study-layout.html"), study).size)
        assertEquals(8, source.parseSections(sample("people-layout.html"), people).size)
        val pages = source.parseSections(sample("south-layout.html"), south)
        assertEquals(7, pages.size)
        assertTrue(pages.all { "/202610/18/" in it.url })
        assertTrue(source.parseDailyStories(Paper.STUDY,sample("study-layout.html"),study,"一版","2026-10-16").isNotEmpty())
        assertEquals(5,source.parseDailyStories(Paper.SOUTH,sample("south-layout.html"),south,"A01","2026-10-18").size)
    }
    @Test fun oldAndNewQiushiYearsAndProtocolRelativeUrls() {
        assertTrue(source.parseYears(sample("qiushi-years.html"),"https://www.qstheory.cn/qs/mulu.htm").any { it.label=="2022" })
        val issues = source.parseQiushiIssues(sample("qiushi-2022.html"),"https://www.qstheory.cn/dukan/qs/2022-01/01/c_1128224917.htm")
        assertEquals(24,issues.size)
        assertTrue(issues.all { it.url.startsWith("https://www.qstheory.cn/dukan/qs/2014/") })
        val recent=source.parseQiushiIssues(sample("qiushi-2026.html"),"https://www.qstheory.cn/20251231/2d916da295774130ac2fb223fd208895/c.html")
        assertTrue(recent.size>=19)
        assertTrue(source.parseQiushiStories(sample("qiushi-issue.html"),urls.getString("qiushi-issue"),"2026年第19期").size>=20)
    }
    @Test fun realOrganizationJsonFields() {
        val issues=source.parseOrganizationIssues(sample("organization-issues.json"))
        assertTrue(issues.any { it.date=="2026-10-16" })
        val stories=source.parseOrganizationStories(sample("organization-layout.json"),"2026-10-16")
        assertEquals(43,stories.size)
        assertTrue(stories.all { "selectNews?id=" in it.url && it.title.isNotBlank() })
    }
    @Test fun studyOfficialMonthlyCalendarAndOldQiushiBody() {
        val dates = source.parseStudyIssues(sample("study-calendar.json"),YearMonth.of(2026,10))
        assertTrue(dates.any { it.date=="2026-10-16" })
        assertFalse(dates.any { it.date=="2026-10-17" })
        val story=Story(Paper.QIUSHI,"https://www.qstheory.cn/dukan/qs/2019-12/16/c_1125346113.htm","")
        val article=ContentParser.article(story,sample("qiushi-2019-article.html"))
        assertEquals("本期导读",article.story.title)
        assertTrue(article.blocks.sumOf { it.text.length }>100)
    }
    @Test fun realHtmlBodiesPreserveTextAndImages() {
        for ((name,paper) in listOf("study" to Paper.STUDY,"people" to Paper.PEOPLE,"south" to Paper.SOUTH,"qiushi" to Paper.QIUSHI)) {
            val article=ContentParser.article(Story(paper,urls.getString("$name-article"),""),sample("$name-article.html"))
            assertTrue(name,article.story.title.isNotBlank())
            assertTrue(name,article.blocks.sumOf { it.text.length }>100)
            assertEquals(article.blocks.size,article.blocks.map { it.id }.distinct().size)
        }
    }
    @Test fun structureKeepsShortSentencesImageOnlyParagraphsAndLegitimateKeywords() {
        val html="<nav>上一篇</nav><p>好。</p><p>广告法与债权公告是正常新闻。</p><p><img src='../a.jpg'></p><figure><img src='//example.org/b.jpg'><figcaption>图注</figcaption></figure><ol><li>第一项</li><li>第二项</li></ol><blockquote>引用</blockquote><table><tr><th>甲</th><th>乙</th></tr><tr><td>一</td><td>二</td></tr></table>"
        val blocks=ContentParser.blocks(html,"https://example.org/news/a.html")
        assertEquals(listOf(BlockKind.PARAGRAPH,BlockKind.PARAGRAPH,BlockKind.IMAGE,BlockKind.IMAGE,BlockKind.CAPTION,BlockKind.LIST,BlockKind.LIST,BlockKind.QUOTE,BlockKind.TABLE),blocks.map { it.kind })
        assertEquals("好。",blocks[0].text)
        assertTrue(blocks[1].text.contains("债权"))
        assertEquals("https://example.org/a.jpg",blocks[2].url)
        assertEquals("甲\t乙\n一\t二",blocks.last().text)
        assertEquals(blocks[3].parent,blocks[4].parent)
    }
    @Test fun imageAndLinkProtocolsAreRestricted() {
        assertNull(ContentParser.safeUrl("https://example.org/","javascript:alert(1)"))
        assertNull(ContentParser.safeUrl("https://example.org/","file:///sdcard/a"))
        assertNull(ContentParser.safeUrl("https://example.org/","https://user:pass@example.org/a"))
        assertTrue(ContentParser.blocks("<p><img src='data:image/png;base64,a'><img src='https://example.org/a.jpg'></p>","https://example.org/").size==1)
    }
    @Test fun authorMetadataDoesNotMistakePageNumberForPerson() {
        val study=ContentParser.article(Story(Paper.STUDY,urls.getString("study-article"),""),sample("study-article.html"))
        assertTrue(study.story.author.contains("李常官"))
        val people=ContentParser.article(Story(Paper.PEOPLE,"https://paper.people.com.cn/rmrb/pc/content/202610/18/content_30186272.html",""),sample("people-image-article.html"))
        assertFalse(people.story.author.matches(Regex("第\\d+版")))
        assertTrue(people.blocks.any { it.kind==BlockKind.IMAGE })
    }
    @Test fun incompleteSectionsAreNotReportedCompleteAndCancellationPropagates() = runBlocking {
        val root="https://paper.people.com.cn/rmrb/pc/layout/202610/18/node_01.html"
        val fake=object : Transport { override suspend fun load(url: String,json: String?): String {
            if(url.endsWith("node_02.html")) throw java.io.IOException("offline")
            return "<a href='node_01.html'>一</a><a href='node_02.html'>二</a><div class='news-list'><a href='../../../content/202610/18/content_123.html'>广告法的新闻</a></div>"
        } }
        val result=NewsSources(fake).stories(Paper.PEOPLE,Issue("2026-10-18",root))
        assertEquals(1,result.items.size);assertFalse(result.complete);assertEquals(1,result.failures.size)
        val cancellation=NewsSources(object:Transport { override suspend fun load(url:String,json:String?):String = awaitCancellation() })
        val request=async { cancellation.stories(Paper.PEOPLE,Issue("2026-10-18",root)) }
        delay(20);request.cancelAndJoin();assertTrue(request.isCancelled)
    }
    @Test fun missingIssueIsEmptyButNetworkFailureIsNotEmpty() = runBlocking {
        val issue=Issue("2026-10-18","https://example.org/node_01.html")
        val missing=NewsSources(object:Transport { override suspend fun load(url:String,json:String?):String { throw HttpFailure(404,url) } })
        assertTrue(missing.stories(Paper.PEOPLE,issue).items.isEmpty())
        val denied=NewsSources(object:Transport { override suspend fun load(url:String,json:String?):String { throw HttpFailure(403,url) } })
        try { denied.stories(Paper.PEOPLE,issue);fail("403 must not become empty") } catch(e:HttpFailure) { assertEquals(403,e.code) }
    }
    @Test fun coldQiushiIssueUsesUrlWithoutPriorYearState() = runBlocking {
        val url=urls.getString("qiushi-issue")
        val cold=NewsSources(object:Transport { override suspend fun load(address:String,json:String?):String { assertEquals(url,address);return sample("qiushi-issue.html") } })
        assertTrue(cold.stories(Paper.QIUSHI,Issue("2026年第19期",url)).items.size>=20)
    }
    @Test fun speechSegmentsKeepAllCustomTextAndStableBlockMapping() {
        val text="广告 债权。\n"+"中文😀很长；".repeat(1500)+"尾巴。"
        val article=Segmenter.custom(text)
        val pieces=Segmenter.split(article,127)
        assertTrue(pieces.size>10)
        assertTrue(pieces.all { it.text.length<=127 && !Character.isHighSurrogate(it.text.last()) })
        assertEquals(article.blocks.joinToString("") { it.text },pieces.joinToString("") { it.text })
        assertTrue(pieces.all { piece -> article.blocks.any { it.id==piece.blockId } })
        assertEquals(pieces,Segmenter.split(article,127))
        assertTrue(Segmenter.split(Segmenter.custom(" \n\t")).isEmpty())
    }
}
