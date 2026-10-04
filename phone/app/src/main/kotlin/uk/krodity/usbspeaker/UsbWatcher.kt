package uk.krodity.usbspeaker

import android.util.Log
import kotlin.concurrent.thread

/**
 * Makes the phone behave like a speaker you simply plug in -- into anything.
 *
 * WHY POLL THE UDC INSTEAD OF LISTENING FOR A BROADCAST
 *   Android has no public broadcast meaning "a USB host just plugged me in"
 *   while acting as a peripheral. USB_DEVICE_ATTACHED is the opposite case
 *   (phone as host), and ACTION_POWER_CONNECTED cannot tell a PC from a wall
 *   charger reliably. The USB device controller's own state file is the ground
 *   truth, it needs no permission beyond the root we already have, and it works
 *   identically whatever the host is -- a PC, a console, a car stereo -- which
 *   is the whole point of presenting as a class-compliant audio device.
 *
 *     /sys/class/udc/<controller>/state
 *        "not attached"  no host
 *        "addressed"     host is enumerating us
 *        "configured"    host has accepted the device
 */
class UsbWatcher(
    private val onHostAttached: () -> Unit,
    private val onHostGone: () -> Unit,
) {
    private companion object { const val TAG = "UsbSpeaker/Watch" }

    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "usb-speaker-watch", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun loop() {
        val controller = RootShell.exec("getprop sys.usb.controller").orEmpty()
        if (controller.isBlank()) {
            Log.w(TAG, "no sys.usb.controller; watcher disabled")
            return
        }
        val path = "/sys/class/udc/$controller/state"
        var wasAttached: Boolean? = null

        while (running) {
            val state = RootShell.exec("cat $path")?.trim().orEmpty()
            val attached = state.isNotEmpty() && state != "not attached"

            if (wasAttached == null || attached != wasAttached) {
                // Skip the very first reading's callback if nothing changed
                // from the caller's point of view; otherwise every service
                // start would re-trigger a mode switch.
                if (wasAttached != null) {
                    if (attached) {
                        Log.i(TAG, "host attached (state=$state)")
                        onHostAttached()
                    } else {
                        Log.i(TAG, "host gone")
                        onHostGone()
                    }
                } else if (attached) {
                    onHostAttached()
                }
                wasAttached = attached
            }

            try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
        }
    }
}
