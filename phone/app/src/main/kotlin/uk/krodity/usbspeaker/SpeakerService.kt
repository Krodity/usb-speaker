package uk.krodity.usbspeaker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the pump alive while the phone is acting as a speaker.
 *
 * This has to be a foreground service, not a background thread: the pump owns
 * a live ALSA stream and a subprocess, and Android will freeze or kill a
 * backgrounded process holding either. (pc-remote hit exactly this -- Moto's
 * moto_freezer froze its loopback server ~35s after the app left the
 * foreground, stalling the stream with no error.) mediaPlayback is the honest
 * service type: we are, literally, playing media.
 */
class SpeakerService : Service() {

    companion object {
        const val ACTION_START = "uk.krodity.usbspeaker.START"
        const val ACTION_STOP = "uk.krodity.usbspeaker.STOP"
        private const val CHANNEL_ID = "usb_speaker"
        private const val NOTIF_ID = 1

        /** Shared so the UI reflects the same pump the service is running. */
        val pump = AudioPump()

        fun start(ctx: Context) {
            val i = Intent(ctx, SpeakerService::class.java).setAction(ACTION_START)
            ContextCompatStartForeground(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, SpeakerService::class.java).setAction(ACTION_STOP))
        }

        private fun ContextCompatStartForeground(ctx: Context, i: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Drives the whole thing off the USB cable, so the phone is a speaker the
     * moment it is plugged into anything -- with nothing installed on the host.
     *
     * Switching into audio mode re-enumerates USB, which makes the watcher see
     * a detach followed by an attach. That is harmless: the second attach finds
     * the gadget already in audio mode, so it skips straight to starting the
     * pump instead of switching again.
     */
    private val watcher by lazy {
        UsbWatcher(
            onHostAttached = {
                if (!Gadget.isAudioMode()) {
                    Gadget.setAudioMode(true)
                    // The gadget re-enumerates; the card appears a moment later.
                    Thread.sleep(2500)
                }
                if (Gadget.cardIndex() != null) pump.start()
            },
            onHostGone = { pump.stop() },
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Refresh the notification as the pump changes state, so the shade
        // shows "waiting for PC" vs "playing" without opening the app.
        scope.launch {
            combine(pump.state, pump.level) { s, _ -> s }.collect { st ->
                if (st != AudioPump.State.STOPPED) notify(st)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                watcher.stop()
                pump.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIF_ID, build(AudioPump.State.WAITING), fgType())
                // The watcher starts the pump when a host is actually present,
                // so we don't start it here -- doing both would race, and on a
                // phone with no cable in it the pump would just error out.
                watcher.start()
            }
        }
        return START_STICKY
    }

    private fun fgType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        else 0

    override fun onDestroy() {
        watcher.stop()
        pump.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(
            CHANNEL_ID, "USB Speaker", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Playing audio from the USB-connected PC" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun notify(st: AudioPump.State) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(st))
    }

    private fun build(st: AudioPump.State): Notification {
        val text = when (st) {
            AudioPump.State.RUNNING -> "Playing audio from PC"
            AudioPump.State.WAITING -> "Waiting for PC to start playback"
            AudioPump.State.ERROR -> pump.error.value ?: "Error"
            AudioPump.State.STOPPED -> "Stopped"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SpeakerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("USB Speaker")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null, "Stop", stop).build()
            )
            .setOngoing(true)
            .build()
    }
}
