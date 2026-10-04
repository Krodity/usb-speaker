package uk.krodity.usbspeaker

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Minimal root helper.
 *
 * Everything this app does needs root: the UAC2 gadget lives in ConfigFS and
 * the gadget's ALSA card is only readable by uid 0. We shell out to `su`
 * rather than pulling in a root library -- there are exactly three things we
 * need (run a command, read its output, start a long-lived stream) and a
 * library would be more surface than code.
 */
object RootShell {
    private const val TAG = "UsbSpeaker/Root"

    /**
     * Absolute path to su, resolved once.
     *
     * Do NOT just exec "su" and let PATH find it. An app process forked from
     * zygote inherits a minimal environment whose PATH does not necessarily
     * include /system/bin, so ProcessBuilder("su") fails with
     * `error=2, No such file or directory` -- which reads exactly like "this
     * device has no root" and sends you hunting through Magisk's SuList for a
     * problem that isn't there. The binary is plainly visible in the app's own
     * mount namespace; only the lookup was broken.
     */
    private val suPath: String? by lazy {
        listOf(
            "/system/bin/su",
            "/debug_ramdisk/su",
            "/sbin/su",
            "/system/xbin/su",
        ).firstOrNull { java.io.File(it).exists() }
    }

    /** Run [cmd] as root and return stdout (trimmed), or null if it failed. */
    fun exec(cmd: String): String? = try {
        val su = suPath ?: throw java.io.IOException("no su binary found")
        val p = ProcessBuilder(su, "-c", cmd)
            .redirectErrorStream(true)
            .start()
        val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
        val rc = p.waitFor()
        if (rc == 0) out.trim() else {
            Log.w(TAG, "cmd failed rc=$rc: $cmd -> $out")
            null
        }
    } catch (e: Exception) {
        Log.w(TAG, "exec failed: $cmd", e)
        null
    }

    /**
     * Start a long-running root command and hand back the live process so the
     * caller can stream its stdout.
     *
     * `exec` matters here: without it `su -c` leaves a shell between us and the
     * real process, and destroying the shell orphans the child -- tinycap would
     * keep holding the ALSA device and the next start would fail with EBUSY.
     * With `exec` the shell *becomes* tinycap, so there is exactly one pid.
     */
    fun stream(cmd: String): Process {
        val su = suPath ?: throw java.io.IOException("no su binary found")
        return ProcessBuilder(su, "-c", "exec $cmd").start()
    }

    fun isAvailable(): Boolean = exec("id -u") == "0"
}
