package com.airpods.app.update

import com.airpods.app.BuildConfig
import com.airpods.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val API_URL =
        "https://api.github.com/repos/braianj/air_pods-app/releases/tags/latest"
    private const val COMMITS_URL =
        "https://api.github.com/repos/braianj/air_pods-app/commits?per_page=15"
    private const val TAG = "UpdCheck"

    data class Commit(val sha: String, val message: String) {
        val shortSha: String get() = sha.take(7)
        val firstLine: String get() = message.substringBefore('\n')
    }

    /** Fetches the most recent commits on main for the in-app "Info" tab. */
    suspend fun recentCommits(): List<Commit> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(COMMITS_URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }
            if (conn.responseCode != 200) return@withContext emptyList()
            val body = conn.inputStream.bufferedReader().readText()
            // Quick & dirty JSON-ish parse — avoids pulling in a JSON dep.
            val shaRegex = Regex("\"sha\"\\s*:\\s*\"([a-f0-9]{40})\"")
            val msgRegex = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            val shas = shaRegex.findAll(body).map { it.groupValues[1] }.toList()
            val msgs = msgRegex.findAll(body).map {
                it.groupValues[1]
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\/", "/")
                    .replace("\\\\", "\\")
            }.toList()
            shas.zip(msgs).map { (s, m) -> Commit(s, m) }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "recentCommits failed", t)
            emptyList()
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    data class UpdateInfo(
        val installedSha: String,
        val latestSha: String
    ) {
        val installedShort: String get() = installedSha.take(7)
        val latestShort: String get() = latestSha.take(7)
    }

    /**
     * Returns non-null when the release on GitHub points at a different
     * commit than the one this APK was built from, meaning the user can
     * upgrade. Returns null when up-to-date, when running a local dev
     * build, or when the request fails.
     */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val installed = BuildConfig.GIT_SHA
        if (installed.isBlank() || installed == "dev") {
            AppLogger.i(TAG, "skipping check — local dev build (sha=$installed)")
            return@withContext null
        }
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }
            val code = conn.responseCode
            if (code != 200) {
                AppLogger.w(TAG, "API responded $code")
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader().readText()
            val latest = Regex("from commit ([a-f0-9]{7,40})")
                .find(body)?.groupValues?.getOrNull(1)
            if (latest.isNullOrBlank()) {
                AppLogger.w(TAG, "couldn't parse commit from release body")
                return@withContext null
            }
            AppLogger.i(TAG, "installed=$installed latest=$latest")
            if (latest.startsWith(installed) || installed.startsWith(latest)) {
                AppLogger.i(TAG, "up to date")
                null
            } else {
                UpdateInfo(installed, latest)
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "check failed", t)
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
