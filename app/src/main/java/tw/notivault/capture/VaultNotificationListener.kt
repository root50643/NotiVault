package tw.notivault.capture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import tw.notivault.VaultApplication

class VaultNotificationListener : NotificationListenerService() {
    private val graph get() = (application as VaultApplication).graph
    override fun onListenerConnected() {
        graph.listenerConnected.value = true
        runCatching {
            val current = activeNotifications.orEmpty()
            graph.captureQueue.trySend(CaptureEvent.Reconcile(current.map { it.key }.toSet()))
            current.forEach { onNotificationPosted(it, currentRanking) }
        }.onFailure { graph.repository.error.value = "讀取現有通知失敗：${it.message}" }
    }
    override fun onListenerDisconnected() { graph.listenerConnected.value = false }
    override fun onDestroy() { graph.listenerConnected.value = false; super.onDestroy() }
    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn == null) return
        val receivedAt = System.currentTimeMillis()
        runCatching {
            val ranking = Ranking().takeIf { rankingMap?.getRanking(sbn.key, it) == true }
            graph.captureQueue.trySend(CaptureEvent.Posted(sbn.clone(), ranking, receivedAt))
        }.onFailure { graph.repository.error.value = "通知快照失敗：${it.message}" }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        sbn?.let { graph.captureQueue.trySend(CaptureEvent.Removed(it.key)) }
    }
}

sealed interface CaptureEvent {
    data class Posted(val sbn: StatusBarNotification, val ranking: NotificationListenerService.Ranking?, val receivedAt: Long) : CaptureEvent
    data class Removed(val key: String) : CaptureEvent
    data class Reconcile(val keys: Set<String>) : CaptureEvent
}
