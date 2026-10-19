#!/bin/bash
# usage: ./disable.sh <batchname> pkg... — one pm call per package, logged for restore.sh, then a screenshot
cd "$(dirname "$0")"; mkdir -p shots; b=$1; shift
for p in "$@"; do
  echo "$p" >> disabled_by_us.txt
  .venv/bin/python tv.py sh "pm disable-user --user 0 $p"
done
.venv/bin/python tv.py key KEYCODE_HOME; sleep 3
.venv/bin/python tv.py shot "shots/batch_$b.png"
