package uk.krodity.usbspeaker

import android.content.Context

/**
 * Tiny settings store.
 *
 * SharedPreferences rather than DataStore on purpose: PlugReceiver needs to
 * read this synchronously inside onReceive, where suspending on a Flow would
 * mean either blocking the main thread or letting the broadcast return before
 * the value arrives.
 */
object Prefs {
    private const val FILE = "usb_speaker"
    private const val KEY_AUTOSTART = "auto_start"

    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun autoStart(ctx: Context): Boolean = p(ctx).getBoolean(KEY_AUTOSTART, false)

    fun setAutoStart(ctx: Context, v: Boolean) =
        p(ctx).edit().putBoolean(KEY_AUTOSTART, v).apply()
}
