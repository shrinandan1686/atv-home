#!/bin/bash
# Build the launcher, install it on the TV over ADB (Python adb-shell client) and make it the default home.
set -e
cd "$(dirname "$0")"
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
(cd launcher && gradle assembleDebug -q)
T=".venv/bin/python tv.py"
$T push launcher/build/outputs/apk/debug/atvhome-debug.apk /data/local/tmp/atvhome.apk
$T sh "pm install -r /data/local/tmp/atvhome.apk; rm /data/local/tmp/atvhome.apk"
$T sh "pm grant dev.atvhome android.permission.ACCESS_FINE_LOCATION"   # lets it read the Wi-Fi name
$T sh "cmd package set-home-activity dev.atvhome/.MainActivity"
$T sh "am force-stop dev.atvhome"
$T key KEYCODE_HOME
