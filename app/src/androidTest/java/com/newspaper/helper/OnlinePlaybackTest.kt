package com.newspaper.helper

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** 使用真实 Edge 合成和 Media3 解码。目录/正文来自原站样本，不冒充五来源实时抓取。 */
@RunWith(AndroidJUnit4::class)
class OnlinePlaybackTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext
    private fun <T> main(block:()->T):T {
        var result:Result<T>?=null
        instrumentation.runOnMainSync { result=runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
    private fun waitFor(message:String,predicate:()->Boolean) {
        val end=System.currentTimeMillis()+90000
        while(System.currentTimeMillis()<end) { if(predicate()) return;Thread.sleep(150) }
        fail("$message: ${PlaybackSignals.state.value}")
    }
    private fun shell(command:String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    @Test fun pastedNoteUsesOnlineYunyangAndPrefetchesEditsWithoutLosingLoopOrPause() {
        val settings=SettingsStore(app)
        runBlocking {settings.route(jsonCodec.toJson(Route()));settings.appearance(0,2,2);settings.playbackMode(PlaybackMode.FULL_ONCE)}
        val future=main {MediaController.Builder(app,SessionToken(app,ComponentName(app,ReadAloudService::class.java))).buildAsync()}
        val player=future.get(30,TimeUnit.SECONDS)
        val evidence=File(app.getExternalFilesDir(null),"online-evidence").apply {mkdirs()}
        var noteId:String?=null
        try {ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            scenario.onActivity {it.findViewById<android.view.View>(R.id.paste).performClick()}
            waitFor("Note history did not open") {var ok=false;scenario.onActivity {ok=it.findViewById<android.view.View>(R.id.newNote).isShown};ok}
            scenario.onActivity {it.findViewById<android.view.View>(R.id.newNote).performClick()}
            waitFor("New note did not become editable") {var ok=false;scenario.onActivity {ok=it.findViewById<android.widget.EditText>(R.id.editor).isShown&&it.findViewById<android.widget.EditText>(R.id.editor).isEnabled};ok}
            scenario.onActivity {
                noteId=androidx.lifecycle.ViewModelProvider(it)[ReaderViewModel::class.java].screen.value.route.noteId
                it.findViewById<android.widget.EditText>(R.id.noteTitle).setText("朗读检查")
                it.findViewById<android.widget.EditText>(R.id.editor).setText("这是一段用于检查粘贴朗读的文本。\n同一份笔记可以继续编辑，也可以暂停和恢复朗读。")
                it.findViewById<android.view.View>(R.id.speech).performClick()
            }
            Thread.sleep(400)
            assertEquals("Opening the headphone panel started synthesis",0,main {player.mediaItemCount})
            assertFalse("Opening the headphone panel started playback",main {player.playWhenReady||player.isPlaying})
            assertFalse("Opening the headphone panel prepared audio",PlaybackSignals.state.value.preparing)
            scenario.onActivity {it.window.decorView.findViewWithTag<GlyphView>("speechPlay").performClick()}
            waitFor("Pasted text did not play") {PlaybackSignals.state.value.article.startsWith("custom:")&&main {player.isPlaying&&player.currentPosition>100}}
            main {player.pause()}
            assertEquals(noteId,PlaybackSignals.state.value.noteId)
            val path=main {checkNotNull(player.currentMediaItem?.localConfiguration?.uri?.path)}
            assertTrue("Pasted text fell back to system speech",path.endsWith(".mp3"))
            assertTrue("Pasted audio entered shared cache",File(path).name.startsWith("private-"))
            scenario.onActivity {androidx.lifecycle.ViewModelProvider(it)[ReaderViewModel::class.java].navigate(Route())}
            waitFor("Paused note did not return home") {var ok=false;scenario.onActivity {ok=it.findViewById<android.view.View>(R.id.paste).isShown};ok}
            scenario.onActivity {assertFalse("Paused audio kept a playing badge",it.findViewById<android.view.View>(R.id.nowPlaying).isShown)}
            checkNotNull(main {player.sessionActivity}).send()
            waitFor("Notification did not open paused note") {var ok=false;scenario.onActivity {val s=androidx.lifecycle.ViewModelProvider(it)[ReaderViewModel::class.java].screen.value;ok=s.route.page==Page.CUSTOM&&s.route.noteId==noteId&&!s.loading};ok}
            assertFalse("Opening paused note from notification started audio",main {player.playWhenReady})
            File(path).copyTo(File(evidence,"pasted-yunyang.mp3"),overwrite=true)
            File(evidence,"pasted-speech.png").outputStream().use {instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            main {player.play()}
            waitFor("Pasted audio did not resume") {main {player.isPlaying}}
            scenario.onActivity {androidx.lifecycle.ViewModelProvider(it)[ReaderViewModel::class.java].navigate(Route())}
            waitFor("Playing note badge did not appear") {var ok=false;scenario.onActivity {ok=it.findViewById<GlyphView>(R.id.nowPlaying).let {v->v.isShown&&v.playing}};ok}
            scenario.onActivity {it.findViewById<android.view.View>(R.id.nowPlaying).performClick()}
            waitFor("Home badge did not open the playing note") {var ok=false;scenario.onActivity {val s=androidx.lifecycle.ViewModelProvider(it)[ReaderViewModel::class.java].screen.value;ok=s.route.page==Page.CUSTOM&&s.route.noteId==noteId&&!s.loading};ok}
            val oldArticle=PlaybackSignals.state.value.article
            scenario.onActivity {it.findViewById<android.widget.EditText>(R.id.editor).append("\n编辑后旧声音必须停止。")}
            waitFor("Edited paragraph was not prepared") {PlaybackSignals.state.value.article!=oldArticle && PlaybackSignals.state.value.block=="custom-3" && main {player.mediaItemCount>0&&player.duration>0}}
            assertFalse("Editing in play-once mode resumed automatically",main {player.playWhenReady})
            waitFor("Edited note was not saved") {runBlocking {ReaderDatabase.get(app).reader().note(checkNotNull(noteId))?.content?.endsWith("编辑后旧声音必须停止。")==true}}
            assertTrue("Stopping/editing discarded reusable note audio",File(path).exists())
            assertTrue("Note audio must remain in private non-backup storage",File(path).canonicalPath.startsWith(app.noBackupFilesDir.canonicalPath+File.separator))
            fun command(name:String)=main {player.sendCustomCommand(SessionCommand(name,Bundle.EMPTY),Bundle.EMPTY)}.get(10,TimeUnit.SECONDS)
            while(PlaybackSignals.state.value.mode!=PlaybackMode.PARAGRAPH_LOOP)command("cycleMode")
            main {player.play()}
            waitFor("Paragraph loop did not play") {main {player.isPlaying}}
            val priorAudio=main {player.currentMediaItem!!.localConfiguration!!.uri.path}
            val beforeEdit=PlaybackSignals.state.value.article
            var latestArticle=""
            // 同一主线程内连续输入必须只采用最后一份正文；改动落在原文第三自然段。
            scenario.onActivity {
                val editor=it.findViewById<android.widget.EditText>(R.id.editor)
                editor.append("现在改字");editor.append("后的原文继续循环。");editor.append("最后确认。")
                latestArticle=Segmenter.custom(it.findViewById<android.widget.EditText>(R.id.noteTitle).text.toString()+"\n"+editor.text).story.url
            }
            waitFor("Editing a playing paragraph loop did not resume latest text") {
                PlaybackSignals.state.value.article==latestArticle && PlaybackSignals.state.value.article!=beforeEdit && PlaybackSignals.state.value.block=="custom-3" &&
                    main {player.isPlaying&&player.currentPosition>50&&player.currentMediaItem?.localConfiguration?.uri?.path!=priorAudio}
            }
            assertEquals(PlaybackMode.PARAGRAPH_LOOP,PlaybackSignals.state.value.mode)
            val editedAudio=main {player.currentMediaItem!!.localConfiguration!!.uri.path}
            main {player.pause()}
            val pausedArticle=PlaybackSignals.state.value.article
            scenario.onActivity {it.findViewById<android.widget.EditText>(R.id.editor).append("暂停时只准备音频。")}
            waitFor("Paused edit was not prefetched") {PlaybackSignals.state.value.article!=pausedArticle && main {player.mediaItemCount>0&&player.duration>0&&player.currentMediaItem?.localConfiguration?.uri?.path!=editedAudio}}
            Thread.sleep(800)
            assertFalse("Paused edit started speaking",main {player.playWhenReady||player.isPlaying})
            assertTrue("An unrelated title clip was removed by editing",File(path).isFile)
            main {player.play()}
            waitFor("Prepared edited paragraph did not resume") {main {player.isPlaying}}
            val cachedPath=main {player.currentMediaItem!!.localConfiguration!!.uri.path}
            main {player.seekTo((player.duration-80).coerceAtLeast(0))}
            waitFor("Edited paragraph did not loop from its start") {main {player.isPlaying&&player.currentPosition in 50..1500}}
            assertEquals(cachedPath,main {player.currentMediaItem!!.localConfiguration!!.uri.path})
            scenario.onActivity {it.findViewById<android.widget.EditText>(R.id.editor).append("停止后不能再自动播放。")}
            command("stop")
            Thread.sleep(2500)
            assertEquals("A delayed edited request resurrected stopped audio",0,main {player.mediaItemCount})
            assertTrue(PlaybackSignals.state.value.article.isEmpty())
            File(evidence,"pasted-playback.json").writeText(jsonCodec.toJson(mapOf("online_mp3" to true,"private_cache" to true,"pause_resume" to true,
                "manual_start_only" to true,"notification_opens_paused_note" to true,"home_badge_opens_playing_note" to true,
                "editing_cancels_old_audio" to true,"local_note_saved" to true,"edited_paragraph_prefetched" to true,
                "loop_uses_latest_original_text" to true,"paused_edit_stays_paused" to true,"stop_rejects_delayed_edit" to true)))
            shell("mkdir -p /sdcard/Download/dubao-online-evidence")
            shell("cp -r /sdcard/Android/data/com.newspaper.helper/files/online-evidence/. /sdcard/Download/dubao-online-evidence/")
        }} finally {
            runCatching {main {player.sendCustomCommand(SessionCommand("stop",Bundle.EMPTY),Bundle.EMPTY)}.get(10,TimeUnit.SECONDS)}
            noteId?.let {runBlocking {ReaderDatabase.get(app).reader().deleteNotes(listOf(it));SpeechAudioCache(SpeechAudioCache.root(app)).deleteNotes(listOf(it))}}
            main {player.release()}
        }
    }
    @Test fun originalParagraphLoopsWholeArticleRestartsAndOncePauses() {
        // 仅测试 APK 内的受控文本，不作为原站采集证据；使用真实联网合成和播放器。
        val article=Article(Story(Paper.PEOPLE,"https://example.org/dubao-mode-regression","",date="2026-10-19"),
            (0..11).map { index -> BodyBlock("mode-$index",BlockKind.PARAGRAPH,
                if(index==0) "原文第一句。"+"这是同一自然段后面的内容。".repeat(7) else "这是第${index+1}自然段。") })
        val plan=Segmenter.split(article)
        val paragraph=PlaybackBounds.paragraph(plan,0)
        assertTrue("Fixture must split a natural paragraph",paragraph.count()>1)
        assertTrue("Fixture must exceed the rolling window",plan.size>8)
        val settings=SettingsStore(app)
        runBlocking(Dispatchers.IO) {
            ReaderDatabase.get(app).reader().saveArticle(ArticleRecord(article.story.url,jsonCodec.toJson(article),System.currentTimeMillis()))
            ReaderDatabase.get(app).reader().savePosition(ReadingPosition(article.story.url))
            settings.route(jsonCodec.toJson(Route(Page.READER,story=article.story)))
            settings.appearance(0,2,2)
            settings.playbackMode(PlaybackMode.FULL_ONCE)
        }
        val future=main { MediaController.Builder(app,SessionToken(app,ComponentName(app,ReadAloudService::class.java))).buildAsync() }
        val player=future.get(30,TimeUnit.SECONDS)
        fun command(name:String,args:Bundle=Bundle.EMPTY)=main { player.sendCustomCommand(SessionCommand(name,Bundle.EMPTY),args) }.get(10,TimeUnit.SECONDS)
        fun choose(mode:PlaybackMode) {
            repeat(3) {
                if(PlaybackSignals.state.value.mode==mode) return
                command("cycleMode")
                Thread.sleep(100)
            }
            assertEquals(mode,PlaybackSignals.state.value.mode)
        }
        fun skip(delta:Int)=command("step",Bundle().apply { putInt("delta",delta) })
        fun at(index:Int)=main { player.currentMediaItem?.mediaId==index.toString() && player.duration>0 }
        fun nearEndAndPlay()=main { player.seekTo((player.duration-80).coerceAtLeast(0));player.play() }
        val evidence=File(app.getExternalFilesDir(null),"online-evidence").apply { mkdirs() }
        try {
            choose(PlaybackMode.FULL_ONCE)
            main { player.setPlaybackSpeed(1f) }
            command("speak",Bundle().apply { putString("request",jsonCodec.toJson(SpeechRequest(stories=listOf(article.story)))) })
            waitFor("Mode fixture did not play") { main { player.isPlaying && player.currentPosition>80 } }
            main { player.pause() }
            val firstAudio=File(main {checkNotNull(player.currentMediaItem?.localConfiguration?.uri?.path)})
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor("Mode reader did not load") {
                    var ready=false
                    scenario.onActivity { ready=it.findViewById<android.view.View>(R.id.speech).isShown && it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list).adapter!!.itemCount>3 }
                    ready
                }
                scenario.onActivity { it.findViewById<android.view.View>(R.id.speech).performClick() }
                fun tapMode() { scenario.onActivity { it.window.decorView.findViewWithTag<GlyphView>("playbackMode").performClick() } }
                for(mode in listOf(PlaybackMode.FULL_LOOP,PlaybackMode.PARAGRAPH_LOOP,PlaybackMode.FULL_ONCE)) {
                    tapMode()
                    waitFor("Button did not select $mode") { PlaybackSignals.state.value.mode==mode }
                    waitFor("Mode choice not persisted") { runBlocking { settings.playbackMode()==mode } }
                    assertFalse("Mode selection resumed paused audio",main { player.playWhenReady })
                    instrumentation.waitForIdleSync();Thread.sleep(150)
                    File(evidence,"mode-${mode.name.lowercase()}.png").outputStream().use {
                        instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                    }
                }
                tapMode() // FULL_LOOP
                waitFor("Full loop not selected") { PlaybackSignals.state.value.mode==PlaybackMode.FULL_LOOP }
                scenario.recreate()
                waitFor("Reader did not recreate") {
                    var ready=false
                    scenario.onActivity { ready=it.findViewById<android.view.View>(R.id.speech).isShown }
                    ready
                }
                scenario.onActivity { it.findViewById<android.view.View>(R.id.speech).performClick() }
                waitFor("Mode icon lost on recreation") {
                    var correct=false
                    scenario.onActivity { correct=it.window.decorView.findViewWithTag<GlyphView>("playbackMode")?.glyph==Glyph.REPEAT_ALL }
                    correct
                }
                skip(plan.lastIndex-PlaybackSignals.state.value.segmentIndex)
                waitFor("Tail did not load") { at(plan.lastIndex) }
                assertFalse("Tail must test restart outside rolling window",main { (0 until player.mediaItemCount).any { player.getMediaItemAt(it).mediaId=="0" } })
                assertTrue("Article start was evicted with the player window",firstAudio.exists())
                val rewritten=java.util.Collections.synchronizedList(mutableListOf<String>())
                @Suppress("DEPRECATION")
                val observer=object:android.os.FileObserver(firstAudio.parentFile!!.absolutePath,android.os.FileObserver.CREATE or android.os.FileObserver.MOVED_TO) {
                    override fun onEvent(event:Int,path:String?) {if(path?.startsWith(firstAudio.nameWithoutExtension)==true)rewritten+=path}
                }
                observer.startWatching()
                try {
                    nearEndAndPlay()
                    waitFor("Full loop failed to return to article start") { at(0) && main { player.isPlaying && player.currentPosition>50 } }
                    assertEquals(firstAudio.absolutePath,main {player.currentMediaItem?.localConfiguration?.uri?.path})
                    assertTrue("Loop regenerated its cached first segment: $rewritten",rewritten.isEmpty())
                } finally {observer.stopWatching()}
                main { player.pause() }
                // 当前缓冲确实包含下个自然段，选择单段模式后必须将它裁掉。
                waitFor("No next paragraph was prefetched") { main { (0 until player.mediaItemCount).any { player.getMediaItemAt(it).mediaId.toInt()>paragraph.last } } }
                tapMode() // PARAGRAPH_LOOP
                waitFor("Paragraph loop not selected") { PlaybackSignals.state.value.mode==PlaybackMode.PARAGRAPH_LOOP }
                assertTrue("Next paragraph leaked into repeat playlist",main { (0 until player.mediaItemCount).all { player.getMediaItemAt(it).mediaId.toInt() in paragraph } })
                val visited=mutableListOf<Int>()
                val listener=object:Player.Listener {
                    override fun onMediaItemTransition(mediaItem:MediaItem?,reason:Int) { mediaItem?.mediaId?.toIntOrNull()?.let { visited+=it } }
                }
                main { player.addListener(listener) }
                try {
                    nearEndAndPlay()
                    waitFor("Paragraph did not read its second audio chunk") { at(paragraph.first+1) && main { player.isPlaying && player.currentPosition>50 } }
                    main { player.pause() }
                    nearEndAndPlay()
                    waitFor("Whole natural paragraph did not repeat from its start") { at(paragraph.first) && main { player.isPlaying && player.currentPosition>50 } }
                    main { player.pause() }
                    assertTrue(main { visited.all { it in paragraph } })
                } finally { main { player.removeListener(listener) } }
                skip(1)
                waitFor("Next did not switch the repeating original paragraph") { PlaybackSignals.state.value.block=="mode-1" && at(paragraph.last+1) }
                skip(-1)
                waitFor("Previous did not return to original paragraph start") { at(paragraph.first) }
                tapMode() // FULL_ONCE
                waitFor("Play once not selected") { PlaybackSignals.state.value.mode==PlaybackMode.FULL_ONCE }
                skip(plan.lastIndex-PlaybackSignals.state.value.segmentIndex)
                waitFor("Once tail did not load") { at(plan.lastIndex) }
                nearEndAndPlay()
                waitFor("Article did not pause at its end") { main { player.playbackState==Player.STATE_ENDED && !player.playWhenReady && !player.isPlaying } }
                Thread.sleep(1000)
                assertTrue(at(plan.lastIndex))
                assertEquals(article.story.url,PlaybackSignals.state.value.article)
                File(evidence,"repeat-modes.json").writeText(jsonCodec.toJson(mapOf(
                    "original_paragraph_chunks" to paragraph.count(),"article_chunks" to plan.size,
                    "full_loop_returns_to_start" to true,"first_segment_reused_without_regeneration" to true,
                    "paragraph_keeps_all_chunks" to true,"once_pauses_at_end" to true)))
            }
            command("stop")
            Thread.sleep(1000)
            assertEquals(0,main { player.mediaItemCount })
            shell("mkdir -p /sdcard/Download/dubao-online-evidence")
            shell("cp -r /sdcard/Android/data/com.newspaper.helper/files/online-evidence/. /sdcard/Download/dubao-online-evidence/")
        } finally {
            runCatching { choose(PlaybackMode.FULL_ONCE);command("stop") }
            main { player.release() }
        }
    }
    @Test fun fivePaperSamplesPlayOnlineAndSurvivePauseSwitchAndScreenOff() {
        val assets=instrumentation.context.assets
        val urls=JSONObject(assets.open("sources/provenance.json").bufferedReader().readText())
        val cases=listOf("study" to Paper.STUDY,"organization" to Paper.ORGANIZATION,"people" to Paper.PEOPLE,"south" to Paper.SOUTH,"qiushi" to Paper.QIUSHI)
        val articles=runBlocking(Dispatchers.IO) {
            cases.map { (name,paper) ->
                val story=Story(paper,urls.getString("$name-article"),"")
                val body=assets.open("sources/$name-article.${if(paper==Paper.ORGANIZATION) "json" else "html"}").bufferedReader().readText()
                val source=NewsSources(object:Transport { override suspend fun load(url:String,json:String?)=body })
                source.article(story).also { article ->
                    val dao=ReaderDatabase.get(app).reader()
                    dao.saveArticle(ArticleRecord(story.url,jsonCodec.toJson(article),System.currentTimeMillis()))
                    dao.savePosition(ReadingPosition(story.url))
                }
            }
        }
        val future=main { MediaController.Builder(app,SessionToken(app,ComponentName(app,ReadAloudService::class.java))).buildAsync() }
        val player=future.get(30,TimeUnit.SECONDS)
        fun command(name:String,bundle:Bundle=Bundle.EMPTY)=main { player.sendCustomCommand(SessionCommand(name,Bundle.EMPTY),bundle) }.get(10,TimeUnit.SECONDS)
        val evidence=File(app.getExternalFilesDir(null),"online-evidence").apply { mkdirs() }
        val results=mutableListOf<Map<String,Any>>()
        try {
            for(article in articles) {
                val started=System.currentTimeMillis()
                command("speak",Bundle().apply { putString("request",jsonCodec.toJson(SpeechRequest(stories=listOf(article.story)))) })
                waitFor("No online playback for ${article.story.paper}") {
                    PlaybackSignals.state.value.article==article.story.url && main { player.isPlaying && player.currentPosition>500 }
                }
                val uri=main { player.currentMediaItem!!.localConfiguration!!.uri }
                assertTrue("Offline fallback is not online evidence",uri.path!!.endsWith(".mp3"))
                File(uri.path!!).copyTo(File(evidence,"${article.story.paper.name.lowercase()}.mp3"),overwrite=true)
                results+=mapOf("paper" to article.story.paper.name,"url" to article.story.url,"blocks" to article.blocks.size,
                    "first_play_ms" to System.currentTimeMillis()-started,"media_position_ms" to main { player.currentPosition })
                // 冷启动使用通知相同的 Intent；随后真实发送会话的 PendingIntent 验证热启动。
                ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java).setAction(ReadAloudService.OPEN_PLAYING)).use {entry->
                    fun atReader():Boolean {var ok=false;entry.onActivity {val s=androidx.lifecycle.ViewModelProvider(it)[ReaderViewModel::class.java].screen.value;ok=s.route.page==Page.READER&&s.article?.story?.url==article.story.url};return ok}
                    waitFor("Notification cold start opened wrong article: ${article.story.paper}") {atReader()}
                    entry.onActivity {androidx.lifecycle.ViewModelProvider(it)[ReaderViewModel::class.java].navigate(Route())}
                    waitFor("Home playing icon missing: ${article.story.paper}") {var ok=false;entry.onActivity {val badge=it.findViewById<GlyphView>(R.id.nowPlaying);ok=badge.isShown&&badge.playing};ok}
                    entry.onActivity {it.findViewById<android.view.View>(R.id.nowPlaying).performClick()}
                    waitFor("Home playing icon opened wrong article") {atReader()}
                    entry.onActivity {it.onBackPressedDispatcher.onBackPressed()}
                    waitFor("Playing article added duplicate back-stack entries") {var ok=false;entry.onActivity {ok=androidx.lifecycle.ViewModelProvider(it)[ReaderViewModel::class.java].screen.value.route.page==Page.HOME};ok}
                    checkNotNull(main {player.sessionActivity}).send()
                    waitFor("Notification warm start opened wrong article") {atReader()}
                    assertEquals(article.story.url,PlaybackSignals.state.value.article)
                    assertTrue("Opening current article paused playback",main {player.playWhenReady})
                }
                results[results.lastIndex]=results.last()+mapOf("notification_cold_start" to true,"notification_warm_start" to true,"home_playing_badge" to true,"back_returns_home" to true)
            }
            val last=articles.last()
            val settings=SettingsStore(app)
            runBlocking { settings.route(jsonCodec.toJson(Route(Page.READER,last.story.paper,date=last.story.date,story=last.story)));settings.appearance(0,2,2) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor("Reader toolbar missing") {
                    var ready=false
                    scenario.onActivity { ready=it.findViewById<android.view.View>(R.id.speech).isShown && it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list).adapter!!.itemCount>3 }
                    ready
                }
                scenario.onActivity { it.findViewById<android.view.View>(R.id.speech).performClick() }
                waitFor("Live speech panel did not receive duration") {
                    var ready=false
                    scenario.onActivity { ready=it.findViewById<android.view.View>(R.id.sheet).isShown && it.window.decorView.findViewWithTag<com.google.android.material.slider.Slider>("speechProgress")?.isEnabled==true }
                    ready
                }
                for(theme in 0..2) {
                    runBlocking { settings.appearance(theme,2,2) };Thread.sleep(300)
                    scenario.onActivity {
                        assertFalse(it.findViewById<android.view.View>(R.id.paste).isShown)
                        assertTrue(it.findViewById<android.view.View>(R.id.settings).isShown)
                    }
                    instrumentation.waitForIdleSync()
                    File(evidence,"reader-speech-$theme.png").outputStream().use {
                        instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                    }
                }
                for(rate in listOf(.8f,1f,1.1f,1.2f,1.3f)) {
                    scenario.onActivity { it.window.decorView.findViewWithTag<android.widget.TextView>(rate).performClick() }
                    waitFor("Toolbar speed $rate did not reach service") { main { player.playbackParameters.speed==rate } }
                }
                scenario.onActivity { it.window.decorView.findViewWithTag<GlyphView>("speechPlay").performClick() }
                waitFor("Toolbar pause did not reach service") { main { !player.playWhenReady } }
                scenario.onActivity { it.window.decorView.findViewWithTag<GlyphView>("speechPlay").performClick() }
                waitFor("Toolbar resume did not reach service") { main { player.playWhenReady } }
                main { player.setPlaybackSpeed(1f) }
                // 后台朗读仍在继续时，首页和中间页不得重新长出播放栏或朗读按钮。
                scenario.onActivity { androidx.lifecycle.ViewModelProvider(it)[ReaderViewModel::class.java].navigate(Route()) }
                Thread.sleep(300)
                scenario.onActivity { assertTrue(it.findViewById<android.view.View>(R.id.paste).isShown);assertFalse(it.findViewById<android.view.View>(R.id.speech).isShown);assertFalse(it.findViewById<android.view.View>(R.id.sheet).isShown) }
                scenario.onActivity { it.openPaper(Paper.QIUSHI) }
                Thread.sleep(300)
                scenario.onActivity { assertFalse(it.findViewById<android.view.View>(R.id.actions).isShown) }
            }
            main { player.pause() }
            waitFor("Pause not acknowledged") { main { !player.isPlaying && !player.playWhenReady } }
            Thread.sleep(300)
            val paused=main { player.currentPosition }
            Thread.sleep(500)
            assertTrue(kotlin.math.abs(main { player.currentPosition }-paused)<100)
            main { player.play() }
            waitFor("Resume did not advance") { main { player.isPlaying && player.currentPosition>paused+150 } }
            val old=main { player.currentMediaItem!!.mediaId }
            command("step",Bundle().apply { putInt("delta",1) })
            waitFor("Next segment did not play") { main { player.currentMediaItem?.mediaId!=old && player.isPlaying } }
            command("step",Bundle().apply { putInt("delta",-1) })
            waitFor("Previous segment did not play") { main { player.currentMediaItem?.mediaId==old && player.isPlaying } }
            app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val before=main { player.currentMediaItem?.mediaId to player.currentPosition }
            shell("input keyevent 223")
            waitFor("Screen off stopped playback") {
                main { player.isPlaying && (player.currentMediaItem?.mediaId!=before.first || player.currentPosition>before.second+500) }
            }
            shell("input keyevent 224");shell("wm dismiss-keyguard")
            File(evidence,"playback.json").writeText(jsonCodec.toJson(results))
            command("stop")
            waitFor("Stop retained old media") { main { player.mediaItemCount==0 } }
            Thread.sleep(2000)
            assertEquals("Cancelled prefetch restarted after stop",0,main { player.mediaItemCount })
            assertFalse(PlaybackSignals.state.value.playing)
            // AGP 测试结束会卸载应用，提前将公开新闻样本证据导出到模拟器共享目录。
            shell("mkdir -p /sdcard/Download/dubao-online-evidence")
            shell("cp -r /sdcard/Android/data/com.newspaper.helper/files/online-evidence/. /sdcard/Download/dubao-online-evidence/")
        } finally {
            shell("input keyevent 224");shell("wm dismiss-keyguard")
            runCatching { command("stop") };main { player.release() }
        }
    }
}
