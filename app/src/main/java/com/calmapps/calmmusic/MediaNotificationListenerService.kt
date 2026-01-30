package com.calmapps.calmmusic

import android.app.Notification
import android.app.PendingIntent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MediaNotificationListenerService : NotificationListenerService() {

    private val TAG = "MediaListener"
    private val RADIO_PACKAGES = setOf("com.android.fmradio", "com.mediatek.fmradio", "com.caf.fmradio")

    override fun onListenerConnected() {
        super.onListenerConnected()
        scanForActiveMedia()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        if (isMediaNotification(sbn)) {
            Log.d(TAG, "Media Session Found: $packageName")
            updateControllerFromSbn(sbn)
        } else if (RADIO_PACKAGES.contains(packageName)) {
            Log.d(TAG, "Radio Notification Found (Raw): $packageName")
            updateRawFromSbn(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == ExternalMediaRepository.value.packageName) {
            ExternalMediaRepository.clear()
            scanForActiveMedia()
        }
    }

    private fun scanForActiveMedia() {
        try {
            val notifications = activeNotifications

            val mediaNotification = notifications.firstOrNull { isMediaNotification(it) }
            if (mediaNotification != null) {
                updateControllerFromSbn(mediaNotification)
                return
            }

            val radioNotification = notifications.firstOrNull { RADIO_PACKAGES.contains(it.packageName) }
            if (radioNotification != null) {
                updateRawFromSbn(radioNotification)
                return
            }

            ExternalMediaRepository.clear()

        } catch (e: Exception) {
            Log.e(TAG, "Error scanning: ${e.message}")
        }
    }

    private fun isMediaNotification(sbn: StatusBarNotification): Boolean {
        return sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
    }

    private fun updateControllerFromSbn(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val token = extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)

        if (token != null) {
            val controller = MediaController(this, token)
            ExternalMediaRepository.updateController(controller)
        }
    }

    private fun updateRawFromSbn(sbn: StatusBarNotification) {
        val n = sbn.notification
        val extras = n.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "FM Radio"

        val sb = StringBuilder()
        extras.getCharSequence(Notification.EXTRA_TEXT)?.let { sb.append(it).append(" ") }
        extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.let { sb.append(it).append(" ") }
        extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.let { sb.append(it).append(" ") }
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { sb.append(it).append(" ") }
        n.tickerText?.let { sb.append(it).append(" ") }

        val combinedText = sb.toString().trim()

        var actionPrev: PendingIntent? = null
        var actionPlayPause: PendingIntent? = null
        var actionNext: PendingIntent? = null

        val actions = n.actions
        if (actions != null) {
            for (action in actions) {
                val name = action.title?.toString()?.lowercase() ?: ""
                if (name.contains("prev") || name.contains("back") || name.contains("<<")) actionPrev = action.actionIntent
                if (name.contains("next") || name.contains("skip") || name.contains(">>")) actionNext = action.actionIntent
                if (name.contains("pause") || name.contains("play") || name.contains("stop")) actionPlayPause = action.actionIntent
            }
            if (actionPrev == null && actions.isNotEmpty()) actionPrev = actions[0].actionIntent
            if (actionNext == null && actions.size > 1) actionNext = actions[actions.size - 1].actionIntent
            if (actionPlayPause == null && actions.size > 1) {
                if (actions.size == 3) actionPlayPause = actions[1].actionIntent
            }
        }

        ExternalMediaRepository.updateRawState(
            packageName = sbn.packageName,
            title = title,
            text = combinedText, // We send the giant string so UI regex can find "104.5"
            isPlaying = true,
            actionPrev = actionPrev,
            actionPlayPause = actionPlayPause,
            actionNext = actionNext
        )
    }
}