package com.newspaper.helper

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class VoiceUnavailable : IllegalStateException("TTS engine or voice unavailable")
interface SpeechEngine {
    suspend fun synthesize(text: String, file: File)
    fun cancel()
    fun close()
}
class AndroidSpeechEngine(context: Context) : SpeechEngine {
    private val initialized = CompletableDeferred<Int>()
    private val tts = TextToSpeech(context.applicationContext) { initialized.complete(it) }
    override suspend fun synthesize(text: String, file: File) {
        if (initialized.await() != TextToSpeech.SUCCESS) throw VoiceUnavailable()
        val locale = if (text.any { it.code in 0x3400..0x9fff }) Locale.SIMPLIFIED_CHINESE else Locale.getDefault()
        if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) throw VoiceUnavailable()
        // 只使用已安装的离线音色，避免自定义文本被系统的网络音色上传。
        fun offline(voice:android.speech.tts.Voice) = !voice.isNetworkConnectionRequired && voice.features?.contains("notInstalled") != true
        val voice=tts.voice?.takeIf(::offline) ?: tts.voices.orEmpty()
            .filter { offline(it) && it.locale.language==locale.language }.maxByOrNull { it.quality }
            ?: throw VoiceUnavailable()
        if(tts.setVoice(voice)==TextToSpeech.ERROR) throw VoiceUnavailable()
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
