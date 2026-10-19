package com.newspaper.helper

import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

data class PlaybackStatus(val article: String = "", val block: String = "", val playing: Boolean = false, val preparing: Boolean = false, val voiceMissing: Boolean = false, val failed: Boolean = false,
    val segmentIndex: Int = 0, val segmentCount: Int = 0)
object PlaybackSignals {
    internal val mutable = MutableStateFlow(PlaybackStatus())
    val state = mutable.asStateFlow()
}
data class SpeechRequest(val stories: List<Story> = emptyList(), val custom: String? = null, val append: Boolean = false)
data class QueueSnapshot(val stories: List<Story>, val index: Int)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ReadAloudService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private lateinit var repository: ArticleRepository
    private lateinit var engine: SpeechEngine
    private var usingOnline = true
    private var audioFingerprint = ""
    private var durations = LongArray(0)
    private var speechFailed = false
    private var voiceMissing = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    private var generation = 0L
    private var segments = emptyList<SpeechSegment>()
    private var article: Article? = null
    private val queue = mutableListOf<Story>()
    private var queueIndex = 0
    private var baseSegment = 0
    private var generated = 0
    private var desiredPlay = true
    private var resumeMs = 0L
    private var custom = false
    private var resetting = false
    private lateinit var audioDir: File
    override fun onCreate() {
        super.onCreate()
        repository = ArticleRepository(this)
        engine = EdgeSpeechEngine()
        audioDir = File(cacheDir, "speech")
        scope.launch(Dispatchers.IO) { audioDir.mkdirs(); prune(emptySet()) }
        player = ExoPlayer.Builder(this).build().apply {
            setWakeMode(C.WAKE_MODE_LOCAL)
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { publish() }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (!resetting) desiredPlay = playWhenReady; publish() }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if(resetting || mediaItem==null) return
                    val discard = player.currentMediaItemIndex - 1
                    if (discard > 0) { baseSegment += discard; player.removeMediaItems(0, discard) }
                    publish(); fillAhead()
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if(resetting) return
                    if (playbackState == Player.STATE_ENDED) {
                        if (generated < segments.size) fillAhead()
                        else if (queueIndex + 1 < queue.size) {
                            persist(reset=true); queueIndex++; saveQueue(); loadStory(queue[queueIndex], restore = false)
                        } else { persist(reset=true); publish() }
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    speechFailed=true;publish()
                }
            })
        }
        val sessionPlayer=object : androidx.media3.common.ForwardingPlayer(player) {
            override fun play() {
                desiredPlay=true
                if (player.playbackState==Player.STATE_ENDED && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                } else if (player.playbackState==Player.STATE_ENDED && generated>=segments.size && segments.isNotEmpty()) {
                    seekSegment(-segments.size)
                }
                super.play()
            }
            override fun pause() { desiredPlay=false;super.pause() }
            override fun setPlayWhenReady(playWhenReady:Boolean) { desiredPlay=playWhenReady;super.setPlayWhenReady(playWhenReady) }
        }
        session = MediaSession.Builder(this, sessionPlayer).setSessionActivity(PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(
                        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(SessionCommand("speak", Bundle.EMPTY))
                            .add(SessionCommand("stop", Bundle.EMPTY)).add(SessionCommand("step", Bundle.EMPTY)).add(SessionCommand("toggle", Bundle.EMPTY))
                            .add(SessionCommand("refreshVoice", Bundle.EMPTY)).add(SessionCommand("resumeQueue", Bundle.EMPTY)).build()).build()
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        "speak" -> {
                            val request = runCatching { jsonCodec.fromJson(args.getString("request"), SpeechRequest::class.java) }.getOrNull()
                                ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                            if (request.append && request.custom == null && queue.isNotEmpty()) {
                                queue += request.stories.filter { candidate -> queue.none { it.url == candidate.url } }
                                saveQueue()
                            } else start(request)
                        }
                        "stop" -> stopReading()
                        "step" -> seekSegment(args.getInt("delta", 1))
                        "toggle" -> { desiredPlay = !desiredPlay; player.playWhenReady = desiredPlay }
                        "refreshVoice" -> {
                            work?.cancel()
                            work = null
                            engine.close()
                            engine = if(usingOnline) EdgeSpeechEngine() else AndroidSpeechEngine(this@ReadAloudService)
                            speechFailed=false;voiceMissing=false
                            PlaybackSignals.mutable.value = PlaybackSignals.mutable.value.copy(voiceMissing=false,failed=false,preparing=false)
                        }
                        "resumeQueue" -> restoreQueue(true)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }).build()
        scope.launch {
            while (true) { delay(2000); if (player.isPlaying) { persist();fillAhead() } }
        }
        restoreQueue(false)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        // 冷启动媒体按键不能等待首段合成才进入前台，否则会触发系统五秒超时。
        ensureForeground()
        val result=super.onStartCommand(intent,flags,startId)
        @Suppress("DEPRECATION")
        val key=intent?.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
        if (intent?.action==Intent.ACTION_MEDIA_BUTTON && key?.action==android.view.KeyEvent.ACTION_DOWN &&
            key.keyCode in listOf(android.view.KeyEvent.KEYCODE_MEDIA_PLAY,android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,android.view.KeyEvent.KEYCODE_HEADSETHOOK) && player.mediaItemCount==0) {
            restoreQueue(true)
        }
        return result
    }
    private fun ensureForeground() {
        val manager=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel="reader_playback_preparing"
        if (Build.VERSION.SDK_INT>=26) manager.createNotificationChannel(NotificationChannel(channel,getString(R.string.app_name),NotificationManager.IMPORTANCE_LOW))
        val notification=NotificationCompat.Builder(this,channel).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(article?.story?.title?.ifBlank { getString(R.string.app_name) } ?: getString(R.string.app_name))
            .setContentIntent(PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT).setOngoing(true).setSilent(true).build()
        if(Build.VERSION.SDK_INT>=29) startForeground(1001,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(1001,notification)
    }

    private fun restoreQueue(play: Boolean) {
        val token=generation
        scope.launch {
            val snapshot=withContext(Dispatchers.IO) { runCatching { repository.dao.queue()?.let { jsonCodec.fromJson(it.payload,QueueSnapshot::class.java) } }.getOrNull() }
            if (token!=generation) return@launch
            if (snapshot==null || snapshot.stories.isEmpty()) {
                if(play) endPreparingIfEmpty()
                return@launch
            }
            queue.clear();queue+=snapshot.stories;queueIndex=snapshot.index.coerceIn(0,queue.lastIndex)
            if(play) { desiredPlay=true;custom=false;loadStory(queue[queueIndex],true) }
            else if(PlaybackSignals.state.value.article.isEmpty()) PlaybackSignals.mutable.value=PlaybackStatus(article=queue[queueIndex].url)
        }
    }

    private fun start(request: SpeechRequest) {
        ensureForeground()
        persist()
        work?.cancel(); engine.cancel(); generation++
        speechFailed=false;voiceMissing=false
        resetPlayer()
        segments = emptyList()
        desiredPlay = true
        queue.clear(); queue += request.stories; queueIndex = 0
        custom = request.custom != null
        if (request.custom != null) {
            work = scope.launch {
                try {
                    val input = File(File(cacheDir, "speech-input"), request.custom)
                    require(request.custom.matches(Regex("[a-f0-9]{64}\\.txt")))
                    val text = withContext(Dispatchers.IO) { input.readText().also { input.delete() } }
                    val textArticle = withContext(Dispatchers.Default) { Segmenter.custom(text) }
                    begin(textArticle, true)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { reportFailure(e) }
            }
        } else if (queue.isNotEmpty()) {
            val selected = queue.first()
            work = scope.launch {
                val prior = withContext(Dispatchers.IO) {
                    runCatching { repository.dao.queue()?.let { jsonCodec.fromJson(it.payload, QueueSnapshot::class.java) } }.getOrNull()
                }
                if (request.stories.size == 1 && prior != null) {
                    val index = prior.stories.indexOfFirst { it.url == selected.url }
                    if (index >= 0) { queue.clear(); queue += prior.stories; queueIndex = index }
                }
                saveQueue()
                work = null
                loadStory(selected, true)
            }
        }
    }
    private fun loadStory(story: Story, restore: Boolean) {
        work?.cancel(); engine.cancel(); generation++
        val token = generation
        resetPlayer()
        segments=emptyList();durations=LongArray(0)
        PlaybackSignals.mutable.value = PlaybackStatus(article = story.url, preparing = true)
        work = scope.launch {
            try {
                val loaded = repository.article(story)
                if (token == generation) begin(loaded, restore)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                engine.cancel()
                reportFailure(e,story.url)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportFailure(e,story.url) }
        }
    }
    private suspend fun begin(loaded: Article, restore: Boolean) {
        article = loaded
        segments = withContext(Dispatchers.Default) { Segmenter.split(loaded) }
        durations=LongArray(segments.size)
        usingOnline=SpeechPolicy.online(loaded)
        custom=!usingOnline
        engine.close()
        engine=if(usingOnline) EdgeSpeechEngine() else AndroidSpeechEngine(this)
        if(segments.isNotEmpty()) prepareEngine(loaded)
        val position = if (restore) repository.dao.position(loaded.story.url) else null
        if (position==null && custom) repository.dao.savePosition(ReadingPosition(loaded.story.url))
        val restored=withContext(Dispatchers.Default) { SpeechPolicy.resume(position,loaded,segments,audioFingerprint) }
        baseSegment = restored.first
        generated = baseSegment
        resumeMs = restored.second
        speechFailed=false;voiceMissing=false
        PlaybackSignals.mutable.value = PlaybackStatus(article = loaded.story.url, preparing = segments.isNotEmpty())
        // 首段完成即可播放；预取按可播放时长计量，并限制为最多八段。
        work = null
        fillAhead()
    }
    private suspend fun prepareEngine(loaded:Article) {
        val sample=withContext(Dispatchers.Default) {
            loaded.blocks.firstOrNull { block -> block.text.any { it.code in 0x3400..0x9fff } }?.text
                ?: segments.firstOrNull()?.text.orEmpty()
        }
        val selectedEngine=engine
        withContext(Dispatchers.IO) { selectedEngine.prepare(sample) }
        audioFingerprint=withContext(Dispatchers.Default) { SpeechPolicy.fingerprint(loaded,selectedEngine) }
    }
    private fun needsAudio():Boolean {
        val current=baseSegment+maxOf(0,player.currentMediaItemIndex)
        val buffered=(current until generated).sumOf { durations.getOrElse(it) { 0L } }-player.currentPosition.coerceAtLeast(0)
        return SpeechPolicy.prefetch(current,generated,segments.size,buffered,player.playbackParameters.speed)
    }
    private fun fillAhead() {
        if (work?.isActive == true || segments.isEmpty() || speechFailed || voiceMissing || !needsAudio()) return
        val token = generation
        work = scope.launch {
            var succeeded = false
            try {
                while (needsAudio() && token == generation) {
                    val segment = segments[generated]
                    val clip=withTimeout(60000) { SpeechAudioCache(audioDir).render(segment,engine,custom) }
                    if (token != generation) {
                        return@launch
                    }
                    durations[generated]=clip.durationMs
                    val item = MediaItem.Builder().setUri(android.net.Uri.fromFile(clip.file)).setMediaId(segment.index.toString())
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(article?.story?.title?.ifBlank { getString(R.string.app_name) })
                            .setArtist(article?.story?.paper?.label).build()).build()
                    val first = player.mediaItemCount == 0
                    val ended = player.playbackState == Player.STATE_ENDED
                    player.addMediaItem(item); generated++
                    if (first) { player.prepare(); player.seekTo(0, resumeMs); player.playWhenReady = desiredPlay }
                    else if (ended && desiredPlay) { player.seekToNextMediaItem(); player.prepare(); player.playWhenReady = true }
                    publish()
                    val keep = (0 until player.mediaItemCount).mapNotNull { i -> player.getMediaItemAt(i).localConfiguration?.uri?.path }.toSet()
                    withContext(Dispatchers.IO) { prune(keep) }
                }
                succeeded = true
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                engine.cancel()
                reportFailure(e)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // 只在尚未播放首段时尝试离线后备；已有在线声音时绝不在中途换声。
                if(usingOnline && player.mediaItemCount==0) {
                    engine.close();usingOnline=false;engine=AndroidSpeechEngine(this@ReadAloudService)
                    try {
                        prepareEngine(checkNotNull(article));resumeMs=0;succeeded=true
                    } catch(cancel:CancellationException) { throw cancel }
                    catch(fallback:Exception) { reportFailure(fallback) }
                } else reportFailure(e)
            } finally {
                if (token == generation) {
                    work = null
                    if (succeeded && needsAudio()) {
                        scope.launch { kotlinx.coroutines.yield(); fillAhead() }
                    }
                }
            }
        }
    }
    private fun seekSegment(delta: Int) {
        if (segments.isEmpty()) return
        persist()
        val index = (baseSegment + maxOf(0, player.currentMediaItemIndex) + delta).coerceIn(0, segments.lastIndex)
        if (index in baseSegment until generated) { player.seekTo(index - baseSegment, 0); return }
        val playing = desiredPlay
        work?.cancel(); engine.cancel(); generation++
        resetPlayer(); desiredPlay = playing
        baseSegment = index; generated = index; resumeMs = 0; work = null; fillAhead()
    }
    private fun publish() {
        val segment = segments.getOrNull(baseSegment + maxOf(0, player.currentMediaItemIndex))
        PlaybackSignals.mutable.value = PlaybackStatus(article?.story?.url.orEmpty(), segment?.blockId.orEmpty(), player.isPlaying,
            player.mediaItemCount == 0 && work?.isActive == true && !speechFailed && !voiceMissing,voiceMissing,speechFailed,
            baseSegment + maxOf(0,player.currentMediaItemIndex),segments.size)
    }
    private fun reportFailure(error:Exception,url:String=article?.story?.url.orEmpty()) {
        voiceMissing=error is VoiceUnavailable;speechFailed=!voiceMissing
        publish()
        PlaybackSignals.mutable.value=PlaybackSignals.mutable.value.copy(article=url,preparing=false)
        endPreparingIfEmpty()
    }
    private fun resetPlayer() { resetting = true; player.stop(); player.clearMediaItems(); resetting = false }
    @Suppress("DEPRECATION")
    private fun endPreparingIfEmpty() { if(player.mediaItemCount==0) stopForeground(true) }
    private fun persist(reset:Boolean=false) {
        val url = article?.story?.url ?: return
        val finished=reset || (player.playbackState==Player.STATE_ENDED && generated>=segments.size)
        val segment = if(finished) 0 else (baseSegment + maxOf(0, player.currentMediaItemIndex)).coerceAtLeast(0)
        val ms = if(finished) 0L else player.currentPosition.coerceAtLeast(0)
        val anchor=segments.getOrNull(segment)
        val fingerprint=audioFingerprint
        scope.launch(Dispatchers.IO) { repository.dao.audioPosition(url, segment, ms,anchor?.blockId.orEmpty(),anchor?.start ?: 0,fingerprint) }
    }
    private fun saveQueue() {
        val payload = jsonCodec.toJson(QueueSnapshot(queue.toList(), queueIndex))
        scope.launch(Dispatchers.IO) { repository.dao.saveQueue(QueueRecord(payload = payload)) }
    }
    private fun stopReading() {
        persist(); work?.cancel(); work = null; engine.cancel(); generation++
        val privateFiles=if(custom) (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).localConfiguration?.uri?.path } else emptyList()
        // 停止期间禁止清空媒体项的回调再次触发预取，避免旧稿在停止后重新入队。
        resetPlayer(); segments = emptyList(); article = null; queue.clear()
        speechFailed=false;voiceMissing=false
        PlaybackSignals.mutable.value = PlaybackStatus()
        endPreparingIfEmpty()
        scope.launch(Dispatchers.IO) { privateFiles.forEach { File(it).delete() } }
    }
    private fun prune(keep: Set<String>) {
        var size = 0L
        audioDir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { file ->
            size += file.length()
            if (file.absolutePath !in keep && (size > 32 * 1024 * 1024 || System.currentTimeMillis() - file.lastModified() > 24 * 60 * 60 * 1000)) file.delete()
        }
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!desiredPlay && work?.isActive != true) { persist(); stopSelf() }
    }
    override fun onDestroy() {
        resetting=true
        @Suppress("DEPRECATION")
        stopForeground(true)
        work?.cancel(); engine.close(); session?.release(); player.release(); scope.cancel()
        super.onDestroy()
    }
}
