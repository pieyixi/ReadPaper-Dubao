package com.newspaper.helper

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth

enum class Page { HOME, CALENDAR, YEARS, ISSUES, STORIES, READER, CUSTOM }
data class Route(val page: Page = Page.HOME, val paper: Paper = Paper.STUDY, val label: String = "", val url: String = "", val date: String = "", val month: String = YearMonth.now().toString(), val story: Story? = null)
data class Screen(val route: Route = Route(), val loading: Boolean = false, val error: Boolean = false, val partial: Boolean = false,
    val issues: List<Issue> = emptyList(), val stories: List<Story> = emptyList(), val article: Article? = null)
class ReaderViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    val repository = ArticleRepository(application)
    private val store = SettingsStore(application)
    private val mutable = MutableStateFlow(Screen())
    val screen = mutable.asStateFlow()
    private val appearance = MutableStateFlow(ReaderSettings())
    val settings = appearance.asStateFlow()
    private var request: Job? = null
    private val stack = mutableListOf<Route>()
    var draft: String = ""
    var readingPosition: ReadingPosition? = null
    var listForQueue: List<Story> = emptyList()
    init {
        viewModelScope.launch {
            val first = store.flow.first()
            val encoded = saved.get<String>("route") ?: first.route
            val route = runCatching { jsonCodec.fromJson(encoded, Route::class.java) }.getOrNull() ?: Route()
            saved.get<ArrayList<String>>("stack")?.forEach { runCatching { stack += jsonCodec.fromJson(it, Route::class.java) } }
            navigate(route, false)
            store.flow.collect { appearance.value = it }
        }
    }
    fun changeSettings(theme: Int = settings.value.theme, font: Int = settings.value.font, line: Int = settings.value.line) {
        viewModelScope.launch { store.appearance(theme, font, line) }
    }
    fun navigate(route: Route, push: Boolean = true) {
        if (push) stack += mutable.value.route
        saved["route"] = jsonCodec.toJson(route)
        saved["stack"] = ArrayList(stack.map(jsonCodec::toJson))
        request?.cancel()
        mutable.value = Screen(route, loading = route.page !in listOf(Page.HOME, Page.CUSTOM))
        readingPosition = null
        request = viewModelScope.launch {
            if (route.page != Page.CUSTOM) store.route(jsonCodec.toJson(route))
            try {
                val next = when (route.page) {
                    Page.HOME, Page.CUSTOM -> Screen(route)
                    Page.CALENDAR -> repository.sources.issues(route.paper, YearMonth.parse(route.month)).let { Screen(route, issues = it.items, partial = !it.complete) }
                    Page.YEARS -> Screen(route, issues = repository.sources.years())
                    Page.ISSUES -> Screen(route, issues = repository.sources.qiushiIssues(route.label, route.url.ifBlank { null }))
                    Page.STORIES -> repository.sources.stories(route.paper, Issue(route.label, route.url, route.date)).let {
                        listForQueue = it.items
                        Screen(route, partial = !it.complete, stories = it.items)
                    }
                    Page.READER -> {
                        val article = repository.article(checkNotNull(route.story))
                        readingPosition = repository.dao.position(article.story.url)
                        Screen(route, article = article)
                    }
                }
                mutable.value = next
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("Reader", "Load failed ${route.url}", e); mutable.value = Screen(route, error = true) }
        }
    }
    fun retry() = navigate(screen.value.route, false)
    fun back(): Boolean {
        if (stack.isNotEmpty()) { navigate(stack.removeAt(stack.lastIndex), false); return true }
        if (screen.value.route.page != Page.HOME) { navigate(Route(), false); return true }
        return false
    }
    fun month(delta: Long) {
        val route = screen.value.route
        navigate(route.copy(month = YearMonth.parse(route.month).plusMonths(delta).toString()), false)
    }
    fun saveScroll(block: Int, offset: Int) {
        val url = screen.value.article?.story?.url ?: return
        readingPosition = (readingPosition ?: ReadingPosition(url)).copy(block=block,offset=offset)
        viewModelScope.launch { repository.dao.readPosition(url, block, offset) }
    }
    suspend fun customArticle(): Article = withContext(Dispatchers.Default) { Segmenter.custom(draft) }
}
