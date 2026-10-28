package com.kidasniger.learn.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.kidasniger.learn.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

internal class AppUpdateManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val checkMutex = Mutex()
    private val downloadMutex = Mutex()

    private val _checkState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val checkState: StateFlow<UpdateCheckState> = _checkState.asStateFlow()
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var cancelRequested = false
    @Volatile private var currentUpdateInfo: UpdateInfo? = null

    init { cleanupTemporaryFiles() }

    suspend fun checkForUpdates(): UpdateCheckState = withContext(Dispatchers.IO) {
        if (!checkMutex.tryLock()) return@withContext _checkState.value
        _checkState.value = UpdateCheckState.Checking
        try {
            val (httpCode, body) = requestText(LATEST_RELEASE_URL, API_USER_AGENT, MAX_RELEASE_JSON_BYTES)
            if (httpCode !in 200..299) {
                val message = when (httpCode) {
                    404 -> "Aucune release publique n'est encore disponible."
                    403 -> "GitHub limite temporairement les requêtes. Réessayez plus tard."
                    else -> "Vérification impossible (HTTP $httpCode)."
                }
                return@withContext UpdateCheckState.Error(message).also { _checkState.value = it }
            }

            val release = GitHubReleaseParser.parse(body)
            val installedVersion = BuildConfig.VERSION_NAME
            val remoteVersion = release.tagName.removePrefix("v").removePrefix("V")
            if (SemanticVersion.parse(installedVersion) == null) {
                throw IllegalStateException("La version installée n'est pas au format X.Y.Z.")
            }

            if (!SemanticVersion.isNewer(remoteVersion, installedVersion)) {
                currentUpdateInfo = null
                val latestState = UpdateCheckState.UpToDate(installedVersion, remoteVersion)
                _checkState.value = latestState
                return@withContext latestState
            }

            val apk = release.assets.firstOrNull {
                it.name.endsWith(".apk", ignoreCase = true) &&
                    (it.name.contains("learn", ignoreCase = true) ||
                        release.assets.count { asset -> asset.name.endsWith(".apk", ignoreCase = true) } == 1)
            } ?: throw IOException("La release ne contient aucun APK Learn compatible.")

            var sha256 = apk.digest
            if (sha256 == null && release.checksumUrl != null) {
                val (checksumCode, checksumBody) = requestText(
                    release.checksumUrl, DOWNLOAD_USER_AGENT, MAX_CHECKSUM_BYTES
                )
                if (checksumCode == 200) {
                    sha256 = GitHubReleaseParser.parseChecksum(checksumBody)
                        ?: throw IOException("Le fichier SHA-256 publié est invalide.")
                }
            }

            val info = UpdateInfo(
                currentVersion = installedVersion,
                version = remoteVersion,
                releaseName = release.name ?: "Learn v$remoteVersion",
                releaseNotes = cleanReleaseNotes(release.body),
                releaseUrl = release.htmlUrl,
                downloadUrl = apk.downloadUrl,
                apkName = apk.name,
                apkSize = apk.size,
                sha256 = sha256
            )
            currentUpdateInfo = info

            val cached = findVerifiedCachedApk(info)
            if (cached != null) {
                _downloadState.value = DownloadState.Downloaded(cached.absolutePath, info.version)
            } else if (_downloadState.value !is DownloadState.Downloading) {
                _downloadState.value = DownloadState.Idle
            }
            UpdateCheckState.UpdateAvailable(info).also { _checkState.value = it }
        } catch (error: Exception) {
            UpdateCheckState.Error(userMessage(error)).also { _checkState.value = it }
        } finally {
            checkMutex.unlock()
        }
    }

    suspend fun checkAutomaticallyIfDue() {
        val now = System.currentTimeMillis()
        val last = preferences.getLong(KEY_LAST_AUTOMATIC_CHECK, 0L)
        if (now - last < AUTOMATIC_CHECK_INTERVAL_MS) return
        // Record failures too, so an offline device does not retry on every Activity resume.
        preferences.edit().putLong(KEY_LAST_AUTOMATIC_CHECK, now).apply()
        checkForUpdates()
    }

    suspend fun downloadUpdate(info: UpdateInfo) = withContext(Dispatchers.IO) {
        if (!downloadMutex.tryLock()) return@withContext
        cancelRequested = false
        var connection: HttpURLConnection? = null
        val directory = File(appContext.cacheDir, UPDATE_DIRECTORY)
        val safeVersion = info.version.replace(Regex("[^A-Za-z0-9.+-]"), "_")
        val destination = File(directory, "Learn-v$safeVersion.apk")
        val partial = File(directory, "Learn-v$safeVersion.apk.part")
        try {
            if (currentUpdateInfo?.version != info.version) {
                throw IOException("La version distante a changé. Vérifiez à nouveau les mises à jour.")
            }
            findVerifiedCachedApk(info)?.let { cached ->
                _downloadState.value = DownloadState.Downloaded(cached.absolutePath, info.version)
                return@withContext
            }
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Impossible de préparer le cache privé pour le téléchargement.")
            }
            partial.delete()
            _downloadState.value = DownloadState.Downloading(info.version, 0, 0L, info.apkSize)

            connection = openConnection(info.downloadUrl, DOWNLOAD_USER_AGENT)
            activeConnection = connection
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) throw IOException("Téléchargement impossible (HTTP $responseCode).")
            validateFinalDownloadHost(connection.url)
            val responseSize = connection.contentLengthLong
            if (responseSize > 0L && responseSize != info.apkSize) {
                throw IOException("La taille annoncée par le serveur ne correspond pas à la release.")
            }
            if (info.apkSize <= 0L || info.apkSize > MAX_APK_BYTES) {
                throw IOException("La taille publiée pour l'APK est invalide.")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            val total = info.apkSize
            connection.inputStream.use { raw ->
                BufferedInputStream(raw).use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelRequested) throw CancellationException("Téléchargement annulé.")
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            downloaded += count
                            if (downloaded > total || downloaded > MAX_APK_BYTES) {
                                throw IOException("Le fichier téléchargé dépasse la taille attendue.")
                            }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            _downloadState.value = DownloadState.Downloading(
                                version = info.version,
                                progressPercent = ((downloaded * 100L) / total).toInt().coerceIn(0, 100),
                                downloadedBytes = downloaded,
                                totalBytes = total
                            )
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }
            }
            if (cancelRequested) throw CancellationException("Téléchargement annulé.")
            if (downloaded != total || partial.length() != total) {
                throw IOException("Téléchargement incomplet : la taille finale ne correspond pas à la release.")
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
            if (info.sha256 != null && !MessageDigest.isEqual(
                    actualHash.toByteArray(Charsets.US_ASCII),
                    info.sha256.lowercase(Locale.ROOT).toByteArray(Charsets.US_ASCII)
                )
            ) {
                throw IOException("L'empreinte SHA-256 ne correspond pas. Le fichier a été rejeté.")
            }
            ApkIntegrityVerifier.verify(partial, info.apkSize, info.sha256)

            if (destination.exists() && !destination.delete()) {
                throw IOException("Impossible de remplacer l'ancien APK en cache.")
            }
            if (!partial.renameTo(destination)) throw IOException("Impossible de finaliser le fichier APK téléchargé.")
            _downloadState.value = DownloadState.Downloaded(destination.absolutePath, info.version)
        } catch (error: CancellationException) {
            partial.delete()
            _downloadState.value = DownloadState.Cancelled
        } catch (error: Exception) {
            partial.delete()
            _downloadState.value = if (cancelRequested) DownloadState.Cancelled else DownloadState.Error(userMessage(error))
        } finally {
            connection?.disconnect()
            activeConnection = null
            downloadMutex.unlock()
        }
    }

    fun cancelDownload() {
        cancelRequested = true
        activeConnection?.disconnect()
    }

    fun installDownloadedApk(apkPath: String, version: String) {
        val apk = File(apkPath)
        if (!apk.exists() || !apk.isFile) {
            _downloadState.value = DownloadState.Error("Le fichier APK n'est plus disponible. Téléchargez-le à nouveau.")
            return
        }
        try {
            val info = currentUpdateInfo?.takeIf { it.version == version }
            ApkIntegrityVerifier.verify(apk, info?.apkSize ?: 0L, info?.sha256)
            validatePackageAndSigningKey(apk, version)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !appContext.packageManager.canRequestPackageInstalls()
            ) {
                preferences.edit()
                    .putString(KEY_PENDING_INSTALL_PATH, apk.absolutePath)
                    .putString(KEY_PENDING_INSTALL_VERSION, version)
                    .putLong(KEY_PENDING_INSTALL_SIZE, info?.apkSize ?: apk.length())
                    .putString(KEY_PENDING_INSTALL_SHA256, info?.sha256)
                    .apply()
                _downloadState.value = DownloadState.AwaitingInstallPermission(apk.absolutePath, version)
                appContext.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${appContext.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                return
            }
            launchPackageInstaller(apk)
        } catch (error: Exception) {
            _downloadState.value = DownloadState.Error(userMessage(error))
        }
    }

    /** Called on Activity ON_RESUME after the user returns from Android Settings. */
    fun onAppResumed() {
        val path = preferences.getString(KEY_PENDING_INSTALL_PATH, null) ?: return
        val version = preferences.getString(KEY_PENDING_INSTALL_VERSION, "") ?: ""
        val apk = File(path)
        if (!apk.isFile) {
            clearPendingInstall()
            _downloadState.value = DownloadState.Error("Le fichier téléchargé a expiré. Téléchargez la mise à jour à nouveau.")
            return
        }
        val expectedSize = preferences.getLong(KEY_PENDING_INSTALL_SIZE, 0L)
        val expectedHash = preferences.getString(KEY_PENDING_INSTALL_SHA256, null)
        try {
            ApkIntegrityVerifier.verify(apk, expectedSize, expectedHash)
            validatePackageAndSigningKey(apk, version)
        } catch (error: Exception) {
            clearPendingInstall()
            _downloadState.value = DownloadState.Error(userMessage(error))
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || appContext.packageManager.canRequestPackageInstalls()) {
            clearPendingInstall()
            _downloadState.value = DownloadState.Downloaded(apk.absolutePath, version)
            launchPackageInstaller(apk)
        } else {
            clearPendingInstall()
            _downloadState.value = DownloadState.Downloaded(apk.absolutePath, version)
        }
    }

    private fun validatePackageAndSigningKey(apk: File, expectedVersion: String) {
        val pm = appContext.packageManager
        val flags = signingFlags()
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw IOException("Android ne reconnaît pas cet APK.")
        if (archive.packageName != appContext.packageName) {
            throw IOException("Cet APK n'appartient pas à Learn et ne sera pas installé.")
        }
        val apkVersion = archive.versionName.orEmpty()
        if (SemanticVersion.parse(apkVersion) == null || SemanticVersion.compare(apkVersion, expectedVersion) != 0) {
            throw IOException("La version contenue dans l'APK ne correspond pas à la release publiée.")
        }
        val installed = try {
            pm.getPackageInfo(appContext.packageName, flags)
        } catch (error: Exception) {
            throw IOException("Impossible de vérifier la signature de l'application installée.", error)
        }
        if (packageVersionCode(archive) <= packageVersionCode(installed)) {
            throw IOException("La version téléchargée n'est pas plus récente que celle installée.")
        }
        val installedCertificates = signingCertificateDigests(installed)
        val newCertificates = signingCertificateDigests(archive)
        if (installedCertificates.isEmpty() || newCertificates.isEmpty() || installedCertificates != newCertificates) {
            throw IOException(
                "La clé de signature de cet APK est différente de celle installée. " +
                    "Android refuserait la mise à jour ; sauvegardez vos données avant toute réinstallation manuelle."
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun packageVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signingCertificateDigests(info: PackageInfo): Set<String> {
        val signatures = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else info.signatures)
            ?: return emptySet()
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        }.toSet()
    }

    private fun launchPackageInstaller(apk: File) {
        try {
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        } catch (_: Exception) {
            _downloadState.value = DownloadState.Error(
                "Android n'a pas pu ouvrir l'installateur. Vérifiez les autorisations puis réessayez."
            )
        }
    }

    private fun findVerifiedCachedApk(info: UpdateInfo): File? {
        val safeVersion = info.version.replace(Regex("[^A-Za-z0-9.+-]"), "_")
        val file = File(File(appContext.cacheDir, UPDATE_DIRECTORY), "Learn-v$safeVersion.apk")
        if (!file.isFile) return null
        return try {
            ApkIntegrityVerifier.verify(file, info.apkSize, info.sha256)
            file
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun cleanupTemporaryFiles() {
        val directory = File(appContext.cacheDir, UPDATE_DIRECTORY)
        val cutoff = System.currentTimeMillis() - CACHE_TTL_MS
        val pendingPath = preferences.getString(KEY_PENDING_INSTALL_PATH, null)
        directory.listFiles()?.forEach { file ->
            if (file.absolutePath == pendingPath) return@forEach
            if (file.name.endsWith(".part") || file.lastModified() < cutoff) file.delete()
        }
    }

    private fun clearPendingInstall() {
        preferences.edit()
            .remove(KEY_PENDING_INSTALL_PATH)
            .remove(KEY_PENDING_INSTALL_VERSION)
            .remove(KEY_PENDING_INSTALL_SIZE)
            .remove(KEY_PENDING_INSTALL_SHA256)
            .apply()
    }

    private fun requestText(urlValue: String, userAgent: String, maxBytes: Int): Pair<Int, String> {
        val connection = openConnection(urlValue, userAgent)
        return try {
            val code = connection.responseCode
            validateFinalDownloadHost(connection.url)
            if (code !in 200..299) return code to ""
            val body = connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw IOException("La réponse du serveur est anormalement volumineuse.")
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            code to body
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(urlValue: String, userAgent: String): HttpURLConnection {
        val url = URL(urlValue)
        if (!url.protocol.equals("https", ignoreCase = true) ||
            !(url.host.equals("api.github.com", true) || url.host.equals("github.com", true))
        ) throw IOException("L'URL de mise à jour n'est pas une adresse HTTPS GitHub autorisée.")
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            setRequestProperty("User-Agent", userAgent)
        }
    }

    private fun validateFinalDownloadHost(url: URL) {
        val host = url.host.lowercase(Locale.ROOT)
        val allowed = host == "api.github.com" || host == "github.com" || host.endsWith(".githubusercontent.com")
        if (!url.protocol.equals("https", true) || !allowed) {
            throw IOException("Le serveur de téléchargement a redirigé vers une destination non autorisée.")
        }
    }

    private fun cleanReleaseNotes(raw: String?): String {
        if (raw.isNullOrBlank()) return "Améliorations et corrections de stabilité."
        val lines = raw.lines()
            .map { it.trim().replace(Regex("^#{1,6}\s*"), "").replace(Regex("""^[-*•]\s*"""), "• ") }
            .filter { it.isNotBlank() && !it.startsWith("<!--") }
        return lines.take(40).joinToString("\n").take(5000).ifBlank {
            "Améliorations et corrections de stabilité."
        }
    }

    private fun userMessage(error: Throwable): String =
        error.message?.take(240)?.ifBlank { null } ?: "Une erreur est survenue. Vérifiez la connexion et réessayez."

    companion object {
        private const val GITHUB_OWNER = "kidasniger"
        private const val GITHUB_REPO = "Learn"
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
        private const val API_USER_AGENT = "Learn-Android-Updater"
        private const val DOWNLOAD_USER_AGENT = "Learn-Android-Updater"
        private const val UPDATE_DIRECTORY = "updates"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val PREFERENCES_NAME = "learn_update_preferences"
        private const val KEY_LAST_AUTOMATIC_CHECK = "last_automatic_check"
        private const val KEY_PENDING_INSTALL_PATH = "pending_install_path"
        private const val KEY_PENDING_INSTALL_VERSION = "pending_install_version"
        private const val KEY_PENDING_INSTALL_SIZE = "pending_install_size"
        private const val KEY_PENDING_INSTALL_SHA256 = "pending_install_sha256"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_RELEASE_JSON_BYTES = 2 * 1024 * 1024
        private const val MAX_CHECKSUM_BYTES = 4 * 1024
        private const val MAX_APK_BYTES = 512L * 1024L * 1024L
        private const val AUTOMATIC_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
        private const val CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
