package tw.notivault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import tw.notivault.core.NotificationRecord
import tw.notivault.data.*
import tw.notivault.ui.VaultApp

class MainActivity : ComponentActivity() {
    private val model: VaultViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { VaultApp(model) } }
}

class VaultViewModel(application: android.app.Application) : AndroidViewModel(application) {
    val graph = (application as VaultApplication).graph
    val settings = graph.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings(installationId = ""))
    val counts = graph.repository.counts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val apps = graph.repository.apps.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val connected = graph.listenerConnected
    val busy = graph.backup.busy
    val captureError = graph.repository.error
    val message = MutableStateFlow<String?>(null)
    val storage = MutableStateFlow(0L)
    val detail = MutableStateFlow<NotificationRecord?>(null)
    val requestRemoteHistory = MutableStateFlow(false)
    val remote = RemoteHistoryStore(application)
    init { viewModelScope.launch(Dispatchers.IO) { counts.collect { storage.value = graph.repository.storageBytes() } } }
    fun notify(text: String) { message.value = text }
    fun update(transform: (AppSettings) -> AppSettings) { viewModelScope.launch(Dispatchers.IO) {
        runCatching { graph.settings.update(transform) }.onFailure { notify("設定儲存失敗：${it.message}") }
    } }
    fun saveServer(draft: AppSettings) { viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            tw.notivault.backup.WebDavClient(tw.notivault.backup.WebDavConfig(draft.serverUrl, draft.remoteDirectory, draft.username, draft.password, draft.allowHttp))
            graph.settings.update {
                val updated = it.copy(serverUrl = draft.serverUrl.trim(), remoteDirectory = draft.remoteDirectory.trim('/'), username = draft.username, password = draft.password, allowHttp = draft.allowHttp)
                if (updated.destinationId() != it.destinationId()) updated.copy(backupStatus = "目的地已儲存，等待首次備份", lastBackupAt = 0) else updated
            }
            notify("WebDAV 設定已儲存")
        }.onFailure { notify("無法儲存：${it.message}") }
    } }
    fun testServer(draft: AppSettings) { viewModelScope.launch {
        try { notify(graph.backup.testConnection(draft)) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { notify("連線測試失敗：${failure.message}") }
    } }
    fun backupNow() { viewModelScope.launch {
        if (!settings.value.configured) { notify("請先儲存 WebDAV 連線設定"); return@launch }
        graph.settings.update { it.copy(backupStatus = "已排程備份，等待符合設定的網路") }
        graph.backup.enqueueManual(); notify("備份工作已排程")
    } }
    fun showDetail(id: String, fromRemote: Boolean) { viewModelScope.launch(Dispatchers.IO) {
        runCatching { if (fromRemote) remote.read(id) else graph.repository.read(id) }.onSuccess { detail.value = it }.onFailure { notify("無法讀取通知：${it.message}") }
    } }
    override fun onCleared() { graph.scope.launch { remote.clear() }; super.onCleared() }
}
