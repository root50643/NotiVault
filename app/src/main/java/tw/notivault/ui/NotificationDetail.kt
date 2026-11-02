@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package tw.notivault.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import tw.notivault.core.NotificationRecord
import tw.notivault.core.VaultJson

@Composable
internal fun NotificationDetail(record: NotificationRecord, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) scope.launch {
        try { withContext(Dispatchers.IO) { checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter(Charsets.UTF_8).use { it.write(VaultJson.encodeToString(record)) } }; onMessage("完整通知 JSON 已匯出") }
        catch (failure: Exception) { onMessage("匯出失敗：${failure.message}") }
    } }
    val images by produceState(initialValue = emptyList<JsonObject>(), record.eventId) { value = withContext(Dispatchers.Default) { findImages(record.notificationJson).distinctBy { it["data"] } } }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
            TopAppBar(title = { Text("通知詳情", fontSize = 18.sp) }, navigationIcon = { IconButton(onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } }, actions = {
                IconButton({ context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(record.title, "${record.appLabel}\n${timestamp(record.postTime, "yyyy/MM/dd HH:mm:ss")}\n${record.title}\n${record.text}")); onMessage("通知文字已複製") }) { Icon(Icons.Rounded.ContentCopy, "複製通知文字") }
                IconButton({ export.launch("notification-${record.eventId}.json") }) { Icon(Icons.Rounded.FileDownload, "匯出完整 JSON") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item { Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) { IconTile(Icons.Rounded.Notifications); Column { Text(record.appLabel, style = MaterialTheme.typography.titleMedium); Text(timestamp(record.postTime, "yyyy/MM/dd HH:mm:ss.SSS"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
                item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { listOf("內容", "原始資料", "圖片 ${images.size}").forEachIndexed { index, title -> FilterChip(tab == index, { tab = index }, label = { Text(title, fontSize = 12.sp) }) } } }
                when (tab) {
                    0 -> {
                        item { VaultCard {
                            SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { Text(record.title.ifBlank { "未提供標題" }, style = MaterialTheme.typography.titleLarge); Text(record.text.ifBlank { "此通知未提供文字內容。" }, style = MaterialTheme.typography.bodyLarge) } }
                        } }
                        item { VaultCard {
                            SectionTitle("通知資訊")
                            DetailField("App 套件", record.packageName)
                            DetailField("postTime", record.postTime.toString())
                            DetailField("接收時間", timestamp(record.capturedAt, "yyyy/MM/dd HH:mm:ss.SSS"))
                            DetailField("保存日期", "${record.archiveDate} · ${record.archiveZoneId}")
                            DetailField("通知版本", "v${record.version}")
                            DetailField("通知識別", record.notificationKey)
                            DetailField("事件 ID", record.eventId)
                        } }
                        if (record.serializationErrors.isNotEmpty()) {
                            item { SectionTitle("資料可用性", "${record.serializationErrors.size} 個欄位有系統限制或讀取問題") }
                            items(record.serializationErrors) { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    1 -> {
                        item { Text("預覽會收合 Base64 與過長文字；右上角可匯出包含完整圖片資料的原始 JSON。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        listOf("StatusBarNotification" to record.sbnJson, "Notification" to record.notificationJson, "Ranking" to record.rankingJson).forEach { (name, payload) ->
                            item { VaultCard {
                                SectionTitle(name)
                                val preview by produceState("正在整理欄位…", record.eventId, name) { value = withContext(Dispatchers.Default) {
                                    Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), displayJson(payload)).take(120_000)
                                } }
                                SelectionContainer { Text(preview, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 16.sp) }
                            } }
                        }
                    }
                    2 -> if (images.isEmpty()) item { EmptyState(Icons.Rounded.ImageNotSupported, "此通知沒有可讀取的圖片", "圖片必須由系統提供，或仍具有資源讀取權限。") } else items(images) { image ->
                        VaultCard {
                            val bitmap by produceState<android.graphics.Bitmap?>(null, image) { value = withContext(Dispatchers.IO) { runCatching {
                                val raw = Base64.decode(image.getValue("data").jsonPrimitive.content, Base64.NO_WRAP)
                                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
                                options.inSampleSize = 1
                                while (options.outWidth / options.inSampleSize > 800 || options.outHeight / options.inSampleSize > 800) options.inSampleSize *= 2
                                options.inJustDecodeBounds = false
                                BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
                            }.getOrNull() } }
                            bitmap?.let { Image(it.asImageBitmap(), "通知圖片", Modifier.fillMaxWidth().heightIn(max = 350.dp), contentScale = ContentScale.Fit) }
                            Text(listOfNotNull(if (image["width"] != null) "${image["width"]} × ${image["height"]}" else null, image["mimeType"]?.jsonPrimitive?.content).joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun DetailField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); SelectionContainer { Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium) } }
}
private fun displayJson(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapValues { (key, value) -> if (key == "data" && element["encoding"]?.jsonPrimitive?.content == "base64") JsonPrimitive("<Base64: ${value.jsonPrimitive.content.length} 字元；完整內容請匯出 JSON>") else displayJson(value) })
    is JsonArray -> JsonArray(element.map(::displayJson))
    is JsonPrimitive -> if (element.isString && element.content.length > 8000) JsonPrimitive(element.content.take(8000) + "… <完整內容請匯出 JSON>") else element
    else -> element
}
private fun findImages(element: JsonElement): List<JsonObject> = when (element) {
    is JsonObject -> if (element["mimeType"]?.jsonPrimitive?.content?.startsWith("image/") == true && element["data"] is JsonPrimitive) listOf(element) else element.values.flatMap(::findImages)
    is JsonArray -> element.flatMap(::findImages)
    else -> emptyList()
}
