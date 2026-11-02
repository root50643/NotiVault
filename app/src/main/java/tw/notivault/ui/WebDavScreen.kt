package tw.notivault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tw.notivault.VaultViewModel
import tw.notivault.data.AppSettings

@Composable
internal fun WebDavScreen(model: VaultViewModel, settings: AppSettings, onHistory: () -> Unit) {
    var url by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var directory by remember(settings.remoteDirectory) { mutableStateOf(settings.remoteDirectory) }
    var username by remember(settings.username) { mutableStateOf(settings.username) }
    var password by remember(settings.password) { mutableStateOf(settings.password) }
    var allowHttp by remember(settings.allowHttp) { mutableStateOf(settings.allowHttp) }
    var visiblePassword by remember { mutableStateOf(false) }
    val busy by model.busy.collectAsStateWithLifecycle()
    fun draft() = settings.copy(serverUrl = url, remoteDirectory = directory, username = username, password = password, allowHttp = allowHttp)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp, 25.dp, 22.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { PageHeading("讓記憶，有備份。", "連到你自己的 WebDAV 伺服器", action = { IconTile(Icons.Rounded.CloudSync) }) }
        item { VaultCard {
            SectionTitle("備份狀態", action = { StatusPill(if (busy) "處理中" else if (settings.configured) "已設定" else "待設定", settings.configured && !busy) })
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(settings.backupStatus, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(if (settings.lastBackupAt > 0) "上次成功：${timestamp(settings.lastBackupAt, "yyyy/MM/dd HH:mm:ss")}" else "尚未完成首次備份", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button({ model.backupNow() }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = settings.configured && !busy, shape = RoundedCornerShape(14.dp)) { Icon(Icons.Rounded.Backup, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("立即備份並驗證") }
        } }
        item { VaultCard {
            SectionTitle("伺服器連線", "支援標準 WebDAV 帳密／應用程式密碼")
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("WebDAV 網址") }, placeholder = { Text("https://nas.example.com/dav/") }, leadingIcon = { Icon(Icons.Rounded.Link, null) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = RoundedCornerShape(14.dp))
            OutlinedTextField(directory, { directory = it }, Modifier.fillMaxWidth(), label = { Text("備份目錄") }, leadingIcon = { Icon(Icons.Rounded.Folder, null) }, singleLine = true, shape = RoundedCornerShape(14.dp))
            OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("帳號") }, leadingIcon = { Icon(Icons.Rounded.PersonOutline, null) }, singleLine = true, shape = RoundedCornerShape(14.dp))
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("密碼／應用程式密碼") }, leadingIcon = { Icon(Icons.Rounded.Lock, null) }, trailingIcon = { IconButton({ visiblePassword = !visiblePassword }) { Icon(if (visiblePassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (visiblePassword) "隱藏密碼" else "顯示密碼") } }, singleLine = true,
                visualTransformation = if (visiblePassword) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = RoundedCornerShape(14.dp))
            PreferenceRow(Icons.Rounded.Http, "允許 HTTP", "僅在伺服器使用 http:// 時開啟", allowHttp) { allowHttp = it }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({ model.testServer(draft()) }, Modifier.weight(1f), enabled = !busy && url.isNotBlank()) { Text("測試連線") }
                Button({ model.saveServer(draft()) }, Modifier.weight(1f), enabled = !busy && url.isNotBlank()) { Text("儲存設定") }
            }
            Text("測試連線會使用目前輸入值，在遠端建立並移除測試檔。備份則使用已儲存的設定。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { VaultCard {
            SectionTitle("備份方式")
            PreferenceRow(Icons.Rounded.Sync, "自動備份", "每 ${intervalLabel(settings.backupMinutes)}，實際執行依系統排程", settings.automaticBackup) { value -> model.update { it.copy(automaticBackup = value) } }
            PreferenceRow(Icons.Rounded.Wifi, "允許行動網路", "預設只用 Wi-Fi，手動備份也遵守此設定", settings.allowMobile) { value -> model.update { it.copy(allowMobile = value) } }
            ChoiceMenu("頻率", intervalLabel(settings.backupMinutes), listOf(15, 30, 60, 360, 1440).map { it.toString() to intervalLabel(it) }) { value -> model.update { it.copy(backupMinutes = value.toInt()) } }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .3f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Rounded.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary); Text("暫存上傳 → 條件式發布 → 重新下載 → SHA-256 驗證 → 到期清理", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } }
        item { VaultCard {
            SectionTitle("遠端典藏", "依裝置、年份與月份整理")
            Text("${settings.remoteDirectory.ifBlank { "NotiVault" }}/\n  ${settings.installationId.take(8)}…/\n    年份/月份/YYYY-MM-DD.csv", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onHistory, Modifier.fillMaxWidth()) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("前往月曆查閱") }
        } }
    }
}
