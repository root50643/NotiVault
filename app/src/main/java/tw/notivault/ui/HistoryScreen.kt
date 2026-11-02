package tw.notivault.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import tw.notivault.VaultViewModel
import tw.notivault.data.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun HistoryScreen(model: VaultViewModel, counts: List<DayCount>, settings: AppSettings) {
    val today = LocalDate.now(ZoneId.of(settings.zoneId))
    var remote by rememberSaveable { mutableStateOf(false) }
    var monthValue by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var date by rememberSaveable { mutableStateOf(today.toString()) }
    var query by rememberSaveable { mutableStateOf("") }
    var app by rememberSaveable { mutableStateOf("") }
    var device by rememberSaveable { mutableStateOf("") }
    var devices by remember { mutableStateOf(emptyList<String>()) }
    var remoteDates by remember { mutableStateOf(emptySet<String>()) }
    var remoteApps by remember { mutableStateOf(emptyList<AppChoice>()) }
    var sessionGeneration by remember { mutableIntStateOf(0) }
    var remoteCount by remember { mutableIntStateOf(0) }
    var browserLoading by remember { mutableStateOf(false) }
    var dayLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf(emptyList<RecordIndex>()) }
    var loading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    var moreJob by remember { mutableStateOf<Job?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val localApps by model.apps.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val month = YearMonth.parse(monthValue)
    val remoteRequest by model.requestRemoteHistory.collectAsStateWithLifecycle()
    LaunchedEffect(remoteRequest) { if (remoteRequest) { remote = true; model.requestRemoteHistory.value = false } }

    LaunchedEffect(remote) { app = ""; loadError = null; if (!remote) model.remote.clear() }
    LaunchedEffect(remote, monthValue, device, settings.destinationId(), refresh) {
        if (!remote || !settings.configured) { remoteDates = emptySet(); return@LaunchedEffect }
        browserLoading = true; loadError = null
        try {
            val found = model.remote.devices(settings)
            devices = found
            val chosen = device.takeIf { it in found } ?: found.firstOrNull().orEmpty()
            if (device != chosen) { device = chosen; return@LaunchedEffect }
            remoteDates = if (chosen.isEmpty()) emptySet() else model.remote.dates(settings, chosen, month)
            if (date !in remoteDates && remoteDates.isNotEmpty()) date = remoteDates.max()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { loadError = failure.message; remoteDates = emptySet() }
        finally { browserLoading = false }
    }
    LaunchedEffect(remote, date, device, remoteDates, refresh, settings.destinationId()) {
        if (!remote) return@LaunchedEffect
        rows = emptyList(); dayLoading = true; remoteCount = 0
        try {
            if (settings.configured && device.isNotBlank() && date in remoteDates) {
                model.remote.load(settings, device, date)
                remoteCount = model.remote.sessionCount
                remoteApps = model.remote.appChoices(date)
            } else { model.remote.clear(); remoteApps = emptyList() }
            sessionGeneration++
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { loadError = failure.message; model.remote.clear(); remoteApps = emptyList(); sessionGeneration++ }
        finally { dayLoading = false }
    }
    LaunchedEffect(remote, date, query, app, counts, sessionGeneration, dayLoading) {
        moreJob?.cancel(); rows = emptyList(); hasMore = false
        if (remote && dayLoading) return@LaunchedEffect
        loading = true
        try {
            delay(180)
            rows = withContext(Dispatchers.IO) { if (remote) model.remote.page(date, app, query) else model.graph.repository.page(date, app, query) }
            hasMore = rows.size == 50
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { loadError = failure.message }
        finally { loading = false }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> if (uri != null) scope.launch {
        try { withContext(Dispatchers.IO) {
            val file = if (remote) model.remote.exportCopy() else checkNotNull(model.graph.repository.snapshot(date)) { "當日沒有通知可匯出" }.file
            try { checkNotNull(context.contentResolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } } } finally { file.delete() }
        }; model.notify("CSV 已匯出") }
        catch (failure: Exception) { model.notify("匯出失敗：${failure.message}") }
    } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp, 25.dp, 22.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { PageHeading("回到那一天。", "用月曆翻閱通知的每一頁", action = { IconButton({ refresh++ }) { Icon(Icons.Rounded.Refresh, "重新整理") } }) }
        item { Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(false to "手機典藏", true to "遠端 WebDAV").forEach { (value, label) ->
                val color = if (remote == value) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else MaterialTheme.colorScheme.surface
                Surface(Modifier.weight(1f).clickable { remote = value }, shape = RoundedCornerShape(12.dp), color = color) {
                    Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { Icon(if (value) Icons.Rounded.CloudQueue else Icons.Rounded.PhoneAndroid, null, Modifier.size(17.dp), tint = if (remote == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(7.dp)); Text(label, fontSize = 12.sp, color = if (remote == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        } }
        if (remote && settings.configured) item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceMenu("裝置", device.takeIf { it.isNotBlank() }?.let { if (it == settings.installationId) "這支手機 · ${it.take(8)}" else it.take(12) } ?: "尚無備份", devices.map { it to if (it == settings.installationId) "這支手機 · ${it.take(8)}" else it }) { device = it; app = "" }
                Text("遠端查閱不會增加本機通知統計。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (remote && !settings.configured) item { VaultCard { EmptyState(Icons.Rounded.CloudOff, "尚未設定 WebDAV", "請先到 WebDAV 頁面儲存伺服器設定。") } }
        item { VaultCard {
            MonthCalendar(month, date, today, if (remote) emptyMap() else counts.associate { it.date to it.count }, remoteDates.takeIf { remote }.orEmpty(), onMonth = { next ->
                monthValue = next.toString()
                date = if (next == YearMonth.from(today)) today.toString() else counts.map { it.date }.filter { it.startsWith(next.toString()) }.maxOrNull() ?: next.atDay(1).toString()
            }, onDate = { date = it; loadError = null })
            if (browserLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        } }
        item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val total = if (remote) remoteCount.toLong() else counts.firstOrNull { it.date == date }?.count ?: 0
            SectionTitle(LocalDate.parse(date).format(DateTimeFormatter.ofPattern("M 月 d 日，EEEE", Locale.TAIWAN)), "${if (remote) "遠端" else "本機"} · ${number(total)} 筆通知", action = {
                IconButton({ exportLauncher.launch("$date.csv") }, enabled = total > 0 && !dayLoading) { Icon(Icons.Rounded.FileDownload, "匯出當日 CSV", tint = MaterialTheme.colorScheme.primary) }
            })
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜尋標題、內容或 App", fontSize = 13.sp) }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "清除搜尋") } }, singleLine = true, shape = RoundedCornerShape(15.dp))
            val choices = if (remote) remoteApps else localApps
            ChoiceMenu("App", choices.find { it.packageName == app }?.appLabel ?: "全部", listOf("" to "全部 App") + choices.map { it.packageName to it.appLabel }) { app = it }
        } }
        loadError?.let { error -> item { VaultCard { Text("無法載入：$error", color = MaterialTheme.colorScheme.error, fontSize = 12.sp); TextButton({ refresh++ }) { Text("重試") } } } }
        if (loading || dayLoading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp) } }
        if (rows.isEmpty() && !loading && !dayLoading && loadError == null) item { EmptyState(Icons.Rounded.Forum, if (query.isNotBlank() || app.isNotBlank()) "沒有符合的通知" else "這一天，留白。", if (remote) "選擇有雲端標記的日期查閱備份。" else "收到的通知會自動出現在這裡。") }
        items(rows, key = { it.eventId }) { row -> NotificationRow(row) { model.showDetail(row.eventId, remote) } }
        if (hasMore) item { OutlinedButton(onClick = {
            moreJob = scope.launch {
                loading = true
                try { val next = withContext(Dispatchers.IO) { if (remote) model.remote.page(date, app, query, rows.size) else model.graph.repository.page(date, app, query, rows.size) }; rows = rows + next; hasMore = next.size == 50 }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { model.notify("載入失敗：${failure.message}") }
                finally { loading = false }
            }
        }, modifier = Modifier.fillMaxWidth(), enabled = !loading) { Text("載入更多通知") } }
    }
}

@Composable internal fun MonthCalendar(month: YearMonth, selected: String, today: LocalDate, counts: Map<String, Long>, remoteDates: Set<String>, onMonth: (YearMonth) -> Unit, onDate: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${month.year} 年 ${month.monthValue} 月", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton({ onMonth(month.minusMonths(1)) }, Modifier.size(34.dp)) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "上個月") }
            IconButton({ onMonth(month.plusMonths(1)) }, Modifier.size(34.dp)) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "下個月") }
        }
        Row(Modifier.fillMaxWidth()) { listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        val offset = month.atDay(1).dayOfWeek.value - 1
        val weeks = (offset + month.lengthOfMonth() + 6) / 7
        repeat(weeks) { week -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(7) { column ->
                val day = week * 7 + column - offset + 1
                if (day !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).heightIn(min = 49.dp)) else {
                    val date = month.atDay(day); val key = date.toString(); val chosen = key == selected
                    val color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    Column(Modifier.weight(1f).heightIn(min = 49.dp).background(if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(13.dp))
                        .then(if (date == today) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .4f), RoundedCornerShape(13.dp)) else Modifier)
                        .clickable { onDate(key) }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(day.toString(), fontSize = 14.sp, lineHeight = 20.sp, color = color, fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium)
                        val count = counts[key] ?: 0
                        if (count > 0) Text(if (count >= 1000) "%.1fk".format(count / 1000.0) else count.toString(), fontSize = 8.sp, lineHeight = 11.sp, color = MaterialTheme.colorScheme.primary)
                        else if (key in remoteDates) Icon(Icons.Rounded.Cloud, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.primary)
                        else Spacer(Modifier.height(11.dp))
                    }
                }
            }
        } }
    }
}

@Composable internal fun NotificationRow(row: RecordIndex, onClick: () -> Unit) {
    Surface(onClick, shape = RoundedCornerShape(19.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .15f))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary)
            val accent = colors[(row.packageName.hashCode() and Int.MAX_VALUE) % colors.size]
            Box(Modifier.size(39.dp).background(accent.copy(alpha = .13f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text(row.appLabel.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = accent) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(row.appLabel, fontSize = 11.sp, color = accent, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Text(timestamp(row.postTime, "HH:mm:ss"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(row.title.ifBlank { "未提供標題" }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (row.text.isNotBlank()) Text(row.text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (row.version > 1) Text("通知內容更新 · v${row.version}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f))
            }
        }
    }
}
