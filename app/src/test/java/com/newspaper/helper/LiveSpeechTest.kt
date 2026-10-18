package com.newspaper.helper

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 真实协议抽查，与 MockWebServer 回归分开；音频可解码仍不等于人工听感合格。 */
class LiveSpeechTest {
    @Test fun yunyangReturnsRealMp3()=runBlocking {
        assumeTrue(System.getenv("DUBAO_LIVE_SPEECH")=="1")
        val engine=EdgeSpeechEngine()
        val output=File("build/speech-evidence/yunyang-kotlin.mp3").apply { parentFile!!.mkdirs() }
        try {
            withTimeout(60000) { engine.synthesize("推动高质量发展，要把改善民生作为重要着力点。既要看经济总量的增长，也要看群众生活的变化。",output) }
            assertTrue(output.length()>1000)
            assertTrue(EdgeSpeechEngine.validHeader(output.readBytes()))
        } finally { engine.close() }
    }
}
