@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package tw.notivault.ui

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tw.notivault.VaultViewModel
import tw.notivault.capture.VaultNotificationListener
import tw.notivault.data.AppSettings
import tw.notivault.data.DayCount
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal fun number(value: Long) = NumberFormat.getIntegerInstance(Locale.TAIWAN).format(value)
internal fun bytes(value: Long): String = when { value >= 1024L * 1024 * 1024 -> "%.1f GB".format(value / (1024.0 * 1024 * 1024)); value >= 1024 * 1024 -> "%.1f MB".format(value / (1024.0 * 1024)); else -> "%.0f KB".format(value / 1024.0) }
internal fun timestamp(value: Long, pattern: String = "MM/dd HH:mm:ss"): String = java.time.Instant.ofEpochMilli(value).atZone(ZoneId.of("Asia/Taipei")).format(DateTimeFormatter.ofPattern(pattern))

@Composable
fun VaultApp(model: VaultViewModel) {
    val configuration by model.settings.collectAsStateWithLifecycle()
    val counts by model.counts.collectAsStateWithLifecycle()
    val connected by model.connected.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val detail by model.detail.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val pageState = rememberSaveableStateHolder()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var authorized by remember { mutableStateOf(false) }
    fun refreshPermission() { authorized = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context) }
    DisposableEffect(owner) {
        refreshPermission()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshPermission() }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); model.message.value = null } }
    VaultTheme(configuration.theme) {
        if (!configuration.onboardingComplete) {
            WelcomeScreen(authorized, onAuthorize = { openNotificationAccess(context) }, onContinue = { model.update { it.copy(onboardingComplete = true) } })
        } else Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    val labels = listOf("總覽", "歷史", "WebDAV", "設定")
                    val icons = listOf(Icons.Rounded.Dashboard, Icons.Rounded.CalendarMonth, Icons.Rounded.CloudSync, Icons.Rounded.Tune)
                    labels.forEachIndexed { index, label -> NavigationBarItem(selected = page == index, onClick = { page = index },
                        icon = { Icon(icons[index], label, Modifier.size(23.dp)) }, label = { Text(label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = .13f))) }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 840.dp).fillMaxSize()) { pageState.SaveableStateProvider(page) { when (page) {
                    0 -> Dashboard(model, counts, authorized, connected, onHistory = { page = 1 }, onBackup = { page = 2 })
                    1 -> HistoryScreen(model, counts, configuration)
                    2 -> WebDavScreen(model, configuration, onHistory = { model.requestRemoteHistory.value = true; page = 1 })
                    3 -> SettingsScreen(model, configuration, authorized, connected)
                } } }
            }
        }
        detail?.let { NotificationDetail(it, onDismiss = { model.detail.value = null }, onMessage = model::notify) }
    }
}

internal fun openNotificationAccess(context: android.content.Context) {
    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable internal fun PageHeading(title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("N O T I V A U L T", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f)); action?.invoke()
        }
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun VaultCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .23f))) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}
@Composable internal fun IconTile(icon: ImageVector, color: Color = MaterialTheme.colorScheme.primary, size: Int = 42) {
    Box(Modifier.size(size.dp).background(color.copy(alpha = .12f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp), tint = color) }
}
@Composable internal fun SectionTitle(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); subtitle?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        action?.invoke()
    }
}
@Composable internal fun StatusPill(text: String, good: Boolean) {
    val color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.background(color.copy(alpha = .1f), CircleShape).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).background(color, CircleShape)); Text(text, fontSize = 11.sp, color = color)
    }
}

@Composable
private fun Dashboard(model: VaultViewModel, counts: List<DayCount>, authorized: Boolean, connected: Boolean, onHistory: () -> Unit, onBackup: () -> Unit) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val storage by model.storage.collectAsStateWithLifecycle()
    val captureError by model.captureError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val today = LocalDate.now(ZoneId.of(settings.zoneId))
    val total = counts.sumOf { it.count }
    val todayCount = counts.find { it.date == today.toString() }?.count ?: 0
    val max = counts.maxOfOrNull { it.count } ?: 0
    val peaks = counts.filter { it.count == max }
    var showPeaks by remember { mutableStateOf(false) }
    var period by rememberSaveable { mutableIntStateOf(7) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp, 25.dp, 22.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { PageHeading("每一則，都留存。", "你的通知時間膠囊", action = { IconTile(Icons.Rounded.Inventory2) }) }
        item {
            Surface(shape = RoundedCornerShape(27.dp), modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
                Box(Modifier.background(Brush.linearGradient(listOf(Color(0xFF123B4B), Color(0xFF14283F), Color(0xFF232844)))).padding(24.dp)) {
                    Canvas(Modifier.matchParentSize()) {
                        drawCircle(Cyan.copy(alpha = .06f), 135.dp.toPx(), Offset(size.width + 35.dp.toPx(), 20.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(24.dp.toPx()))
                        drawCircle(Cyan.copy(alpha = .08f), 85.dp.toPx(), Offset(size.width + 35.dp.toPx(), 20.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("本機已典藏通知", color = Color(0xFFB5D0D9), fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Box(Modifier.size(5.dp).background(if (connected) Mint else Color(0xFFA2ADC0), CircleShape))
                                Text(if (connected) "持續守候中" else "等待連線", color = if (connected) Mint else Color(0xFFA2ADC0), fontSize = 11.sp)
                            }
                        }
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(number(total), fontSize = 49.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = (-2).sp, maxLines = 1, modifier = Modifier.weight(1f, false))
                            Text("筆", color = Color(0xFFA5BFCF), fontSize = 15.sp, modifier = Modifier.padding(bottom = 11.dp))
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = .1f))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("只計算手機目前保留的通知版本", fontSize = 11.sp, color = Color(0xFFA5BFCF), modifier = Modifier.weight(1f))
                            IconButton(onHistory, Modifier.size(30.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "查看通知歷史", tint = Cyan, modifier = Modifier.size(19.dp)) }
                        }
                    }
                }
            }
        }
        if (!authorized || !connected || captureError != null) item {
            VaultCard {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(Icons.Rounded.NotificationsActive, MaterialTheme.colorScheme.secondary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (!authorized) "開始保存你的通知" else if (!connected) "通知服務尚未連線" else "保存狀態需處理", fontWeight = FontWeight.SemiBold)
                        Text(captureError ?: if (!authorized) "開啟通知存取權限後，系統通知就會自動留存。" else "可重新啟用通知存取權限；強制停止 App 期間無法收集通知。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedButton(onClick = { if (authorized) android.service.notification.NotificationListenerService.requestRebind(ComponentName(context, VaultNotificationListener::class.java)) else openNotificationAccess(context) }, modifier = Modifier.fillMaxWidth()) { Text(if (authorized) "重新連線" else "開啟通知存取") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VaultCard(Modifier.weight(1f)) {
                    IconTile(Icons.Rounded.Today, MaterialTheme.colorScheme.primary, 36)
                    Text("今日通知", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(number(todayCount), style = MaterialTheme.typography.headlineMedium)
                    Text(today.format(DateTimeFormatter.ofPattern("MM / dd")), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                VaultCard(Modifier.weight(1f)) {
                    IconTile(Icons.Rounded.Storage, MaterialTheme.colorScheme.secondary, 36)
                    Text("本機用量", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(bytes(storage), style = MaterialTheme.typography.headlineMedium, maxLines = 1)
                    Text(if (settings.keepDays == 0) "不限保存天數" else "保留最近 ${settings.keepDays} 天*", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            VaultCard {
                SectionTitle("通知趨勢", "每天保存的通知版本數")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(7 to "7 天", 30 to "30 天", 90 to "90 天", 0 to "全部").forEach { (days, label) -> FilterChip(selected = period == days, onClick = { period = days }, label = { Text(label, fontSize = 11.sp) }) }
                }
                val first = if (period == 0) counts.firstOrNull()?.date?.let(LocalDate::parse) ?: today.minusDays(6) else today.minusDays(period.toLong() - 1)
                val data = remember(counts, first, today) { val map = counts.associate { it.date to it.count }; generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.map { it to (map[it.toString()] ?: 0) }.toList() }
                TrendChart(data)
            }
        }
        item {
            VaultCard {
                Row(horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.EmojiEvents, MaterialTheme.colorScheme.secondary)
                    Column(Modifier.weight(1f)) {
                        Text("通知最多的一天", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(peaks.firstOrNull()?.date ?: "尚無紀錄", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) { Text(if (max > 0) number(max) else "—", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Text("筆通知", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (peaks.size > 1) TextButton({ showPeaks = true }, contentPadding = PaddingValues(0.dp)) { Text("另有 ${peaks.size - 1} 個並列日期", fontSize = 12.sp) }
            }
        }
        item {
            VaultCard {
                SectionTitle("雲端接力", if (settings.lastBackupAt > 0) "上次成功 ${timestamp(settings.lastBackupAt)}" else "讓通知歷史安心留在你的伺服器", action = { Icon(Icons.Rounded.CloudDone, null, tint = MaterialTheme.colorScheme.primary) })
                Text(settings.backupStatus, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onBackup, Modifier.fillMaxWidth()) { Text(if (settings.configured) "管理 WebDAV 備份" else "設定 WebDAV") }
                Text("*超過保留天數的資料，只有在遠端驗證成功後才會清除。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showPeaks) AlertDialog(onDismissRequest = { showPeaks = false }, title = { Text("並列最高 · ${number(max)} 筆") }, text = { LazyColumn { items(peaks) { Text(it.date, Modifier.padding(vertical = 8.dp)) } } }, confirmButton = { TextButton({ showPeaks = false }) { Text("完成") } })
}

@Composable private fun TrendChart(data: List<Pair<LocalDate, Long>>) {
    var selected by remember(data) { mutableIntStateOf(-1) }
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = .3f)
    val maximum = (data.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    val summary = if (data.all { it.second == 0L }) "此期間尚無通知" else "點選折線查看每日筆數"
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.height(160.dp).width(36.dp), verticalArrangement = Arrangement.SpaceBetween) { Text(number(maximum), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(number(maximum / 2), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("0", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Canvas(Modifier.weight(1f).height(160.dp).semantics { contentDescription = "每日通知數量折線圖，最高 $maximum 筆" }.pointerInput(data) {
                detectTapGestures { offset -> selected = if (data.size < 2) 0 else ((offset.x / size.width) * (data.size - 1)).roundToInt().coerceIn(0, data.lastIndex) }
            }) {
                val height = size.height - 5.dp.toPx()
                for (line in 0..2) drawLine(grid, Offset(0f, height * line / 2), Offset(size.width, height * line / 2), strokeWidth = 1f)
                if (data.isEmpty()) return@Canvas
                val points = data.mapIndexed { i, value -> Offset(if (data.size == 1) size.width / 2 else size.width * i / (data.size - 1), height - (value.second.toFloat() / maximum) * (height - 8.dp.toPx())) }
                val line = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.zipWithNext().forEach { (from, to) -> val midpoint = (from.x + to.x) / 2; cubicTo(midpoint, from.y, midpoint, to.y, to.x, to.y) }
                }
                val fill = Path().apply { addPath(line); lineTo(points.last().x, height); lineTo(points.first().x, height); close() }
                drawPath(fill, Brush.verticalGradient(listOf(primary.copy(alpha = .24f), primary.copy(alpha = 0f))))
                drawPath(line, primary, style = androidx.compose.ui.graphics.drawscope.Stroke(2.7.dp.toPx(), cap = StrokeCap.Round))
                val marked = if (selected in points.indices) selected else data.indices.maxByOrNull { data[it].second } ?: 0
                if (selected >= 0) drawLine(primary.copy(alpha = .25f), Offset(points[marked].x, 0f), Offset(points[marked].x, height), 1.dp.toPx())
                drawCircle(primary.copy(alpha = .15f), 8.dp.toPx(), points[marked]); drawCircle(primary, 4.dp.toPx(), points[marked])
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 36.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(data.firstOrNull()?.first?.format(DateTimeFormatter.ofPattern("MM/dd")) ?: "", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(data.lastOrNull()?.first?.format(DateTimeFormatter.ofPattern("MM/dd")) ?: "", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (selected in data.indices) "${data[selected].first} · ${number(data[selected].second)} 筆" else summary, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun WelcomeScreen(authorized: Boolean, onAuthorize: () -> Unit, onContinue: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(30.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            Spacer(Modifier.height(30.dp))
            Box(Modifier.size(88.dp).background(Brush.linearGradient(listOf(Color(0xFF123B4B), Color(0xFF252A49))), RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Inventory2, null, Modifier.size(42.dp), tint = Cyan) }
            PageHeading("值得留下的，\n一則也不錯過。", "通知典藏 · 你的私人通知時間膠囊")
            Text("把散落在通知欄的訊息整理成每天的歷史，並備份到你自己的 WebDAV。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            VaultCard {
                WelcomeFeature(Icons.Rounded.CalendarMonth, "每天一份 CSV", "原始時間、完整結構與可讀取的圖片，一起保存。")
                WelcomeFeature(Icons.Rounded.CloudSync, "備份確認後再清理", "預設保留 7 天，未成功驗證的資料會留在手機。")
                WelcomeFeature(Icons.Rounded.Lock, "只連到你設定的伺服器", "沒有廣告或第三方分析服務。")
            }
            Text("需要你在系統中開啟「通知存取」。只能保存啟用後收到或當下仍存在的通知；系統遮蔽的敏感內容無法還原。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onAuthorize, Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) { Icon(if (authorized) Icons.Rounded.CheckCircle else Icons.Rounded.NotificationsActive, null); Spacer(Modifier.width(9.dp)); Text(if (authorized) "已授權 · 檢查通知存取" else "開啟通知存取") }
            TextButton(onContinue, Modifier.fillMaxWidth()) { Text(if (authorized) "進入我的典藏" else "稍後設定，先看看介面") }
        }
    }
}
@Composable private fun WelcomeFeature(icon: ImageVector, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 3.dp).size(21.dp)); Column { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

@Composable internal fun EmptyState(icon: ImageVector, title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(icon, size = 56); Text(title, fontWeight = FontWeight.SemiBold); Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun PreferenceRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium); Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onChange)
    }
}

@Composable internal fun ChoiceMenu(label: String, value: String, choices: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, shape = RoundedCornerShape(12.dp)) { Text("$label · $value", fontSize = 12.sp); Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp)) }
        DropdownMenu(expanded, { expanded = false }) { choices.forEach { (key, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(key) }) } }
    }
}

@Composable private fun SettingsScreen(model: VaultViewModel, settings: AppSettings, authorized: Boolean, connected: Boolean) {
    val context = LocalContext.current
    var customDays by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf(settings.keepDays.toString()) }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp, 25.dp, 22.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { PageHeading("照你的方式。", "保存、外觀與備份偏好") }
        item { VaultCard {
            SectionTitle("通知存取", action = { StatusPill(if (connected) "已連線" else if (authorized) "已授權" else "未啟用", connected) })
            Text("系統授權：${if (authorized) "已開啟" else "尚未開啟"}\n背景服務：${if (connected) "持續接收通知" else "等待系統連線"}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton({ openNotificationAccess(context) }, Modifier.fillMaxWidth()) { Text("管理通知存取權限") }
        } }
        item { VaultCard {
            SectionTitle("本機保留", "確認遠端內容一致後，才會刪除到期資料")
            ChoiceMenu("保存天數", if (settings.keepDays == 0) "不限" else "${settings.keepDays} 天", listOf("7" to "7 天", "14" to "14 天", "30" to "30 天", "0" to "不限天數", "custom" to "自訂天數")) { value -> if (value == "custom") { days = settings.keepDays.coerceAtLeast(1).toString(); customDays = true } else model.update { it.copy(keepDays = value.toInt()) } }
            Text("以今天及之前的日曆日計算。未備份、驗證失敗或設定尚未完成時，資料會繼續保留，因此可能超過指定天數。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { VaultCard {
            SectionTitle("備份排程")
            PreferenceRow(Icons.Rounded.Sync, "自動備份", "系統依網路與省電狀態安排執行", settings.automaticBackup) { value -> model.update { it.copy(automaticBackup = value) } }
            PreferenceRow(Icons.Rounded.SignalCellularAlt, "允許行動網路", "關閉時，自動與手動備份都只用 Wi-Fi", settings.allowMobile) { value -> model.update { it.copy(allowMobile = value) } }
            ChoiceMenu("備份頻率", intervalLabel(settings.backupMinutes), listOf(15, 30, 60, 360, 1440).map { it.toString() to intervalLabel(it) }) { value -> model.update { it.copy(backupMinutes = value.toInt()) } }
        } }
        item { VaultCard {
            SectionTitle("外觀")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("dark" to "深色", "light" to "淺色", "system" to "系統").forEach { (key, name) -> FilterChip(settings.theme == key, { model.update { it.copy(theme = key) } }, label = { Text(name) }, leadingIcon = { Icon(if (key == "dark") Icons.Rounded.DarkMode else if (key == "light") Icons.Rounded.LightMode else Icons.Rounded.BrightnessAuto, null, Modifier.size(17.dp)) }) }
            }
        } }
        item { VaultCard {
            SectionTitle("資料與隱私")
            Text("每天依 postTime 分檔\n固定時區：${settings.zoneId}\n統計單位：保存的通知版本\nCSV 格式：UTF-8 BOM / schema v1\n遠端暫存：50 MB，開啟中的單日檔案可暫時超過", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton({ scope.launch { model.remote.clear(evictAll = true); model.notify("遠端查閱暫存已釋放；本機通知不受影響") } }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.CleaningServices, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("清除遠端查閱暫存") }
            Text("WebDAV 密碼以 Android Keystore 加密。App 不會將通知傳送到你未設定的第三方服務。Android 15 以上可能遮蔽驗證碼等敏感內容。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) { Text("通知典藏 ${tw.notivault.BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("每一則，都有歸處。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f)) } }
    }
    if (customDays) AlertDialog(onDismissRequest = { customDays = false }, title = { Text("自訂保留天數") }, text = { OutlinedTextField(days, { days = it.filter(Char::isDigit).take(4) }, label = { Text("1–3650 天") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)) }, confirmButton = { TextButton({ days.toIntOrNull()?.takeIf { it in 1..3650 }?.let { value -> model.update { it.copy(keepDays = value) }; customDays = false } }, enabled = days.toIntOrNull() in 1..3650) { Text("儲存") } }, dismissButton = { TextButton({ customDays = false }) { Text("取消") } })
}
internal fun intervalLabel(minutes: Int) = when (minutes) { 15 -> "15 分鐘"; 30 -> "30 分鐘"; 60 -> "1 小時"; 360 -> "6 小時"; else -> "24 小時" }
