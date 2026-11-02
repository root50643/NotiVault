package tw.notivault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import tw.notivault.backup.DavEntry
import tw.notivault.backup.WebDavConfig
import tw.notivault.backup.withInteractiveDav

@Composable
internal fun WebDavDirectoryPicker(configuration: WebDavConfig, onDismiss: () -> Unit, onSelect: (String) -> Unit,
    loadDirectory: suspend (List<String>) -> List<DavEntry> = { path -> withInteractiveDav(configuration) { it.browse(path) } }) {
    var path by remember { mutableStateOf(emptyList<String>()) }
    var entries by remember { mutableStateOf(emptyList<DavEntry>()) }
    var loading by remember { mutableStateOf(true) }
    var loadedPath by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(path, refresh) {
        loading = true; error = null; entries = emptyList(); loadedPath = null
        try {
            entries = loadDirectory(path).filter { it.collection }
            loadedPath = path
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "無法讀取目錄" }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("選擇備份目錄") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("從 WebDAV 網址所指的根目錄開始瀏覽。選取後再按「儲存設定」套用。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ path = path.dropLast(1) }, enabled = path.isNotEmpty() && !loading) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "上一層目錄") }
                    Text("/" + path.joinToString("/"), Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    IconButton({ refresh++ }, enabled = !loading) { Icon(Icons.Rounded.Refresh, "重新整理目錄") }
                }
                if (path.isNotEmpty()) TextButton({ path = emptyList() }, enabled = !loading) { Text("回到 WebDAV 根目錄") }
                when {
                    loading -> Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp) }
                    error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        TextButton({ refresh++ }) { Text("重新載入") }
                    }
                    entries.isEmpty() -> Text("這個目錄沒有子目錄，可直接選取目前目錄。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(entries, key = { it.name }) { entry ->
                            Row(Modifier.fillMaxWidth().clickable { path = path + entry.name }.padding(vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                Text(entry.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onSelect(path.joinToString("/")) }, enabled = !loading && error == null && loadedPath == path) { Text("選取此目錄") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
