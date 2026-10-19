package com.newspaper.helper

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import java.time.LocalDate
import java.time.YearMonth

enum class RowKind { HOME, CARDS, CALENDAR, ISSUE, SECTION, STORY, BADGE, TITLE, META, BLOCK, IMAGE, MESSAGE, RETRY }
data class NativeRow(val id: String, val kind: RowKind, val title: String = "", val subtitle: String = "", val issue: Issue? = null,
    val story: Story? = null, val block: BodyBlock? = null, val papers: List<Paper> = emptyList(), val issues: List<Issue> = emptyList())
class NativeRows(private val host: MainActivity, private val onClick: (NativeRow) -> Unit) : ListAdapter<NativeRow, NativeRows.Holder>(object : DiffUtil.ItemCallback<NativeRow>() {
    override fun areItemsTheSame(a: NativeRow, b: NativeRow) = a.id == b.id && a.kind == b.kind
    override fun areContentsTheSame(a: NativeRow, b: NativeRow) = a == b
}) {
    var items: List<NativeRow> get() = currentList; set(value) { submitList(value) }
    var appearance = ReaderSettings(); set(value) {
        val changed=field.theme!=value.theme || field.font!=value.font || field.line!=value.line
        field=value
        if(changed)notifyItemRangeChanged(0,itemCount)
    }
    var month = YearMonth.now().toString()
    var highlight = ""; set(value) {
        if (field == value) return
        val old = currentList.indexOfFirst { it.block?.id == field }
        field = value
        val next = currentList.indexOfFirst { it.block?.id == value }
        if (old >= 0) notifyItemChanged(old)
        if (next >= 0) notifyItemChanged(next)
    }
    class Holder(val box: LinearLayout) : RecyclerView.ViewHolder(box)
    override fun getItemViewType(position: Int) = getItem(position).kind.ordinal
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    })
    override fun onViewRecycled(holder: Holder) { clearImages(holder.box); super.onViewRecycled(holder) }
    private fun clearImages(view: View) {
        if (view is ImageView) view.dispose()
        if (view is ViewGroup) for (i in 0 until view.childCount) clearImages(view.getChildAt(i))
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val box = holder.box
        val p = host.palette
        clearImages(box); box.removeAllViews()
        box.setPadding(host.dp(16), 0, host.dp(16), 0)
        box.setBackgroundColor(p.bg)
        box.setOnClickListener(null); box.isClickable = false; box.isFocusable = false
        fun text(value: String, size: Float, color: Int = p.text, bold: Boolean = false, bottom: Int = 0): TextView = host.text(value,size,color).apply {
            if (bold) setTypeface(typeface,Typeface.BOLD)
            box.addView(this, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = host.dp(bottom) })
        }
        when (row.kind) {
            RowKind.HOME -> {
                box.setPadding(host.dp(16),host.dp(8),host.dp(16),host.dp(28))
                text(host.getString(R.string.app_name),28f,bold=true,bottom=4)
                text(host.getString(R.string.ui_subtitle),14f,p.faint)
            }
            RowKind.CARDS -> {
                val line = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
                row.papers.forEachIndexed { i, paper ->
                    val card = LinearLayout(host).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, paper.colors).apply { cornerRadius = host.dp(if (host.resources.configuration.screenWidthDp <= 360) 8 else 12).toFloat() }
                        val name = host.text(paper.label,17f,Color.WHITE).apply { gravity=Gravity.CENTER; setTypeface(typeface,Typeface.BOLD) }
                        addView(name)
                        addView(host.text(paper.subtitle,12f,0xddffffff.toInt()).apply { gravity=Gravity.CENTER; setPadding(0,host.dp(4),0,0) })
                        isClickable = true; isFocusable = true; contentDescription = paper.label
                        pressFeedback()
                        setOnClickListener { host.expandFrom(this,paper.colors[0]);host.openPaper(paper) }
                    }
                    val width = host.resources.configuration.screenWidthDp
                    val gap = if (width <= 360) 10 else 16
                    line.addView(card,LinearLayout.LayoutParams(0,host.dp(((width-32-gap)/2f/1.3f).toInt()),1f).apply { if (i==0) rightMargin=host.dp(gap) })
                }
                if (row.papers.size == 1) line.addView(View(host),LinearLayout.LayoutParams(0,1,1f))
                box.addView(line,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=host.dp(if(host.resources.configuration.screenWidthDp<=360) 10 else 16) })
            }
            RowKind.CALENDAR -> {
                val ym = YearMonth.parse(month)
                val header = LinearLayout(host).apply { gravity=Gravity.CENTER; orientation=LinearLayout.HORIZONTAL }
                fun arrow(delta:Long)=GlyphView(host).apply {glyph=if(delta<0)Glyph.BACK else Glyph.CHEVRON_RIGHT;ink=p.faint;contentDescription=host.getString(if(delta<0)R.string.a11y_previous_month else R.string.a11y_next_month);setOnClickListener {host.calendarNavigate(delta)}}
                header.addView(arrow(-1),LinearLayout.LayoutParams(host.dp(48),host.dp(48)))
                header.addView(host.text("${ym.year}年${ym.monthValue}月",20f).apply { gravity=Gravity.CENTER; setTypeface(typeface,Typeface.BOLD) },LinearLayout.LayoutParams(host.dp(160),host.dp(48)))
                header.addView(arrow(1),LinearLayout.LayoutParams(host.dp(48),host.dp(48)))
                box.addView(header,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=host.dp(20) })
                val weekdays = LinearLayout(host)
                listOf("日","一","二","三","四","五","六").forEach { weekdays.addView(host.text(it,13f,p.faint).apply { gravity=Gravity.CENTER },LinearLayout.LayoutParams(0,host.dp(32),1f)) }
                box.addView(weekdays)
                val offset = ym.atDay(1).dayOfWeek.value % 7
                val dates = row.issues.map { it.date }.toSet()
                val cellSize = (host.resources.configuration.screenWidthDp-32)/7
                repeat(6) { week ->
                    val line = LinearLayout(host)
                    repeat(7) { day ->
                        val number=week*7+day-offset+1
                        val cell=host.text(if(number in 1..ym.lengthOfMonth()) number.toString() else "",16f).apply { gravity=Gravity.CENTER }
                        if(number in 1..ym.lengthOfMonth()) {
                            val date=ym.atDay(number).toString()
                            val has=date in dates
                            cell.setTextColor(if(has) Color.WHITE else if(appearance.theme==1) 0xff888888.toInt() else 0xffcccccc.toInt())
                            if(has) {
                                cell.background=host.rounded(if(date==LocalDate.now().toString()) 0xffc0392b.toInt() else p.active,10)
                                cell.setTypeface(cell.typeface,Typeface.BOLD); cell.isClickable=true; cell.isFocusable=true
                                cell.setOnClickListener { host.calendarDate(date) }
                            }
                        }
                        line.addView(cell,LinearLayout.LayoutParams(0,host.dp(cellSize-2),1f).apply { setMargins(host.dp(1),host.dp(1),host.dp(1),host.dp(1)) })
                    }
                    box.addView(line)
                }
                text("● ${host.getString(R.string.ui_has)}    ○ ${host.getString(R.string.ui_none)}",13f,p.faint).apply { gravity=Gravity.CENTER; setPadding(0,host.dp(16),0,0) }
            }
            RowKind.ISSUE -> {
                box.setPadding(host.dp(20),host.dp(16),host.dp(20),host.dp(16))
                box.background = host.rounded(p.card,10)
                val params=box.layoutParams as RecyclerView.LayoutParams
                params.setMargins(host.dp(16),0,host.dp(16),host.dp(12)); box.layoutParams=params
                text(row.title,16f,bold=true,bottom=4)
                if(row.subtitle.isNotBlank()) text(row.subtitle,13f,p.faint)
            }
            RowKind.SECTION -> {
                box.setPadding(host.dp(16),host.dp(12),host.dp(16),host.dp(6))
                text(row.title,13f,p.muted,true).apply { background=host.rounded(p.card,6); setPadding(host.dp(12),host.dp(8),host.dp(12),host.dp(8)) }
            }
            RowKind.STORY -> {
                box.setPadding(host.dp(28),host.dp(12),host.dp(28),host.dp(12))
                text(row.title,15f,bottom=4)
                if(row.subtitle.isNotBlank()) text(row.subtitle,12f,p.faint)
                box.addView(View(host).apply { setBackgroundColor(p.card) },LinearLayout.LayoutParams(-1,host.dp(1)).apply { topMargin=host.dp(12) })
            }
            RowKind.BADGE -> {
                val badge=host.text(row.title,12f,Color.WHITE).apply { background=host.rounded(if(appearance.theme==1) 0xff444444.toInt() else 0xff1a5276.toInt(),4); setPadding(host.dp(12),host.dp(3),host.dp(12),host.dp(3)) }
                box.addView(badge,LinearLayout.LayoutParams(-2,-2).apply { bottomMargin=host.dp(12) })
            }
            RowKind.TITLE -> text(row.title,if(host.resources.configuration.screenWidthDp<=360) 20f else 22f,bold=true,bottom=10).apply { setLineSpacing(0f,1.4f) }
            RowKind.META -> { text(row.title,14f,p.muted,bottom=24); box.addView(View(host).apply { setBackgroundColor(p.card) },LinearLayout.LayoutParams(-1,host.dp(1)).apply { bottomMargin=host.dp(16) }) }
            RowKind.BLOCK -> {
                val block=checkNotNull(row.block)
                val font=floatArrayOf(15f,17f,19f,21f)[appearance.font]
                val line=floatArrayOf(1.65f,1.85f,2.05f,2.25f)[appearance.line]
                val value=when(block.kind) { BlockKind.PARAGRAPH -> "\u3000\u3000${block.text}"; BlockKind.LIST -> "• ${block.text}"; else -> block.text }
                if (block.kind == BlockKind.TABLE) {
                    val scroll=HorizontalScrollView(host)
                    scroll.addView(host.text(value,font).apply { readerLineHeight(line); setPadding(host.dp(8),host.dp(8),host.dp(8),host.dp(8)); typeface=Typeface.MONOSPACE })
                    box.addView(scroll)
                } else text(value,if(block.kind==BlockKind.CAPTION) 13f else font,if(block.kind in listOf(BlockKind.CAPTION,BlockKind.QUOTE)) p.muted else p.text,block.kind==BlockKind.TITLE,bottom=20).apply {
                    readerLineHeight(line)
                    if(Build.VERSION.SDK_INT>=29 && block.kind==BlockKind.PARAGRAPH) justificationMode=android.graphics.text.LineBreaker.JUSTIFICATION_MODE_INTER_WORD
                    if(block.kind==BlockKind.CAPTION) gravity=Gravity.CENTER
                    if(block.kind==BlockKind.QUOTE) { setBackgroundColor(p.card); setPadding(host.dp(12),host.dp(12),host.dp(12),host.dp(12)) }
                }
                if(block.id==highlight) box.setBackgroundColor(if(appearance.theme==1) 0xff273848.toInt() else 0xffedf3f8.toInt())
            }
            RowKind.IMAGE -> {
                val image=ImageView(host).apply { adjustViewBounds=true; scaleType=ImageView.ScaleType.FIT_CENTER; minimumHeight=host.dp(120); contentDescription=null; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                box.addView(image,LinearLayout.LayoutParams(-1,-2).apply { topMargin=host.dp(8); bottomMargin=host.dp(20) })
                val width=minOf(host.resources.displayMetrics.widthPixels,1440)
                val height=minOf(width*8,4_000_000/width)
                image.load(row.block!!.url) { size(width,height); crossfade(false) }
            }
            RowKind.MESSAGE -> { box.setPadding(host.dp(20),host.dp(60),host.dp(20),host.dp(16)); text(row.title,15f,p.faint).gravity=Gravity.CENTER }
            RowKind.RETRY -> { text(row.title,14f).apply { gravity=Gravity.CENTER; setPadding(host.dp(24),host.dp(12),host.dp(24),host.dp(12)); background=host.rounded(p.card,8) } }
        }
        if(row.kind in listOf(RowKind.ISSUE,RowKind.STORY,RowKind.RETRY)) { box.isClickable=true; box.isFocusable=true;box.pressFeedback();box.setOnClickListener { onClick(row) } }
    }
}
