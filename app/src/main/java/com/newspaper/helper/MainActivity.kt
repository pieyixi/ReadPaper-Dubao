package com.newspaper.helper

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.doOnPreDraw
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import com.newspaper.helper.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : AppCompatActivity() {
    private val model: ReaderViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding
    private lateinit var rows: NativeRows
    private var controller: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var current = Screen()
    private var appearance = ReaderSettings()
    private var appearanceApplied=false
    private var status = PlaybackStatus()
    private var lastRoute: Route? = null
    private var restoring = false
    private lateinit var chrome: ReadingChrome
    private lateinit var notes:NativeNotes
    private lateinit var motion:NativeMotion
    private var bindingDraft=false
    private var visualRoute:Route?=null
    private var returning=false
    private var enterRoute:Route?=null
    private var enterBack=false
    private var renderRevision=0L
    private var keyboardVisible=false
    private val directoryScroll=mutableMapOf<Route,Pair<Int,Int>>()
    private var lastNoteError=false
    private var speechRevision=0L
    private var noteEditJob:Job?=null
    private var pendingEditNote:String?=null
    private var pendingEditToken:String?=null
    private var pendingSpeech: SpeechRequest? = null
    private var voiceSettingsOpened = false
    private var pendingPlayingTarget:String?=null
    private var openPlayingJob:Job?=null
    val palette: Palette get() = Palette.forTheme(appearance.theme)
    fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        motion=NativeMotion(this)
        notes=NativeNotes(this,binding,model)
        chrome = ReadingChrome(this,binding,{ controller },
            { theme,font,line -> model.changeSettings(theme ?: appearance.theme,font ?: appearance.font,line ?: appearance.line) },
            ::playOrPause,::command)
        rows = NativeRows(this, ::rowClick)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = rows
        // 页面共用一个列表，行增删动画会把上一页的 holder 留在下一页；便签的重排动画独立保留。
        binding.list.itemAnimator = null
        binding.list.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE && !restoring && current.article != null) {
                    val lm = binding.list.layoutManager as LinearLayoutManager
                    val pos = lm.findFirstVisibleItemPosition()
                    model.saveScroll(pos, lm.findViewByPosition(pos)?.top ?: 0)
                }
            }
            override fun onScrolled(recyclerView:androidx.recyclerview.widget.RecyclerView,dx:Int,dy:Int) { toolbarScrolled(recyclerView.canScrollVertically(-1)) }
        })
        binding.back.glyph = Glyph.BACK
        binding.back.contentDescription = getString(R.string.a11y_back)
        binding.back.setOnClickListener { back() }
        binding.paste.glyph = Glyph.PASTE
        binding.paste.contentDescription = getString(R.string.a11y_custom)
        binding.paste.setOnClickListener { model.navigate(Route(Page.NOTES)) }
        binding.nowPlaying.glyph=Glyph.HEADPHONES
        binding.nowPlaying.contentDescription=getString(R.string.a11y_now_playing)
        binding.nowPlaying.setOnClickListener {openPlaying()}
        binding.settings.contentDescription = getString(R.string.a11y_settings)
        binding.editor.contentDescription = getString(R.string.a11y_editor)
        binding.editor.setText(model.draft)
        binding.editorPanel.isFocusableInTouchMode=true
        listOf(binding.noteTitle,binding.editor).forEach {field ->
            var downX=0f;var downY=0f;var dragged=false
            val slop=android.view.ViewConfiguration.get(this).scaledTouchSlop
            field.setOnFocusChangeListener {_,focused ->field.isCursorVisible=focused}
            // 阅读态的滑动不能顺便唤起输入法；确认是轻点后才恢复编辑和系统粘贴能力。
            field.setOnTouchListener {_,event ->
                when(event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN->{downX=event.x;downY=event.y;dragged=false}
                    android.view.MotionEvent.ACTION_MOVE->if(kotlin.math.abs(event.x-downX)>slop || kotlin.math.abs(event.y-downY)>slop)dragged=true
                    android.view.MotionEvent.ACTION_UP->if(!dragged && !field.hasFocus()) {
                        field.isFocusableInTouchMode=true;field.requestFocus();field.isCursorVisible=true
                        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field,InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                false
            }
        }
        binding.root.viewTreeObserver.addOnGlobalLayoutListener {
            // 旧版 Android 的 Decor 会先消费传给内容区的 IME Insets，必须读取窗口根状态。
            val insets=ViewCompat.getRootWindowInsets(binding.root)
            val visible=if(insets!=null)insets.isVisible(WindowInsetsCompat.Type.ime()) else {
                val visibleFrame=android.graphics.Rect()
                window.decorView.getWindowVisibleDisplayFrame(visibleFrame)
                window.decorView.rootView.height-visibleFrame.bottom>dp(120)
            }
            val wasVisible=keyboardVisible
            keyboardVisible=visible
            if(wasVisible && !visible && current.route.page==Page.CUSTOM)finishNoteEditing()
        }
        binding.editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if(bindingDraft)return
                speechRevision++
                model.updateDraft(binding.noteTitle.text.toString(),s?.toString().orEmpty())
                noteMetadata()
                prepareEditedNote(start,false,count)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.noteTitle.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun afterTextChanged(s:Editable?)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                if(bindingDraft)return
                speechRevision++
                model.updateDraft(s?.toString().orEmpty(),binding.editor.text.toString());noteMetadata()
                prepareEditedNote(start,true,count)
            }
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { back() } })
        connectController()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.screen.collect { screen -> current = screen; render() } }
                launch { model.settings.collect { settings ->
                    val changed=appearance.theme!=settings.theme || appearance.font!=settings.font || appearance.line!=settings.line
                    appearance=settings
                    // 保存导航地址也会触发设置流，不能因此重建当前页的全部文字和布局。
                    if(changed || !appearanceApplied) {appearanceApplied=true;applyAppearance();render()}
                } }
                launch { PlaybackSignals.state.collect { playback ->
                    status = playback
                    highlightNotePlayback(playback,playback.playing)
                    rows.highlight = if (playback.article == current.article?.story?.url) playback.block else ""
                    chrome.playback(playback)
                    updateNowPlaying()
                    if (playback.playing && playback.article == current.article?.story?.url) {
                        val position = rows.items.indexOfFirst { it.block?.id == playback.block }
                        val lm = binding.list.layoutManager as LinearLayoutManager
                        if (position >= 0 && position !in lm.findFirstVisibleItemPosition()..lm.findLastVisibleItemPosition() && binding.list.scrollState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) binding.list.smoothScrollToPosition(position)
                    }
                } }
                launch { while(true) { chrome.updateProgress(); kotlinx.coroutines.delay(500) } }
            }
        }
        if(intent?.action==ReadAloudService.OPEN_PLAYING)openPlaying()
    }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent);setIntent(intent)
        if(intent.action==ReadAloudService.OPEN_PLAYING)openPlaying()
    }
    private fun playingRoute(playback:PlaybackStatus):Route? {
        if(playback.article.isBlank())return null
        playback.noteId?.let {return Route(Page.CUSTOM,noteId=it)}
        return playback.story?.takeUnless {it.url.startsWith("custom:")}?.let {Route(Page.READER,it.paper,date=it.date,story=it)}
    }
    private fun targetKey(route:Route)=if(route.page==Page.CUSTOM)"note:${route.noteId}" else route.story?.url
    private fun openPlaying() {
        // 只读取当前服务状态，不把建立通知时的旧篇目固化在 PendingIntent 中。
        intent?.takeIf {it.action==ReadAloudService.OPEN_PLAYING}?.action=null
        openPlayingJob?.cancel()
        openPlayingJob=lifecycleScope.launch {
            model.initialized.await()
            val destination=playingRoute(PlaybackSignals.state.value) ?: return@launch
            pendingPlayingTarget=targetKey(destination)
            chrome.close()
            if(targetKey(current.route)==pendingPlayingTarget && current.route.page==destination.page)focusPlayingIfReady()
            else model.navigate(destination)
        }
    }
    private fun updateNowPlaying() {
        val playback=PlaybackSignals.state.value
        val visible=current.route.page==Page.HOME && playback.playWhenReady && !playback.failed && !playback.voiceMissing && playingRoute(playback)!=null
        binding.nowPlaying.isVisible=visible
        binding.nowPlaying.playing=visible&&lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
    private fun highlightNotePlayback(playback:PlaybackStatus,scroll:Boolean) {
        if(current.route.page!=Page.CUSTOM)return
        val edit=binding.editor.text
        edit.getSpans(0,edit.length,android.text.style.BackgroundColorSpan::class.java).forEach {edit.removeSpan(it)}
        val rawLine=if(playback.noteId==current.route.noteId)playback.block.removePrefix("custom-").toIntOrNull() else null
        val line=rawLine?.minus(if(model.draftTitle.isNotBlank())1 else 0)
        val highlight=if(appearance.theme==1)0xff273848.toInt() else 0xffedf3f8.toInt()
        binding.noteTitle.setBackgroundColor(if(line==-1 && playback.article.startsWith("custom:"))highlight else Color.TRANSPARENT)
        if(line!=null && line>=0 && playback.article.startsWith("custom:")) {
            var start=0
            repeat(line) {start=edit.indexOf('\n',start).let {if(it<0)edit.length else it+1}}
            val end=edit.indexOf('\n',start).let {if(it<0)edit.length else it}
            if(start<end) {
                edit.setSpan(android.text.style.BackgroundColorSpan(highlight),start,end,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if(scroll && !binding.editor.hasFocus() && !binding.noteTitle.hasFocus())binding.editor.bringPointIntoView(start)
            }
        }
    }
    private fun focusPlayingIfReady() {
        if(current.loading || pendingPlayingTarget==null || targetKey(current.route)!=pendingPlayingTarget)return
        val playback=PlaybackSignals.state.value
        val destination=playingRoute(playback) ?: return
        if(targetKey(destination)!=pendingPlayingTarget) {pendingPlayingTarget=null;return}
        if(current.route.page==Page.CUSTOM) {
            binding.editor.doOnPreDraw {highlightNotePlayback(PlaybackSignals.state.value,true)}
        } else {
            val index=rows.items.indexOfFirst {it.block?.id==playback.block}
            if(index<0)return
            (binding.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index,dp(8))
        }
        pendingPlayingTarget=null
    }
    private fun connectController() {
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, ReadAloudService::class.java))).buildAsync()
        controllerFuture = future
        future.addListener({
            runCatching { controller = future.get(); pendingSpeech?.let { sendSpeech(it) }; pendingSpeech = null }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun back() {
        motion.cancel()
        if (chrome.close()) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(binding.editor.windowToken, 0)
        returning=true
        if (!model.back()) finish()
        if(model.screen.value.route==current.route)returning=false
    }
    fun deletedNotes(ids:Set<String>) {if(status.noteId in ids || pendingEditNote in ids)command("stop")}
    private fun cancelNoteEdit() {
        noteEditJob?.cancel();noteEditJob=null;pendingEditNote=null;pendingEditToken=null
    }
    private fun prepareEditedNote(offset:Int,titleChanged:Boolean,insertedCount:Int) {
        val id=current.route.noteId ?: return
        if(pendingEditNote!=id && !(status.article.startsWith("custom:") && status.noteId==id))return
        noteEditJob?.cancel()
        val title=model.draftTitle;val body=model.draft
        val token=java.util.UUID.randomUUID().toString()
        pendingEditNote=id;pendingEditToken=token
        // 先取消旧稿，再防抖预取最新原文；令牌由服务校验，停止后迟到的编辑不能自行出声。
        command("invalidateNote",Bundle().apply {putString("noteId",id);putString("token",token)})
        noteEditJob=lifecycleScope.launch {
            try {
                delay(600)
                val line=withContext(Dispatchers.Default) {
                    val start=offset.coerceIn(0,body.length)
                    // 插入内容以换行开头时，预备新增段落，不能退回换行前的旧段。
                    val firstText=(start until (start+insertedCount).coerceAtMost(body.length)).firstOrNull {!body[it].isWhitespace()} ?: start
                    if(titleChanged)0 else body.take(firstText).count {it=='\n'}+if(title.isNotBlank())1 else 0
                }
                speakDraft(title,body,id,startBlock="custom-$line",editToken=token)
            } finally {
                if(pendingEditToken==token) {pendingEditToken=null;pendingEditNote=null;noteEditJob=null}
            }
        }
    }
    private suspend fun speakDraft(title:String,body:String,noteId:String?,revision:Long=speechRevision,startBlock:String?=null,editToken:String?=null) {
        val draft=withContext(Dispatchers.Default) {listOf(title,body).filter(String::isNotBlank).joinToString("\n")}
        if(draft.isBlank()) {command("stop");return}
        val input=File(File(cacheDir,"speech-input"),stableId(java.util.UUID.randomUUID().toString())+".txt")
        var submitted=false
        try {
            withContext(Dispatchers.IO) {input.parentFile?.mkdirs();input.writeText(draft)}
            if(if(editToken!=null)pendingEditToken==editToken else revision==speechRevision) {
                sendSpeech(SpeechRequest(custom=input.name,noteId=noteId,startBlock=startBlock,editToken=editToken));submitted=true
            }
        } finally {
            if(!submitted)withContext(NonCancellable+Dispatchers.IO) {input.delete()}
        }
    }
    fun toolbarScrolled(scrolled:Boolean) {binding.toolbar.elevation=if(scrolled)dp(1).toFloat() else 0f}
    private fun noteMetadata() {
        val title=binding.noteTitle.text.toString();val body=binding.editor.text.toString()
        // 字数只统计非空白 Unicode 码点，正文扫描放到后台，旧结果不得覆盖更新后的输入。
        lifecycleScope.launch {
            val count=withContext(Dispatchers.Default){NoteText.count(title)+NoteText.count(body)}
            if(title!=binding.noteTitle.text.toString()||body!=binding.editor.text.toString())return@launch
            binding.noteMetadata.text=java.text.SimpleDateFormat("M月d日 HH:mm",java.util.Locale.SIMPLIFIED_CHINESE).format(java.util.Date(model.draftUpdated))+"  |  "+getString(R.string.ui_note_words,count)
        }
    }
    private fun finishNoteEditing() {
        model.flushDraft()
        listOf(binding.noteTitle,binding.editor).forEach {it.clearFocus();it.isCursorVisible=false;it.isFocusable=false}
        binding.editorPanel.requestFocus()
    }
    private fun applyAppearance() {
        val p = palette
        binding.root.setBackgroundColor(p.bg)
        binding.title.setTextColor(p.text)
        binding.settings.setTextColor(p.muted)
        binding.editor.setTextColor(p.text)
        binding.noteTitle.setTextColor(p.text);binding.noteTitle.setHintTextColor(p.faint)
        binding.noteMetadata.setTextColor(p.faint)
        binding.selectionHeading.setTextColor(p.text)
        binding.toolbar.setBackgroundColor(p.bg)
        binding.editor.textSize = floatArrayOf(15f,17f,19f,21f)[appearance.font]
        binding.editor.readerLineHeight(floatArrayOf(1.65f,1.85f,2.05f,2.25f)[appearance.line])
        binding.back.ink = p.muted; binding.paste.ink = p.muted
        window.statusBarColor = p.bg; window.navigationBarColor = p.bg
        if (Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = if (appearance.theme == 1) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        chrome.appearance(appearance)
        binding.nowPlaying.background=rounded(ChromeColors.forTheme(appearance.theme).accent,99)
        binding.nowPlaying.ink=Color.WHITE
        rows.appearance = appearance
    }
    private fun playOrPause() {
        if(current.route.page==Page.CUSTOM && ((pendingEditNote==current.route.noteId && pendingEditToken!=null) ||
                (status.noteId==current.route.noteId && status.article.startsWith("custom:") && status.preparing))) {
            command("toggle");return
        }
        if (current.route.page==Page.READER && current.article==null) return
        if (status.voiceMissing) {
            voiceSettingsOpened = true
            runCatching { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }.recoverCatching {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            }
            return
        }
        if (current.route.page != Page.CUSTOM && current.article == null && controller?.mediaItemCount == 0) { command("resumeQueue");return }
        lifecycleScope.launch {
            val revision=speechRevision
            val target = if (current.route.page == Page.CUSTOM) model.customArticle().story.url else current.article?.story?.url
            if(revision!=speechRevision)return@launch
            if (target == status.article && status.preparing) { command("toggle"); return@launch }
            if (target != null && target != status.article || status.failed || controller?.mediaItemCount == 0) {
                if (current.route.page == Page.CUSTOM) {
                    val title=model.draftTitle;val body=model.draft
                    speakDraft(title,body,current.route.noteId,revision)
                }
                else current.article?.let { sendSpeech(SpeechRequest(stories = listOf(it.story))) }
            } else {
                if (controller?.playWhenReady == true) controller?.pause() else controller?.play()
            }
        }
    }
    private fun command(action: String, bundle: Bundle = Bundle.EMPTY) {
        if(action=="stop") {cancelNoteEdit();speechRevision++;pendingSpeech=null}
        controller?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), bundle)
    }
    private fun sendSpeech(request: SpeechRequest) {
        if (controller == null) { pendingSpeech = request; if (controllerFuture?.isDone == true) connectController(); return }
        command("speak", Bundle().apply { putString("request", jsonCodec.toJson(request)) })
    }
    private fun render() {
        val route = current.route
        val revision=++renderRevision
        val changedRoute=visualRoute!=route
        if(changedRoute) {
            if(pendingPlayingTarget!=targetKey(route))pendingPlayingTarget=null
            speechRevision++
            motion.cancelEntry()
            val previousReady=!restoring && lastRoute==visualRoute
            restoring=true
            lastRoute=null
            visualRoute?.takeIf {previousReady && it.page in listOf(Page.CALENDAR,Page.YEARS,Page.ISSUES,Page.STORIES)}?.let {
                val lm=binding.list.layoutManager as LinearLayoutManager
                val first=lm.findFirstVisibleItemPosition()
                if(first>=0)directoryScroll[it]=first to (lm.findViewByPosition(first)?.top ?: 0)
            }
            visualRoute=route;enterRoute=route;enterBack=returning;returning=false
            // submitList(null) 同步清空并使尚未完成的旧 diff 失效，新页不能先绘制旧行。
            rows.submitList(null)
            binding.list.stopScroll()
            binding.list.scrollToPosition(0)
            toolbarScrolled(false)
        }
        binding.back.isVisible = route.page != Page.HOME
        chrome.route(route)
        binding.title.text = when (route.page) {
            Page.HOME, Page.CUSTOM,Page.NOTES -> ""
            Page.CALENDAR, Page.YEARS -> route.paper.label
            Page.ISSUES -> "${route.label}年《求是》"
            Page.READER -> current.article?.story?.date ?: route.date
            Page.STORIES -> route.label
        }
        binding.list.isVisible = route.page !in listOf(Page.CUSTOM,Page.NOTES)
        binding.editorPanel.isVisible = route.page == Page.CUSTOM
        binding.editor.isEnabled=!current.loading && !current.error
        binding.noteTitle.isEnabled=binding.editor.isEnabled
        if(route.page==Page.CUSTOM) {
            bindingDraft=true
            if(binding.editor.text.toString()!=model.draft)binding.editor.setText(model.draft)
            if(binding.noteTitle.text.toString()!=model.draftTitle)binding.noteTitle.setText(model.draftTitle)
            bindingDraft=false;noteMetadata()
            if(changedRoute)finishNoteEditing()
        }
        notes.render(current,appearance)
        updateNowPlaying()
        if(current.noteError && !lastNoteError)android.widget.Toast.makeText(this,R.string.ui_error,android.widget.Toast.LENGTH_LONG).show()
        lastNoteError=current.noteError
        fun enterWhenReady() {
            if(current.loading || enterRoute!=route || current.route!=route || revision!=renderRevision)return
            val view=if(route.page==Page.CUSTOM)binding.editorPanel else if(route.page==Page.NOTES)binding.notesPanel else binding.list
            // 新列表完成布局后才做入场，加载占位和旧列表均不参与页面动画。
            view.doOnPreDraw {
                if(enterRoute==route && current.route==route && revision==renderRevision) {
                    enterRoute=null
                    motion.enter(view,enterBack)
                }
            }
        }
        val content = mutableListOf<NativeRow>()
        when {
            current.loading -> content += NativeRow("loading", RowKind.MESSAGE, getString(R.string.ui_loading))
            current.error -> { content += NativeRow("error", RowKind.MESSAGE, getString(R.string.ui_error)); content += NativeRow("retry", RowKind.RETRY, getString(R.string.ui_retry)) }
            route.page == Page.HOME -> {
                content += NativeRow("home", RowKind.HOME)
                Paper.entries.chunked(2).forEachIndexed { i, papers -> content += NativeRow("cards$i", RowKind.CARDS, papers = papers) }
            }
            route.page == Page.CALENDAR -> content += NativeRow("calendar-${route.month}", RowKind.CALENDAR, issues = current.issues)
            route.page == Page.YEARS || route.page == Page.ISSUES -> current.issues.forEach { issue ->
                content += NativeRow(issue.url, RowKind.ISSUE, if (route.page == Page.YEARS) "${issue.label}年" else issue.label,
                    if (route.page == Page.YEARS) "《求是》${issue.label}年" else "", issue = issue)
            }
            route.page == Page.STORIES -> {
                if (current.partial) { content += NativeRow("partial", RowKind.MESSAGE, getString(R.string.ui_error)); content += NativeRow("retry", RowKind.RETRY, getString(R.string.ui_retry)) }
                current.stories.groupBy { it.section }.forEach { (section, stories) ->
                    if (route.paper != Paper.QIUSHI) content += NativeRow("section$section", RowKind.SECTION, "$section (${stories.size}篇)")
                    stories.forEach { story -> content += NativeRow(story.url, RowKind.STORY, story.title, story.author, story = story) }
                }
            }
            route.page == Page.READER -> current.article?.let { article ->
                if (article.story.section.isNotBlank()) content += NativeRow("badge", RowKind.BADGE, article.story.section)
                content += NativeRow("title", RowKind.TITLE, article.story.title)
                content += NativeRow("meta", RowKind.META, listOf(article.story.author, article.story.date).filter(String::isNotBlank).joinToString(" · "))
                content += article.blocks.map { NativeRow(it.id, if (it.kind == BlockKind.IMAGE) RowKind.IMAGE else RowKind.BLOCK, block = it) }
            }
            else -> Unit
        }
        if (content.isEmpty() && route.page !in listOf(Page.CUSTOM,Page.NOTES)) content += NativeRow("empty", RowKind.MESSAGE, getString(R.string.ui_empty))
        rows.month = route.month
        rows.submitList(content) {
            if(current.route!=route || revision!=renderRevision)return@submitList
            rows.highlight = if (status.article == current.article?.story?.url) status.block else ""
            if (current.route == route && lastRoute != route && !current.loading) {
                lastRoute = route
                val position = model.readingPosition
                if (position != null && route.page == Page.READER) {
                    restoring = true
                    (binding.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position.block, position.offset)
                } else {
                    val scroll=directoryScroll[route]
                    (binding.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(scroll?.first ?: 0,scroll?.second ?: 0)
                }
                binding.list.doOnPreDraw {if(current.route==route && lastRoute==route)restoring=false}
            }
            if(route.page==Page.CUSTOM)highlightNotePlayback(PlaybackSignals.state.value,false)
            focusPlayingIfReady()
            enterWhenReady()
        }
    }
    private fun rowClick(row: NativeRow) {
        when (row.kind) {
            RowKind.RETRY -> model.retry()
            RowKind.ISSUE -> row.issue?.let {
                if (current.route.page == Page.YEARS) model.navigate(Route(Page.ISSUES, Paper.QIUSHI, it.label, it.url))
                else model.navigate(Route(Page.STORIES, current.route.paper, it.label, it.url, it.date))
            }
            RowKind.STORY -> row.story?.let { model.navigate(Route(Page.READER, it.paper, it.title, it.url, it.date, story = it)) }
            else -> Unit
        }
    }
    fun openPaper(paper: Paper) = model.navigate(Route(if (paper == Paper.QIUSHI) Page.YEARS else Page.CALENDAR, paper))
    fun calendarNavigate(delta: Long) = model.month(delta)
    fun calendarDate(date: String) {
        val issue = current.issues.firstOrNull { it.date == date } ?: return
        model.navigate(Route(Page.STORIES, current.route.paper, date, issue.url, date))
    }
    fun text(value: String, size: Float = 15f, color: Int = palette.text) = TextView(this).apply { text = value; textSize = size; setTextColor(color); includeFontPadding = false }
    fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    override fun onResume() {
        super.onResume()
        if(::binding.isInitialized)updateNowPlaying()
        if (voiceSettingsOpened) { command("refreshVoice");voiceSettingsOpened=false }
    }
    override fun onPause() {
        model.flushDraft()
        if (::binding.isInitialized && current.article != null && !restoring) {
            val lm=binding.list.layoutManager as LinearLayoutManager
            val first=lm.findFirstVisibleItemPosition()
            if(first>=0) model.saveScroll(first,lm.findViewByPosition(first)?.top ?: 0)
        }
        super.onPause()
    }
    override fun onStop() {if(::binding.isInitialized)binding.nowPlaying.playing=false;super.onStop()}
    override fun onDestroy() {
        if(::motion.isInitialized)motion.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

data class Palette(val bg: Int, val text: Int, val muted: Int, val faint: Int, val card: Int, val active: Int) {
    companion object {
        fun forTheme(theme: Int) = when (theme) {
            1 -> Palette(0xff1a1a1a.toInt(),0xffc8c8c8.toInt(),0xffaaaaaa.toInt(),0xff888888.toInt(),0xff2a2a2a.toInt(),0xff3b6fb6.toInt())
            2 -> Palette(0xfff5f0e6.toInt(),0xff4a3f35.toInt(),0xff666666.toInt(),0xff999999.toInt(),0xffede6d8.toInt(),0xff4a3f35.toInt())
            else -> Palette(Color.WHITE,0xff1a1a1a.toInt(),0xff666666.toInt(),0xff999999.toInt(),0xfff8f8f8.toInt(),0xff1a1a1a.toInt())
        }
    }
}
