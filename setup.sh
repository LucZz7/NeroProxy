#!/bin/sh
# Fetches the native tun2socks engine (needs git + internet). Run once, before opening in Android Studio.
set -e
git clone --recursive https://github.com/heiher/hev-socks5-tunnel app/src/main/jni/hev-socks5-tunnel
echo "Done. Install NDK via Android Studio > SDK Manager > SDK Tools > NDK (Side by side), then sync."
