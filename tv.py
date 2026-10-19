#!/usr/bin/env python3
"""Minimal ADB client over TCP (macOS Local Network privacy blocks stock adb).
usage: tv.py sh "<cmd>" | shot <out.png> | push <local> <remote> | key <KEYCODE>...
"""
import base64, hashlib, os, sys
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.keygen import keygen
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

HOST = os.environ.get("TV_IP", "192.168.1.4")
KEY = os.path.join(os.path.dirname(os.path.abspath(__file__)), "adbkey")


def dev():
    if not os.path.exists(KEY):
        keygen(KEY)
    signer = PythonRSASigner(open(KEY + ".pub").read(), open(KEY).read())
    d = AdbDeviceTcp(HOST, 5555, default_transport_timeout_s=30)
    d.connect(rsa_keys=[signer], auth_timeout_s=120)  # time to accept the TV prompt
    return d


def sh(d, cmd, t=120):
    return d.shell(cmd, read_timeout_s=t, transport_timeout_s=t, timeout_s=t)


def push(d, src, dst):
    # adb-shell's sync push hangs on big files; stream base64 chunks through the shell instead
    data = open(src, "rb").read()
    b64 = base64.b64encode(data).decode()
    sh(d, f"rm -f {dst}.b64 {dst}")
    for i in range(0, len(b64), 60000):
        sh(d, f"echo -n '{b64[i:i+60000]}' >> {dst}.b64")
    sh(d, f"base64 -d {dst}.b64 > {dst} && rm {dst}.b64")
    remote = sh(d, f"md5sum {dst}").split()[0]
    local = hashlib.md5(data).hexdigest()
    assert remote == local, f"md5 mismatch {remote} != {local}"
    print(f"pushed {dst} md5 ok")


if __name__ == "__main__":
    op, *args = sys.argv[1:]
    d = dev()
    if op == "sh":
        print(sh(d, args[0]), end="")
    elif op == "shot":
        sh(d, "screencap -p /data/local/tmp/s.png")
        b64 = sh(d, "base64 /data/local/tmp/s.png", 60).replace("\n", "")
        open(args[0], "wb").write(base64.b64decode(b64))
        print(args[0])
    elif op == "push":
        push(d, *args)
    elif op == "key":
        for k in args:
            sh(d, f"input keyevent {k}")
    d.close()
