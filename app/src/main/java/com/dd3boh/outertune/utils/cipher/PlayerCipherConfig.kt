/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils.cipher

import android.os.SystemClock
import android.util.Log
import com.dd3boh.outertune.App
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Signature deobfuscation config for one player.js, looked up by its hash and used by [CipherWebView].
 *
 * @property sigFuncName name of the deobfuscation function
 * @property sigConstantArgs constants placed before the signature, so the call is
 *   `sigFuncName(sigConstantArgs..., sig)`
 * @property nClass the player's URL builder class used to apply the n-transform to the `n`
 *   throttling parameter
 * @property signatureTimestamp timestamp that must be sent with player requests deciphered by
 *   this exact player; mixing timestamps across YouTube A/B player variants produces CDN 403s
 */
data class PlayerCipherConfig(
    val sigFuncName: String,
    val sigConstantArgs: List<Int>,
    val nClass: String,
    val signatureTimestamp: Int,
)

/**
 * Provides player.js cipher configs keyed by the 8-hex player hash (aliases included).
 *
 * The data is the bundled `player_configs.json` from ZemerTeam/zemer-cipher
 * (https://github.com/ZemerTeam/zemer-cipher, GPL-3.0), whose upstream validates each entry
 * against the live CDN before shipping it. A validated remote copy is fetched when an unknown
 * player is encountered and cached as a last-known-good overlay, allowing installed builds to
 * recover from YouTube player rotations without waiting for a new APK.
 */
object PlayerCipherConfigStore {

    private const val TAG = "PlayerCipherConfig"
    private const val ASSET_NAME = "player_configs.json"
    private const val CACHE_NAME = "player_configs_remote.json"
    private const val REMOTE_URL =
        "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
    private const val REFRESH_COOLDOWN_MS = 5 * 60 * 1000L

    private val hashRegex = Regex("^[a-f0-9]{8}$")
    private val signatureRegex =
        Regex("""^([A-Za-z0-9${'$'}_]{1,8})\((\d+),(\d+),INPUT\)$""")
    private val nClassRegex = Regex("""^[A-Za-z0-9${'$'}_]{1,8}$""")

    private val refreshMutex = Mutex()
    private var lastRefreshAttemptMs = 0L

    @Volatile
    private var configs: Map<String, PlayerCipherConfig>? = null

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .proxy(YouTube.proxy)
            .build()
    }

    suspend fun get(playerHash: String?): PlayerCipherConfig? {
        if (playerHash == null) return null
        currentConfigs()[playerHash]?.let { return it }

        return refreshMutex.withLock {
            // Another playback request may have refreshed the table while this one waited.
            currentConfigs()[playerHash]?.let { return@withLock it }

            val now = SystemClock.elapsedRealtime()
            if (lastRefreshAttemptMs != 0L &&
                now - lastRefreshAttemptMs in 0 until REFRESH_COOLDOWN_MS
            ) {
                Log.d(TAG, "Remote refresh skipped (cooldown); player $playerHash still unknown")
                return@withLock null
            }
            lastRefreshAttemptMs = now

            val remote = fetchRemote() ?: return@withLock null
            val merged = currentConfigs() + remote.configs
            configs = merged
            persistRemote(remote.body)
            Log.i(TAG, "Applied remote cipher configs (${remote.configs.size} hashes, merged=${merged.size})")
            merged[playerHash]
        }
    }

    /** All known player hashes, aliases included. For diagnostics only. */
    fun knownHashes(): Set<String> = currentConfigs().keys

    private fun currentConfigs(): Map<String, PlayerCipherConfig> {
        configs?.let { return it }
        return synchronized(this) {
            configs?.let { return@synchronized it }
            val bundled = loadSource("bundled asset") {
                App.instance.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            }.orEmpty()
            val cached = loadSource("cached remote copy") {
                cacheFile().takeIf(File::exists)?.readText()
            }.orEmpty()
            (bundled + cached).also {
                configs = it
                Log.d(TAG, "Loaded ${it.size} player cipher configs")
            }
        }
    }

    private data class RemoteConfig(
        val body: String,
        val configs: Map<String, PlayerCipherConfig>,
    )

    private suspend fun fetchRemote(): RemoteConfig? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(REMOTE_URL)
                .header("User-Agent", YouTubeClient.USER_AGENT_WEB)
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Remote config fetch failed: HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body?.string()
                if (body.isNullOrEmpty()) {
                    Log.w(TAG, "Remote config fetch returned an empty body")
                    return@withContext null
                }
                parse(body)?.let { RemoteConfig(body, it) }.also {
                    if (it == null) Log.w(TAG, "Remote cipher configs failed validation")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Remote config fetch failed; keeping last-known-good configs", e)
            null
        }
    }

    private fun loadSource(
        label: String,
        read: () -> String?,
    ): Map<String, PlayerCipherConfig>? = try {
        val text = read()
        if (text == null) {
            null
        } else {
            parse(text).also { if (it == null) Log.w(TAG, "Could not validate $label") }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not load $label", e)
        null
    }

    /**
     * Validation boundary for remotely supplied values: only fixed-shape identifiers and integer
     * arguments are accepted because these fields are interpolated into player JavaScript.
     */
    private fun parse(text: String): Map<String, PlayerCipherConfig>? = try {
        val root = JSONObject(text)
        if (root.optInt("schemaVersion", -1) != 1) return null
        val players = root.optJSONObject("players") ?: return null
        val result = mutableMapOf<String, PlayerCipherConfig>()
        players.keys().forEach { hash ->
            if (!hashRegex.matches(hash)) return null
            val entry = players.getJSONObject(hash)
            val config = parseEntry(entry) ?: return null
            if (result.put(hash, config) != null) return null
            entry.optJSONArray("aliases")?.let { aliases ->
                for (i in 0 until aliases.length()) {
                    val alias = aliases.optString(i)
                    if (!hashRegex.matches(alias) || result.put(alias, config) != null) return null
                }
            }
        }
        result
    } catch (e: Exception) {
        Log.w(TAG, "Cipher config JSON rejected", e)
        null
    }

    // sig is locked to a `name(int,int,INPUT)` call so remote config cannot inject JavaScript.
    private fun parseEntry(entry: JSONObject): PlayerCipherConfig? {
        val sig = entry.optString("sig")
        val nClass = entry.optString("nClass")
        val match = signatureRegex.matchEntire(sig) ?: return null
        val signatureTimestamp = entry.optInt("sts", -1)
        if (!nClassRegex.matches(nClass) || signatureTimestamp <= 0) return null
        return PlayerCipherConfig(
            sigFuncName = match.groupValues[1],
            sigConstantArgs = listOf(
                match.groupValues[2].toIntOrNull() ?: return null,
                match.groupValues[3].toIntOrNull() ?: return null,
            ),
            nClass = nClass,
            signatureTimestamp = signatureTimestamp,
        )
    }

    private fun cacheFile(): File =
        File(App.instance.filesDir, "cipher_cache").apply { mkdirs() }
            .resolve(CACHE_NAME)

    private fun persistRemote(body: String) {
        try {
            val target = cacheFile()
            val temporary = target.resolveSibling("${target.name}.tmp")
            temporary.writeText(body)
            if (!temporary.renameTo(target)) {
                target.delete()
                if (!temporary.renameTo(target)) {
                    target.writeText(body)
                    temporary.delete()
                }
            }
        } catch (e: Exception) {
            // The validated in-memory copy is already active; a cache failure must not undo it.
            Log.w(TAG, "Could not persist remote cipher configs", e)
        }
    }
}
