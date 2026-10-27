package com.kidasniger.learn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.kidasniger.learn.data.TranscriptionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: MainViewModel,
    onEdit: (TranscriptionEntity) -> Unit
) {
    val context = LocalContext.current
    val transcriptions by viewModel.transcriptions.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<TranscriptionEntity?>(null) }

    val filtered = remember(transcriptions, query, favoritesOnly) {
        transcriptions.filter { item ->
            (!favoritesOnly || item.favorite) &&
                (query.isBlank() ||
                    item.title.contains(query, ignoreCase = true) ||
                    item.text.contains(query, ignoreCase = true) ||
                    item.folder.contains(query, ignoreCase = true) ||
                    item.notes.contains(query, ignoreCase = true))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Historique") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    TextButton(onClick = { favoritesOnly = !favoritesOnly }) {
                        Text(if (favoritesOnly) "Tous" else "Favoris")
                    }
                    if (transcriptions.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirmation = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Effacer tout l'historique")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Rechercher dans les titres, textes, dossiers et notes") },
                singleLine = true
            )
            Text(
                "${filtered.size} transcription(s) ${if (favoritesOnly) "favorite(s)" else "enregistrée(s)"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(if (query.isBlank()) "Aucune transcription sauvegardée" else "Aucun résultat pour cette recherche")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 20.dp, top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.id }) { item ->
                        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(item.timestamp))
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { viewModel.toggleFavorite(item) }) {
                                        Icon(
                                            Icons.Default.Star,
                                            contentDescription = if (item.favorite) "Retirer des favoris" else "Ajouter aux favoris",
                                            tint = if (item.favorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text("$date · ${item.folder}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(item.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                                if (item.notes.isNotBlank()) {
                                    Text("Notes : ${item.notes}", style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.secondary)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("transcription", item.text))
                                        scope.launch { snackbarHostState.showSnackbar("Texte copié.") }
                                    }) { Text("Copier") }
                                    TextButton(onClick = {
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, item.title)
                                            putExtra(Intent.EXTRA_TEXT, item.text)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Partager la transcription"))
                                    }) { Text("Partager") }
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = { onEdit(item) }) { Text("Ouvrir / modifier") }
                                    IconButton(onClick = { deleteTarget = item }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Supprimer la transcription", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (deleteTarget != null) {
        val selected = deleteTarget!!
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Supprimer cette transcription ?") },
            text = { Text("« ${selected.title} » sera supprimée définitivement du stockage local.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTranscription(selected.id)
                    deleteTarget = null
                    scope.launch { snackbarHostState.showSnackbar("Transcription supprimée.") }
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Annuler") } }
        )
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Effacer l'historique ?") },
            text = { Text("Toutes les transcriptions sauvegardées seront supprimées définitivement. Cette action est irréversible.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    showClearConfirmation = false
                    scope.launch { snackbarHostState.showSnackbar("Historique effacé.") }
                }) { Text("Tout effacer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearConfirmation = false }) { Text("Annuler") } }
        )
    }
}
