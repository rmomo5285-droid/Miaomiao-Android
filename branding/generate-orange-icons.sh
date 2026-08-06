#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
source_icon="$repo_root/branding/orange-icon.png"
res="$repo_root/V2rayNG/app/src/main/res"

command -v convert >/dev/null
test -f "$source_icon"

densities=(mdpi hdpi xhdpi xxhdpi xxxhdpi)
legacy_sizes=(48 72 96 144 192)
foreground_sizes=(108 162 216 324 432)
safe_sizes=(56 84 112 168 224)

for i in "${!densities[@]}"; do
  density=${densities[$i]}
  legacy=${legacy_sizes[$i]}
  foreground=${foreground_sizes[$i]}
  safe=${safe_sizes[$i]}
  target="$res/mipmap-$density"

  convert -size "${legacy}x${legacy}" 'xc:#101216' \
    \( "$source_icon" -resize "$((legacy * 65 / 100))x$((legacy * 65 / 100))" \) \
    -gravity center -composite -strip "$target/ic_launcher.png"
  cp "$target/ic_launcher.png" "$target/ic_launcher_round.png"
  convert -size "${foreground}x${foreground}" xc:none \
    \( "$source_icon" -resize "${safe}x${safe}" \) \
    -gravity center -composite -strip "$target/ic_launcher_foreground.png"
done
