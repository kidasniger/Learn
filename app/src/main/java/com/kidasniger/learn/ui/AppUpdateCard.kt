package com.kidasniger.learn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kidasniger.learn.updater.AppUpdateViewModel
import com.kidasniger.learn.updater.DownloadState
import com.kidasniger.learn.updater.UpdateCheckState
import com.kidasniger.learn.updater.UpdateInfo
import java.util.Locale

@Composable
fun AppUpdateCard(viewModel: AppUpdateViewModel, modifier: Modifier = Modifier) {
    val check by viewModel.checkState.collectAsState()
    val download by viewModel.downloadState.collectAsState()
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Mise à jour de Learn", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Version installée : ${com.kidasniger.learn.BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            when (val state = check) {
                UpdateCheckState.Idle -> {
                    Text("Vérifiez si une nouvelle version est publiée.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = viewModel::checkManually, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Vérifier les mises à jour")
                    }
                }
                UpdateCheckState.Checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
                        Text("Vérification de la dernière release publique…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is UpdateCheckState.UpToDate -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Application à jour", fontWeight = FontWeight.SemiBold)
                            Text("Dernière release : v${state.latestVersion}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    OutlinedButton(onClick = viewModel::checkManually, modifier = Modifier.fillMaxWidth()) {
                        Text("Rechercher à nouveau")
                    }
                }
                is UpdateCheckState.Error -> {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(state.message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(onClick = viewModel::checkManually, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Réessayer")
                    }
                }
                is UpdateCheckState.UpdateAvailable -> {
                    val info = state.info
                    Text("Nouvelle version : v${info.version}", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("Taille : ${formatBytes(info.apkSize)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (info.releaseNotes.isNotBlank()) {
                        Text("Nouveautés", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Text(info.releaseNotes.take(1200), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    val stateForInfo = when (download) {
                        is DownloadState.Downloading -> (download as DownloadState.Downloading).takeIf { it.version == info.version }
                        else -> null
                    }
                    when {
                        stateForInfo != null -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Téléchargement…", style = MaterialTheme.typography.bodySmall)
                                Text("${stateForInfo.progressPercent}%", fontWeight = FontWeight.Bold)
                            }
                            LinearProgressIndicator(progress = stateForInfo.progressPercent / 100f, modifier = Modifier.fillMaxWidth())
                            Text("${formatBytes(stateForInfo.downloadedBytes)} / ${formatBytes(stateForInfo.totalBytes)}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = viewModel::cancelDownload, modifier = Modifier.fillMaxWidth()) {
                                Text("Annuler le téléchargement")
                            }
                        }
                        download is DownloadState.Downloaded && (download as DownloadState.Downloaded).version == info.version -> {
                            Button(onClick = { viewModel.install((download as DownloadState.Downloaded).apkPath, info.version) },
                                modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.SystemUpdate, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Installer la mise à jour")
                            }
                        }
                        download is DownloadState.AwaitingInstallPermission &&
                            (download as DownloadState.AwaitingInstallPermission).version == info.version -> {
                            Text("Autorisez l'installation de cette source dans les réglages Android, puis revenez ici.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            Button(onClick = { viewModel.install((download as DownloadState.AwaitingInstallPermission).apkPath, info.version) },
                                modifier = Modifier.fillMaxWidth()) { Text("Continuer l'installation") }
                        }
                        else -> {
                            if (download is DownloadState.Error) {
                                Text((download as DownloadState.Error).message, color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall)
                            } else if (download is DownloadState.Cancelled) {
                                Text("Téléchargement annulé. Vous pouvez réessayer.", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = { viewModel.download(info) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (download is DownloadState.Error || download is DownloadState.Cancelled) "Réessayer le téléchargement" else "Télécharger")
                            }
                        }
                    }
                    OutlinedButton(onClick = viewModel::checkManually, modifier = Modifier.fillMaxWidth()) {
                        Text("Vérifier à nouveau")
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes o"
    if (bytes < 1024L * 1024L) return String.format(Locale.getDefault(), "%.0f Ko", bytes / 1024.0)
    return String.format(Locale.getDefault(), "%.1f Mo", bytes / (1024.0 * 1024.0))
}
