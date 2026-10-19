package com.newspaper.helper

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.media3.common.Player
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.newspaper.helper.databinding.ActivityMainBinding
import java.util.Locale

/** 顶栏和悬浮面板共用真实播放状态；不占正文高度，不生成演示进度或文案。 */
internal class ReadingChrome(
    private val host: MainActivity,
    private val binding: ActivityMainBinding,
    private val player: () -> Player?,
    private val settingsChanged: (Int?, Int?, Int?) -> Unit,
    private val play: () -> Unit,
    private val start: () -> Unit,
    private val command: (String, Bundle) -> Unit,
    private val queue: () -> Unit
) {
    private enum class Panel { TYPE, SPEECH }
    private var panel: Panel? = null
    private var route: Route? = null
    private var settings = ReaderSettings()
    private var status = PlaybackStatus()
    private var playButton: GlyphView? = null
    private var count: TextView? = null
    private var elapsed: TextView? = null
    private var duration: TextView? = null
    private var progress: Slider? = null
    private var seeking = false
    private var seekMediaId: String? = null
    private val speedButtons = mutableListOf<TextView>()
    private val ink get() = ChromeColors.forTheme(settings.theme)
    private fun dp(n: Int) = host.dp(n)
    private fun label(resource: Int) = host.getString(resource)
    private fun text(value: String, size: Float = 13f, color: Int = ink.text) = host.text(value,size,color)
    private fun box(color: Int, radius: Int, stroke: Int? = null) = host.rounded(color,radius).apply {
        stroke?.let { setStroke(dp(1),it) }
    }
    init {
        binding.speech.glyph = Glyph.HEADPHONES
        binding.speech.contentDescription = label(R.string.a11y_read)
        binding.settings.text = android.text.SpannableString(label(R.string.ui_aa)).apply {
            setSpan(android.text.style.RelativeSizeSpan(.72f),1,2,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.speech.setOnClickListener { toggle(Panel.SPEECH); if (panel == Panel.SPEECH) start() }
        binding.settings.setOnClickListener { toggle(Panel.TYPE) }
        binding.dismissPanel.setOnClickListener { close() }
    }
    fun route(next: Route) {
        if (route != next) close()
        route = next
        val home = next.page == Page.HOME
        val reader = next.page == Page.READER || next.page == Page.CUSTOM
        binding.actions.isVisible = home || reader
        binding.paste.isVisible = home
        binding.speech.isVisible = reader
        binding.settings.isVisible = home || reader
        styleButtons()
    }
    fun appearance(next: ReaderSettings) {
        val changed = next.theme != settings.theme || next.font != settings.font || next.line != settings.line
        settings = next
        styleButtons()
        if (changed && panel != null) build()
    }
    fun playback(next: PlaybackStatus) { status = next; updateProgress(); styleButtons() }
    fun close(): Boolean {
        if (panel == null) return false
        panel = null
        binding.sheet.animate().cancel()
        binding.sheet.isVisible = false
        binding.dismissPanel.isVisible = false
        binding.list.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        binding.editor.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        styleButtons()
        return true
    }
    private fun toggle(next: Panel) {
        if (panel == next) { close(); return }
        panel = next
        build()
        binding.dismissPanel.isVisible = true
        binding.sheet.isVisible = true
        binding.list.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        binding.editor.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        binding.sheet.alpha = 0f
        binding.sheet.translationY = -dp(10).toFloat()
        binding.sheet.animate().alpha(1f).translationY(0f).setDuration(180).start()
        styleButtons()
    }
    private fun styleButtons() {
        binding.actions.background = box(ink.panel,99)
        binding.paste.ink = ink.text
        binding.speech.ink = if (panel == Panel.SPEECH || status.playing) ink.accent else ink.text
        binding.speech.isSelected = panel == Panel.SPEECH
        binding.settings.isSelected = panel == Panel.TYPE
        binding.speech.background = box(if (panel == Panel.SPEECH || status.playing) ink.tint else android.graphics.Color.TRANSPARENT,99)
        binding.settings.setTextColor(if (panel == Panel.TYPE) ink.accent else ink.text)
        binding.settings.background = box(if (panel == Panel.TYPE) ink.tint else android.graphics.Color.TRANSPARENT,99)
    }
    private fun row() = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun add(view: View, height: Int = -2, margin: Int = 0) {
        binding.panelContent.addView(view,LinearLayout.LayoutParams(-1,if(height < 0) height else dp(height)).apply { topMargin = dp(margin) })
    }
    private fun heading(resource: Int, value: String? = null, margin: Int = 0) {
        val row = row()
        row.addView(text(label(resource),13f,ink.muted),LinearLayout.LayoutParams(0,dp(24),1f))
        value?.let { row.addView(text(it).apply { setTypeface(typeface,Typeface.BOLD) }) }
        add(row,margin=margin)
    }
    private fun slider(max: Float, value: Float, description: Int) = Slider(host).apply {
        valueFrom = 0f; valueTo = max; this.value = value.coerceIn(0f,max)
        contentDescription = label(description)
        labelBehavior = LabelFormatter.LABEL_GONE
        trackActiveTintList = ColorStateList.valueOf(ink.accent)
        trackInactiveTintList = ColorStateList.valueOf(ink.line)
        thumbTintList = ColorStateList.valueOf(ink.accent)
        haloRadius = 0
        trackHeight = dp(4)
    }
    private fun build() {
        binding.panelContent.removeAllViews()
        playButton = null; count = null; elapsed = null; duration = null; progress = null
        speedButtons.clear(); seeking = false
        binding.sheet.background = box(ink.card,24)
        binding.sheet.scrollTo(0,0)
        if (panel == Panel.TYPE) buildType() else if (panel == Panel.SPEECH) buildSpeech()
    }
    private fun buildType() {
        val sizes = listOf(R.string.ui_small,R.string.ui_medium,R.string.ui_large,R.string.ui_xlarge)
        heading(R.string.ui_font,label(sizes[settings.font]))
        val fontRow = row()
        fontRow.addView(text("A",12f,ink.muted),LinearLayout.LayoutParams(dp(24),-2))
        fontRow.addView(slider(3f,settings.font.toFloat(),R.string.ui_font).apply {
            var tracking=false
            stepSize = 1f
            thumbTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
            setLabelFormatter { label(sizes[it.toInt()]) }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) { tracking=true }
                override fun onStopTrackingTouch(slider: Slider) { tracking=false;settingsChanged(null,slider.value.toInt(),null) }
            })
            // 键盘和辅助功能调整也会触发 value change，拖动时仅在松手保存。
            addOnChangeListener { _,value,fromUser -> if (fromUser && !tracking) settingsChanged(null,value.toInt(),null) }
        },LinearLayout.LayoutParams(0,dp(44),1f))
        fontRow.addView(text("A",22f,ink.muted).apply { gravity=Gravity.END },LinearLayout.LayoutParams(dp(24),-2))
        add(fontRow)
        val gaps = listOf(R.string.ui_tight,R.string.ui_standard,R.string.ui_loose,R.string.ui_wider)
        heading(R.string.ui_line,label(gaps[settings.line]),14)
        val options = row()
        gaps.forEachIndexed { index,res ->
            val selected = settings.line == index
            val option = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = box(if(selected) ink.tint else ink.panel,14,if(selected) ink.accent else null)
                isSelected = selected; isClickable = true; isFocusable = true
                contentDescription = label(res)
                setOnClickListener { settingsChanged(null,null,index) }
            }
            option.addView(object : View(host) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=if(selected) ink.accent else ink.muted; strokeWidth=dp(2).toFloat(); strokeCap=Paint.Cap.ROUND }
                override fun onDraw(canvas: Canvas) {
                    val gap = dp(3 + index * 2).toFloat()
                    for (i in -1..1) canvas.drawLine(width/2f-dp(12),height/2f+gap*i,width/2f+dp(12),height/2f+gap*i,paint)
                }
            },LinearLayout.LayoutParams(dp(30),dp(28)))
            option.addView(text(label(res),12f,if(selected) ink.accent else ink.muted).apply { gravity=Gravity.CENTER })
            options.addView(option,LinearLayout.LayoutParams(0,dp(68),1f).apply { if(index>0) leftMargin=dp(8) })
        }
        add(options,margin=4)
        heading(R.string.ui_mode,margin=18)
        val themes = row()
        listOf(R.string.ui_day,R.string.ui_night,R.string.ui_eye).forEachIndexed { index,res ->
            val selected = settings.theme == index
            val colors = ChromeColors.forTheme(index)
            val option = LinearLayout(host).apply {
                orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER
                isClickable=true; isFocusable=true; isSelected=selected
                contentDescription=label(res)
                setOnClickListener { settingsChanged(index,null,null) }
            }
            val ring = android.widget.FrameLayout(host).apply { background=box(ink.card,99,if(selected) ink.accent else null) }
            ring.addView(text(label(R.string.ui_aa),17f,colors.text).apply {
                gravity=Gravity.CENTER; setTypeface(typeface,Typeface.BOLD)
                background=box(colors.bg,99,0x44808080)
            },android.widget.FrameLayout.LayoutParams(dp(52),dp(52),Gravity.CENTER))
            option.addView(ring,LinearLayout.LayoutParams(dp(62),dp(62)))
            option.addView(text(label(res),12f,if(selected) ink.text else ink.muted).apply { gravity=Gravity.CENTER },LinearLayout.LayoutParams(-1,dp(28)))
            themes.addView(option,LinearLayout.LayoutParams(0,dp(92),1f))
        }
        add(themes,margin=2)
    }
    private fun buildSpeech() {
        count = text("").apply { tag="speechSegments" }
        add(count!!,24)
        // 只显示当前真实音频片段的进度，尚未合成的整篇时长不作估算。
        progress = slider(1000f,0f,R.string.a11y_segment_progress).apply {
            tag="speechProgress"
            thumbRadius=dp(7)
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) { seeking=true; seekMediaId=player()?.currentMediaItem?.mediaId }
                override fun onStopTrackingTouch(slider: Slider) {
                    val p = player()
                    if (p != null && p.currentMediaItem?.mediaId == seekMediaId && p.duration > 0) p.seekTo((p.duration*slider.value/1000f).toLong())
                    seeking=false; updateProgress()
                }
            })
        }
        add(progress!!,40)
        val times = row()
        elapsed=text("0:00",11f,ink.muted)
        duration=text("--:--",11f,ink.muted).apply { gravity=Gravity.END }
        times.addView(elapsed,LinearLayout.LayoutParams(0,-2,1f)); times.addView(duration,LinearLayout.LayoutParams(0,-2,1f))
        add(times)
        val controls = row().apply { tag="speechControls" }
        fun button(glyph: Glyph, description: Int, main: Boolean = false, action: () -> Unit): GlyphView {
            val wrap = android.widget.FrameLayout(host)
            val view = GlyphView(host).apply {
                this.glyph=glyph; ink=if(main) android.graphics.Color.WHITE else this@ReadingChrome.ink.text
                contentDescription=label(description)
                if(main) { background=box(this@ReadingChrome.ink.accent,99); elevation=dp(4).toFloat() }
                setOnClickListener { action() }
            }
            wrap.addView(view,android.widget.FrameLayout.LayoutParams(dp(if(main)62 else 48),dp(if(main)62 else 48),Gravity.CENTER))
            controls.addView(wrap,LinearLayout.LayoutParams(0,dp(74),if(main)1.4f else 1f))
            return view
        }
        button(Glyph.QUEUE,R.string.a11y_queue) { queue() }.apply { isEnabled=route?.page==Page.READER; alpha=if(isEnabled)1f else .3f }
        button(Glyph.PREVIOUS,R.string.a11y_previous) { command("step",Bundle().apply { putInt("delta",-1) }) }
        playButton=button(Glyph.PLAY,R.string.a11y_play,true) { play() }.apply { tag="speechPlay" }
        button(Glyph.NEXT,R.string.a11y_next) { command("step",Bundle().apply { putInt("delta",1) }) }
        button(Glyph.STOP,R.string.a11y_stop) { command("stop",Bundle.EMPTY) }
        add(controls,margin=10)
        val rates=row().apply { background=box(ink.panel,12); setPadding(dp(3),dp(3),dp(3),dp(3)) }
        listOf(.8f,1f,1.1f,1.2f,1.3f).forEach { rate ->
            val button=text("${java.text.DecimalFormat("0.##",java.text.DecimalFormatSymbols(Locale.ROOT)).format(rate)}×").apply {
                tag=rate; gravity=Gravity.CENTER; isClickable=true; isFocusable=true
                contentDescription="${label(R.string.a11y_speed)} $text"
                setOnClickListener { player()?.setPlaybackSpeed(rate); updateProgress() }
            }
            speedButtons += button
            rates.addView(button,LinearLayout.LayoutParams(0,dp(40),1f))
        }
        add(rates,margin=10)
        updateProgress()
    }
    fun updateProgress() {
        if (panel != Panel.SPEECH) return
        playButton?.glyph = if(status.playing) Glyph.PAUSE else if(status.failed || status.voiceMissing) Glyph.RETRY else Glyph.PLAY
        count?.text = when {
            status.failed || status.voiceMissing -> label(R.string.ui_retry)
            status.preparing -> label(R.string.ui_loading)
            status.segmentCount > 0 -> "${(status.segmentIndex+1).coerceAtMost(status.segmentCount)} / ${status.segmentCount}"
            else -> "— / —"
        }
        val p=player()
        val total=p?.duration?.takeIf { it>0 } ?: 0L
        val position=p?.currentPosition?.coerceIn(0L,total) ?: 0L
        progress?.isEnabled=total>0 && p?.isCurrentMediaItemSeekable==true
        if(!seeking) progress?.value=if(total>0) (position.toFloat()/total*1000).coerceIn(0f,1000f) else 0f
        elapsed?.text=formatTime(position)
        duration?.text=if(total>0) formatTime(total) else "--:--"
        val speed=p?.playbackParameters?.speed ?: 1f
        speedButtons.forEach { button ->
            val selected=button.tag==speed
            button.isSelected=selected
            button.setTextColor(if(selected) ink.accent else ink.muted)
            button.background=box(if(selected) ink.card else android.graphics.Color.TRANSPARENT,9)
            button.setTypeface(null,if(selected)Typeface.BOLD else Typeface.NORMAL)
        }
    }
    private fun formatTime(ms: Long) = String.format(Locale.ROOT,"%d:%02d",ms/60000,ms/1000%60)
}

/** 用户提供的 HTML 顶栏配色，仅作用于控件和弹层，正文保持已有主题。 */
private data class ChromeColors(val bg:Int,val text:Int,val muted:Int,val panel:Int,val card:Int,val accent:Int,val tint:Int,val line:Int) {
    companion object {
        fun forTheme(theme:Int) = when(theme) {
            1 -> ChromeColors(0xff14161a.toInt(),0xffd9dbe0.toInt(),0xff80848d.toInt(),0xff22252b.toInt(),0xff1e2127.toInt(),0xff79b8e6.toInt(),0x2979b8e6,0xff2a2d34.toInt())
            2 -> ChromeColors(0xfff4ecd6.toInt(),0xff3b3426.toInt(),0xff8b816a.toInt(),0xffe9dfc2.toInt(),0xfff8f1de.toInt(),0xff8a5a14.toInt(),0x248a5a14,0xffe2d6b6.toInt())
            else -> ChromeColors(-1,0xff1c1c1e.toInt(),0xff8a8a8e.toInt(),0xfff1f2f5.toInt(),-1,0xff1e5a82.toInt(),0x1f1e5a82,0xffececec.toInt())
        }
    }
}
