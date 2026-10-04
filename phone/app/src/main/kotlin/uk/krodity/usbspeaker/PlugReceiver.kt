package uk.krodity.usbspeaker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Arms the speaker without anyone opening the app.
 *
 * This only has to get SpeakerService running; UsbWatcher inside it does the
 * real work of noticing a host and switching the gadget. So both triggers here
 * are just "wake up and start watching":
 *
 *   BOOT_COMPLETED    - armed from boot, so a cold phone plugged into a PC
 *                       becomes a speaker with no interaction at all.
 *   POWER_CONNECTED   - covers the case where the service was killed (Drowser
 *                       on this phone force-kills apps when the screen goes
 *                       off) and the cable is what wakes us again.
 *
 * Deliberately opt-in via Prefs.autoStart: otherwise every wall charger would
 * start a foreground service on a phone nobody asked to be a speaker.
 */
class PlugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!Prefs.autoStart(context)) return

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_POWER_CONNECTED -> {
                Log.i("UsbSpeaker/Plug", "$action -> arming speaker service")
                SpeakerService.start(context)
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                // Leave the service running: the watcher has already stopped
                // the pump, and staying armed means re-plugging works without
                // another broadcast. Stopping here would make unplug/replug
                // cycles depend on POWER_CONNECTED arriving, which is exactly
                // the signal we stopped trusting.
            }
        }
    }
}
