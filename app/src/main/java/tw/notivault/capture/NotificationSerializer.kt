package tw.notivault.capture

import android.app.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.LocusId
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.*
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.Spanned
import android.util.Base64
import android.util.SparseArray
import android.widget.RemoteViews
import android.webkit.MimeTypeMap
import kotlinx.serialization.json.*
import tw.notivault.core.CapturedNotification
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap

/** Allowlisted from the public API 37 SDK. No hidden APIs or parcel marshalling. */
class NotificationSerializer(private val context: Context, private val sourcePackage: String) {
    private val errors = mutableListOf<String>()
    private val stack = IdentityHashMap<Any, Boolean>()
    private val packageContext by lazy { runCatching { context.createPackageContext(sourcePackage, 0) }.getOrDefault(context) }

    fun capture(sbn: StatusBarNotification, ranking: NotificationListenerService.Ranking?, receivedAt: Long): CapturedNotification {
        val notification = encode(sbn.notification, "notification", 0) as JsonObject
        val status = describe(sbn, "sbn", 0)
        val rank = ranking?.let { describe(it, "ranking", 0) } ?: buildJsonObject { put("available", false) }
        val label = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val extras = sbn.notification.extras
        fun text(key: String) = runCatching { extras?.getCharSequence(key)?.toString().orEmpty() }.getOrDefault("")
        val body = text(Notification.EXTRA_BIG_TEXT).ifBlank { text(Notification.EXTRA_TEXT) }
        return CapturedNotification(sbn.postTime, sbn.key, sbn.packageName, label, text(Notification.EXTRA_TITLE), body, status, notification, rank, errors.toList(), receivedAt)
    }

    private fun encode(value: Any?, path: String, depth: Int): JsonElement {
        if (value == null) return JsonNull
        if (depth > 40) return unavailable(value, path, "巢狀深度超過 40")
        when (value) {
            is JsonElement -> return value
            is String -> return JsonPrimitive(value)
            is Number -> return JsonPrimitive(value)
            is Boolean -> return JsonPrimitive(value)
            is Char -> return JsonPrimitive(value.toString())
            is Uri -> return uri(value, path)
            is ComponentName -> return JsonPrimitive(value.flattenToString())
            is UserHandle -> return buildJsonObject { put("type", "UserHandle"); put("description", value.toString()) }
            is LocusId -> return buildJsonObject { put("type", "LocusId"); put("id", value.id) }
            is ByteArray -> return buildJsonObject { put("type", "byte[]"); put("encoding", "base64"); put("data", Base64.encodeToString(value, Base64.NO_WRAP)) }
            is Class<*> -> return JsonPrimitive(value.name)
            is Enum<*> -> return JsonPrimitive(value.name)
        }
        if (stack.put(value, true) != null) return unavailable(value, path, "循環參照")
        return try {
            when {
                value is Spanned -> buildJsonObject {
                    put("type", value.javaClass.name); put("text", value.toString())
                    put("spans", JsonArray(value.getSpans(0, value.length, Any::class.java).map { span -> buildJsonObject {
                        put("type", span.javaClass.name); put("start", value.getSpanStart(span)); put("end", value.getSpanEnd(span)); put("flags", value.getSpanFlags(span))
                        put("properties", spanProperties(span, "$path.span", depth + 1))
                    } }))
                }
                value is CharSequence -> JsonPrimitive(value.toString())
                value is Bundle -> buildJsonObject { value.keySet().sorted().forEach { key -> put(key, safe("$path.$key") {
                    val item = value.get(key)
                    if (item is Uri) uri(item, "$path.$key", runCatching { value.getString("type") ?: value.getString("mimeType") }.getOrNull())
                    else encode(item, "$path.$key", depth + 1)
                }) } }
                value is PersistableBundle -> buildJsonObject { value.keySet().sorted().forEach { key -> put(key, safe("$path.$key") { encode(value.get(key), "$path.$key", depth + 1) }) } }
                value is Bitmap -> bitmap(value)
                value is Icon -> icon(value, path, depth)
                value is PendingIntent -> buildJsonObject {
                    put("type", "PendingIntent"); put("creatorPackage", value.creatorPackage); put("creatorUid", value.creatorUid)
                    put("creatorUserHandle", encode(value.creatorUserHandle, path, depth + 1))
                    if (Build.VERSION.SDK_INT >= 31) {
                        put("isActivity", value.isActivity); put("isBroadcast", value.isBroadcast); put("isService", value.isService); put("isForegroundService", value.isForegroundService)
                        put("isImmutable", value.isImmutable)
                    }
                    put("limitation", "系統權杖無法永久匯出；不會執行此動作")
                }
                value is RemoteViews -> buildJsonObject {
                    put("type", "RemoteViews"); put("package", value.`package`); put("layoutId", value.layoutId)
                    put("limitation", "自訂版面內部指令未由公開 API 提供")
                }
                value is Intent -> buildJsonObject {
                    put("type", "Intent"); put("action", value.action); put("data", encode(value.data, path, depth + 1)); put("mimeType", value.type)
                    put("package", value.`package`); put("component", encode(value.component, path, depth + 1)); put("categories", encode(value.categories, path, depth + 1))
                    put("flags", value.flags); put("extras", encode(value.extras, path, depth + 1)); put("selector", encode(value.selector, path, depth + 1))
                    put("clipData", encode(value.clipData, path, depth + 1)); put("sourceBounds", value.sourceBounds?.toShortString())
                }
                value is SparseArray<*> -> buildJsonObject { for (i in 0 until value.size()) put(value.keyAt(i).toString(), encode(value.valueAt(i), "$path[$i]", depth + 1)) }
                value is Map<*, *> -> buildJsonObject { put("type", value.javaClass.name); put("entries", JsonArray(value.entries.mapIndexed { index, entry -> buildJsonObject {
                    put("key", encode(entry.key, "$path[$index].key", depth + 1)); put("value", encode(entry.value, "$path[$index].value", depth + 1))
                } })) }
                value is Iterable<*> -> JsonArray(value.mapIndexed { i, item -> encode(item, "$path[$i]", depth + 1) })
                value.javaClass.isArray -> JsonArray((0 until java.lang.reflect.Array.getLength(value)).map { encode(java.lang.reflect.Array.get(value, it), "$path[$it]", depth + 1) })
                registries.containsKey(value.javaClass.name) || value is Notification -> describe(value, path, depth)
                else -> unavailable(value, path, "沒有可攜式公開序列化介面")
            }
        } catch (failure: Exception) { unavailable(value, path, failure.cause?.message ?: failure.message ?: failure.javaClass.simpleName) }
        finally { stack.remove(value) }
    }

    private fun bitmap(bitmap: Bitmap): JsonObject {
        val bytes = ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
        return buildJsonObject {
            put("type", "Bitmap"); put("width", bitmap.width); put("height", bitmap.height); put("density", bitmap.density)
            put("config", bitmap.config?.name); put("hasAlpha", bitmap.hasAlpha()); put("mimeType", "image/png"); put("encoding", "base64")
            put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }

    private fun uri(uri: Uri, path: String, mimeHint: String? = null): JsonObject = buildJsonObject {
        put("type", "Uri"); put("value", uri.toString())
        if (uri.scheme !in listOf("content", "android.resource", "file")) {
            put("limitation", "保存 URI 描述；不連線下載通知提供的網路網址")
            return@buildJsonObject
        }
        val mime = mimeHint?.takeIf { it.startsWith("image/") }
            ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase())
        put("mimeType", mime)
        if (mime?.startsWith("image/") != true) return@buildJsonObject
        val image = safe(path) {
            if (uri.scheme == "file" && sourcePackage != context.packageName) {
                val candidate = java.io.File(uri.path.orEmpty()).canonicalPath
                val ownRoot = java.io.File(context.applicationInfo.dataDir).canonicalPath + java.io.File.separator
                require(!candidate.startsWith(ownRoot)) { "其他 App 的通知不能引用本 App 私有檔案" }
            }
            val bytes = checkNotNull(context.contentResolver.openInputStream(uri)) { "URI 沒有提供可讀取的內容" }.use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(32 * 1024)
                while (true) { val size = input.read(buffer); if (size < 0) break; require(output.size() + size <= 16 * 1024 * 1024) { "圖片 URI 超過單筆 16 MB 的安全讀取上限" }; output.write(buffer, 0, size) }
                output.toByteArray()
            }
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
            buildJsonObject {
                put("mimeType", mime); put("encoding", "base64"); put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                if (dimensions.outWidth > 0) { put("width", dimensions.outWidth); put("height", dimensions.outHeight) }
            }
        }
        put("image", image)
    }

    private fun icon(icon: Icon, path: String, depth: Int) = buildJsonObject {
        put("type", "Icon"); put("iconType", icon.type)
        if (icon.type == Icon.TYPE_RESOURCE) { put("resId", icon.resId); put("resPackage", icon.resPackage) }
        if (icon.type == Icon.TYPE_URI || (Build.VERSION.SDK_INT >= 30 && icon.type == Icon.TYPE_URI_ADAPTIVE_BITMAP)) put("uri", icon.uri.toString())
        put("image", safe(path) {
            val drawable = checkNotNull(icon.loadDrawable(packageContext)) { "無法讀取圖示資源" }
            if (drawable is BitmapDrawable) bitmap(drawable.bitmap) else {
                val width = drawable.intrinsicWidth.coerceAtLeast(96)
                val height = drawable.intrinsicHeight.coerceAtLeast(96)
                require(width.toLong() * height <= 16_000_000) { "圖示過大，無法安全解碼" }
                val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try { drawable.setBounds(0, 0, width, height); drawable.draw(Canvas(rendered)); bitmap(rendered) } finally { rendered.recycle() }
            }
        })
    }

    private fun describe(value: Any, path: String, depth: Int): JsonObject = buildJsonObject {
        put("_type", value.javaClass.name)
        if (value is StatusBarNotification) { put("_sdkInt", Build.VERSION.SDK_INT); put("_osRelease", Build.VERSION.RELEASE) }
        if (value is Notification) {
            notificationFields.forEach { name ->
                val field = runCatching { Notification::class.java.getField(name) }.getOrNull() ?: return@forEach
                put(name, safe("$path.$name") { encode(field.get(value), "$path.$name", depth + 1) })
            }
        } else if (value is Notification.Action) {
            put("title", encode(value.title, "$path.title", depth + 1)); put("icon", value.icon)
            put("actionIntent", encode(value.actionIntent, "$path.actionIntent", depth + 1))
        }
        val getters = if (value is Notification) notificationGetters else registries[value.javaClass.name].orEmpty()
        getters.forEach { name ->
            val method = runCatching { value.javaClass.getMethod(name) }.getOrNull() ?: return@forEach
            put(name, safe("$path.$name") { encode(method.invoke(value), "$path.$name", depth + 1) })
        }
    }

    private fun spanProperties(span: Any, path: String, depth: Int): JsonElement {
        val allowed = listOf("getForegroundColor", "getBackgroundColor", "getStyle", "getSize", "getSizeChange", "getFamily", "getURL", "getText", "getKey", "getValue", "getScaleX", "getLocale", "getLocales", "getBulletRadius", "getGapWidth", "getColor")
        val properties = buildJsonObject {
            allowed.forEach { name -> val method = runCatching { span.javaClass.getMethod(name) }.getOrNull()
                if (method != null) put(name, safe(path) { encode(method.invoke(span), path, depth + 1) }) }
        }
        return if (properties.isEmpty()) unavailable(span, path, "此文字樣式只有類型與範圍可匯出") else properties
    }

    private fun safe(path: String, block: () -> JsonElement): JsonElement = try { block() } catch (failure: Exception) {
        errors += "$path: ${failure.cause?.message ?: failure.message}"
        buildJsonObject { put("unavailable", true); put("reason", failure.cause?.message ?: failure.message ?: "讀取失敗") }
    }
    private fun unavailable(value: Any, path: String, reason: String): JsonObject {
        errors += "$path (${value.javaClass.name}): $reason"
        return buildJsonObject { put("type", value.javaClass.name); put("unavailable", true); put("reason", reason) }
    }

    companion object {
        val notificationFields = "actions audioAttributes audioStreamType bigContentView category color contentIntent contentView defaults deleteIntent extras flags fullScreenIntent headsUpContentView icon iconLevel largeIcon ledARGB ledOffMS ledOnMS number priority publicVersion sound tickerText tickerView vibrate visibility when".split(" ")
        val notificationGetters = "getAllowSystemGeneratedContextualActions getBadgeIconType getBridgedNotificationMetadata getBubbleMetadata getChannelId getContextualActions getGroup getGroupAlertBehavior getLargeIcon getLocusId getSettingsText getShortCriticalText getShortcutId getSmallIcon getSortKey getTimeoutAfter hasImage hasPromotableCharacteristics hasSummarizedContent isRequestPromotedOngoing".split(" ")
        private val registries = mapOf(
            "android.service.notification.StatusBarNotification" to "getGroupKey getId getKey getOpPkg getOverrideGroupKey getPackageName getPostTime getTag getUid getUser getUserId isAppGroup isClearable isGroup isOngoing",
            "android.service.notification.NotificationListenerService\$Ranking" to "canBubble canShowBadge getChannel getConversationShortcutInfo getImportance getImportanceExplanation getKey getLastAudiblyAlertedMillis getLockscreenVisibilityOverride getOverrideGroupKey getRank getSmartActions getSmartReplies getSummarization getSuppressedVisualEffects getUserSentiment isAmbient isConversation isSuspended matchesInterruptionFilter",
            "android.app.Notification\$Action" to "getAllowGeneratedReplies getDataOnlyRemoteInputs getEmphasisHint getExtras getIcon getRemoteInputs getSemanticAction getStyleHint isAuthenticationRequired isContextual",
            "android.app.Notification\$BubbleMetadata" to "getAutoExpandBubble getDeleteIntent getDesiredHeight getDesiredHeightResId getIcon getIntent getShortcutId isBubbleSuppressable isBubbleSuppressed isNotificationSuppressed",
            "android.app.Notification\$BridgedNotificationMetadata" to "getAppIcon getChannelId getOriginDeviceName getPackageName",
            "android.app.NotificationChannel" to "canBubble canBypassDnd canShowBadge getAudioAttributes getConversationId getDescription getGroup getId getImportance getLightColor getLockscreenVisibility getName getOriginalImportance getParentChannelId getSound getVibrationEffect getVibrationPattern hasUserSetImportance hasUserSetSound isBlockable isConversation isDemoted isImportantConversation shouldShowLights shouldVibrate",
            "android.app.Person" to "getIcon getKey getName getUri isBot isImportant",
            "android.app.RemoteInput" to "getAllowFreeFormInput getAllowedDataTypes getChoices getEditChoicesBeforeSending getExtras getLabel getResultKey isDataOnly",
            "android.content.pm.ShortcutInfo" to "getActivity getCapabilities getCategories getDisabledMessage getDisabledReason getExcludedFromSurfaces getExtras getId getIntent getIntents getLastChangedTimestamp getLocusId getLongLabel getPackage getRank getShortLabel getUserHandle hasKeyFieldsOnly isCached isDeclaredInManifest isDynamic isEnabled isImmutable isPinned",
            "android.media.AudioAttributes" to "areHapticChannelsMuted getAllowedCapturePolicy getContentType getFlags getSpatializationBehavior getUsage getVolumeControlStream isContentSpatialized",
        ).mapValues { it.value.split(" ") }
    }
}
