package com.newspaper.helper

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.YearMonth

enum class Page { HOME, CALENDAR, YEARS, ISSUES, STORIES, READER, CUSTOM, NOTES }
data class Route(val page: Page = Page.HOME, val paper: Paper = Paper.STUDY, val label: String = "", val url: String = "", val date: String = "", val month: String = YearMonth.now().toString(), val story: Story? = null,val noteId:String?=null)
data class Screen(val route: Route = Route(), val loading: Boolean = false, val error: Boolean = false, val partial: Boolean = false,
    val issues: List<Issue> = emptyList(), val stories: List<Story> = emptyList(), val article: Article? = null,
    val notes:List<NotePreview> = emptyList(),val selecting:Boolean=false,val selected:Set<String> = emptySet(),val noteError:Boolean=false)
class ReaderViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    val repository = ArticleRepository(application)
    private val store = SettingsStore(application)
    private val mutable = MutableStateFlow(Screen(loading=true))
    val screen = mutable.asStateFlow()
    private val appearance = MutableStateFlow(ReaderSettings())
    val settings = appearance.asStateFlow()
    // 通知冷启动须等持久路由恢复发起后再导航，避免迟到的恢复覆盖正在播放的目标。
    val initialized=CompletableDeferred<Unit>()
    private var request: Job? = null
    private var navigationRevision=0L
    // 完整目录最多八页、五分钟，返回不必重走加载；到期及重试仍能获取官网新内容。
    private val directories=object:LinkedHashMap<Route,Pair<Screen,Long>>(8,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Route,Pair<Screen,Long>>?)=size>8
    }
    private val stack = mutableListOf<Route>()
    var draft: String = ""
        private set
    var draftTitle:String=""
        private set
    var draftUpdated:Long=System.currentTimeMillis()
        private set
    private var draftCreated:Long=draftUpdated
    private var draftReady=false
    private var draftPinned=false
    private var draftExists=false
    private var debounce:Job?=null
    // 已离开页面的最后一次保存仍须完成；按创建顺序串行落库，避免旧快照覆盖新稿。
    private val noteWrites=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var lastWrite:Job?=null
    var readingPosition: ReadingPosition? = null
    var listForQueue: List<Story> = emptyList()
    init {
        viewModelScope.launch {
            val first = store.flow.first()
            val encoded = saved.get<String>("route") ?: first.route
            val route = runCatching { jsonCodec.fromJson(encoded, Route::class.java) }.getOrNull() ?: Route()
            saved.get<ArrayList<String>>("stack")?.forEach { runCatching { stack += jsonCodec.fromJson(it, Route::class.java) } }
            navigate(route, false)
            initialized.complete(Unit)
            store.flow.collect { appearance.value = it }
        }
    }
    fun changeSettings(theme: Int = settings.value.theme, font: Int = settings.value.font, line: Int = settings.value.line) {
        viewModelScope.launch { store.appearance(theme, font, line) }
    }
    fun navigate(route: Route, push: Boolean = true) {
        if(route==mutable.value.route && request?.isActive==true) return
        flushDraft()
        if (push) stack += mutable.value.route
        saved["route"] = jsonCodec.toJson(route)
        saved["stack"] = ArrayList(stack.map(jsonCodec::toJson))
        request?.cancel()
        val revision=++navigationRevision
        draftReady=false
        if(route.page==Page.CUSTOM) { draft="";draftTitle="";draftUpdated=System.currentTimeMillis();draftCreated=draftUpdated;draftExists=false;draftPinned=false }
        val cached=directories[route]?.takeIf {android.os.SystemClock.elapsedRealtime()-it.second<300000}?.first
        mutable.value = cached ?: Screen(route, loading = route.page != Page.HOME)
        readingPosition = null
        request = viewModelScope.launch {
            if (route.page != Page.CUSTOM) store.route(jsonCodec.toJson(route))
            try {
                if(cached!=null) {if(route.page==Page.STORIES)listForQueue=cached.stories;return@launch}
                if(route.page==Page.NOTES) {
                    repository.dao.notes().collect { notes ->
                        coroutineContext.ensureActive()
                        if(revision==navigationRevision)mutable.value=mutable.value.copy(route=route,loading=false,notes=notes)
                    }
                    return@launch
                }
                val next = when (route.page) {
                    Page.HOME,Page.NOTES -> Screen(route)
                    Page.CUSTOM -> {
                        lastWrite?.join()
                        val note=route.noteId?.let { repository.dao.note(it) }
                        draft=note?.content.orEmpty();draftTitle=note?.title.orEmpty()
                        draftExists=note!=null;draftCreated=note?.created ?: System.currentTimeMillis()
                        draftUpdated=note?.updated ?: draftCreated;draftPinned=note?.pinned ?: false;draftReady=true
                        Screen(route)
                    }
                    Page.CALENDAR -> repository.sources.issues(route.paper, YearMonth.parse(route.month)).let { Screen(route, issues = it.items, partial = !it.complete) }
                    Page.YEARS -> Screen(route, issues = repository.sources.years())
                    Page.ISSUES -> Screen(route, issues = repository.sources.qiushiIssues(route.label, route.url.ifBlank { null }))
                    Page.STORIES -> repository.sources.stories(route.paper, Issue(route.label, route.url, route.date)).let {
                        coroutineContext.ensureActive()
                        listForQueue = it.items
                        Screen(route, partial = !it.complete, stories = it.items)
                    }
                    Page.READER -> {
                        val article = repository.article(checkNotNull(route.story))
                        val position=repository.dao.position(article.story.url)
                        coroutineContext.ensureActive()
                        readingPosition = position
                        Screen(route, article = article)
                    }
                }
                coroutineContext.ensureActive()
                if(revision==navigationRevision) {
                    if(route.page in listOf(Page.CALENDAR,Page.YEARS,Page.ISSUES,Page.STORIES) && !next.partial)directories[route]=next to android.os.SystemClock.elapsedRealtime()
                    mutable.value = next
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("Reader", "Load failed ${route.url}", e); if(revision==navigationRevision)mutable.value = Screen(route, error = true) }
        }
    }
    fun retry() {directories.remove(screen.value.route);navigate(screen.value.route, false)}
    fun back(): Boolean {
        if(screen.value.selecting) { selection();return true }
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
    fun newNote() {
        if(screen.value.route.page!=Page.NOTES) return
        navigate(Route(Page.CUSTOM,noteId=java.util.UUID.randomUUID().toString()))
    }
    fun updateDraft(title:String,text:String) {
        if(!draftReady || screen.value.route.page!=Page.CUSTOM) return
        draftTitle=title;draft=text;draftUpdated=System.currentTimeMillis()
        debounce?.cancel();debounce=viewModelScope.launch { delay(250);saveDraft() }
    }
    fun flushDraft() { debounce?.cancel();if(draftReady && screen.value.route.page==Page.CUSTOM) saveDraft() }
    private fun saveDraft() {
        val id=screen.value.route.noteId ?: return
        if(!draftExists && draft.isBlank() && draftTitle.isBlank()) return
        val note=PastedNote(id,draftTitle,draft,draftCreated,draftUpdated,draftPinned)
        draftExists=true
        val prior=lastWrite
        lastWrite=noteWrites.launch {
            prior?.join()
            try { repository.dao.saveNote(note) }
            catch(e:Exception) { withContext(Dispatchers.Main) { mutable.value=mutable.value.copy(noteError=true) } }
        }
    }
    fun selection(id:String?=null) {
        val s=screen.value
        mutable.value=if(id==null) s.copy(selecting=false,selected=emptySet())
            else s.copy(selecting=true,selected=if(id in s.selected) s.selected-id else s.selected+id)
    }
    fun selectAll() { val s=screen.value;mutable.value=s.copy(selected=if(s.selected.size==s.notes.size) emptySet() else s.notes.map { it.id }.toSet()) }
    fun pinNotes() {
        val s=screen.value;if(s.selected.isEmpty())return
        val pin=!s.notes.filter { it.id in s.selected }.all { it.pinned }
        viewModelScope.launch { try { lastWrite?.join();repository.dao.pinNotes(s.selected.toList(),pin);selection() } catch(e:Exception) { mutable.value=mutable.value.copy(noteError=true) } }
    }
    fun deleteNotes() {
        val ids=screen.value.selected.toList();if(ids.isEmpty())return
        viewModelScope.launch { try {
            lastWrite?.join();repository.dao.deleteNotes(ids)
            SpeechAudioCache(SpeechAudioCache.root(getApplication())).deleteNotes(ids)
            selection()
        } catch(e:Exception) { mutable.value=mutable.value.copy(noteError=true) } }
    }
    suspend fun customArticle(): Article {val title=draftTitle;val body=draft;return withContext(Dispatchers.Default) {Segmenter.custom(listOf(title,body).filter(String::isNotBlank).joinToString("\n"))}}
}
