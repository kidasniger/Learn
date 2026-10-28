package com.kidasniger.learn.updater

data class UpdateInfo(
    val currentVersion: String,
    val version: String,
    val releaseName: String,
    val releaseNotes: String,
    val releaseUrl: String,
    val downloadUrl: String,
    val apkName: String,
    val apkSize: Long,
    val sha256: String?
)

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class UpdateAvailable(val info: UpdateInfo) : UpdateCheckState
    data class UpToDate(val currentVersion: String, val latestVersion: String) : UpdateCheckState
    data class Error(val message: String) : UpdateCheckState
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(
        val version: String,
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : DownloadState
    data class Downloaded(val apkPath: String, val version: String) : DownloadState
    data class AwaitingInstallPermission(val apkPath: String, val version: String) : DownloadState
    data object Cancelled : DownloadState
    data class Error(val message: String) : DownloadState
}

internal data class ReleaseAsset(
    val name: String,
    val size: Long,
    val downloadUrl: String,
    val contentType: String?,
    val digest: String?
)

internal data class GitHubRelease(
    val tagName: String,
    val name: String?,
    val body: String?,
    val htmlUrl: String,
    val assets: List<ReleaseAsset>,
    val checksumUrl: String?
)
