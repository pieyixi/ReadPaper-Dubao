package com.newspaper.helper

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class VoiceUnavailable : IllegalStateException("TTS engine or voice unavailable")
interface SpeechEngine {
    val extension: String get() = "wav"
    val cacheIdentity: String get() = "system-offline-v2"
    suspend fun prepare(text: String) = Unit
    suspend fun synthesize(text: String, file: File)
    fun cancel()
    fun close()
}
class AndroidSpeechEngine(context: Context) : SpeechEngine {
    private val initialized = CompletableDeferred<Int>()
    private val tts = TextToSpeech(context.applicationContext) { initialized.complete(it) }
    private var selected: android.speech.tts.Voice? = null
    override val cacheIdentity get() = "system-offline-v2|${tts.defaultEngine}|${selected?.name}|rate=1|pitch=1"
    override suspend fun prepare(text: String) {
        if (withTimeoutOrNull(15000) { initialized.await() } != TextToSpeech.SUCCESS) throw VoiceUnavailable()
        val locale = if (text.any { it.code in 0x3400..0x9fff }) Locale.SIMPLIFIED_CHINESE else Locale.US
        if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) throw VoiceUnavailable()
        // 只使用已安装的离线音色，避免自定义文本被系统的网络音色上传。
        fun offline(voice:android.speech.tts.Voice) = !voice.isNetworkConnectionRequired && voice.features?.contains("notInstalled") != true
        val candidates=(tts.voices.orEmpty()+listOfNotNull(tts.voice)).distinctBy { it.name }
        val voice=candidates.filter { offline(it) && it.locale.language==locale.language }
            .sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country==locale.country }
                .thenByDescending { it.quality }.thenBy { it.latency }.thenBy { it.name }).firstOrNull()
            ?: throw VoiceUnavailable()
        if(tts.setVoice(voice)==TextToSpeech.ERROR) throw VoiceUnavailable()
        tts.setSpeechRate(1f);tts.setPitch(1f)
        selected=voice
    }
    override suspend fun synthesize(text: String, file: File) {
        if(selected==null) prepare(text)
        suspendCancellableCoroutine<Unit> { c ->
            val id = file.name
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { if (utteranceId == id && c.isActive) c.resume(Unit) }
                @Deprecated("Android callback") override fun onError(utteranceId: String?) { if (utteranceId == id && c.isActive) c.resumeWithException(VoiceUnavailable()) }
                override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
            })
            c.invokeOnCancellation { tts.stop() }
            if (tts.synthesizeToFile(text, null, file, id) == TextToSpeech.ERROR && c.isActive) c.resumeWithException(VoiceUnavailable())
        }
    }
    override fun cancel() { tts.stop() }
    override fun close() { tts.stop(); tts.shutdown() }
}
