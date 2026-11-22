package com.bolm.mudif.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Take 4, in-app app updater plus versioned releases.
 *
 * Fresh Mudif code. The shape (check a release feed, download with
 * progress, verify the signer fingerprint before installing) is the
 * idea taken from the DroidDesk reference; no DroidDesk code was
 * copied. The release feed is a small JSON file:
 *   {"versionName":"0.2.1","versionCode":3,
 *    "apkUrl":"https://example.com/mudif-0.2.1.apk",
 *    "apkSize":70502091,
 *    "sha256":"<hex of the APK file>",
 *    "notes":"short release notes"}
 * A release is newer only when its versionCode is higher than the
 * installed one. The APK is accepted only when all of these hold:
 * the package name matches this app, the downloaded size matches
 * apkSize, the file SHA-256 matches sha256 when the feed gives one,
 * and at least one signer fingerprint of the APK matches a signer
 * fingerprint of the installed app. A mismatch is reported with its
 * own code instead of launching the installer.
 *
 * Release process (versioned releases): bump versionCode and
 * versionName in app/build.gradle.kts, build the APK, publish the
 * APK plus the feed JSON above. Production gap, stated plainly: no
 * release keystore exists yet (the decision is still pending) and
 * no production feed exists yet, so the default feed URL is empty
 * and the updater reports "no source set" until an operator sets
 * one. The debug builds used on the rig are signed with the pinned
 * debug keystore (app/debug.keystore); a release keystore
 * fingerprint is configured by publishing releases signed with
 * that key, never by hardcoding a fingerprint that does not exist.
 * Never commit a keystore or its passwords.
 */
object MudifUpdater {
    private const val TAG = "MudifUpdater"
    private const val DIR = "updates"
    private const val PREFS = "mudif_updater"
    private const val KEY_FEED_URL = "feed_url"

    class UpdateException(val code: String, message: String) : Exception(message)

    data class ReleaseInfo(
        val versionName: String,
        val versionCode: Long,
        val apkUrl: String,
        val apkSize: Long,
        val sha256: String,
        val notes: String
    )

    sealed class CheckResult {
        data class Available(val release: ReleaseInfo) : CheckResult()
        object UpToDate : CheckResult()
    }

    fun currentVersion(context: Context): Pair<String, Long> {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return (info.versionName ?: "") to info.longVersionCode
    }

    fun feedUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FEED_URL, "") ?: ""

    fun saveFeedUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_FEED_URL, url.trim()).apply()
    }

    /** First 8 hex chars of the first installed signer fingerprint, for display. */
    fun installedFingerprintPrefix(context: Context): String {
        val prints = fingerprints(context.packageManager.getPackageInfo(context.packageName, signingFlags()))
        return prints.sorted().firstOrNull()?.take(8) ?: "?"
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Removes APKs left over from earlier update attempts. */
    fun cleanup(context: Context) {
        File(context.cacheDir, DIR).listFiles()?.forEach { it.delete() }
    }

    /**
     * Fetches the feed and reports whether a newer release exists.
     * Throws [UpdateException] with code "no_source", "network" or
     * "invalid" when the check itself cannot run.
     */
    fun check(context: Context, feed: String): CheckResult {
        if (feed.isBlank()) throw UpdateException("no_source", "No update source is set")
        val release = fetchRelease(feed)
        val (_, code) = currentVersion(context)
        return if (release.versionCode > code) CheckResult.Available(release)
        else CheckResult.UpToDate
    }

    /** Fetches and parses one feed JSON object. Follows up to 5 redirects. */
    fun fetchRelease(feedUrl: String): ReleaseInfo {
        var current = feedUrl
        var redirects = 0
        while (true) {
            val connection = openConnection(current)
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw UpdateException("network", "Redirect without location")
                    if (++redirects > 5) throw UpdateException("network", "Too many redirects")
                    current = URL(URL(current), location).toString()
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw UpdateException("network", "Server returned $code")
                }
                val body = connection.inputStream.bufferedReader().readText()
                return parseRelease(body)
            } catch (e: UpdateException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Update check failed", e)
                throw UpdateException("network", e.message ?: "Network error")
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun parseRelease(body: String): ReleaseInfo {
        val json = try {
            JSONObject(body)
        } catch (_: Exception) {
            throw UpdateException("invalid", "The release feed is not valid JSON")
        }
        val versionName = json.optString("versionName", "")
        val versionCode = json.optLong("versionCode", -1)
        val apkUrl = json.optString("apkUrl", "")
        if (versionName.isBlank() || versionCode <= 0 || apkUrl.isBlank()) {
            throw UpdateException("invalid", "The release feed is missing versionName, versionCode or apkUrl")
        }
        return ReleaseInfo(
            versionName = versionName,
            versionCode = versionCode,
            apkUrl = apkUrl,
            apkSize = json.optLong("apkSize", -1),
            sha256 = json.optString("sha256", "").lowercase(),
            notes = json.optString("notes", "")
        )
    }

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Mudif-Updater")
        }
    }

    /**
     * Streams [release] to the app cache and verifies size, file
     * hash and signer fingerprint. [onProgress] receives
     * (downloadedBytes, totalBytes). Returns the verified APK file.
     * A finished file with matching size and hash is reused, not
     * downloaded again; it is still verified before it is returned.
     */
    fun download(
        context: Context,
        release: ReleaseInfo,
        onProgress: (Long, Long) -> Unit
    ): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val apk = File(dir, "mudif-${release.versionCode}.apk")
        val part = File(dir, "${apk.name}.part")
        dir.listFiles()?.filter { it != apk && it != part }?.forEach { it.delete() }
        val reuse = apk.isFile &&
            (release.apkSize <= 0 || apk.length() == release.apkSize) &&
            (release.sha256.isBlank() || sha256Hex(apk) == release.sha256)
        if (!reuse) {
            part.delete()
            fetch(release.apkUrl, part, release.apkSize, onProgress)
            if (release.apkSize > 0 && part.length() != release.apkSize) {
                part.delete()
                throw UpdateException("size_mismatch", "Downloaded file is incomplete")
            }
            if (release.sha256.isNotBlank() && sha256Hex(part) != release.sha256) {
                part.delete()
                throw UpdateException("hash_mismatch", "Downloaded file hash does not match")
            }
            apk.delete()
            if (!part.renameTo(apk)) throw UpdateException("storage", "Could not save the update")
        }
        verify(context, apk)
        return apk
    }

    private fun fetch(url: String, target: File, expectedSize: Long, onProgress: (Long, Long) -> Unit) {
        var current = url
        var redirects = 0
        while (true) {
            val connection = openConnection(current)
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw UpdateException("network", "Redirect without location")
                    if (++redirects > 5) throw UpdateException("network", "Too many redirects")
                    current = URL(URL(current), location).toString()
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw UpdateException("network", "Server returned $code")
                }
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: expectedSize
                var done = 0L
                var lastReported = -1L
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total <= 0 || done - lastReported >= total / 100) {
                                lastReported = done
                                onProgress(done, total)
                            }
                        }
                    }
                }
                onProgress(done, total)
                return
            } catch (e: UpdateException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Update download failed", e)
                throw UpdateException("network", e.message ?: "Network error")
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Package name plus signer fingerprint, against the installed app. */
    fun verify(context: Context, apk: File) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, signingFlags())
            ?: throw UpdateException("invalid", "The downloaded file is not a valid app")
        if (archive.packageName != context.packageName) {
            throw UpdateException("invalid", "The downloaded app is not Mudif")
        }
        val installed = pm.getPackageInfo(context.packageName, signingFlags())
        if (fingerprints(archive).intersect(fingerprints(installed)).isEmpty()) {
            throw UpdateException(
                "signature_mismatch",
                "This release is signed with a different key than the installed app"
            )
        }
    }

    private fun signingFlags(): Int = PackageManager.GET_SIGNING_CERTIFICATES

    private fun fingerprints(info: PackageInfo): Set<String> {
        val signatures = info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        val sha = MessageDigest.getInstance("SHA-256")
        return signatures.map {
            sha.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
        }.toSet()
    }

    fun sha256Hex(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sha.update(buffer, 0, read)
            }
        }
        return sha.digest().joinToString("") { b -> "%02x".format(b) }
    }

    /** Opens the system installer for a verified APK in the app cache. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
