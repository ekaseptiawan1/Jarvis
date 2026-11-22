package com.jarvis.assistant

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.Bundle

object MessageReplyBridge {
    @Volatile var replyAction: Notification.Action? = null
    @Volatile var remoteInputs: Array<RemoteInput>? = null

    fun sendReply(service: NotificationListenerService, text: String): Boolean {
        val action = replyAction ?: return false
        val inputs = remoteInputs ?: return false
        return try {
            val fillIn = Intent()
            val results = Bundle()
            inputs.forEach { results.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(inputs, fillIn, results)
            action.actionIntent.send(service, 0, fillIn)
            replyAction = null
            remoteInputs = null
            true
        } catch (_: Exception) { false }
    }
}

class MessageNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isBlank() || text.isBlank()) return

        val actions = n.actions ?: emptyArray()
        val reply = actions.firstOrNull { action ->
            val inputs = action.remoteInputs
            !inputs.isNullOrEmpty() && inputs.any { it.allowFreeFormInput }
        } ?: return

        // Keep only the latest reply-capable message; the user confirms every outgoing reply.
        MessageReplyBridge.replyAction = reply
        MessageReplyBridge.remoteInputs = reply.remoteInputs
        val intent = Intent(this, VoiceService::class.java).apply {
            action = VoiceService.ACTION_INCOMING_MESSAGE
            putExtra(VoiceService.EXTRA_SENDER, title)
            putExtra(VoiceService.EXTRA_MESSAGE, text)
        }
        try { startService(intent) } catch (_: Exception) { /* Voice service may not be active. */ }
    }
}
