#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
source_icon="$repo_root/branding/orange-icon.png"
res="$repo_root/V2rayNG/app/src/main/res"

expected_source_sha256=b6045af66e2e765643a50ac4871d388a9004e90dea93046696ac742ff8bf2e23
printf '%s  %s\n' "$expected_source_sha256" "$source_icon" | sha256sum --check --strict

densities=(mdpi hdpi xhdpi xxhdpi xxxhdpi)
legacy_sizes=(48 72 96 144 192)
foreground_sizes=(108 162 216 324 432)

for i in "${!densities[@]}"; do
  density=${densities[$i]}
  legacy=${legacy_sizes[$i]}
  foreground=${foreground_sizes[$i]}
  target="$res/mipmap-$density"
  file "$target/ic_launcher.png" | grep -Fq "${legacy} x ${legacy}"
  file "$target/ic_launcher_round.png" | grep -Fq "${legacy} x ${legacy}"
  file "$target/ic_launcher_foreground.png" | grep -Fq "${foreground} x ${foreground}"
done

cmp "$res/mipmap-mdpi/ic_launcher.png" "$res/mipmap-mdpi/ic_launcher_round.png"
