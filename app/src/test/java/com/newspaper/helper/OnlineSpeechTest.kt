package com.newspaper.helper

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

class OnlineSpeechTest {
    private val mockSockets=CopyOnWriteArrayList<WebSocket>()
    private open inner class ServerListener:WebSocketListener() {
        override fun onOpen(socket:WebSocket,response:Response) { mockSockets+=socket }
        override fun onClosing(socket:WebSocket,code:Int,reason:String) { socket.close(code,reason) }
    }
    private suspend fun withMockServer(block:suspend (okhttp3.mockwebserver.MockWebServer)->Unit) {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            try { block(server) }
            finally {
                // 测试端显式关闭升级后的连接，避免 MockWebServer 关闭队列与客户端取消竞态。
                mockSockets.forEach { it.cancel() };mockSockets.clear()
            }
        }
    }
    private fun output()=File(Files.createTempDirectory("speech-test").toFile(),"audio.mp3")
    private fun engine(server:okhttp3.mockwebserver.MockWebServer)=EdgeSpeechEngine(endpoint=server.url("/speech"),retryDelayMs=0,attemptTimeoutMs=3000)
    private fun frame(audio:ByteArray):okio.ByteString {
        val headers="Path:audio\r\nContent-Type:audio/mpeg\r\n".toByteArray()
        return ByteBuffer.allocate(2+headers.size+audio.size).putShort(headers.size.toShort()).put(headers).put(audio).array().toByteString()
    }
    private val audio="ID3".toByteArray()+ByteArray(600) { (it%255).toByte() }
    private fun success()=MockResponse().withWebSocketUpgrade(object:ServerListener() {
        override fun onMessage(socket:WebSocket,text:String) {
            if(text.contains("Path:ssml")) { socket.send(frame(audio));socket.send("Path:turn.end\r\n\r\n") }
        }
    })
    @Test fun v2TokensMatchEightReferenceVectors() {
        val text=javaClass.getResource("/speech/edge-token-vectors.json")!!.readText()
        val vectors=JSONArray(text)
        for(i in 0 until vectors.length()) {
            val item=vectors.getJSONObject(i)
            assertEquals(item.getString("token"),EdgeToken.generate(item.getLong("unixMillis")))
        }
    }
    @Test fun protocolEscapesTextAndRejectsMalformedBinaryHeaders() {
        assertEquals("A &amp; B &lt;tag&gt; ",EdgeSpeechEngine.escapeText("A & B <tag>\u0001"))
        assertArrayEquals(audio,EdgeSpeechEngine.audioPayload(frame(audio).toByteArray()))
        for(bytes in listOf(byteArrayOf(),byteArrayOf(0,100,1),byteArrayOf(0,0))) {
            assertTrue(runCatching { EdgeSpeechEngine.audioPayload(bytes) }.isFailure)
        }
        val bad="Path:audio\r\nContent-Type:text/html\r\n".toByteArray()
        assertTrue(runCatching { EdgeSpeechEngine.audioPayload(ByteBuffer.allocate(2+bad.size+1).putShort(bad.size.toShort()).put(bad).put(1).array()) }.isFailure)
    }
    @Test fun handshakeAndAudioAreDeliveredWithoutReadingUserCredentials()=runBlocking {
        withMockServer { server ->
            server.enqueue(success())
            val engine=engine(server);val file=output()
            try {
                withTimeout(5000) { engine.synthesize("新闻 & <正文>。",file) }
                assertArrayEquals(audio,file.readBytes())
                val request=server.takeRequest(2,TimeUnit.SECONDS)!!
                assertEquals(EdgeToken.VERSION,request.requestUrl!!.queryParameter("Sec-MS-GEC-Version"))
                assertEquals(81,request.requestUrl!!.queryParameter("Sec-MS-GEC")!!.length)
            } finally { engine.close();file.parentFile!!.deleteRecursively() }
        }
    }
    @Test fun transientServerFailureRetriesOnce()=runBlocking {
        withMockServer { server ->
            server.enqueue(MockResponse().setResponseCode(503));server.enqueue(success())
            val engine=engine(server);val file=output()
            try { engine.synthesize("重试。",file);assertTrue(file.isFile);assertEquals(2,server.requestCount) }
            finally { engine.close();file.parentFile!!.deleteRecursively() }
        }
    }
    @Test fun rateLimitWithLongRetryAfterDoesNotHammerService()=runBlocking {
        withMockServer { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","60"))
            val engine=engine(server);val file=output()
            try {
                assertTrue(runCatching { engine.synthesize("限流。",file) }.exceptionOrNull() is OnlineSpeechFailure)
                assertEquals(1,server.requestCount);assertFalse(file.exists())
            } finally { engine.close();file.parentFile!!.deleteRecursively() }
        }
    }
    @Test fun emptyAudioFailsWithoutPublishingAFile()=runBlocking {
        withMockServer { server ->
            repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(object:ServerListener() {
                override fun onMessage(socket:WebSocket,text:String) { if(text.contains("Path:ssml")) socket.send("Path:turn.end\r\n\r\n") }
            })) }
            val engine=engine(server);val file=output()
            try { assertTrue(runCatching { engine.synthesize("空音频。",file) }.isFailure);assertFalse(file.exists());assertEquals(2,server.requestCount) }
            finally { engine.close();file.parentFile!!.deleteRecursively() }
        }
    }
    @Test fun cancellationClosesOldRequestAndNextTextStillSynthesizes()=runBlocking {
        withMockServer { server ->
            val started=CompletableDeferred<Unit>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object:ServerListener() {
                override fun onMessage(socket:WebSocket,text:String) { if(text.contains("Path:ssml")) started.complete(Unit) }
            }));server.enqueue(success())
            val engine=engine(server);val first=output();val second=output()
            try {
                val job=launch { engine.synthesize("取消旧稿。",first) }
                withTimeout(5000) { started.await() };job.cancelAndJoin()
                withTimeout(5000) { engine.synthesize("新的稿件。",second) }
                assertFalse(first.exists());assertArrayEquals(audio,second.readBytes())
            } finally { engine.close();first.parentFile!!.deleteRecursively();second.parentFile!!.deleteRecursively() }
        }
    }
}
