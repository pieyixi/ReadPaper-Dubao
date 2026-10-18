package com.newspaper.helper

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import com.newspaper.helper.databinding.ActivityMainBinding
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

class MainActivity : AppCompatActivity() {
    private val model: ReaderViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding
    private lateinit var rows: NativeRows
    private var controller: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var current = Screen()
    private var appearance = ReaderSettings()
    private var status = PlaybackStatus()
    private var lastRoute: Route? = null
    private var restoring = false
    private var playButton: GlyphView? = null
    private var pendingSpeech: SpeechRequest? = null
    private var voiceSettingsOpened = false
    val palette: Palette get() = Palette.forTheme(appearance.theme)
    fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        rows = NativeRows(this, ::rowClick)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = rows
        binding.list.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE && !restoring && current.article != null) {
                    val lm = binding.list.layoutManager as LinearLayoutManager
                    val pos = lm.findFirstVisibleItemPosition()
                    model.saveScroll(pos, lm.findViewByPosition(pos)?.top ?: 0)
                }
            }
        })
        binding.back.glyph = Glyph.BACK
        binding.back.contentDescription = getString(R.string.a11y_back)
        binding.back.setOnClickListener { back() }
        binding.paste.glyph = Glyph.PASTE
        binding.paste.contentDescription = getString(R.string.a11y_custom)
        binding.paste.setOnClickListener { model.navigate(Route(Page.CUSTOM)) }
        binding.settings.contentDescription = getString(R.string.a11y_settings)
        binding.settings.setOnClickListener { binding.settingsPanel.isVisible = !binding.settingsPanel.isVisible }
        binding.gear.glyph = Glyph.GEAR
        binding.gear.contentDescription = getString(R.string.a11y_settings)
        binding.gear.setOnClickListener { binding.settingsPanel.isVisible = !binding.settingsPanel.isVisible }
        binding.editor.contentDescription = getString(R.string.a11y_editor)
        binding.editor.setText(model.draft)
        binding.editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                model.draft = s?.toString().orEmpty()
                if (status.article.startsWith("custom:")) command("stop")
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { back() } })
        connectController()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.screen.collect { screen -> current = screen; render() } }
                launch { model.settings.collect { settings -> appearance = settings; applyAppearance(); render() } }
                launch { PlaybackSignals.state.collect { playback ->
                    status = playback
                    if (current.route.page == Page.CUSTOM) {
                        val edit = binding.editor.text
                        edit.getSpans(0,edit.length,android.text.style.BackgroundColorSpan::class.java).forEach { edit.removeSpan(it) }
                        val line = playback.block.removePrefix("custom-").toIntOrNull()
                        if (line != null && playback.article.startsWith("custom:")) {
                            var start = 0
                            repeat(line) { start = edit.indexOf('\n',start).let { if (it < 0) edit.length else it + 1 } }
                            val end = edit.indexOf('\n',start).let { if (it < 0) edit.length else it }
                            if (start < end) {
                                edit.setSpan(android.text.style.BackgroundColorSpan(if (appearance.theme==1) 0xff273848.toInt() else 0xffedf3f8.toInt()),start,end,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                if (playback.playing) binding.editor.bringPointIntoView(start)
                            }
                        }
                    }
                    rows.highlight = if (playback.article == current.article?.story?.url) playback.block else ""
                    playButton?.glyph = if (playback.playing) Glyph.PAUSE else if (playback.failed || playback.voiceMissing) Glyph.RETRY else Glyph.PLAY
                    binding.playback.isVisible = current.route.page in listOf(Page.READER, Page.CUSTOM) || playback.article.isNotEmpty()
                    if (playback.playing && playback.article == current.article?.story?.url) {
                        val position = rows.items.indexOfFirst { it.block?.id == playback.block }
                        val lm = binding.list.layoutManager as LinearLayoutManager
                        if (position >= 0 && position !in lm.findFirstVisibleItemPosition()..lm.findLastVisibleItemPosition() && binding.list.scrollState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) binding.list.smoothScrollToPosition(position)
                    }
                } }
            }
        }
    }
    private fun connectController() {
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, ReadAloudService::class.java))).buildAsync()
        controllerFuture = future
        future.addListener({
            runCatching { controller = future.get(); pendingSpeech?.let { sendSpeech(it) }; pendingSpeech = null }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun back() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(binding.editor.windowToken, 0)
        binding.settingsPanel.isVisible = false
        if (!model.back()) finish()
    }
    private fun applyAppearance() {
        val p = palette
        binding.root.setBackgroundColor(p.bg)
        binding.title.setTextColor(p.text)
        binding.settings.setTextColor(p.muted)
        binding.editor.setTextColor(p.text)
        binding.editor.textSize = floatArrayOf(15f,17f,19f,21f)[appearance.font]
        binding.back.ink = p.muted; binding.paste.ink = p.muted
        binding.gear.ink = p.muted
        window.statusBarColor = p.bg; window.navigationBarColor = p.bg
        if (Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = if (appearance.theme == 1) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        buildSettings()
        buildPlayback()
        rows.appearance = appearance
    }
    private fun buildSettings() {
        binding.settingsPanel.removeAllViews()
        binding.settingsPanel.setBackgroundColor(palette.card)
        fun optionRow(label: Int, choices: List<Int>, selected: Int, action: (Int) -> Unit) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(getString(label), 13f, palette.muted), LinearLayout.LayoutParams(dp(40), dp(44)))
            choices.forEachIndexed { index, resource ->
                row.addView(text(getString(resource), 13f, if (index == selected) palette.bg else palette.text).apply {
                    gravity = Gravity.CENTER; isClickable = true; isFocusable = true; isSelected = selected == index
                    background = rounded(if (index == selected) palette.text else palette.card, 6)
                    setOnClickListener { action(index) }
                }, LinearLayout.LayoutParams(0, dp(44), 1f))
            }
            binding.settingsPanel.addView(row)
        }
        optionRow(R.string.ui_font, listOf(R.string.ui_small,R.string.ui_medium,R.string.ui_large,R.string.ui_xlarge), appearance.font) { model.changeSettings(font = it) }
        optionRow(R.string.ui_line, listOf(R.string.ui_tight,R.string.ui_standard,R.string.ui_loose,R.string.ui_wider), appearance.line) { model.changeSettings(line = it) }
        optionRow(R.string.ui_mode, listOf(R.string.ui_day,R.string.ui_night,R.string.ui_eye), appearance.theme) { model.changeSettings(theme = it) }
    }
    private fun buildPlayback() {
        binding.playback.removeAllViews(); binding.playback.setBackgroundColor(palette.card)
        fun button(glyph: Glyph, description: Int, action: () -> Unit): GlyphView = GlyphView(this).apply {
            this.glyph = glyph; ink = palette.text; contentDescription = getString(description)
            setOnClickListener { action() }
            binding.playback.addView(this, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        button(Glyph.PREVIOUS, R.string.a11y_previous) { command("step", Bundle().apply { putInt("delta", -1) }) }
        playButton = button(if (status.playing) Glyph.PAUSE else Glyph.PLAY, R.string.a11y_play) { playOrPause() }
        button(Glyph.STOP, R.string.a11y_stop) { command("stop") }
        button(Glyph.NEXT, R.string.a11y_next) { command("step", Bundle().apply { putInt("delta", 1) }) }
        button(Glyph.QUEUE, R.string.a11y_queue) {
            val story = current.article?.story
            if (story != null) {
                val remaining = model.listForQueue.dropWhile { it.url != story.url }.ifEmpty { listOf(story) }
                sendSpeech(SpeechRequest(stories = remaining, append = true))
            }
        }
        val speed = Slider(this).apply {
            valueFrom = .5f; valueTo = 2f; stepSize = .25f; value = controller?.playbackParameters?.speed?.coerceIn(.5f,2f) ?: 1f
            contentDescription = getString(R.string.a11y_speed)
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
            addOnChangeListener { _, value, fromUser -> if (fromUser) controller?.setPlaybackSpeed(value) }
        }
        binding.playback.addView(speed, LinearLayout.LayoutParams(dp(64), dp(48)))
    }
    private fun playOrPause() {
        if (status.voiceMissing) {
            voiceSettingsOpened = true
            runCatching { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }.recoverCatching {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            }
            return
        }
        if (current.route.page != Page.CUSTOM && current.article == null && controller?.mediaItemCount == 0) { command("resumeQueue");return }
        lifecycleScope.launch {
            val target = if (current.route.page == Page.CUSTOM) model.customArticle().story.url else current.article?.story?.url
            if (target == status.article && status.preparing) { command("toggle"); return@launch }
            if (target != null && target != status.article || status.failed || controller?.mediaItemCount == 0) {
                if (current.route.page == Page.CUSTOM) {
                    val draft = model.draft
                    if (draft.isNotBlank()) {
                        val name = withContext(Dispatchers.IO) {
                            val directory = File(cacheDir, "speech-input").apply { mkdirs() }
                            directory.listFiles()?.forEach { it.delete() }
                            val name = stableId(draft) + ".txt"
                            File(directory,name).writeText(draft)
                            name
                        }
                        sendSpeech(SpeechRequest(custom = name))
                    }
                }
                else current.article?.let { sendSpeech(SpeechRequest(stories = listOf(it.story))) }
            } else {
                if (controller?.playWhenReady == true) controller?.pause() else controller?.play()
            }
        }
    }
    private fun command(action: String, bundle: Bundle = Bundle.EMPTY) { controller?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), bundle) }
    private fun sendSpeech(request: SpeechRequest) {
        if (controller == null) { pendingSpeech = request; if (controllerFuture?.isDone == true) connectController(); return }
        command("speak", Bundle().apply { putString("request", jsonCodec.toJson(request)) })
    }
    private fun render() {
        val route = current.route
        binding.back.isVisible = route.page != Page.HOME
        binding.settings.isVisible = route.page != Page.HOME
        binding.gear.isVisible = route.page == Page.HOME
        binding.paste.isVisible = route.page != Page.CUSTOM
        binding.title.text = when (route.page) {
            Page.HOME, Page.CUSTOM -> ""
            Page.CALENDAR, Page.YEARS -> route.paper.label
            Page.ISSUES -> "${route.label}年《求是》"
            Page.READER -> current.article?.story?.date ?: route.date
            Page.STORIES -> route.label
        }
        binding.list.isVisible = route.page != Page.CUSTOM
        binding.editor.isVisible = route.page == Page.CUSTOM
        binding.playback.isVisible = route.page in listOf(Page.READER, Page.CUSTOM) || status.article.isNotEmpty()
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
        if (content.isEmpty() && route.page != Page.CUSTOM) content += NativeRow("empty", RowKind.MESSAGE, getString(R.string.ui_empty))
        rows.month = route.month
        rows.submitList(content) {
            rows.highlight = if (status.article == current.article?.story?.url) status.block else ""
            if (current.route == route && lastRoute != route && !current.loading) {
                lastRoute = route
                val position = model.readingPosition
                if (position != null && route.page == Page.READER) {
                    restoring = true
                    (binding.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position.block, position.offset)
                    binding.list.post { restoring = false }
                } else binding.list.scrollToPosition(0)
            }
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
        if (voiceSettingsOpened) { command("refreshVoice");voiceSettingsOpened=false }
    }
    override fun onPause() {
        if (::binding.isInitialized && current.article != null && !restoring) {
            val lm=binding.list.layoutManager as LinearLayoutManager
            val first=lm.findFirstVisibleItemPosition()
            if(first>=0) model.saveScroll(first,lm.findViewByPosition(first)?.top ?: 0)
        }
        super.onPause()
    }
    override fun onDestroy() {
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
