package com.kidasniger.learn.updater

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val manager = AppUpdateManager(application)
    val checkState: StateFlow<UpdateCheckState> = manager.checkState
    val downloadState: StateFlow<DownloadState> = manager.downloadState

    private val preferences = application.getSharedPreferences("learn_update_preferences", android.content.Context.MODE_PRIVATE)
    private val _dismissedVersion = MutableStateFlow(preferences.getString(KEY_DISMISSED_VERSION, null))
    val dismissedVersion: StateFlow<String?> = _dismissedVersion

    fun checkManually() {
        viewModelScope.launch { manager.checkForUpdates() }
    }

    fun checkAutomaticallyIfDue() {
        viewModelScope.launch { manager.checkAutomaticallyIfDue() }
    }

    fun download(info: UpdateInfo) {
        viewModelScope.launch { manager.downloadUpdate(info) }
    }

    fun cancelDownload() = manager.cancelDownload()

    fun install(apkPath: String, version: String) = manager.installDownloadedApk(apkPath, version)

    fun onAppResumed() = manager.onAppResumed()

    fun dismiss(version: String) {
        preferences.edit().putString(KEY_DISMISSED_VERSION, version).apply()
        _dismissedVersion.value = version
    }

    companion object {
        private const val KEY_DISMISSED_VERSION = "dismissed_version"
    }
}
