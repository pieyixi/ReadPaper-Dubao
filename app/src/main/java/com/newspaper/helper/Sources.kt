package com.newspaper.helper

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpFailure(val code: Int, val address: String) : IOException("HTTP $code: $address")
interface Transport { suspend fun load(url: String, json: String? = null): String }
class NewsHttp : Transport {
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).followSslRedirects(true).build()
    private val permits = Semaphore(4)
    override suspend fun load(url: String, json: String?): String = permits.withPermit {
        require(ContentParser.safeUrl(url, url) != null)
        var failure: Exception? = null
        repeat(3) { attempt ->
            try { return@withPermit request(url, json) }
            catch (e: CancellationException) { throw e }
            catch (e: HttpFailure) { if (e.code in 400..499 && e.code != 429) throw e; failure = e }
            catch (e: IOException) { failure = e }
            if (attempt < 2) delay(350L * (attempt + 1))
        }
        throw checkNotNull(failure)
    }
    private suspend fun request(url: String, json: String?): String = suspendCancellableCoroutine { continuation ->
        val builder = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Referer", url)
        if (url.startsWith(NewsSources.ZZ)) builder.header("Origin", "https://www.zuzhirenshi.com")
        if (json != null) builder.post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
        val call = client.newCall(builder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) throw HttpFailure(response.code, url)
                        val body = response.body ?: throw IOException("Empty response")
                        if (body.contentLength() > 12 * 1024 * 1024) throw IOException("Response too large")
                        body.source().request(12L * 1024 * 1024 + 1)
                        if (body.source().buffer.size > 12L * 1024 * 1024) throw IOException("Response too large")
                        val text = body.string()
                        if (continuation.isActive) continuation.resume(text)
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            }
        })
    }
}

class NewsSources(private val http: Transport = NewsHttp()) {
    companion object {
        const val QS = "https://www.qstheory.cn"
        const val ZZ = "https://www.zuzhirenshi.com/api/welcome"
        fun qiushiArticle(url: String): Boolean = Regex("/(?:\\d{8}/[^/]+/c\\.html|dukan/qs/(?:\\d{4}/)?\\d{4}-\\d{2}/\\d{2}/c_\\d+\\.htm)(?:[?#].*)?$").containsMatchIn(url)
        fun doc(html: String, url: String): Document = Jsoup.parse(html, url)
    }
    suspend fun years(): List<Issue> = withContext(Dispatchers.IO) {
        val url = "$QS/qs/mulu.htm"
        parseYears(http.load(url), url).also { check(it.isNotEmpty()) { "Year index missing" } }
    }
    fun parseYears(html: String, url: String): List<Issue> = doc(html, url).select("a[href]").mapNotNull { a ->
        val year = Regex("^(\\d{4})年$").find(a.text().trim())?.groupValues?.get(1) ?: return@mapNotNull null
        ContentParser.safeUrl(url, a.attr("href"))?.let { Issue(year, it) }
    }.distinctBy { it.label }.sortedByDescending { it.label }

    suspend fun issues(paper: Paper, month: YearMonth = YearMonth.now()): SourceResult<Issue> = withContext(Dispatchers.IO) {
        when (paper) {
            Paper.QIUSHI -> SourceResult(years())
            Paper.STUDY -> {
                // 官网原生日历使用 nav.json；避免 ccps 索引的 WAF，并支持任意历史月份。
                val url = "https://paper.studytimes.cn/cntheory/$month/nav.json"
                val raw = try { http.load(url) } catch (e: HttpFailure) {
                    if (e.code == 404) return@withContext SourceResult<Issue>(emptyList()) else throw e
                }
                SourceResult(parseStudyIssues(raw, month))
            }
            Paper.ORGANIZATION -> {
                val json = JSONObject().put("years", month.year.toString()).put("months", "%02d".format(month.monthValue)).toString()
                SourceResult(parseOrganizationIssues(http.load("$ZZ/selectPastDianZiBao", json)))
            }
            else -> SourceResult((1..month.lengthOfMonth()).map { month.atDay(it) }.filter { !it.isAfter(LocalDate.now()) }
                .map { date -> Issue(date.toString(), layoutUrl(paper, date.toString())) })
        }
    }
    fun parseStudyIssues(raw: String, month: YearMonth): List<Issue> {
        val data = JSONArray(raw)
        check(data.length() in 1..31) { "Invalid calendar index" }
        return (0 until data.length()).mapNotNull { index ->
            val value = data.opt(index)
            if (value !is String) return@mapNotNull null
            val date = Regex("(\\d{4}-\\d{2})/(\\d{2})/node_").find(value)?.let { "${it.groupValues[1]}-${it.groupValues[2]}" } ?: error("Unknown calendar URL")
            check(date.startsWith(month.toString()) && LocalDate.parse(date).dayOfMonth == index + 1)
            Issue(date, layoutUrl(Paper.STUDY, date))
        }
    }
    fun parseOrganizationIssues(json: String): List<Issue> {
        val root = checkedJson(json)
        return root.optJSONArray("data").objects().mapNotNull { o ->
            val date = o.optString("releaseDate").take(10)
            val id = o.optString("id")
            if (date.isBlank() || id.isBlank()) null else Issue(o.optString("columnName"), id, date)
        }
    }
    suspend fun qiushiIssues(year: String, yearUrl: String? = null): List<Issue> = withContext(Dispatchers.IO) {
        val url = yearUrl ?: years().first { it.label == year }.url
        parseQiushiIssues(http.load(url), url).also { check(it.isNotEmpty()) { "Issue structure missing" } }
    }
    fun parseQiushiIssues(html: String, url: String): List<Issue> = doc(html, url).select("#detailContent a[href], .text a[href]").mapNotNull { a ->
        val label = Regex("(\\d{4})年\\s*第\\s*(\\d+)\\s*期").find(a.text()) ?: return@mapNotNull null
        val href = ContentParser.safeUrl(url, a.attr("href")) ?: return@mapNotNull null
        if (!qiushiArticle(href)) null else Issue("${label.groupValues[1]}年第${label.groupValues[2]}期", href)
    }.distinctBy { it.label }.sortedByDescending { Regex("第(\\d+)期").find(it.label)!!.groupValues[1].toInt() }

    fun layoutUrl(paper: Paper, date: String): String {
        LocalDate.parse(date)
        val ymd = date.replace("-", "").let { it.take(6) + "/" + it.takeLast(2) }
        return when (paper) {
            Paper.PEOPLE -> "https://paper.people.com.cn/rmrb/pc/layout/$ymd/node_01.html"
            Paper.SOUTH -> "https://epaper.nfnews.com/nfdaily/html/$ymd/node_A01.html"
            Paper.STUDY -> "https://paper.studytimes.cn/cntheory/${date.take(7)}/${date.takeLast(2)}/node_1.html"
            else -> error("Not a daily layout")
        }
    }

    suspend fun stories(paper: Paper, issue: Issue): SourceResult<Story> = withContext(Dispatchers.IO) {
        if (paper == Paper.ORGANIZATION) return@withContext SourceResult(parseOrganizationStories(http.load("$ZZ/dianZiBaoHomePage", JSONObject().put("id", issue.url).toString()), issue.date))
        if (paper == Paper.QIUSHI) return@withContext SourceResult(parseQiushiStories(http.load(issue.url), issue.url, issue.label).also { check(it.isNotEmpty()) { "Issue articles missing" } })
        val first = try { http.load(issue.url) } catch (e: HttpFailure) {
            if (e.code == 404) return@withContext SourceResult(emptyList()) else throw e
        }
        val nodes = parseSections(first, issue.url)
        check(nodes.isNotEmpty()) { "Section directory missing: ${issue.url}" }
        // 目录来自目标日期，遍历实际全部版面，不套用当天模板或固定前十二版。
        val parts = coroutineScope { nodes.map { section -> async {
            try {
                val html = if (section.url == issue.url) first else http.load(section.url)
                val stories = parseDailyStories(paper, html, section.url, section.label, issue.date)
                if (stories.isEmpty()) error("Section articles missing: ${section.url}")
                SourceResult(stories)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { SourceResult<Story>(emptyList(), listOf(section.url + ": " + e.message)) }
        } }.awaitAll() }
        SourceResult(parts.flatMap { it.items }.distinctBy { it.url }, parts.flatMap { it.failures })
    }
    fun parseSections(html: String, url: String): List<Issue> {
        val document=doc(html,url)
        val catalog=document.select(".layout-catalogue-list a[href], .swiper-slide a[href], a.layout-list-item[href], #list a[href]")
        return (if(catalog.isNotEmpty()) catalog else document.select("a[href]")).mapNotNull { a ->
        if (!Regex("node_[\\w]+\\.html").containsMatchIn(a.attr("href"))) return@mapNotNull null
        val href = ContentParser.safeUrl(url, a.attr("href")) ?: return@mapNotNull null
        if (href.substringBeforeLast('/') != url.substringBeforeLast('/')) null else Issue(a.text().ifBlank { a.attr("href").substringAfterLast('/') }, href)
        }.distinctBy { it.url }
    }
    fun parseDailyStories(paper: Paper, html: String, url: String, section: String, date: String): List<Story> {
        val d = doc(html, url)
        return d.select(".news-list a[href], a.article-list-item[href], #titleList a[href]").mapNotNull { a ->
            if (!Regex("content[_-]?\\d+\\.html").containsMatchIn(a.attr("href"))) return@mapNotNull null
            val href = ContentParser.safeUrl(url, a.attr("href")) ?: return@mapNotNull null
            if (a.text().isBlank()) null else Story(paper, href, a.selectFirst("p")?.text() ?: a.text(), section, date)
        }.distinctBy { it.url }
    }
    fun parseQiushiStories(html: String, url: String, issue: String): List<Story> = doc(html, url).select("#detailContent a[href], .text a[href]").mapNotNull { a ->
        val href = ContentParser.safeUrl(url, a.attr("href")) ?: return@mapNotNull null
        if (!qiushiArticle(href) || a.text().isBlank() || Regex("年\\s*第\\s*\\d+\\s*期").containsMatchIn(a.text())) null
        else Story(Paper.QIUSHI, href, a.text(), "求是", issue, a.parent()?.text()?.substringAfter('/', "").orEmpty())
    }.distinctBy { it.url }
    fun parseOrganizationStories(json: String, date: String): List<Story> {
        val root = checkedJson(json)
        val sections = root.optJSONObject("data")?.optJSONArray("dianzibaoShowList") ?: root.optJSONArray("dianzibaoShowList") ?: error("Section JSON missing")
        return sections.objects().flatMap { section -> section.optJSONArray("areaCoordinateList").objects().mapNotNull { item ->
            val id = item.optString("newsId")
            if (id.isBlank()) null else Story(Paper.ORGANIZATION, "$ZZ/selectNews?id=$id", item.optString("newsName"), section.optString("columnName"), date)
        } }.distinctBy { it.url }
    }
    suspend fun article(story: Story): Article = withContext(Dispatchers.IO) {
        val raw = http.load(story.url)
        if (story.paper != Paper.ORGANIZATION) ContentParser.article(story, raw)
        else {
            val data = checkedJson(raw).optJSONObject("data") ?: error("Article JSON missing")
            val blocks = ContentParser.blocks(data.optString("newContent").ifBlank { data.optString("content") }, story.url)
            check(blocks.isNotEmpty()) { "Empty article" }
            Article(story.copy(title = data.optString("showTitle").ifBlank { story.title }, author = data.optString("author"), date = data.optString("createTime").take(10).ifBlank { story.date }), blocks)
        }
    }
    private fun checkedJson(raw: String): JSONObject = JSONObject(raw).also { check(it.optInt("code") == 200) { "API code ${it.optInt("code")}" } }
}
private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
