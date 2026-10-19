package com.newspaper.helper

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

internal class OnlineSpeechFailure(val status: Int = 0, val serverDate: String? = null, val retryMs: Long = 1000) :
    IOException("Online speech failed ($status)")

/** 合成用户选择朗读的报纸或便签；服务层先查本机音频，未命中才建立在线连接。 */
internal class EdgeSpeechEngine(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS)
        .readTimeout(20,TimeUnit.SECONDS).build(),
    private val endpoint: HttpUrl = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1".toHttpUrl(),
    private val now: () -> Long = System::currentTimeMillis,
    private val attemptTimeoutMs: Long = 25000,
    private val retryDelayMs: Long = 1000
) : SpeechEngine {
    override val extension = "mp3"
    override val cacheIdentity = "edge-v2|zh-CN-YunyangNeural|rate=0|pitch=0|volume=0"
    private val mutex=Mutex()
    private val active=AtomicReference<Attempt?>()
    private val muid=UUID.randomUUID().toString().replace("-", "").uppercase(Locale.ROOT)
    @Volatile private var closed=false
    private var skewMs=0L

    private class Attempt {
        val result=CompletableDeferred<ByteArray>()
        val socket=AtomicReference<WebSocket?>()
        val audio=ByteArrayOutputStream()
        fun cancel() { result.cancel(); socket.get()?.cancel() }
    }
    override suspend fun synthesize(text: String, file: File) = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(!closed)
            val escaped=escapeText(text)
            require(escaped.toByteArray(Charsets.UTF_8).size<=4096) { "Speech segment exceeds protocol limit" }
            for (attempt in 0..1) {
                try {
                    val bytes=withTimeout(attemptTimeoutMs) { request(escaped) }
                    coroutineContext.ensureActive()
                    file.writeBytes(bytes)
                    return@withContext
                } catch(e:TimeoutCancellationException) {
                    // 外层切文取消不能误当成超时重试。
                    coroutineContext.ensureActive()
                    if(attempt==1) throw OnlineSpeechFailure()
                } catch(e:OnlineSpeechFailure) {
                    if(attempt==1) throw e
                    when {
                        e.status==403 -> {
                            val server=e.serverDate?.let(::parseDate) ?: throw e
                            val correction=server-now()
                            if(kotlin.math.abs(correction-skewMs)<1000) throw e
                            skewMs=correction
                        }
                        e.status==429 -> { if(e.retryMs>5000) throw e; delay(e.retryMs.coerceAtLeast(1000)) }
                        e.status in 400..499 -> throw e
                        else -> delay(retryDelayMs)
                    }
                }
            }
        }
    }
    private suspend fun request(escaped: String): ByteArray {
        val attempt=Attempt()
        active.set(attempt)
        val url=endpoint.newBuilder().addQueryParameter("TrustedClientToken",EdgeToken.CLIENT)
            .addQueryParameter("ConnectionId",UUID.randomUUID().toString().replace("-",""))
            .addQueryParameter("Sec-MS-GEC",EdgeToken.generate(now()+skewMs))
            .addQueryParameter("Sec-MS-GEC-Version",EdgeToken.VERSION).build()
        val request=Request.Builder().url(url).header("Pragma","no-cache").header("Cache-Control","no-cache")
            .header("Origin","chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
            .header("Accept-Language","en-US,en;q=0.9").header("Cookie","muid=$muid;").build()
        try {
            val socket=client.newWebSocket(request,object:WebSocketListener() {
                override fun onOpen(webSocket:WebSocket,response:Response) {
                    if(attempt.result.isCompleted) { webSocket.cancel(); return }
                    val timestamp=timestamp(now()+skewMs)
                    val config="X-Timestamp:$timestamp\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n"+
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                    val ssml="<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"+
                        "<voice name='Microsoft Server Speech Text to Speech Voice (zh-CN, YunyangNeural)'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>"+
                        escaped+"</prosody></voice></speak>"
                    val message="X-RequestId:${UUID.randomUUID().toString().replace("-","")}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${timestamp}Z\r\nPath:ssml\r\n\r\n$ssml"
                    if(!webSocket.send(config) || !webSocket.send(message)) fail(webSocket)
                }
                private fun fail(socket:WebSocket) { attempt.result.completeExceptionally(OnlineSpeechFailure());socket.cancel() }
                override fun onMessage(webSocket:WebSocket,text:String) {
                    if(attempt.result.isCompleted) return
                    val headers=text.substringBefore("\r\n\r\n").lineSequence().mapNotNull {
                        val colon=it.indexOf(':'); if(colon<0) null else it.substring(0,colon).lowercase(Locale.ROOT) to it.substring(colon+1)
                    }.toMap()
                    if(headers["path"]=="turn.end") {
                        val data=attempt.audio.toByteArray()
                        if(data.size>256 && validHeader(data)) attempt.result.complete(data) else fail(webSocket)
                        webSocket.close(1000,null)
                    }
                }
                override fun onMessage(webSocket:WebSocket,bytes:ByteString) {
                    if(attempt.result.isCompleted) return
                    try {
                        val payload=audioPayload(bytes.toByteArray())
                        check(attempt.audio.size()+payload.size<=2*1024*1024)
                        attempt.audio.write(payload)
                    } catch(e:Exception) { fail(webSocket) }
                }
                override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) {
                    val retry=response?.header("Retry-After")?.toLongOrNull()?.coerceIn(0,86400)?.times(1000)
                        ?: response?.header("Retry-After")?.let(::parseDate)?.minus(now()) ?: 1000
                    attempt.result.completeExceptionally(OnlineSpeechFailure(response?.code ?: 0,response?.header("Date"),retry))
                }
                override fun onClosed(webSocket:WebSocket,code:Int,reason:String) {
                    if(!attempt.result.isCompleted) attempt.result.completeExceptionally(OnlineSpeechFailure())
                }
                override fun onClosing(webSocket:WebSocket,code:Int,reason:String) { webSocket.close(code,null) }
            })
            attempt.socket.set(socket)
            if(attempt.result.isCancelled) socket.cancel()
            return attempt.result.await()
        } finally { active.compareAndSet(attempt,null); attempt.socket.get()?.cancel() }
    }
    override fun cancel() { active.get()?.cancel() }
    override fun close() { closed=true;cancel();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown() }

    companion object {
        internal fun validHeader(bytes:ByteArray) = bytes.size>=3 &&
            (String(bytes,0,3,Charsets.US_ASCII)=="ID3" || (bytes[0].toInt() and 255==255 && bytes[1].toInt() and 224==224))
        internal fun audioPayload(bytes:ByteArray):ByteArray {
            require(bytes.size>=2)
            val length=((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
            require(length>0 && length<=bytes.size-2)
            val headers=String(bytes,2,length,Charsets.US_ASCII).lineSequence().mapNotNull {
                val colon=it.indexOf(':');if(colon<0) null else it.substring(0,colon).lowercase(Locale.ROOT) to it.substring(colon+1)
            }.toMap()
            require(headers["path"]=="audio")
            val payload=bytes.copyOfRange(length+2,bytes.size)
            require(headers["content-type"]=="audio/mpeg" || (headers["content-type"]==null && payload.isEmpty()))
            return payload
        }
        internal fun escapeText(text:String):String = buildString {
            text.forEach { c -> append(when(c) {
                '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"
                in '\u0000'..'\u0008', '\u000B', '\u000C', in '\u000E'..'\u001F' -> " "
                else -> c.toString()
            }) }
        }
        private fun timestamp(ms:Long)=SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",Locale.US)
            .apply { timeZone=TimeZone.getTimeZone("UTC") }.format(Date(ms))
        private fun parseDate(value:String):Long?=runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z",Locale.US).apply { isLenient=false }.parse(value)?.time
        }.getOrNull()
    }
}
