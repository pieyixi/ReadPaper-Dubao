package com.newspaper.helper

import android.view.View
import android.view.animation.PathInterpolator

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

/** 只动画新页内容，禁止把入口颜色铺成全屏覆盖层；业务导航不等待动画。 */
internal class NativeMotion(private val host:MainActivity) {
    private var entering:View?=null
    fun enter(view:View,back:Boolean) {
        cancelEntry()
        if(!host.motionEnabled())return
        entering=view
        view.alpha=0f;view.translationX=host.dp(if(back)-22 else 22).toFloat()
        view.animate().alpha(1f).translationX(0f).setDuration(220).setInterpolator(PathInterpolator(.2f,.8f,.2f,1f)).start()
    }
    fun cancelEntry(){entering?.let {it.animate().cancel();it.alpha=1f;it.translationX=0f};entering=null}
    fun cancel(){cancelEntry()}
}
