package com.kidasniger.learn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kidasniger.learn.data.TranscriptionEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val transcriptions by viewModel.transcriptions.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("learn_history_ui", Context.MODE_PRIVATE) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Tout") }
    var sort by remember { mutableStateOf("Date récente") }
    var sortMenu by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    var deleteItem by remember { mutableStateOf<TranscriptionEntity?>(null) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    val favorites = remember { mutableStateListOf<Long>().apply {
        prefs.getString("favorites", "").orEmpty().split(",").mapNotNull { it.toLongOrNull() }.forEach { add(it) }
    } }
    val recent = remember { mutableStateListOf<Long>().apply {
        prefs.getString("recent", "").orEmpty().split(",").mapNotNull { it.toLongOrNull() }.forEach { add(it) }
    } }
    fun persistFavorites() { prefs.edit().putString("favorites", favorites.joinToString(",")).apply() }
    fun persistRecent() { prefs.edit().putString("recent", recent.joinToString(",")).apply() }

    val filtered = transcriptions.filter { item ->
        (query.isBlank() || item.title.contains(query, true) || item.text.contains(query, true)) &&
            when (filter) {
                "Favoris" -> favorites.contains(item.id)
                "Récents" -> recent.contains(item.id)
                else -> true
            }
    }.let { list ->
        when (sort) {
            "Titre A–Z" -> list.sortedBy { it.title.lowercase(Locale.getDefault()) }
            "Consultation" -> list.sortedBy { recent.indexOf(it.id).let { index -> if (index < 0) Int.MAX_VALUE else index } }
            else -> list.sortedByDescending { it.timestamp }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = {
            Column {
                Text("Historique")
                Text("${filtered.size} transcription(s)", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, actions = {
            if (transcriptions.isNotEmpty()) IconButton(onClick = { clearDialog = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Effacer tout l'historique")
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (transcriptions.isNotEmpty()) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("Rechercher un titre ou un mot…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Effacer la recherche")
                    } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Tout", "Favoris", "Récents").forEach { label ->
                        FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) })
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, contentDescription = "Trier") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf("Date récente", "Titre A–Z", "Consultation").forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = { sort = option; sortMenu = false })
                            }
                        }
                    }
                }
            }
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(54.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Text(if (transcriptions.isEmpty()) "Aucune transcription enregistrée" else "Aucun résultat pour ces filtres",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Les transcriptions sont enregistrées localement sur cet appareil.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().weight(1f).padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(filtered, key = { it.id }) { item ->
                        val date = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(item.timestamp))
                        val expanded = expandedId == item.id
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).clickable {
                                        expandedId = if (expanded) null else item.id
                                        recent.remove(item.id); recent.add(0, item.id)
                                        while (recent.size > 100) recent.removeAt(recent.lastIndex)
                                        persistRecent()
                                    }) {
                                        Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text("$date · ${item.language.uppercase(Locale.ROOT)}", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = {
                                        if (favorites.contains(item.id)) favorites.remove(item.id) else favorites.add(item.id)
                                        persistFavorites()
                                    }) {
                                        Icon(if (favorites.contains(item.id)) Icons.Default.Star else Icons.Default.StarBorder,
                                            contentDescription = if (favorites.contains(item.id)) "Retirer des favoris" else "Ajouter aux favoris",
                                            tint = if (favorites.contains(item.id)) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Text(item.text, maxLines = if (expanded) Int.MAX_VALUE else 3, style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = {
                                    expandedId = if (expanded) null else item.id
                                    recent.remove(item.id); recent.add(0, item.id); persistRecent()
                                }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "Réduire" else "Lire la transcription") }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    IconButton(onClick = {
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, item.title); putExtra(Intent.EXTRA_TEXT, item.text)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Partager la transcription"))
                                    }) { Icon(Icons.Default.Share, contentDescription = "Partager") }
                                    IconButton(onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText(item.title, item.text))
                                        scope.launch { snackbar.showSnackbar("Transcription copiée.") }
                                    }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copier") }
                                    IconButton(onClick = { deleteItem = item }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Supprimer", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleteItem?.let { item ->
        AlertDialog(onDismissRequest = { deleteItem = null }, title = { Text("Supprimer cette transcription ?") },
            text = { Text("« ${item.title} » sera supprimée de l'historique local.") },
            confirmButton = { TextButton(onClick = {
                viewModel.deleteTranscription(item.id)
                favorites.remove(item.id); recent.remove(item.id); persistFavorites(); persistRecent()
                deleteItem = null
                scope.launch { snackbar.showSnackbar("Transcription supprimée.") }
            }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteItem = null }) { Text("Annuler") } })
    }
    if (clearDialog) {
        AlertDialog(onDismissRequest = { clearDialog = false }, title = { Text("Effacer l'historique ?") },
            text = { Text("${transcriptions.size} transcription(s) seront supprimées définitivement de cet appareil.") },
            confirmButton = { TextButton(onClick = {
                viewModel.clearHistory()
                favorites.clear(); recent.clear(); persistFavorites(); persistRecent()
                clearDialog = false
                scope.launch { snackbar.showSnackbar("Historique effacé.") }
            }) { Text("Tout effacer", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("Annuler") } })
    }
}
