package com.newspaper.helper

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils

/** 与 mock 的 font-size × line-height 对齐，不再把中文字体额外留白重复乘大。 */
internal fun android.widget.TextView.readerLineHeight(multiplier:Float) {
    includeFontPadding=false
    val metrics=paint.fontMetricsInt
    setLineSpacing((textSize*multiplier-(metrics.descent-metrics.ascent)).coerceAtLeast(0f),1f)
}

internal fun View.pressFeedback() {setOnTouchListener {_,event ->
    if(motionEnabled())when(event.actionMasked) {
        android.view.MotionEvent.ACTION_DOWN->animate().scaleX(.96f).scaleY(.96f).setDuration(80).start()
        android.view.MotionEvent.ACTION_UP,android.view.MotionEvent.ACTION_CANCEL->animate().scaleX(1f).scaleY(1f).setDuration(140).start()
    }
    false
}}

/** 页面立即提交，动画不调度业务跳转；返回/销毁只取消覆盖层，不产生迟到的导航。 */
internal class NativeMotion(private val host:MainActivity,private val frame:FrameLayout) {
    private var ghost:View?=null
    private var animator:ValueAnimator?=null
    private var entering:View?=null
    val expanding get()=ghost!=null
    fun expand(source:View,color:Int) {
        cancel()
        if(!host.motionEnabled() || source.width==0 || frame.width==0)return
        val at=IntArray(2);val origin=IntArray(2);source.getLocationOnScreen(at);frame.getLocationOnScreen(origin)
        val from=RectF((at[0]-origin[0]).toFloat(),(at[1]-origin[1]).toFloat(),(at[0]-origin[0]+source.width).toFloat(),(at[1]-origin[1]+source.height).toFloat())
        val to=RectF(0f,0f,frame.width.toFloat(),frame.height.toFloat())
        var fraction=0f
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val layer=object:View(host) {
            override fun onDraw(canvas:Canvas) {
                val t=(fraction/.75f).coerceAtMost(1f)
                val rect=RectF(from.left+(to.left-from.left)*t,from.top+(to.top-from.top)*t,from.right+(to.right-from.right)*t,from.bottom+(to.bottom-from.bottom)*t)
                paint.color=ColorUtils.blendARGB(color,host.palette.bg,t)
                paint.alpha=if(fraction<.7f)255 else (255*(1f-fraction)/.3f).toInt().coerceIn(0,255)
                canvas.drawRoundRect(rect,host.dp(24)*(1f-t),host.dp(24)*(1f-t),paint)
            }
        }.apply {importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;isClickable=false;elevation=host.dp(16).toFloat()}
        ghost=layer;frame.addView(layer,FrameLayout.LayoutParams(-1,-1))
        animator=ValueAnimator.ofFloat(0f,1f).apply {
            duration=340;interpolator=PathInterpolator(.2f,.8f,.2f,1f)
            addUpdateListener {fraction=it.animatedValue as Float;layer.invalidate()}
            addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(animation:Animator){frame.removeView(layer);if(ghost===layer)ghost=null}})
            start()
        }
    }
    fun enter(view:View,back:Boolean) {
        cancelEntry()
        if(!host.motionEnabled() || expanding)return
        entering=view
        view.alpha=0f;view.translationX=host.dp(if(back)-22 else 22).toFloat()
        view.animate().alpha(1f).translationX(0f).setDuration(220).setInterpolator(PathInterpolator(.2f,.8f,.2f,1f)).start()
    }
    fun cancelEntry(){entering?.let {it.animate().cancel();it.alpha=1f;it.translationX=0f};entering=null}
    fun cancel(){cancelEntry();animator?.cancel();animator=null;ghost?.let {frame.removeView(it)};ghost=null}
}
