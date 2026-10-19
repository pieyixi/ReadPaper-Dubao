package com.newspaper.helper

import android.graphics.Bitmap
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.`is`
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NotesUiTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=ins.targetContext
    private fun waitFor(message:String,check:()->Boolean) {val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){ins.waitForIdleSync();if(check())return;Thread.sleep(80)};fail(message)}
    private fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText()}
    private fun shot(name:String) {
        ins.waitForIdleSync();Thread.sleep(220)
        val directory=File(app.getExternalFilesDir(null),"note-evidence").apply {mkdirs()}
        val file=File(directory,"${app.resources.configuration.screenWidthDp}-notes-$name.png")
        file.outputStream().use {ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
        shell("mkdir -p /sdcard/Download/dubao-topbar-evidence");shell("cp ${file.absolutePath} /sdcard/Download/dubao-topbar-evidence/")
    }
    @Test fun historyTitleSelectionPinDeleteAndImmediateBack() {
        val dao=ReaderDatabase.get(app).reader();val store=SettingsStore(app)
        val prefix="notes-ui-${System.nanoTime()}-";val ids=(0..9).map {"$prefix$it"}
        runBlocking {
            store.route(jsonCodec.toJson(Route()));store.appearance(0,2,2)
            ids.forEachIndexed {index,id->dao.saveNote(PastedNote(id,"便签标题 ${index+1}","原文第一段。\n保留完整正文，便于继续阅读。",updated=System.currentTimeMillis()+10000-index))}
        }
        try {ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            fun model(block:(ReaderViewModel)->Unit){scenario.onActivity {block(ViewModelProvider(it)[ReaderViewModel::class.java])}}
            fun visible(id:Int):Boolean {var result=false;scenario.onActivity {result=it.findViewById<View>(id).isShown};return result}
            onView(withId(R.id.paste)).perform(click())
            waitFor("note grid missing"){visible(R.id.noteList)&&visible(R.id.newNote)}
            var fabY=0f
            scenario.onActivity {fabY=it.findViewById<View>(R.id.newNote).y;it.findViewById<RecyclerView>(R.id.noteList).scrollToPosition(9)}
            scenario.onActivity {assertEquals(fabY,it.findViewById<View>(R.id.newNote).y);it.findViewById<RecyclerView>(R.id.noteList).scrollToPosition(0)}
            shot("list")
            onView(withTagValue(`is`("note-${ids[0]}"))).perform(longClick())
            waitFor("long press did not select"){var ok=false;model {ok=it.screen.value.selecting&&it.screen.value.selected==setOf(ids[0])};ok}
            assertTrue(visible(R.id.selectAll));assertTrue(visible(R.id.selectionBar));assertFalse(visible(R.id.newNote))
            onView(withId(R.id.selectAll)).perform(click())
            model {assertEquals(it.screen.value.notes.size,it.screen.value.selected.size)}
            onView(withId(R.id.selectAll)).perform(click())
            model {assertTrue(it.screen.value.selected.isEmpty());it.selection(ids[0])}
            shot("selected")
            onView(withId(R.id.pinNotes)).perform(click())
            waitFor("pin not persisted"){runBlocking {dao.note(ids[0])?.pinned==true}}
            waitFor("selection did not close"){!visible(R.id.selectionBar)}
            onView(withTagValue(`is`("note-${ids[0]}"))).perform(click())
            waitFor("stored note missing"){var ready=false;scenario.onActivity {ready=it.findViewById<EditText>(R.id.editor).isEnabled&&it.findViewById<EditText>(R.id.noteTitle).text.toString()=="便签标题 1"};ready}
            scenario.onActivity {it.findViewById<EditText>(R.id.noteTitle).setText("秋日读书");it.findViewById<EditText>(R.id.editor).setText("认真阅读。\n记录想法。")} 
            waitFor("word count missing"){var words="";scenario.onActivity {words=it.findViewById<TextView>(R.id.noteMetadata).text.toString()};words.contains("14字")}
            shot("editor")
            scenario.recreate()
            waitFor("title lost on recreation"){var title="";scenario.onActivity {title=it.findViewById<EditText>(R.id.noteTitle).text.toString()};title=="秋日读书"}
            scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
            waitFor("did not return to notes"){visible(R.id.noteList)}
            waitFor("edited note not saved"){runBlocking {dao.note(ids[0])?.content=="认真阅读。\n记录想法。"}}
            // 同一事件周期内重复点击、立即返回：不得生成两层编辑页或迟到跳转。
            repeat(3) {
                scenario.onActivity {activity ->
                    val m=ViewModelProvider(activity)[ReaderViewModel::class.java]
                    activity.findViewById<View>(R.id.newNote).performClick();val id=m.screen.value.route.noteId
                    activity.findViewById<View>(R.id.newNote).performClick();assertEquals(id,m.screen.value.route.noteId)
                    activity.onBackPressedDispatcher.onBackPressed()
                }
                Thread.sleep(450)
                model {assertEquals(Page.NOTES,it.screen.value.route.page)}
            }
            for(theme in 0..2){runBlocking {store.appearance(theme,2,2)};Thread.sleep(250);shot("theme-$theme")}
            model {it.selection(ids[0])}
            waitFor("delete selection missing"){visible(R.id.selectionBar)}
            onView(withId(R.id.deleteNotes)).perform(click())
            onView(withId(android.R.id.button2)).perform(click())
            assertNotNull(runBlocking {dao.note(ids[0])})
            onView(withId(R.id.deleteNotes)).perform(click())
            onView(withId(android.R.id.button1)).perform(click())
            waitFor("selected note not deleted"){runBlocking {dao.note(ids[0])==null}}
            assertNotNull(runBlocking {dao.note(ids[1])})
        }} finally {runBlocking {dao.deleteNotes(ids);store.route(jsonCodec.toJson(Route()));store.appearance(0,2,2)}}
    }
}
