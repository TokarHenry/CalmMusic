package com.calmapps.calmmusic.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calmapps.calmmusic.ExternalMediaRepository
import com.mudita.mmd.components.text.TextMMD
import kotlinx.coroutines.delay
import java.text.DecimalFormat

@Composable
fun RadioScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val mediaState by ExternalMediaRepository.mediaState.collectAsState()

    val isRadioActive = mediaState.packageName.contains("radio", ignoreCase = true) ||
            mediaState.packageName.contains("fm", ignoreCase = true)

    val targetPackage = if (isRadioActive && mediaState.packageName.isNotBlank())
        mediaState.packageName else "com.android.fmradio"

    var systemFrequency by remember { mutableStateOf<Float?>(null) }
    var isScanning by remember { mutableStateOf(false) }

    // Timeout
    LaunchedEffect(isScanning) {
        if (isScanning) {
            delay(10000)
            isScanning = false
        }
    }

    LaunchedEffect(mediaState.title, mediaState.artist) {
        val rawText = "${mediaState.title} ${mediaState.artist}"
        val regex = Regex("(\\d{2,3}(?:\\.\\d)?)")
        val allMatches = regex.findAll(rawText)

        var foundMatch = false
        for (match in allMatches) {
            val parsed = match.value.toFloatOrNull()
            if (parsed != null && parsed >= 87.0f && parsed <= 108.0f) {
                foundMatch = true
                if (isScanning) {
                    if (systemFrequency == null || systemFrequency != parsed) {
                        systemFrequency = parsed
                        isScanning = false
                    }
                } else {
                    systemFrequency = parsed
                }
                break
            }
        }

        if (!foundMatch) {
            systemFrequency = null
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (!isNotificationListenerEnabled(context)) {
                Spacer(modifier = Modifier.height(16.dp))
                PermissionWarning(context)
            } else if (isRadioActive && !mediaState.title.contains("FM Radio", ignoreCase = true)) {
                Spacer(modifier = Modifier.height(8.dp))
                if (!mediaState.title.matches(Regex(".*\\d{2,3}.*"))) {
                    TextMMD(mediaState.title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = {
                    isScanning = true
                    performCommand(context, RadioCommand.PREVIOUS, targetPackage, mediaState.isRawNotification)
                }, modifier = Modifier.size(64.dp)) {
                    Icon(
                        Icons.Outlined.SkipPrevious,
                        "Scan Down",
                        modifier = Modifier.size(48.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                TextMMD(
                    text = systemFrequency?.let {
                        DecimalFormat("0.0").format(it)
                    } ?: "Unknown",
                    fontSize = 44.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if(isScanning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(onClick = {
                    isScanning = true
                    performCommand(context, RadioCommand.NEXT, targetPackage, mediaState.isRawNotification)
                }, modifier = Modifier.size(64.dp)) {
                    Icon(
                        Icons.Outlined.SkipNext,
                        "Scan Up",
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            val isPlaying = isRadioActive && mediaState.isPlaying
            val centerIcon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow

            Box(
                modifier = Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable { performCommand(context, RadioCommand.TOGGLE_POWER, targetPackage, mediaState.isRawNotification) },
                contentAlignment = Alignment.Center
            ) {
                Icon(centerIcon, "Toggle", modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }

        TextMMD("Tap to open system tuner", fontSize = 14.sp, modifier = Modifier.padding(bottom = 24.dp).clickable { launchSystemRadioApp(context, targetPackage) })
    }
}

@Composable
fun PermissionWarning(context: Context) {
    Box(modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.errorContainer).clickable {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Settings, "Settings", tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(modifier = Modifier.width(8.dp))
            TextMMD("Enable Control Permission", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
        }
    }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat != null && flat.contains(context.packageName)
}

enum class RadioCommand { NEXT, PREVIOUS, TOGGLE_POWER }

private fun performCommand(context: Context, command: RadioCommand, packageName: String, isRaw: Boolean) {
    if (isRaw) {
        when (command) {
            RadioCommand.NEXT -> { ExternalMediaRepository.skipToNext(); return }
            RadioCommand.PREVIOUS -> { ExternalMediaRepository.skipToPrevious(); return }
            RadioCommand.TOGGLE_POWER -> { ExternalMediaRepository.togglePlayPause(); return }
        }
    }

    val keyEventCode = when (command) {
        RadioCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
        RadioCommand.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        RadioCommand.TOGGLE_POWER -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    }

    if (sendTargetedMediaKey(context, keyEventCode, packageName)) return

    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyEventCode))
    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyEventCode))
}

private fun sendTargetedMediaKey(context: Context, keyCode: Int, packageName: String): Boolean {
    val pm = context.packageManager
    val queryIntent = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(packageName)
    val receivers = pm.queryBroadcastReceivers(queryIntent, 0)

    if (receivers.isNotEmpty()) {
        val receiver = receivers[0]
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
        intent.component = ComponentName(receiver.activityInfo.packageName, receiver.activityInfo.name)
        intent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        context.sendBroadcast(intent)
        intent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, keyCode))
        context.sendBroadcast(intent)
        return true
    }
    return false
}

private fun launchSystemRadioApp(context: Context, packageName: String) {
    try {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) context.startActivity(intent)
    } catch (_: Exception) { }
}