#!/usr/bin/env bash
# Glass ONN v0.7.2: regenerate short, balanced weather cues from the originals.
# Originals remain unchanged in the Git repository; transforms happen in CI only.
set -euo pipefail
command -v ffmpeg >/dev/null || { echo "ffmpeg required for balanced sound"; exit 1; }
root="app/src/main/assets/nature"
test -f "$root/zen_clear.ogg"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
render() {
  local key="$1" start="$2" duration="$3" target="$4" filters="$5"
  local fadeout
  fadeout="$(awk -v d="$duration" 'BEGIN {printf "%.2f",d-1.3}')"
  ffmpeg -nostdin -hide_banner -loglevel error -y \
    -ss "$start" -i "$root/zen_$key.ogg" -t "$duration" \
    -af "$filters,loudnorm=I=$target:TP=-7:LRA=6,afade=t=in:st=0:d=0.22,afade=t=out:st=$fadeout:d=1.3" \
    -ar 24000 -ac 2 -c:a libvorbis -q:a 3 "$temp/zen_$key.ogg"
  test "$(stat -c%s "$temp/zen_$key.ogg")" -gt 5000
  test "$(head -c4 "$temp/zen_$key.ogg")" = "OggS"
  mv "$temp/zen_$key.ogg" "$root/zen_$key.ogg"
  echo "Balanced $key -> $(stat -c%s "$root/zen_$key.ogg") bytes"
}
render clear 2 6 -25 "highpass=f=400,lowpass=f=8000"
render cloud 2 7 -27 "highpass=f=100,lowpass=f=5200"
render rain 1 6 -30 "highpass=f=100,lowpass=f=3500"
# Original recording has its thunder around 3 seconds. Trim near its onset
# so the audible thunder follows the drawn lightning, not 3 seconds later.
render storm 2.8 5 -31 "highpass=f=60,lowpass=f=2500,acompressor=threshold=0.08:ratio=2:attack=100:release=450"
