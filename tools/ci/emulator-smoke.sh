#!/usr/bin/env bash
# Stage 1 smoke test on an emulator: FGS start, screen off, process kill + sticky restart, pause/private/stop.
# Output: build/smoke/logcat.txt and a summary in $GITHUB_STEP_SUMMARY.
set -uo pipefail
PKG=app.basis.diary.debug
OUT=build/smoke
mkdir -p "$OUT"
API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
cmd() { adb shell am start -W -n "$PKG/app.basis.DebugCommandActivity" --es cmd "$1" >/dev/null; }
svc() { adb shell dumpsys activity services "$PKG" | grep -E "ServiceRecord|isForeground|foregroundServiceType" | head -5; }
step() { echo; echo "=== $*"; echo "=== $*" >> "$OUT/steps.txt"; }

adb root >/dev/null 2>&1; sleep 2
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell dumpsys deviceidle whitelist +$PKG >/dev/null
adb logcat -c

step "download VAD model"
cmd download-models; sleep 15

step "start recording"
cmd start; sleep 15; svc | tee -a "$OUT/steps.txt"

step "screen off for 70 s"
adb shell input keyevent KEYCODE_SLEEP; sleep 70; svc | tee -a "$OUT/steps.txt"
adb shell input keyevent KEYCODE_WAKEUP

step "pause / resume"
cmd pause; sleep 3; cmd resume; sleep 5

step "private mode 1 min (should resume by itself)"
cmd private:1; sleep 70; svc | tee -a "$OUT/steps.txt"

step "kill process (simulates OEM kill / LMK)"
PID=$(adb shell pidof $PKG | tr -d '\r'); echo "pid=$PID"
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell kill -9 "$PID"; sleep 30
echo "new pid: $(adb shell pidof $PKG | tr -d '\r')" | tee -a "$OUT/steps.txt"
svc | tee -a "$OUT/steps.txt"

step "reopen app (restores recording from foreground if it was blocked)"
adb shell am start -W -n "$PKG/app.basis.MainActivity" >/dev/null; sleep 10; svc | tee -a "$OUT/steps.txt"

step "stop"
cmd stop; sleep 5; svc | tee -a "$OUT/steps.txt"

step "ASR end-to-end: GigaAM v3 download + example.wav through the encrypted buffer"
wait_log() { # pattern timeout_s
  for _ in $(seq 1 "$2"); do adb logcat -d | grep -q "$1" && return 0; sleep 1; done; return 1; }
cmd download:gigaam-v3-rnnt
if wait_log "GigaAM v3 (русский): готова" 300; then echo "model ready" | tee -a "$OUT/steps.txt"; else echo "model NOT ready" | tee -a "$OUT/steps.txt"; fi
if [ -f build/asr/example.wav ]; then
  # Apps can't read /data/local/tmp (SELinux): copy into the app's own files dir via run-as.
  adb exec-in run-as $PKG sh -c 'cat > files/example.wav' < build/asr/example.wav
  cmd "inject-wav:/data/user/0/$PKG/files/example.wav"; sleep 2
  cmd transcribe
  if wait_log "Basis/ASR.*распознано" 240; then echo "transcribed" | tee -a "$OUT/steps.txt"; else echo "NOT transcribed" | tee -a "$OUT/steps.txt"; fi
  cmd dump-transcripts; sleep 2
  echo "audio files left in app storage:" | tee -a "$OUT/steps.txt"
  adb shell run-as $PKG find . -name '*.bseg*' -o -name '*.wav' -o -name '*.pcm' 2>&1 | tee -a "$OUT/steps.txt"
fi

# logcat filterspecs don't support tag wildcards: dump everything, grep ours.
adb logcat -d -v time > "$OUT/logcat-all.txt"
grep -E "Basis/|AndroidRuntime|ForegroundService|basis.diary" "$OUT/logcat-all.txt" > "$OUT/logcat.txt"
adb shell dumpsys notification --noredact | grep -A3 "$PKG" > "$OUT/notifications.txt" || true

SUMMARY="$OUT/summary.md"
{
  echo "### Emulator API $API"
  echo '```'
  cat "$OUT/steps.txt"
  echo '--- Basis log (grep) ---'
  grep -E "Basis/" "$OUT/logcat.txt" | cut -c1-240 | tail -120
  echo '```'
} > "$SUMMARY"
cat "$SUMMARY"
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && cat "$SUMMARY" >> "$GITHUB_STEP_SUMMARY"

# Fail on crashes only; behavioural findings are reported, not asserted, at this stage.
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then echo "crash detected"; exit 1; fi
