package uk.krodity.usbspeaker

/**
 * Control of the USB audio gadget.
 *
 * The phone exposes itself to the PC as a USB Audio Class 2 device. We do NOT
 * hand-edit /config/usb_gadget/: android.hardware.usb@1.2-service-qti owns that
 * tree and rewrites it on every USB state change. Instead we set one property
 * and let Qualcomm's own init script do the gadget surgery atomically:
 *
 *   /vendor/etc/init/hw/init.qcom.usb.rc
 *     on property:sys.usb.config=diag,uac2,adb && property:sys.usb.configfs=1
 *         symlink ffs.adb -> configs/b.1/f2
 *         symlink uac2.0  -> configs/b.1/f3
 *         write /config/usb_gadget/g1/UDC ${sys.usb.controller}
 *
 * The combo keeps adb, so a dev connection survives the switch.
 *
 * We only ever set sys.usb.config (runtime), never persist.sys.usb.config.
 * sys.usb.config is not persisted, so a reboot always restores plain adb --
 * this app can never permanently strand the USB port.
 */
object Gadget {

    const val MODE_AUDIO = "diag,uac2,adb"
    const val MODE_NORMAL = "adb"

    /** ALSA card name the f_uac2 function registers on the phone side. */
    private const val CARD_NAME = "UAC2Gadget"

    fun currentMode(): String = RootShell.exec("getprop sys.usb.config") ?: "?"

    fun isAudioMode(): Boolean = currentMode() == MODE_AUDIO

    fun setAudioMode(enable: Boolean) {
        val mode = if (enable) MODE_AUDIO else MODE_NORMAL
        // This tears down USB underneath us, so the command's own pipe may die.
        // That is expected -- don't treat a null return as failure.
        RootShell.exec("setprop sys.usb.config $mode")
    }

    /**
     * Index of the gadget's ALSA card, or null if the gadget isn't bound.
     *
     * /proc/asound/cards looks like:
     *    0 [lahainahhgsndca]: lahaina-hhg-snd - lahaina-hhg-snd-card
     *    1 [UAC2Gadget     ]: UAC2_Gadget - UAC2_Gadget
     *
     * The index is NOT stable -- it depends on bind order -- so always resolve
     * it by name rather than hardcoding 1.
     */
    fun cardIndex(): Int? {
        val cards = RootShell.exec("cat /proc/asound/cards") ?: return null
        for (line in cards.lines()) {
            if (line.contains(CARD_NAME, ignoreCase = true)) {
                return line.trim().substringBefore(' ').toIntOrNull()
            }
        }
        return null
    }

    /**
     * Whether a USB host has enumerated and configured the gadget, i.e. the
     * cable is in and the PC has accepted the audio device.
     *
     * Note there is deliberately no "is the host streaming?" check. The gadget
     * card exposes only `id` and `state` under /proc/asound/card<N>/ -- it has
     * no per-PCM `status` file like a normal ALSA card does, so there is no way
     * to see from here whether the PC currently has the stream open. The pump
     * infers that instead: it blocks reading tinycap until bytes actually
     * arrive, which is the same question answered honestly.
     */
    fun isUsbConfigured(): Boolean {
        val controller = RootShell.exec("getprop sys.usb.controller") ?: return false
        val st = RootShell.exec("cat /sys/class/udc/$controller/state") ?: return false
        return st.trim() == "configured"
    }

    /** Rates offered in the UI. f_uac2 takes a single value on this kernel. */
    val RATES = listOf(44100, 48000, 96000)

    /**
     * Set the rate the gadget advertises for the host->phone direction.
     *
     * In f_uac2 the c_* attributes are the direction where the *host plays and
     * the gadget captures*. Verified against /proc/asound/cardN/stream0 on the
     * PC, which lists "Playback ... Rates: 44100" (= c_srate) and a separate
     * Capture at 48000 (= p_srate, the phone-as-microphone direction this app
     * does not use).
     *
     * Two traps make this more than a single write:
     *
     *  1. ConfigFS refuses to write f_uac2's attributes while the function is
     *     linked into a config, so the symlink has to come out and the UDC has
     *     to be unbound first. Writing without that fails with rc=1 and leaves
     *     the old rate in place.
     *
     *  2. Android's init reacts only to a *change* of sys.usb.config. Setting
     *     it to the value it already holds does nothing, silently leaving the
     *     property and the real ConfigFS layout disagreeing -- which then looks
     *     like "the gadget ignored me". Hence the transition via a throwaway
     *     value before asking for the mode we actually want.
     *
     * USB re-enumerates partway through; that does not affect this process, but
     * it does drop any adb session, and the pump must be stopped first because
     * its capture device disappears.
     */
    fun setHostRate(rate: Int): Boolean {
        val g = "/config/usb_gadget/g1"
        val f = "$g/functions/uac2.0"

        // Do the whole thing ourselves rather than asking init to rebuild.
        //
        // Going via `setprop sys.usb.config adb` looks safer -- init owns this
        // tree -- but it does not reliably unlink uac2, so the c_srate write
        // still hits EBUSY and fails *silently*, leaving the old rate. And
        // init's rebuild rule is
        //     on property:sys.usb.ffs.ready=1 && property:sys.usb.config=...
        // which needs BOTH conditions, so if ffs.ready is 0 when we ask, init
        // does nothing at all and whoever unbound the UDC has just left the
        // phone presenting no USB device whatsoever.
        //
        // Unlinking and rebinding by hand is deterministic and avoids that
        // condition entirely: the final `echo $C > UDC` is unconditional, so
        // USB always comes back even if a step in the middle failed.
        val script = listOf(
            "C=\$(getprop sys.usb.controller)",
            // Remember the slot uac2 occupies so we put it back where init had
            // it; the ordering of f1..fN is what the host sees as interfaces.
            "L=\$(for l in $g/configs/b.1/f*; do [ -L \"\$l\" ] && " +
                "case \"\$(readlink \"\$l\")\" in *uac2*) echo \"\$l\";; esac; done)",
            "[ -z \"\$L\" ] && L=$g/configs/b.1/f3",
            "echo '' > $g/UDC",
            "rm -f \"\$L\"",
            "echo $rate > $f/c_srate",
            "echo 3 > $f/c_chmask",
            "echo 2 > $f/c_ssize",
            "ln -s $f \"\$L\"",
            // Unconditional: this is what guarantees we never leave USB dead.
            "echo \"\$C\" > $g/UDC",
            "cat $f/c_srate",
        ).joinToString("; ")

        val out = RootShell.exec(script)
        return out?.trim()?.lines()?.lastOrNull()?.trim() == rate.toString()
    }

    fun hostRate(): Int? =
        RootShell.exec("cat /config/usb_gadget/g1/functions/uac2.0/c_srate")?.toIntOrNull()
}
