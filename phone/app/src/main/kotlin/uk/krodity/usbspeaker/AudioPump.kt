package uk.krodity.usbspeaker

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.DataInputStream
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Moves audio from the USB gadget's ALSA capture stream to the phone speakers.
 *
 * WHY A SUBPROCESS AND NOT AudioRecord
 *   The UAC2 gadget registers a plain ALSA card. Android's audio HAL only
 *   surfaces the devices it knows about, so AudioRecord cannot see the gadget
 *   card at all. Reading /dev/snd/pcmC*D*c directly would mean ALSA ioctls,
 *   i.e. native code -- and there is no NDK on this machine. tinyalsa's
 *   `tinycap` is already in /system/bin on this GSI and speaks exactly that
 *   protocol, so we run it as root and read its stdout.
 *
 * WHY AudioTrack ON THE OUTPUT SIDE
 *   The obvious alternative is `tinycap | tinyplay`, staying entirely in
 *   tinyalsa. But tinyplay writes straight to a PCM device, bypassing the audio
 *   HAL -- which means the ADSP mixer paths are never set up and you get
 *   silence unless you replicate the routing by hand in tinymix, per-device.
 *   AudioTrack goes through the normal HAL, so routing, volume keys, Bluetooth
 *   and headphone switching all behave like any other app.
 */
class AudioPump {

    enum class State { STOPPED, WAITING, RUNNING, ERROR }

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state

    private val _level = MutableStateFlow(0f)
    /** Peak level 0..1 of the most recent chunk, for the meter. */
    val level: StateFlow<Float> = _level

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _framesPlayed = MutableStateFlow(0L)
    val framesPlayed: StateFlow<Long> = _framesPlayed

    private val _sampleRate = MutableStateFlow(0)
    /** Rate actually in use, resolved from the gadget at start. */
    val sampleRate: StateFlow<Int> = _sampleRate

    @Volatile private var running = false
    private var worker: Thread? = null
    private var proc: Process? = null
    private var track: AudioTrack? = null

    /** tinycap period size in frames. Smaller = less latency, more wakeups. */
    private val periodFrames = 256
    private val periodCount = 4

    fun start() {
        if (running) return
        running = true
        _error.value = null
        _state.value = State.WAITING
        worker = thread(name = "usb-speaker-pump", isDaemon = true) { supervise() }
    }

    fun stop() {
        running = false
        // Kill the capture first so the read() below unblocks, then let the
        // worker unwind and release AudioTrack on its own thread.
        proc?.destroy()
        proc = null
        // Interrupt as well: the supervisor may be in its inter-session sleep,
        // where destroying the (already dead) process wakes nothing.
        worker?.interrupt()
        worker?.join(2000)
        worker = null
        _state.value = State.STOPPED
        _level.value = 0f
    }

    /**
     * Restarts a capture session for as long as the speaker is switched on.
     *
     * tinycap exits as soon as the USB host stops streaming -- i.e. every time
     * the PC pauses a track or switches its output away. A single-shot pump
     * therefore plays exactly one burst of audio and then goes silent forever,
     * which looks exactly like a crash but isn't. A real speaker has to sit
     * there waiting for the host to come back, so each capture is one session
     * inside this loop.
     *
     * A fatal error (no gadget, AudioTrack refused) sets State.ERROR and stops
     * the loop; only a clean end-of-stream is retried.
     */
    private fun supervise() {
        var emptySessions = 0
        while (running) {
            val framesBefore = _framesPlayed.value
            session()
            if (!running) break
            if (_state.value == State.ERROR) break

            // A session that moved no audio at all means something is actually
            // wrong (gadget gone, device busy) rather than the host simply
            // pausing. Tolerate a few, then stop instead of respawning forever.
            emptySessions = if (_framesPlayed.value == framesBefore) emptySessions + 1 else 0
            if (emptySessions >= 12) {
                fail("capture kept failing to start - is the gadget still bound?")
                break
            }

            _state.value = State.WAITING
            _level.value = 0f
            // Brief pause so a gadget that is genuinely gone (cable pulled)
            // doesn't spin us in a tight respawn loop.
            try { Thread.sleep(400) } catch (_: InterruptedException) { break }
        }
        if (_state.value != State.ERROR) _state.value = State.STOPPED
    }

    private fun session() {
        try {
            val card = Gadget.cardIndex()
            if (card == null) {
                fail("USB audio gadget not bound - enable USB Speaker mode first")
                return
            }

            // Take the rate from the gadget, never a constant. The PC opens the
            // stream at whatever c_srate the gadget advertises, so hardcoding
            // 48000 while the gadget says 44100 would have tinycap capturing at
            // the wrong rate -- which does not fail loudly, it just plays back
            // at the wrong speed.
            val rate = Gadget.hostRate() ?: run {
                fail("could not read the gadget's sample rate")
                return
            }
            _sampleRate.value = rate

            val bytesPerFrame = 2 * 2 // stereo, 16-bit
            val minBuf = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                fail("AudioTrack rejected ${rate}Hz stereo 16-bit")
                return
            }

            val t = buildTrack(rate, minBuf)
            track = t
            t.play()

            // `-T` is omitted deliberately: with no duration tinycap captures
            // until killed, which is what a speaker wants.
            val cmd = "tinycap /dev/stdout -D $card -d 0 -c 2 -r $rate -b 16 " +
                "-p $periodFrames -n $periodCount"
            val p = RootShell.stream(cmd)
            proc = p

            val input = DataInputStream(p.inputStream.buffered(64 * 1024))

            // tinycap emits a 44-byte RIFF header before the PCM. It cannot
            // seek back to patch the length on a pipe, so the sizes in it are
            // garbage -- we only need to step over it.
            input.skipFully(44)

            _state.value = State.RUNNING

            val chunk = ByteArray(periodFrames * bytesPerFrame * 2)
            var frames = 0L
            while (running) {
                val n = input.read(chunk)
                if (n < 0) break
                if (n == 0) continue

                t.write(chunk, 0, n)

                frames += n / bytesPerFrame
                _framesPlayed.value = frames
                _level.value = peakOf(chunk, n)
            }

            // Falling out of the loop means tinycap ended: the host closed the
            // stream or the cable went. supervise() decides what happens next.
        } catch (e: Exception) {
            // Stream-level failures are transient by nature (the gadget can
            // disappear mid-read), so they are logged and retried rather than
            // latched as fatal. supervise() gives up if they keep happening.
            if (running) Log.w("UsbSpeaker/Pump", "capture session ended: ${e.message}")
        } finally {
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            track = null
            try { proc?.destroy() } catch (_: Exception) {}
            proc = null
            _level.value = 0f
        }
    }

    private fun buildTrack(rate: Int, minBuf: Int): AudioTrack {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val b = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            // Keep the sink shallow: the buffer depth *is* the output latency,
            // and MODE_STREAM's blocking write is what paces us against the
            // capture side. A generous buffer here would just add delay.
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        return b.build()
    }

    private fun peakOf(buf: ByteArray, n: Int): Float {
        var peak = 0
        var i = 0
        // Stride over samples rather than reading every one; the meter only
        // needs to look right, and this runs on the audio path.
        while (i + 1 < n) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            val a = abs(s)
            if (a > peak) peak = a
            i += 16
        }
        return (peak / 32768f).coerceIn(0f, 1f)
    }

    private fun fail(msg: String) {
        Log.e("UsbSpeaker/Pump", msg)
        _error.value = msg
        _state.value = State.ERROR
        running = false
    }

    /** InputStream.skip can return short; the header must be consumed exactly. */
    private fun DataInputStream.skipFully(count: Int) {
        var left = count
        while (left > 0) {
            val s = skip(left.toLong())
            if (s <= 0) {
                if (read() < 0) throw java.io.EOFException("stream closed inside WAV header")
                left--
            } else left -= s.toInt()
        }
    }
}
