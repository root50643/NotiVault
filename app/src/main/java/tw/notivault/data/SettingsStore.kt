package tw.notivault.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import tw.notivault.core.VaultJson
import tw.notivault.core.sha256
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.vaultPreferences by preferencesDataStore("vault_settings")

@Serializable
data class AppSettings(
    val installationId: String = UUID.randomUUID().toString(),
    val serverUrl: String = "",
    val remoteDirectory: String = "NotiVault",
    val username: String = "",
    val encryptedPassword: String = "",
    @Transient val password: String = "",
    val allowHttp: Boolean = false,
    val allowMobile: Boolean = false,
    val automaticBackup: Boolean = true,
    val backupMinutes: Int = 60,
    val keepDays: Int = 7,
    val theme: String = "dark",
    val zoneId: String = "Asia/Taipei",
    val onboardingComplete: Boolean = false,
    val backupStatus: String = "尚未設定 WebDAV",
    val lastBackupAt: Long = 0,
) {
    val configured get() = serverUrl.isNotBlank()
    // Include credentials and transport policy: a changed destination must be independently verified.
    fun destinationId() = sha256(listOf(serverUrl.trimEnd('/'), remoteDirectory.trim('/'), username, password, allowHttp.toString(), installationId).joinToString("\u0000").toByteArray())
    fun schedulingKey() = listOf(configured, automaticBackup, allowMobile, backupMinutes).joinToString(":")
}

class SettingsStore(context: Context, private val preferences: DataStore<Preferences> = context.vaultPreferences) {
    private val initial = AppSettings()
    private val key = stringPreferencesKey("settings_v1")
    val flow = preferences.data.map { prefs ->
        val stored = prefs[key]?.let { VaultJson.decodeFromString<AppSettings>(it) } ?: initial
        stored.copy(password = if (stored.encryptedPassword.isBlank()) "" else runCatching { decrypt(stored.encryptedPassword) }.getOrDefault(""))
    }
    suspend fun get() = flow.first()
    suspend fun initialize() { preferences.edit { if (it[key] == null) it[key] = VaultJson.encodeToString(initial) } }
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        preferences.edit { prefs ->
            val existing = prefs[key]?.let { VaultJson.decodeFromString<AppSettings>(it) } ?: initial
            val decoded = existing.copy(password = if (existing.encryptedPassword.isBlank()) "" else runCatching { decrypt(existing.encryptedPassword) }.getOrDefault(""))
            val updated = transform(decoded)
            require(updated.backupMinutes in listOf(15, 30, 60, 360, 1440))
            require(updated.keepDays in 0..3650)
            val protected = updated.copy(encryptedPassword = if (updated.password.isEmpty()) "" else encrypt(updated.password))
            prefs[key] = VaultJson.encodeToString(protected)
        }
    }
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("notivault_webdav", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("notivault_webdav", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
}
