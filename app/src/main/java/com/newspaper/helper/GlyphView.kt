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
import androidx.core.graphics.PathParser

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
        fun draw(g:Glyph,alpha:Float){if(alpha<=0)return;paint.alpha=(255*alpha).toInt();paint.style=if(g in filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE;paths[g]?.let {canvas.drawPath(it,paint)}}
        if(mix<1f)draw(old,1f-mix);draw(glyph,mix)
        if(glyph==Glyph.HEADPHONES && playing){paint.style=Paint.Style.FILL;paint.alpha=(255*(.65f+.35f*kotlin.math.cos(pulseValue*6.283f))).toInt();canvas.drawCircle(20f,3f,2f,paint)}
        canvas.restore()
    }
    override fun onDetachedFromWindow(){morph?.cancel();pulse?.cancel();pulse=null;mix=1f;super.onDetachedFromWindow()}
    companion object {
        private val filled=setOf(Glyph.PLAY,Glyph.PAUSE,Glyph.STOP,Glyph.PREVIOUS,Glyph.NEXT)
        private val paths=mapOf(
            Glyph.BACK to "M15 5L8 12L15 19",
            Glyph.CHEVRON_RIGHT to "M9 5L16 12L9 19",
            Glyph.PASTE to "M8 4H7Q5 4 5 7V18Q5 21 8 21H16Q19 21 19 18V7Q19 4 16 4H15 M9 4.5Q9 3 10.5 3H13.5Q15 3 15 4.5V6H9Z M9 12H15 M9 16H13",
            Glyph.HEADPHONES to "M3 14H6Q8 14 8 16V19Q8 21 6 21H5Q3 21 3 19V12A9 9 0 0 1 21 12V19Q21 21 19 21H18Q16 21 16 19V16Q16 14 18 14H21",
            Glyph.PLAY to "M8.5 5.5V18.5L19 12Z",
            Glyph.PAUSE to "M7.7 5H8.8Q10 5 10 6.2V17.8Q10 19 8.8 19H7.7Q6.5 19 6.5 17.8V6.2Q6.5 5 7.7 5Z M15.2 5H16.3Q17.5 5 17.5 6.2V17.8Q17.5 19 16.3 19H15.2Q14 19 14 17.8V6.2Q14 5 15.2 5Z",
            Glyph.STOP to "M9 6.5H15Q17.5 6.5 17.5 9V15Q17.5 17.5 15 17.5H9Q6.5 17.5 6.5 15V9Q6.5 6.5 9 6.5Z",
            Glyph.PREVIOUS to "M6.5 5.5V18.5 M18 6.5V17.5L9.5 12Z",
            Glyph.NEXT to "M17.5 5.5V18.5 M6 6.5V17.5L14.5 12Z",
            Glyph.REPEAT_ALL to "M17 2L21 6L17 10 M3 11V10A4 4 0 0 1 7 6H21 M7 22L3 18L7 14 M21 13V14A4 4 0 0 1 17 18H3",
            Glyph.REPEAT_ONE to "M17 2L21 6L17 10 M3 11V10A4 4 0 0 1 7 6H21 M7 22L3 18L7 14 M21 13V14A4 4 0 0 1 17 18H3 M11 10H12V14",
            Glyph.REPEAT_OFF to "M4 12H15 M11 7L16 12L11 17 M20 6V18",
            Glyph.ADD to "M12 4V20 M4 12H20",
            Glyph.CLOSE to "M5 5L19 19 M19 5L5 19",
            Glyph.CHECK to "M5 12.5L9.5 17L19 7",
            Glyph.SELECT_ALL to "M8.5 3.5H15.5Q20.5 3.5 20.5 8.5V15.5Q20.5 20.5 15.5 20.5H8.5Q3.5 20.5 3.5 15.5V8.5Q3.5 3.5 8.5 3.5Z M8.2 12.3L10.8 14.9L15.8 9.5",
            Glyph.PIN to "M12 17V22 M9 10.8Q9 12 7.9 12.6L6.1 13.5Q5 14 5 15.2V16Q5 17 6 17H18Q19 17 19 16V15.2Q19 14 17.9 13.5L16.1 12.6Q15 12 15 10.8V7Q15 6 16 6A2 2 0 0 0 16 2H8A2 2 0 0 0 8 6Q9 6 9 7Z",
            Glyph.DELETE to "M3 6H21 M19 6V20Q19 22 17 22H7Q5 22 5 20V6 M8 6V4Q8 2 10 2H14Q16 2 16 4V6 M10 11V17 M14 11V17",
            Glyph.RETRY to "M20 4V10H14 M20 10A8 8 0 1 0 18 18",
            Glyph.QUEUE to "M4 6H18 M4 11H14 M4 16H11 M18 13V21 M14 17H22",
            Glyph.GEAR to "M12 4A8 8 0 1 0 12 20A8 8 0 1 0 12 4 M12 8A4 4 0 1 0 12 16A4 4 0 1 0 12 8"
        ).mapValues { checkNotNull(PathParser.createPathFromPathData(it.value)) }
    }
}
