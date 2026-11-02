package tw.notivault.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tw.notivault.VaultApplication
import tw.notivault.core.sha256
import tw.notivault.data.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

fun AppSettings.webDavConfig() = WebDavConfig(serverUrl, remoteDirectory, username, password, allowHttp)

object NetworkGate {
    fun allowed(context: Context, mobileAllowed: Boolean): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (mobileAllowed || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    }
}

class BackupCoordinator(private val context: Context, private val settings: SettingsStore, private val repository: ArchiveRepository) {
    val busy = MutableStateFlow(false)
    private val mutex = Mutex()
    private val cache = File(context.cacheDir, "backup").apply { mkdirs() }
    private val access = object : BackupAccess {
        override suspend fun dates() = repository.dao.days().map { it.date }.filter { repository.dao.records(it).isNotEmpty() }
        override suspend fun snapshot(date: String) = repository.snapshot(date)
        override suspend fun receipt(destination: String, date: String) = repository.dao.receipt(destination, date)
        override suspend fun saveReceipt(receipt: BackupReceipt) = repository.dao.saveReceipt(receipt)
        override suspend fun stillCurrent(destination: String) = settings.get().let { it.configured && it.destinationId() == destination && NetworkGate.allowed(context, it.allowMobile) }
        override suspend fun deleteVerified(date: String, revision: Long, destination: String) = repository.deleteVerified(date, revision, destination)
    }
    suspend fun sync(): BackupResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            busy.value = true
            try {
                val configuration = settings.get()
                check(configuration.configured) { "請先設定 WebDAV" }
                check(NetworkGate.allowed(context, configuration.allowMobile)) { if (configuration.allowMobile) "等待可用網路" else "等待 Wi-Fi；可在設定中允許行動網路" }
                val result = BackupEngine(access, cache).sync(WebDavClient(configuration.webDavConfig()), configuration.destinationId(), configuration.installationId,
                    LocalDate.now(ZoneId.of(configuration.zoneId)), configuration.keepDays) { message -> settings.update { it.copy(backupStatus = message) } }
                settings.update { it.copy(
                    backupStatus = if (result.failures.isEmpty()) "已驗證 ${result.verified} 天 · 清理 ${result.deleted} 天" else "部分備份失敗：${result.failures.take(3).joinToString("；")}",
                    lastBackupAt = if (result.failures.isEmpty()) System.currentTimeMillis() else it.lastBackupAt,
                ) }
                result
            } finally { busy.value = false }
        }
    }

    suspend fun testConnection(configuration: AppSettings): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            busy.value = true
            val client = WebDavClient(configuration.webDavConfig())
            val directory = listOf(configuration.installationId)
            val source = File(cache, "probe-${UUID.randomUUID()}.txt")
            val downloaded = File(cache, "probe-download-${UUID.randomUUID()}.txt")
            val temporary = directory + ".probe-${UUID.randomUUID()}.upload"
            val destination = directory + ".probe-${UUID.randomUUID()}.txt"
            try {
                check(NetworkGate.allowed(context, configuration.allowMobile)) { "目前網路不符合備份設定" }
                client.ensureCollections(directory)
                client.list(emptyList())
                source.writeText("NotiVault connection verification ${UUID.randomUUID()}")
                client.put(temporary, source); client.publish(temporary, destination, null)
                val first = checkNotNull(client.download(destination, downloaded))
                check(sha256(source) == sha256(downloaded)) { "下載內容校驗失敗" }
                source.appendText("\nconditional overwrite")
                client.put(temporary, source); client.publish(temporary, destination, first)
                check(client.download(destination, downloaded) != null && sha256(source) == sha256(downloaded)) { "更新內容校驗失敗" }
                "連線成功：目錄、上傳、條件式 MOVE、下載及 SHA-256 皆正常"
            } finally {
                runCatching { client.deleteTemporary(temporary) }; runCatching { client.deleteTemporary(destination) }
                source.delete(); downloaded.delete(); busy.value = false
            }
        }
    }

    fun schedule(configuration: AppSettings) {
        if (!configuration.configured || !configuration.automaticBackup) { runCatching { WorkManager.getInstance(context).cancelUniqueWork("vault-periodic-backup") }; return }
        val manager = WorkManager.getInstance(context)
        val request = PeriodicWorkRequestBuilder<BackupWorker>(configuration.backupMinutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
        manager.enqueueUniquePeriodicWork("vault-periodic-backup", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    fun enqueueManual() {
        WorkManager.getInstance(context).enqueueUniqueWork("vault-manual-backup", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build())
    }
}

class BackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as VaultApplication).graph
        return try {
            if (!graph.settings.get().configured) return Result.success()
            val result = graph.backup.sync()
            if (result.failures.isEmpty()) Result.success() else Result.retry()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { graph.settings.update { it.copy(backupStatus = failure.message ?: "備份失敗，將重試") }; Result.retry() }
    }
}
