# USB Speaker

Turns a rooted Android phone into a **real USB sound card**. This isn't a
network stream. Over USB the phone shows up as a USB Audio Class 2 (UAC2)
device, so the computer sees an ordinary audio output you can pick in any
mixer. **Nothing has to be installed on the computer.**

```
PC  ──USB──▶  f_uac2 gadget  ──▶  ALSA card "UAC2Gadget"  ──▶  tinycap (root)
                                                                  │
                                              AudioTrack  ◀───────┘
                                                   │
                                     phone speakers / headphones / BT
```

Built and verified on a **Razer Edge WiFi** (Qualcomm SoC, Evolution X GSI,
Kitsune Magisk). Other Qualcomm phones whose vendor init ships the same
`diag,uac2,adb` USB combo should work too, but none have been tested.

---

## Contents

1. [Requirements](#requirements)
2. [Quick start](#quick-start)
3. [The app](#the-app)
4. [Host helpers (optional)](#host-helpers-optional)
5. [How it works](#how-it-works)
6. [Building](#building)
7. [Layout](#layout)
8. [Gotchas](#gotchas)
9. [Verified](#verified)

---

## Requirements

**Phone**

- **Root** (Magisk / KernelSU). Only root can reconfigure the USB gadget.
- A kernel with `CONFIG_USB_F_UAC2=y` and `CONFIG_USB_CONFIGFS_F_UAC2=y`.
  Check with `adb shell su -c 'zcat /proc/config.gz | grep UAC2'`.
- A vendor `init.*.usb.rc` that defines a `uac2` USB combo. Qualcomm's
  `init.qcom.usb.rc` ships `diag,uac2,adb`.
- `/system/bin/tinycap`. It's part of AOSP's tinyalsa and present on most
  builds.
- Android 8.0+ (API 26).

**Host**: anything with USB Audio Class 2 drivers: Windows 10+, macOS, Linux,
Android in host mode, PS4/PS5, most car head units. Hosts that only support
**UAC1** won't work.

## Quick start

1. Build and install the app (see [Building](#building)), then open
   **USB Speaker** and grant root when Magisk asks.
2. Tap **Enable USB audio mode**. USB reconnects, and the computer now sees a
   new sound card named after the phone.
3. Tap **Start speaker**.
4. On the computer, pick the phone as the output device and play something.

To make it work like a plain plug-in speaker, turn on **Start when plugged
in**. The service then stays armed (including after a reboot, via
`BOOT_COMPLETED`). As soon as the phone sees a USB host, it switches the gadget
into audio mode and starts playing by itself.

To go back to normal: **Disable USB audio mode**, or just reboot. The audio
mode is never saved across reboots.

## The app

| Item | Meaning |
|---|---|
| **USB gadget** | `audio mode`, or the phone's normal USB function list (e.g. `adb`). The *Enable/Disable USB audio mode* button switches between them |
| **USB link** | Controller state from `/sys/class/udc/*/state` (`configured` = a host is attached and has set up the device, otherwise `not connected`) |
| **Gadget card** | The ALSA card the gadget registered, e.g. `hw:1,0`, or `not bound` |
| **Host rate / Sample rate** | Rate advertised to the host, 44.1 or 48 kHz. Changing it reconnects USB, and the host may re-pick the device |
| **Pump** | `Stopped`, `Waiting for PC to start playback`, `playing`, or `Error` with a reason |
| **Level** | Live peak meter of the incoming audio |
| **Start / Stop speaker** | Runs the foreground service that pumps audio |
| **Start when plugged in** | Arms the service to switch modes on connect, and on boot |

While it's playing, a persistent notification ("Playing audio from the
USB-connected PC") keeps the service alive. Volume keys, headphone jack and
Bluetooth routing all work as they normally do, because audio goes out through
`AudioTrack`.

## Host helpers (optional)

None of these are needed. They're conveniences for a Linux/PipeWire host.

```bash
# Keep adb working while in audio mode (the combo re-enumerates as 05c6:90ca)
sudo cp packaging/99-android-uac2.rules /etc/udev/rules.d/
sudo udevadm control --reload

# Switch the gadget from the PC over adb instead of from the app
host/usb-speaker-mode on     [adb-serial]   # phone becomes a USB sound card
host/usb-speaker-mode status [adb-serial]
host/usb-speaker-mode off    [adb-serial]   # back to plain adb
host/usb-speaker-mode recover [adb-serial]  # force-rebuild a wedged gadget

# Make the phone the default output whenever it's plugged in, and put the
# old default back when it leaves
install -Dm755 host/usb-speaker-autoswitch ~/.local/bin/
install -Dm644 packaging/usb-speaker-autoswitch.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now usb-speaker-autoswitch
```

`usb-speaker-autoswitch` matches the PipeWire sink by name (default
`Razer_Edge`). For another phone, set `USB_SPEAKER_MATCH` to part of your
phone's USB product string, e.g. with
`systemctl --user edit usb-speaker-autoswitch` → `Environment=USB_SPEAKER_MATCH=Pixel`.
It also raises the new sink to 100%, because PipeWire gives a USB sink it
hasn't seen before about 40%.

## How it works

**The USB half.** Qualcomm's `init.qcom.usb.rc` already has a gadget combo
`diag,uac2,adb`. Setting one runtime property (`sys.usb.config`) makes
**init** rebuild the gadget in one step and rebind the UDC. The combo keeps
`adb`, so a dev connection survives the switch.

`/config/usb_gadget/` is deliberately **not** hand-edited:
`android.hardware.usb@1.2-service-qti` owns that tree and rewrites it on every
USB state change. (The one exception is changing the sample rate. See
[Gotchas](#gotchas).)

**The audio half.** The gadget registers a plain ALSA card on the phone, and
two things follow from that:

- Android's audio HAL only exposes devices it knows about, so `AudioRecord`
  can't see the gadget card. Reading `/dev/snd/*` directly takes ALSA ioctls,
  i.e. native code. Instead the app runs `/system/bin/tinycap` as root and
  reads its stdout, so no NDK is needed.
- The obvious counterpart, `tinycap | tinyplay`, would bypass the audio HAL.
  The DSP mixer paths would never get set up, and you'd hear silence unless
  you set up the routing by hand in `tinymix`. Feeding **`AudioTrack`**
  instead means routing, volume keys, Bluetooth and headphone switching all
  behave normally.

**Staying armed.** `UsbWatcher` polls `/sys/class/udc/<controller>/state` to
spot a host. `PlugReceiver` re-arms the service at boot. `AudioPump.supervise()`
starts a new capture session each time the host starts playing, because
`tinycap` exits when the host stops streaming.

## Building

Requirements: JDK 17+ and the Android SDK with platform 37.

```bash
cd phone
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/usb-speaker-1.0.0-debug.apk
```

Then grant it root in your root manager. On **Kitsune Magisk with SuList
enabled**, also add `uk.krodity.usbspeaker` to the SuList and reboot (see
[Gotchas](#gotchas)).

## Layout

| Path | What |
|---|---|
| `phone/` | Android app (Kotlin/Compose, `uk.krodity.usbspeaker`) |
| `phone/.../Gadget.kt` | Reads and switches gadget state, changes the rate |
| `phone/.../AudioPump.kt` | `tinycap` → `AudioTrack`, with the supervisor loop |
| `phone/.../UsbWatcher.kt`, `PlugReceiver.kt` | Auto-arm on connect / boot |
| `phone/.../RootShell.kt` | `su` wrapper (absolute path, see Gotchas) |
| `host/usb-speaker-mode` | Switch the gadget between normal adb and audio mode from the PC |
| `host/usb-speaker-autoswitch` | Make the phone the default PipeWire sink while it's present |
| `host/set-gadget-rate.sh` | Standalone rate change (runs on the phone) |
| `packaging/99-android-uac2.rules` | udev rule so adb survives audio mode |
| `packaging/usb-speaker-autoswitch.service` | systemd user unit for the autoswitch |

## Gotchas

- **The audio combo re-enumerates under Qualcomm's ids `05c6:90ca`**, not
  Google's `18d1:4ee7`. Without `99-android-uac2.rules`, adb comes back as
  *no permissions*.

- **In audio mode there's no MTP/file transfer.** The combo is
  `diag,uac2,adb`, and it has no MTP function.

- **48 kHz is the sweet spot.** 96 kHz enumerates but showed underruns and
  overruns on the test link.

- **`persist.sys.usb.config` is never touched.** Only the runtime
  `sys.usb.config` gets set, and it isn't saved, so **a reboot always brings
  back plain adb**. That's the way out if anything goes wrong.

- **Init only reacts when `sys.usb.config` *changes*.** Setting it to the
  value it already has does nothing, and the property can end up disagreeing
  with the real ConfigFS layout. Don't trust it to tell you what the gadget is
  actually doing. Read `configs/b.1/` instead.

- **🔴 Changing the rate: do the ConfigFS work yourself, and always rebind.**
  The tidy approach (drop to `sys.usb.config=adb` so init rebuilds without
  uac2, write `c_srate`, switch back) fails two ways, both silent:

  1. Dropping to adb-only does **not** reliably unlink uac2, so the write hits
     EBUSY and the old rate stays, with no error anywhere.
  2. Init's rule is `on property:sys.usb.ffs.ready=1 && property:sys.usb.config=…`
     and needs **both** properties. If `ffs.ready` is 0 at that moment, init
     does nothing. If something already unbound the UDC, the phone then shows
     up as **no USB device at all**: no adb, no audio. A reboot fixes it.

  The working sequence unbinds the UDC, removes the uac2 symlink, writes the
  rate, re-links it, and **always rebinds**, without waiting on `ffs.ready`.
  See `host/set-gadget-rate.sh` and `Gadget.setHostRate`.

- **`su` has to be called by its absolute path.** An app process forked from
  zygote has a minimal `PATH` that may not include `/system/bin`, so
  `ProcessBuilder("su")` fails with `error=2, No such file or directory`. That
  looks exactly like "no root on this device".

- **Kitsune's SuList is a whitelist.** With `sulist=1`, Magisk is unmounted
  from every app that isn't on the list, so `/system/bin/su` really doesn't
  exist in the app's namespace. Add the package to the `sulist` table (the
  `policies` table has **no** `package_name` column, and a REPLACE that names
  one fails silently), then **reboot**. The daemon caches the list.

- **`tinycap` exits when the host stops streaming.** A single-shot pump would
  play one burst and then go silent forever, which looks exactly like a crash.
  That's why the supervisor exists.

- **Battery/app killers** (Greenify, Drowser, aggressive OEM battery savers)
  can kill the foreground service mid-stream when the screen turns off.
  Exclude the app from them.

## Verified

- The host sees `05c6:90ca Qualcomm, Inc. Razer Edge 5G` and gets a PipeWire
  sink `alsa_output.usb-Razer_Razer_Edge_5G_<serial>-02.analog-stereo`.
- A 440 Hz tone played on the PC arrives in the phone's gadget capture stream
  at exactly 440 Hz.
- The app reports `audio mode` / `hw:1,0` / `configured`, pump `playing`, and
  a live level meter. AudioFlinger shows the track out of standby with
  `Fifo frame underruns: none`, `Errors: 0`.
- Switching the rate between 44100 and 48000 was confirmed on both ends:
  under `/proc/asound/cardN/stream0`, the Playback `Rates:` line follows
  `c_srate`.

### Direction note

In `f_uac2`, the `c_*` attributes are the **host-plays / gadget-captures**
direction. This was confirmed on the PC via `/proc/asound/cardN/stream0`,
which listed `Playback ... Rates: 44100` (= `c_srate`) alongside a Capture at
48000 (= `p_srate`, the phone-as-microphone direction, which this project
doesn't use).

## License

MIT. See [LICENSE](LICENSE).
