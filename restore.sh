#!/bin/bash
# Re-enables everything disable.sh turned off and puts the stock launcher back as home.
cd "$(dirname "$0")"
sort -u disabled_by_us.txt | while read -r p; do
  [ -n "$p" ] && .venv/bin/python tv.py sh "pm enable --user 0 $p"
done
.venv/bin/python tv.py sh "cmd package set-home-activity com.google.android.tvlauncher/.MainActivity"
# Optional: remove the custom launcher too:  .venv/bin/python tv.py sh "pm uninstall dev.atvhome"
.venv/bin/python tv.py key KEYCODE_HOME
