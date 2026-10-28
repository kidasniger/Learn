package com.kidasniger.learn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kidasniger.learn.updater.DownloadState
import com.kidasniger.learn.updater.UpdateInfo
import java.util.Locale

@Composable
fun AppUpdateDialog(
    info: UpdateInfo,
    downloadState: DownloadState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: (String, String) -> Unit
) {
    val downloading = downloadState is DownloadState.Downloading && downloadState.version == info.version
    val downloaded = downloadState is DownloadState.Downloaded && downloadState.version == info.version
    val waitingPermission = downloadState is DownloadState.AwaitingInstallPermission && downloadState.version == info.version

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        icon = { androidx.compose.material3.Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Mise à jour disponible") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Learn v${info.currentVersion} → v${info.version}", fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
                Text("Taille du téléchargement : ${formatBytes(info.apkSize)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (info.releaseNotes.isNotBlank()) {
                    HorizontalDivider()
                    Text("Nouveautés", fontWeight = FontWeight.SemiBold)
                    Text(info.releaseNotes, style = MaterialTheme.typography.bodyMedium)
                }
                when {
                    downloading -> {
                        val state = downloadState as DownloadState.Downloading
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Téléchargement en cours…", style = MaterialTheme.typography.bodySmall)
                            Text("${state.progressPercent}%", fontWeight = FontWeight.Bold)
                        }
                        LinearProgressIndicator(progress = state.progressPercent / 100f, modifier = Modifier.fillMaxWidth())
                        Text("${formatBytes(state.downloadedBytes)} sur ${formatBytes(state.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    downloaded -> Text("Le fichier a été téléchargé et vérifié. L'installation sera confirmée par Android.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    waitingPermission -> Text("Autorisez Learn à installer des applications dans les réglages Android. Au retour, l'installateur sera proposé.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                    downloadState is DownloadState.Error -> Text(downloadState.message, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    downloadState is DownloadState.Cancelled -> Text("Téléchargement annulé. Vous pouvez réessayer.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            when {
                downloading -> OutlinedButton(onClick = onCancelDownload) { Text("Annuler") }
                downloaded -> Button(onClick = { onInstall((downloadState as DownloadState.Downloaded).apkPath, info.version) }) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Installer maintenant")
                }
                waitingPermission -> Button(onClick = { onInstall((downloadState as DownloadState.AwaitingInstallPermission).apkPath, info.version) }) {
                    Text("Continuer")
                }
                else -> Button(onClick = onDownload) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (downloadState is DownloadState.Error || downloadState is DownloadState.Cancelled) "Réessayer" else "Télécharger")
                }
            }
        },
        dismissButton = {
            if (!downloading) OutlinedButton(onClick = onDismiss) { Text("Plus tard") }
        }
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes o"
    if (bytes < 1024L * 1024L) return String.format(Locale.getDefault(), "%.0f Ko", bytes / 1024.0)
    return String.format(Locale.getDefault(), "%.1f Mo", bytes / (1024.0 * 1024.0))
}
