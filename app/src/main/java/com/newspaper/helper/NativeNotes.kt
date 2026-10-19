package com.newspaper.helper

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.newspaper.helper.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 便签卡片只绑定预览，编辑时才读取全文；选择只刷新控件状态，保留正在触摸的卡片。 */
internal class NativeNotes(private val host:MainActivity,private val b:ActivityMainBinding,private val model:ReaderViewModel) {
    private var screen=Screen()
    private var settings=ReaderSettings()
    private var selecting=false
    private val adapter=Cards()
    private val colors get()=ChromeColors.forTheme(settings.theme)
    init {
        b.noteList.layoutManager=GridLayoutManager(host,2)
        b.noteList.adapter=adapter
        (b.noteList.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations=false
        b.newNote.glyph=Glyph.ADD;b.newNote.contentDescription=host.getString(R.string.a11y_new_note)
        b.newNote.setOnClickListener { if(screen.route.page==Page.NOTES && !screen.selecting) {host.expandFrom(b.newNote);model.newNote()} }
        b.selectAll.glyph=Glyph.SELECT_ALL;b.selectAll.setOnClickListener {model.selectAll()}
        b.pinIcon.glyph=Glyph.PIN;b.deleteIcon.glyph=Glyph.DELETE
        listOf(b.pinIcon,b.deleteIcon).forEach {it.isClickable=false;it.isFocusable=false;it.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        b.pinNotes.setOnClickListener {model.pinNotes()}
        b.deleteNotes.setOnClickListener {
            val selected=screen.selected.toSet()
            if(selected.isNotEmpty()) AlertDialog.Builder(host)
                .setMessage(host.getString(R.string.ui_delete_notes,selected.size))
                .setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.ui_delete) {_,_->host.deletedNotes(selected);model.deleteNotes()}.show()
        }
        b.noteList.addOnScrollListener(object:RecyclerView.OnScrollListener(){override fun onScrolled(v:RecyclerView,dx:Int,dy:Int){host.toolbarScrolled(v.canScrollVertically(-1))}})
    }
    fun render(next:Screen,appearance:ReaderSettings) {
        val old=screen;val themeChanged=settings.theme!=appearance.theme
        screen=next;settings=appearance
        val notes=next.route.page==Page.NOTES
        b.notesPanel.isVisible=notes
        b.selectionHeading.setTextColor(host.palette.text)
        b.selectionHeading.text=host.getString(R.string.ui_selected,next.selected.size)
        b.selectionHeading.isVisible=notes&&next.selecting
        b.selectAll.isVisible=notes&&next.selecting
        b.selectAll.ink=if(next.selected.size==next.notes.size) colors.accent else host.palette.text
        b.selectAll.contentDescription=host.getString(if(next.notes.isNotEmpty()&&next.selected.size==next.notes.size) R.string.a11y_clear_selection else R.string.a11y_select_all)
        if(notes) {
            b.back.glyph=if(next.selecting) Glyph.CLOSE else Glyph.BACK
            b.back.contentDescription=host.getString(if(next.selecting) R.string.a11y_exit_selection else R.string.a11y_back)
        }
        else {b.back.glyph=Glyph.BACK;b.back.contentDescription=host.getString(R.string.a11y_back)}
        show(b.newNote,notes&&!next.selecting,true)
        show(b.selectionBar,notes&&next.selecting,false)
        b.newNote.background=host.rounded(colors.accent,99);b.newNote.ink=Color.WHITE
        b.selectionBar.setBackgroundColor(host.palette.bg)
        b.pinIcon.ink=host.palette.text;b.deleteIcon.ink=host.palette.text
        b.pinLabel.setTextColor(host.palette.text);b.deleteLabel.setTextColor(host.palette.text)
        val allPinned=next.selected.isNotEmpty()&&next.notes.filter {it.id in next.selected}.all {it.pinned}
        b.pinLabel.setText(if(allPinned) R.string.ui_unpin else R.string.ui_pin)
        b.pinNotes.contentDescription=b.pinLabel.text;b.deleteNotes.contentDescription=host.getString(R.string.ui_delete)
        listOf(b.pinNotes,b.deleteNotes).forEach {it.isEnabled=next.selected.isNotEmpty();it.alpha=if(it.isEnabled)1f else .3f}
        adapter.submitList(next.notes)
        if(themeChanged || old.selected!=next.selected || old.selecting!=next.selecting) adapter.notifyItemRangeChanged(0,adapter.itemCount,"selection")
        if(selecting!=next.selecting && notes && host.motionEnabled()) {
            b.selectionHeading.alpha=if(next.selecting)0f else 1f
            b.selectionHeading.animate().alpha(1f).setDuration(180).start()
        }
        selecting=next.selecting
    }
    private fun show(view:View,visible:Boolean,fab:Boolean) {
        if(view.isSelected==visible && view.isVisible==visible)return
        if(view.isSelected==visible && view.animate()!=null && !visible)return
        view.isSelected=visible;view.animate().cancel()
        view.isClickable=visible
        view.importantForAccessibility=if(visible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if(!host.motionEnabled()){view.isVisible=visible;view.alpha=1f;view.translationY=0f;view.scaleX=1f;view.scaleY=1f;return}
        if(visible){view.isVisible=true;view.alpha=0f;view.translationY=if(fab)0f else host.dp(78).toFloat();view.scaleX=if(fab).3f else 1f;view.scaleY=view.scaleX
            view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(220).start()
        } else view.animate().alpha(0f).translationY(if(fab)0f else host.dp(78).toFloat()).scaleX(if(fab).3f else 1f).scaleY(if(fab).3f else 1f).setDuration(180).withEndAction {if(!view.isSelected)view.isVisible=false}.start()
    }
    private inner class Cards:ListAdapter<NotePreview,Card>(object:DiffUtil.ItemCallback<NotePreview>() {
        override fun areItemsTheSame(a:NotePreview,b:NotePreview)=a.id==b.id
        override fun areContentsTheSame(a:NotePreview,b:NotePreview)=a==b
    }) {
        override fun onCreateViewHolder(parent:ViewGroup,type:Int)=Card()
        override fun onBindViewHolder(holder:Card,position:Int)=holder.bind(getItem(position))
        override fun onBindViewHolder(holder:Card,position:Int,payloads:MutableList<Any>) {if(payloads.isEmpty())holder.bind(getItem(position)) else holder.appearance(getItem(position))}
    }
    private inner class Card:RecyclerView.ViewHolder(LinearLayout(host)) {
        private val box=itemView as LinearLayout
        private val title=host.text("",16f).apply {setTypeface(typeface,Typeface.BOLD);maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END}
        private val preview=host.text("",14f).apply {maxLines=3;setLineSpacing(0f,1.4f);ellipsize=android.text.TextUtils.TruncateAt.END}
        private val date=host.text("",11f).apply {maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
        private val pin=GlyphView(host).apply {glyph=Glyph.PIN;isClickable=false;isFocusable=false;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        private val check=GlyphView(host).apply {glyph=Glyph.CHECK;isClickable=false;isFocusable=false;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        init {
            box.orientation=LinearLayout.VERTICAL;box.setPadding(host.dp(16),host.dp(14),host.dp(16),host.dp(12))
            box.layoutParams=RecyclerView.LayoutParams(-1,host.dp(184)).apply {setMargins(host.dp(6),0,host.dp(6),host.dp(12))}
            box.addView(title,LinearLayout.LayoutParams(-1,-2))
            box.addView(preview,LinearLayout.LayoutParams(-1,0,1f).apply {topMargin=host.dp(6);bottomMargin=host.dp(8)})
            val footer=LinearLayout(host).apply {gravity=Gravity.CENTER_VERTICAL}
            footer.addView(pin,LinearLayout.LayoutParams(host.dp(12),host.dp(14)).apply {rightMargin=host.dp(3)})
            footer.addView(date,LinearLayout.LayoutParams(0,-2,1f))
            footer.addView(check,LinearLayout.LayoutParams(host.dp(22),host.dp(22)).apply {leftMargin=host.dp(4)})
            box.addView(footer,LinearLayout.LayoutParams(-1,host.dp(24)))
            box.isClickable=true;box.isFocusable=true
            box.setOnClickListener {
                val index=bindingAdapterPosition;if(index==RecyclerView.NO_POSITION)return@setOnClickListener
                val note=adapter.currentList[index]
                if(screen.selecting)model.selection(note.id) else {host.expandFrom(box);model.navigate(Route(Page.CUSTOM,noteId=note.id))}
            }
            box.setOnLongClickListener {
                val index=bindingAdapterPosition
                if(index!=RecyclerView.NO_POSITION&&!screen.selecting) {model.selection(adapter.currentList[index].id);true} else screen.selecting
            }
            box.setOnTouchListener {_,event ->
                if(host.motionEnabled()) when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN ->box.animate().scaleX(.95f).scaleY(.95f).setDuration(90).start()
                    MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL ->{val scale=if(box.isSelected).96f else 1f;box.animate().scaleX(scale).scaleY(scale).setDuration(150).start()}
                }
                false
            }
        }
        fun bind(note:NotePreview) {
            box.tag="note-${note.id}"
            val lines=note.preview.lines();val first=lines.indexOfFirst(String::isNotBlank)
            title.text=note.title.ifBlank {lines.getOrNull(first).orEmpty()}
            preview.text=if(note.title.isBlank())lines.drop(first+1).joinToString("\n") else note.preview
            date.text=SimpleDateFormat("MM-dd HH:mm",Locale.SIMPLIFIED_CHINESE).format(Date(note.updated))
            box.contentDescription=title.text
            appearance(note)
        }
        fun appearance(note:NotePreview) {
            val selected=note.id in screen.selected
            box.isSelected=selected
            box.background=host.rounded(if(selected)colors.tintOver(host.palette.bg) else host.palette.card,16)
            title.setTextColor(host.palette.text);preview.setTextColor(host.palette.muted);date.setTextColor(host.palette.faint)
            pin.ink=host.palette.faint;pin.isVisible=note.pinned
            check.ink=if(selected)Color.WHITE else Color.TRANSPARENT
            check.background=host.rounded(if(selected)colors.accent else Color.TRANSPARENT,99).apply {if(!selected)setStroke(host.dp(1),host.palette.faint)}
            check.isVisible=screen.selecting
            val scale=if(selected).96f else 1f
            if(host.motionEnabled())box.animate().scaleX(scale).scaleY(scale).setDuration(160).start() else {box.scaleX=scale;box.scaleY=scale}
        }
    }
}
