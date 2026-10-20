package com.newspaper.helper

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 同一正文仅切换朗读标记，逐像素比较原生阅读区，不能以少读内容换取视觉变化。 */
@RunWith(AndroidJUnit4::class)
class CaptionReadingUiTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=ins.targetContext
    private fun waitFor(message:String,check:()->Boolean) {
        val until=System.currentTimeMillis()+15000
        while(System.currentTimeMillis()<until) {ins.waitForIdleSync();if(check())return;Thread.sleep(30)}
        fail(message)
    }
    private fun descendants(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {descendants(v.getChildAt(it))} else emptyList()
    @Test fun captionSpeechFlagsDoNotChangeAnyVisibleReadingPixel() {
        val store=SettingsStore(app)
        val url="https://www.qstheory.cn/20261020/${UUID.randomUUID()}/c.html"
        val story=Story(Paper.QIUSHI,url,"标题",date="2026-10-20")
        val photo=File(app.cacheDir,"caption-ui-${UUID.randomUUID()}.png")
        Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888).apply {eraseColor(0xffb8cdd5.toInt());photo.outputStream().use {compress(Bitmap.CompressFormat.PNG,100,it)};recycle()}
        val content=ContentParser.blocks("<p>这是正文，重要讲话内容仍应完整保留。</p><figure><img src='a.jpg'><figcaption>配图下方说明。张三 摄</figcaption></figure><p>这是配图之后继续阅读的正文。</p>",url)
            .map {if(it.kind==BlockKind.IMAGE)it.copy(url=photo.toURI().toString()) else it}
        val marked=Article(story,content);val plain=marked.copy(blocks=content.map {it.copy(omitFromSpeech=false)})
        runBlocking {
            val dao=ReaderDatabase.get(app).reader()
            dao.saveArticle(ArticleRecord(url,jsonCodec.toJson(marked),System.currentTimeMillis()))
            dao.savePosition(ReadingPosition(url))
            store.route(jsonCodec.toJson(Route(Page.READER,Paper.QIUSHI,date=story.date,story=story)))
            store.appearance(0,0,0)
        }
        val reports=mutableListOf<Map<String,Any>>()
        try {ActivityScenario.launch(MainActivity::class.java).use {scenario->
            lateinit var model:ReaderViewModel
            lateinit var flow:MutableStateFlow<Screen>
            scenario.onActivity {
                model=ViewModelProvider(it)[ReaderViewModel::class.java]
                @Suppress("UNCHECKED_CAST")
                flow=ReaderViewModel::class.java.getDeclaredField("mutable").apply {isAccessible=true}.get(model) as MutableStateFlow<Screen>
            }
            fun ready(marked:Boolean):Boolean {var ok=false;scenario.onActivity {
                val adapter=it.findViewById<RecyclerView>(R.id.list).adapter as NativeRows
                ok=!model.screen.value.loading && adapter.items.mapNotNull {it.block}.let {b->b.size==content.size && b.any {it.omitFromSpeech}==marked} &&
                    descendants(it.findViewById(R.id.readerFrame)).filterIsInstance<ImageView>().any {image->image.drawable!=null}
            };return ok}
            fun capture():Bitmap {
                val rect=IntArray(4)
                scenario.onActivity {val frame=it.findViewById<View>(R.id.readerFrame);frame.getLocationOnScreen(rect);rect[2]=frame.width;rect[3]=frame.height}
                val full=checkNotNull(ins.uiAutomation.takeScreenshot())
                return Bitmap.createBitmap(full,rect[0],rect[1],rect[2],rect[3]).also {full.recycle()}
            }
            waitFor("Caption reader did not load") {ready(true)}
            for(theme in 0..2) {
                runBlocking {store.appearance(theme,0,0)}
                waitFor("Reader theme did not apply") {var ok=false;scenario.onActivity {ok=it.palette.bg==Palette.forTheme(theme).bg};ok}
                scenario.onActivity {flow.value=flow.value.copy(article=plain)}
                waitFor("Plain reading snapshot not ready") {ready(false)}
                Thread.sleep(200);val before=capture()
                scenario.onActivity {flow.value=flow.value.copy(article=marked)}
                waitFor("Annotated reading snapshot not ready") {ready(true)}
                Thread.sleep(200);val after=capture()
                assertTrue("Speech flags changed visible reading pixels in theme $theme",before.sameAs(after))
                val width=app.resources.configuration.screenWidthDp
                val file=File(app.getExternalFilesDir(null),"$width-caption-reading-$theme.png")
                file.outputStream().use {after.compress(Bitmap.CompressFormat.PNG,100,it)}
                // executeShellCommand 不解释 shell 分隔符，目录创建与复制必须分别执行。
                for(command in listOf("mkdir -p /sdcard/Download/dubao-topbar-evidence","cp ${file.absolutePath} /sdcard/Download/dubao-topbar-evidence/")) {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(command)).use {it.readBytes()}
                }
                reports+=mapOf("theme" to theme,"visible_pixels_identical" to true,"body_blocks" to content.size,"speech_blocks" to Segmenter.split(marked).size)
                before.recycle();after.recycle()
            }
            val file=File(app.getExternalFilesDir(null),"${app.resources.configuration.screenWidthDp}-caption-reading.json")
            file.writeText(jsonCodec.toJson(reports))
            android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/dubao-topbar-evidence/")).use {it.readBytes()}
        }} finally {photo.delete()}
    }
}
