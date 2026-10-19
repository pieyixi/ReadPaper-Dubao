package com.newspaper.helper

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.provider.Settings
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import android.graphics.drawable.Drawable

enum class Glyph { BACK, PASTE, PLAY, PAUSE, STOP, PREVIOUS, NEXT, QUEUE, RETRY, GEAR, HEADPHONES, REPEAT_ALL, REPEAT_ONE, REPEAT_OFF, ADD, CLOSE, SELECT_ALL, PIN, DELETE, CHECK, CHEVRON_RIGHT }
internal fun Context.motionEnabled():Boolean = if(Build.VERSION.SDK_INT>=26) ValueAnimator.areAnimatorsEnabled()
    else Settings.Global.getFloat(contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f
internal fun View.motionEnabled():Boolean=context.motionEnabled()

/** 图标路径缓存；仅状态改变时交叉淡入，不在播放进度回调中重建控件。 */
class GlyphView @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null):View(context,attrs) {
    private var old=Glyph.BACK
    private var mix=1f
    private var morph:ValueAnimator?=null
    private var pulse:ValueAnimator?=null
    private var pulseValue=0f
    var glyph=Glyph.BACK
        set(value) {
            if(field==value)return
            old=field;field=value;morph?.cancel()
            if(isAttachedToWindow && motionEnabled()) {
                morph=ValueAnimator.ofFloat(0f,1f).apply { duration=180;addUpdateListener { mix=it.animatedValue as Float;invalidate() };start() }
            } else { mix=1f;invalidate() }
        }
    var ink=0xff666666.toInt();set(value){if(field!=value){field=value;invalidate()}}
    var playing=false
        set(value){if(field==value)return;field=value;pulse?.cancel();pulse=null
            if(value && isAttachedToWindow && motionEnabled()) pulse=ValueAnimator.ofFloat(0f,1f).apply { duration=1400;repeatCount=ValueAnimator.INFINITE;addUpdateListener {pulseValue=it.animatedValue as Float;invalidate()};start() }
            invalidate()
        }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val icons=mutableMapOf<Glyph,Drawable>()
    init {isClickable=true;isFocusable=true;minimumWidth=(48*resources.displayMetrics.density).toInt();minimumHeight=minimumWidth}
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(isEnabled && motionEnabled()) when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(.93f).scaleY(.93f).setDuration(90).start()
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }
        return super.onTouchEvent(event)
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        canvas.save();val density=resources.displayMetrics.density
        canvas.translate(width/2f-12*density,height/2f-12*density);canvas.scale(density,density)
        paint.color=ink;paint.strokeWidth=1.75f;paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND
        fun draw(g:Glyph,alpha:Float){if(alpha<=0)return;val icon=icons.getOrPut(g){checkNotNull(ContextCompat.getDrawable(context,checkNotNull(resourcesByGlyph[g]))).mutate()};icon.setTint(ink);icon.alpha=(255*alpha).toInt();icon.setBounds(0,0,24,24);icon.draw(canvas)}
        if(mix<1f)draw(old,1f-mix);draw(glyph,mix)
        if(glyph==Glyph.HEADPHONES && playing){paint.style=Paint.Style.FILL;paint.alpha=(255*(.65f+.35f*kotlin.math.cos(pulseValue*6.283f))).toInt();canvas.drawCircle(20f,3f,2f,paint)}
        canvas.restore()
    }
    override fun onDetachedFromWindow(){morph?.cancel();pulse?.cancel();pulse=null;mix=1f;super.onDetachedFromWindow()}
    companion object {
        private val resourcesByGlyph=mapOf(
            Glyph.BACK to R.drawable.ic_glyph_back,
            Glyph.CHEVRON_RIGHT to R.drawable.ic_glyph_chevron_right,
            Glyph.PASTE to R.drawable.ic_glyph_paste,
            Glyph.HEADPHONES to R.drawable.ic_glyph_headphones,
            Glyph.PLAY to R.drawable.ic_glyph_play,
            Glyph.PAUSE to R.drawable.ic_glyph_pause,
            Glyph.STOP to R.drawable.ic_glyph_stop,
            Glyph.PREVIOUS to R.drawable.ic_glyph_previous,
            Glyph.NEXT to R.drawable.ic_glyph_next,
            Glyph.REPEAT_ALL to R.drawable.ic_glyph_repeat_all,
            Glyph.REPEAT_ONE to R.drawable.ic_glyph_repeat_one,
            Glyph.REPEAT_OFF to R.drawable.ic_glyph_repeat_off,
            Glyph.ADD to R.drawable.ic_glyph_add,
            Glyph.CLOSE to R.drawable.ic_glyph_close,
            Glyph.CHECK to R.drawable.ic_glyph_check,
            Glyph.SELECT_ALL to R.drawable.ic_glyph_select_all,
            Glyph.PIN to R.drawable.ic_glyph_pin,
            Glyph.DELETE to R.drawable.ic_glyph_delete,
            Glyph.RETRY to R.drawable.ic_glyph_retry,
            Glyph.QUEUE to R.drawable.ic_glyph_queue,
            Glyph.GEAR to R.drawable.ic_glyph_gear
        )
    }
}
