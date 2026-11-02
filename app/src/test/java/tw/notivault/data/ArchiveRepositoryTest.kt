package tw.notivault.data

import android.app.Application
import android.app.Notification
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tw.notivault.capture.NotificationSerializer
import tw.notivault.core.*
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ArchiveRepositoryTest {
    @Test fun persistentCaptureDeduplicationRemovalReconnectionAndDeletionRecovery() = runBlocking {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory("vault-repository").toFile()
        val context = object : ContextWrapper(baseContext) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(root, "databases/$name")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = scope) { File(root, "settings.preferences_pb") }
        val settings = SettingsStore(context, preferences)
        val database = Room.inMemoryDatabaseBuilder(baseContext, VaultDatabase::class.java).build()
        try {
            settings.initialize()
            val repository = ArchiveRepository(context, database, settings, scope)
            val original = fixture()
            fun capture(text: String, postTime: Long = original.postTime) = CapturedNotification(postTime, "key", "test.pkg", "App", "Title", text,
                buildJsonObject { put("postTime", postTime) }, buildJsonObject { put("extras", buildJsonObject { put("text", text) }) }, buildJsonObject { put("rank", 1) }, emptyList())
            repository.capture(capture("A"))
            repository.capture(capture("A", original.postTime + 5)) // time-only callback is not new content
            repository.capture(capture("B"))
            repository.capture(capture("A")) // A → B → A must retain all three
            assertEquals(listOf(1, 2, 3), database.dao().records(original.archiveDate).map { it.version })
            val initialInstance = database.dao().active("key")!!.instanceId
            repository.removed("key")
            repository.capture(capture("A"))
            assertEquals(4, database.dao().records(original.archiveDate).size)
            assertNotEquals(initialInstance, database.dao().active("key")!!.instanceId)
            val snapshot = repository.snapshot(original.archiveDate)!!
            val decoded = mutableListOf<NotificationRecord>(); CsvCodec.read(snapshot.file.inputStream()) { decoded += it }
            assertEquals(4, decoded.size); assertEquals(original.postTime, decoded.first().postTime)
            snapshot.file.delete()
            val restarted = ArchiveRepository(context, database, settings, scope)
            restarted.reconcileActive(setOf("key")); restarted.capture(capture("A"))
            assertEquals(4, database.dao().records(original.archiveDate).size)
            val day = database.dao().day(original.archiveDate)!!
            database.dao().saveDay(day.copy(deleting = true))
            val recovering = ArchiveRepository(context, database, settings, scope)
            recovering.reconcileActive(setOf("key")) // waits for recovery
            assertTrue(database.dao().records(original.archiveDate).isEmpty())
            assertFalse(File(recovering.csvDirectory, "${original.archiveDate}.csv").exists())
        } finally { scope.cancel(); database.close(); root.deleteRecursively() }
    }

    @Test fun publicNotificationSnapshotIncludesBitmapBase64AndArbitraryExtrasOnAndroid10() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.CYAN) }
        val notification = Notification.Builder(context, "test-channel").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("標題").setContentText("通知內容").setLargeIcon(image).build()
        notification.extras.putStringArray("custom.array", arrayOf("甲", "乙"))
        val uriImage = File(context.cacheDir, "notification-test-image.png")
        uriImage.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        notification.extras.putParcelable("custom.imageUri", android.net.Uri.fromFile(uriImage))
        val sbn = StatusBarNotification(context.packageName, context.packageName, 9, "tag", Process.myUid(), Process.myPid(), 0, notification, Process.myUserHandle(), fixture().postTime)
        val captured = NotificationSerializer(context, context.packageName).capture(sbn, null, fixture().capturedAt)
        assertEquals("標題", captured.title)
        assertEquals("通知內容", captured.text)
        assertTrue(captured.notification.toString().contains("base64"))
        assertTrue(captured.notification.toString().contains("custom.array"))
        assertTrue(captured.sbn.containsKey("getPostTime"))
        val uri = captured.notification.getValue("extras").jsonObject.getValue("custom.imageUri").jsonObject
        assertTrue(uri.getValue("image").jsonObject.getValue("data").jsonPrimitive.content.startsWith("iVBOR"))
        uriImage.delete()
        image.recycle()
    }
}
