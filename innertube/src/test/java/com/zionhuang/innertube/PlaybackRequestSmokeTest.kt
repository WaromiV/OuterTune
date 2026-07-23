package com.zionhuang.innertube

import com.zionhuang.innertube.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRequestSmokeTest {
    @Test
    fun `at least one direct client returns a playable audio stream per video`() = runBlocking {
        withTimeout(PLAYBACK_TEST_TIMEOUT_MS) {
            HttpClient(OkHttp).use { httpClient ->
                val failures = mutableListOf<String>()

                for (videoId in VIDEO_IDS) {
                    val playableClients = mutableListOf<String>()

                    for (client in PLAYER_CLIENTS) {
                        val label = "video=$videoId client=${client.clientName}"
                        val playerResult = YouTube.player(videoId, client = client)
                        val playerResponse = playerResult.getOrNull()
                        if (playerResponse == null) {
                            val error = playerResult.exceptionOrNull()
                            println("$label requestError=${error?.javaClass?.simpleName}: ${error?.message}")
                            continue
                        }

                        val status = playerResponse.playabilityStatus
                        val audioFormats = playerResponse.streamingData
                            ?.adaptiveFormats
                            .orEmpty()
                            .filter { it.isAudio }
                        val directFormats = audioFormats.count { !it.url.isNullOrBlank() }
                        val cipherFormats = audioFormats.count { !it.signatureCipher.isNullOrBlank() }
                        println(
                            "$label status=${status.status} reason=${status.reason} " +
                                "audioFormats=${audioFormats.size} directFormats=$directFormats " +
                                "cipherFormats=$cipherFormats",
                        )

                        if (status.status != "OK") continue

                        val selectedFormat = audioFormats
                            .filter { !it.url.isNullOrBlank() }
                            .maxByOrNull {
                                it.bitrate + if (it.mimeType.startsWith("audio/webm")) 10240 else 0
                            }
                        if (selectedFormat == null) continue

                        val streamResponse = httpClient.get(selectedFormat.url!!) {
                            headers {
                                append("Range", "bytes=0-1023")
                            }
                        }
                        println(
                            "$label selectedItag=${selectedFormat.itag} " +
                                "streamStatus=${streamResponse.status.value}",
                        )
                        if (streamResponse.status.isSuccess()) playableClients += client.clientName
                    }

                    println("video=$videoId playableClients=${playableClients.joinToString().ifEmpty { "none" }}")
                    if (playableClients.isEmpty()) {
                        failures += "video=$videoId had no directly playable client"
                    }
                }

                assertTrue(failures.joinToString(separator = "\n"), failures.isEmpty())
            }
        }
    }

    @Test
    fun `caption track fallback replaces rejected transcript endpoint`() = runBlocking {
        withTimeout(CAPTION_TEST_TIMEOUT_MS) {
            val failures = mutableListOf<String>()

            for (videoId in CAPTION_VIDEO_IDS) {
                val transcriptResult = YouTube.transcript(videoId)
                val lyrics = transcriptResult.getOrNull()
                if (lyrics == null) {
                    val error = transcriptResult.exceptionOrNull()
                    println(
                        "caption video=$videoId error=${error?.javaClass?.simpleName}: " +
                            error?.message,
                    )
                    failures += "video=$videoId caption request failed"
                    continue
                }

                println("caption video=$videoId characters=${lyrics.length} lines=${lyrics.lineSequence().count()}")
                if (lyrics.isBlank()) failures += "video=$videoId returned an empty caption transcript"
            }

            assertTrue(failures.joinToString(separator = "\n"), failures.isEmpty())
        }
    }

    companion object {
        private val PLAYER_CLIENTS = listOf(
            YouTubeClient.ANDROID_VR_NO_AUTH,
            YouTubeClient.WEB_REMIX,
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
