package com.zionhuang.innertube

import com.zionhuang.innertube.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRequestSmokeTest {
    @Test
    fun `primary and fallback clients return playable selected audio streams`() = runBlocking {
        withTimeout(PLAYBACK_TEST_TIMEOUT_MS) {
            HttpClient(OkHttp).use { httpClient ->
                for (videoId in VIDEO_IDS) {
                    for (client in DIRECT_STREAM_CLIENTS) {
                        val playerResponse = YouTube.player(videoId, client = client).getOrThrow()
                        val status = playerResponse.playabilityStatus
                        assertEquals(
                            "video=$videoId client=${client.clientName} reason=${status.reason}",
                            "OK",
                            status.status,
                        )

                        val selectedFormat = playerResponse.streamingData
                            ?.adaptiveFormats
                            .orEmpty()
                            .filter { it.isAudio }
                            .maxByOrNull {
                                it.bitrate + if (it.mimeType.startsWith("audio/webm")) 10240 else 0
                            }
                        assertTrue(
                            "video=$videoId client=${client.clientName} returned no audio format",
                            selectedFormat != null,
                        )

                        val streamUrl = selectedFormat?.url
                        assertTrue(
                            "video=$videoId client=${client.clientName} selected format has no direct URL",
                            !streamUrl.isNullOrBlank(),
                        )

                        val streamResponse = httpClient.get(streamUrl!!) {
                            headers {
                                append("Range", "bytes=0-1023")
                            }
                        }
                        assertTrue(
                            "video=$videoId client=${client.clientName} stream status=${streamResponse.status}",
                            streamResponse.status.isSuccess(),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `caption track fallback replaces rejected transcript endpoint`() = runBlocking {
        withTimeout(CAPTION_TEST_TIMEOUT_MS) {
            for (videoId in CAPTION_VIDEO_IDS) {
                val lyrics = YouTube.transcript(videoId).getOrThrow()
                assertTrue("video=$videoId returned an empty caption transcript", lyrics.isNotBlank())
            }
        }
    }

    companion object {
        private val DIRECT_STREAM_CLIENTS = listOf(
            YouTubeClient.ANDROID_VR_NO_AUTH,
            YouTubeClient.IOS,
        )

        private val VIDEO_IDS = listOf(
            "dQw4w9WgXcQ",
            "jF4KKOsoyDs",
            "NCC6lI0GGy0",
        )

        private val CAPTION_VIDEO_IDS = listOf(
            "dQw4w9WgXcQ",
            "NCC6lI0GGy0",
        )

        private const val PLAYBACK_TEST_TIMEOUT_MS = 180_000L
        private const val CAPTION_TEST_TIMEOUT_MS = 120_000L
    }
}
