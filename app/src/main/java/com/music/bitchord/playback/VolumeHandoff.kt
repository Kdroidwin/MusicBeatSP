package com.music.bitchord.playback

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.EqualizerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One media volume for MusicBeatSP, another for every other app.
 *
 * Android has a single media volume for all apps. Levelling by attenuation
 * (MusicBeatSP turns loud songs down) wants that volume high, which then blasts
 * the next app. So, per output device:
 *
 * - When MusicBeatSP starts making sound, the volume of the moment is remembered as
 *   "the other apps' volume", and MusicBeatSP's own volume is put back.
 * - When it stops making sound (pause, another app taking the audio, MusicBeatSP
 *   closing), the volume of the moment is remembered as MusicBeatSP's, and the
 *   other apps' volume is put back.
 * - When the output is about to go (headphones out, Bluetooth leaving), the
 *   same, before the route changes, so the device keeps a safe volume.
 * - When a device connects while MusicBeatSP is not making sound, it is turned down
 *   to the other apps' volume — never up — in case it had kept MusicBeatSP's.
 *
 * "Making sound" means actually playing: paused, or merely open in the
 * background, counts as not.
 */
object VolumeHandoff {
    private const val TAG = "MusicBeatSPVolume"
    private const val PREFS = "volume_handoff"

    /** True while MusicBeatSP is playing (read by the connect receiver too). */
    @Volatile
    var appAudible: Boolean = false
        private set

    /** The output MusicBeatSP started playing on; its levels are the ones to update. */
    private var startKey: String? = null

    /** The output already got its other-apps volume as it was leaving. */
    private var handedOffOnLeave = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun enabled(): Boolean = AppSettings.volumeHandoff.value

    private fun setVolume(manager: AudioManager, level: Int) {
        runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) }
            .onFailure { Log.w(TAG, "could not set the volume to $level", it) }
    }

    /** MusicBeatSP has started or stopped making sound. */
    fun update(context: Context, audible: Boolean) {
        if (!enabled()) {
            disable(context)
            return
        }
        if (audible == appAudible) return
        appAudible = audible
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val key = EqualizerSettings.currentOutputKey(context)
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val store = prefs(context)
        if (audible) {
            handedOffOnLeave = false
            startKey = key
            val tinque = store.getInt("t|$key", -1)
            if (current != tinque) store.edit().putInt("o|$key", current).apply()
            if (tinque >= 0 && tinque != current) {
                Log.d(TAG, "playing on $key: $current -> $tinque")
                setVolume(manager, tinque)
            }
        } else {
            val started = startKey
            startKey = null
            // Already handed off as the device left; and the volume now is
            // another device's, not MusicBeatSP's level for this one.
            if (handedOffOnLeave || started == null || started != key) return
            store.edit().putInt("t|$key", current).apply()
            val others = store.getInt("o|$key", -1)
            if (others >= 0 && others != current) {
                Log.d(TAG, "stopped on $key: $current -> $others")
                setVolume(manager, others)
            }
        }
    }

    /** Stops managing volume without changing the level currently in use. */
    fun disable(context: Context) {
        if (appAudible) {
            val key = startKey
            val manager = context.getSystemService(AudioManager::class.java)
            if (key != null && manager != null) {
                prefs(context).edit()
                    .putInt("t|$key", manager.getStreamVolume(AudioManager.STREAM_MUSIC))
                    .apply()
            }
        }
        appAudible = false
        startKey = null
        handedOffOnLeave = false
    }

    /** The output is about to change (headphones out, Bluetooth going). */
    fun onBecomingNoisy(context: Context) {
        if (!appAudible || !enabled()) return
        val key = startKey ?: return
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val store = prefs(context)
        store.edit().putInt("t|$key", current).apply()
        val others = store.getInt("o|$key", -1)
        if (others >= 0 && others < current) {
            Log.d(TAG, "leaving $key: $current -> $others")
            setVolume(manager, others)
        }
        handedOffOnLeave = true
    }

    /**
     * An output has connected. If MusicBeatSP is not playing and the device came
     * back at more than the other apps' volume, turn it down to that.
     */
    fun onOutputConnected(context: Context) {
        if (appAudible || !enabled()) return
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val key = EqualizerSettings.currentOutputKey(context)
        val others = prefs(context).getInt("o|$key", -1)
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (others in 0 until current) {
            Log.d(TAG, "connected $key at $current: down to $others")
            setVolume(manager, others)
        }
    }

    /** Whether the system tells MusicBeatSP about Bluetooth connections while it is closed. */
    fun canHearConnections(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}

/**
 * Hears Bluetooth audio devices connecting, even with MusicBeatSP closed, and has
 * [VolumeHandoff] turn a device that came back loud down to the other apps'
 * volume. Waits for the audio to actually route there first.
 */
class VolumeHandoffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val connected = when (intent.action) {
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED ->
                intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            else -> false
        }
        if (!connected) return
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                // The route moves to the device a moment after it connects.
                repeat(16) {
                    if (EqualizerSettings.currentOutputDevice(app)?.isBluetooth() == true) {
                        VolumeHandoff.onOutputConnected(app)
                        return@launch
                    }
                    delay(250)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun AudioDeviceInfo.isBluetooth(): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER))

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
