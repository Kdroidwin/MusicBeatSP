package com.music.bitchord.radio.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class RadioRecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getStringExtra(RadioAutoRecordingScheduler.EXTRA_RECORDING_ID) ?: return
        if (RadioAutoRecordingStore.recording(context, id) == null) return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RadioAutoRecordingService::class.java)
                    .setAction(RadioRecordingAlarmReceiver.ACTION_START)
                    .putExtra(RadioAutoRecordingScheduler.EXTRA_RECORDING_ID, id),
            )
        }
    }

    companion object {
        const val ACTION_START = "com.music.bitchord.radio.START_RECORDING"
    }
}

class RadioRecordingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            RadioAutoRecordingScheduler.rescheduleAfterBoot(context)
        }
    }
}
