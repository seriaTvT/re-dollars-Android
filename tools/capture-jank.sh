#!/usr/bin/env bash
# Capture a Perfetto trace + gfxinfo stats while you reproduce scroll jank.
#
# Usage: tools/capture-jank.sh [seconds]   (default 20)
# Scroll the way that janks while it records; the trace lands next to this
# script as jank-<timestamp>.pftrace — open it at https://ui.perfetto.dev
# or hand it to Claude for analysis.

set -euo pipefail
PKG=mk.ry.redollars
DUR_S=${1:-20}
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
STAMP=$(date +%Y%m%d-%H%M%S)
OUT="$OUT_DIR/jank-$STAMP.pftrace"

CFG=$(mktemp)
cat > "$CFG" <<EOF
buffers: { size_kb: 65536 fill_policy: RING_BUFFER }
duration_ms: $((DUR_S * 1000))
data_sources: {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "power/cpu_frequency"
      ftrace_events: "power/cpu_idle"
      atrace_categories: "gfx"
      atrace_categories: "view"
      atrace_categories: "input"
      atrace_categories: "sched"
      atrace_categories: "freq"
      atrace_apps: "$PKG"
    }
  }
}
data_sources: { config { name: "linux.process_stats" } }
data_sources: { config { name: "android.surfaceflinger.frametimeline" } }
EOF

adb push "$CFG" /data/local/tmp/jank.cfg >/dev/null
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null

echo ">>> Recording for ${DUR_S}s — reproduce the janky scrolling NOW <<<"
adb shell "cat /data/local/tmp/jank.cfg | perfetto --txt -c - -o /data/misc/perfetto-traces/jank.pftrace"

adb pull /data/misc/perfetto-traces/jank.pftrace "$OUT" >/dev/null
rm -f "$CFG"

echo
echo "=== gfxinfo summary (same window) ==="
adb shell dumpsys gfxinfo "$PKG" | sed -n '/Total frames rendered/,/Number Frame deadline missed/p'
echo
echo "=== thermal at capture end ==="
adb shell "dumpsys thermalservice | grep -iE 'status|throttl'" | head -5 || true
echo
echo "Trace saved: $OUT"
