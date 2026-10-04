#!/system/bin/sh
# set-gadget-rate.sh <rate> -- change the rate the UAC2 gadget advertises.
#
# Runs ON THE PHONE. USB re-enumerates partway through, so an adb session
# driving it will drop; launch detached (`nohup setsid sh ... &`) and reconnect.
# Progress goes to $LOG.
#
# WHY THIS DOES THE CONFIGFS WORK ITSELF
#
#   The tidy-looking approach is to let init rebuild the gadget:
#     setprop sys.usb.config adb            # init rebuilds b.1 without uac2
#     echo <rate> > .../uac2.0/c_srate
#     setprop sys.usb.config diag,uac2,adb  # init puts it back
#
#   Two things break that, and both fail silently:
#
#   1. Dropping to adb-only does NOT reliably unlink uac2, so the write still
#      hits EBUSY and the old rate survives with no error anywhere.
#
#   2. Init's rule is
#        on property:sys.usb.ffs.ready=1 && property:sys.usb.config=diag,uac2,adb
#      which needs BOTH conditions. If ffs.ready is 0 at that moment, init does
#      nothing -- and if anything already unbound the UDC, the phone is left
#      presenting NO usb device at all: no adb, no audio, reboot to recover.
#      (This happened during development.)
#
#   Unlinking and rebinding by hand is deterministic and sidesteps the
#   ffs.ready condition entirely. The final bind is unconditional, so USB comes
#   back even if a step in the middle fails.
set -u

RATE="${1:-48000}"
G=/config/usb_gadget/g1
F=$G/functions/uac2.0
LOG=/data/local/tmp/set-gadget-rate.log

exec >"$LOG" 2>&1
echo "=== set-gadget-rate $RATE at $(date) ==="

C=$(getprop sys.usb.controller)
# Keep uac2 in the slot init gave it: f1..fN ordering is what the host sees as
# interface numbering.
L=$(for l in $G/configs/b.1/f*; do
      [ -L "$l" ] && case "$(readlink "$l")" in *uac2*) echo "$l";; esac
    done)
[ -z "$L" ] && L=$G/configs/b.1/f3

echo "-- controller=$C uac2_link=$L before=$(cat $F/c_srate)"

echo '' > $G/UDC
rm -f "$L"

if echo "$RATE" > $F/c_srate 2>/dev/null; then
  echo "-- wrote c_srate=$RATE"
else
  echo "-- FAILED to write c_srate (still $(cat $F/c_srate))"
fi
echo 3 > $F/c_chmask 2>/dev/null
echo 2 > $F/c_ssize  2>/dev/null

ln -s $F "$L"
# Unconditional: this is what guarantees USB never stays dead.
echo "$C" > $G/UDC
sleep 1

echo "-- after: c_srate=$(cat $F/c_srate) UDC=[$(cat $G/UDC)] state=$(getprop sys.usb.state)"
ls -l $G/configs/b.1/ | grep '^l'
echo "=== done ==="
