# NERO Proxy 1.0.0 — full proxy app, free (Android 9+)

A Super Proxy-style app, written from scratch with original code and branding.
It ships **no proxies of its own**: you enter your own HTTP or SOCKS5 proxy,
and the app tunnels the whole device through it, without root.

## Features (all free, no paid lock)
- HTTP and SOCKS5 upstreams, username/password auth, **TLS to the proxy itself**
- No-root full-device VPN: all apps, Wi-Fi and mobile data
- **Unlimited profiles**: add, edit, delete, switch, **export / import as JSON**
- **Real-time logs**, up/down stats and speed, proxy latency (TCP connect time)
- **No DNS leak**: DNS is answered locally by the engine's MapDNS and resolved
  by your proxy. UDP, QUIC and IPv6 are dropped, fail-closed.
- **App filter**: all apps, exclude selected apps, or include only selected apps
- **Quick Settings tile**: tap to connect / disconnect (opens the app first if
  VPN permission was never granted)
- **Dark mode**, minimal UI, thin font
- **Real, not demo**: Connected appears only after a real pre-flight test
  (DNS, TCP, TLS-to-proxy if enabled, auth, tunnel, TLS, HTTPS request) passes
  and the TUN is up. "Forwarding verified" appears only after real bytes flow
  back through the proxy. If the proxy is down, traffic is blocked, never sent
  directly.

## How it works
apps -> Android TUN (VpnService) -> hev-socks5-tunnel (native, MIT) ->
LocalBridge (Kotlin, 127.0.0.1, random per-session SOCKS5 login) ->
your upstream HTTP-CONNECT or SOCKS5 proxy (optionally TLS-wrapped).

## Build (required steps)
1. Install Android Studio (Koala or newer) + in SDK Manager > SDK Tools install
   **NDK (Side by side)**. CMake is not needed.
2. In this folder run `./setup.sh`. This downloads the native engine
   https://github.com/heiher/hev-socks5-tunnel into app/src/main/jni/.
   Without it the app builds but Connect shows "engine missing".
3. Open the folder in Android Studio, sync, then:
   - Tests: `./gradlew testDebugUnitTest`
   - APK: `./gradlew assembleDebug` -> app/build/outputs/apk/debug/app-debug.apk
   - Or push to GitHub: the workflow in `.github/workflows/android.yml` builds
     the debug APK and publishes it to the v1.0.0 release.
4. Phone: install the APK, add a profile, Test it, then Connect and allow the
   VPN permission. Add the Quick Settings tile from the notification shade.

## Privacy and limits
- No ads, analytics, accounts or servers. Settings stay on the device; the
  password is encrypted with an Android Keystore key. Logs stay in memory and
  never contain passwords or visited sites. HTTPS is never intercepted.
- **Export warning**: an exported profiles file contains passwords in clear
  text. Keep it private, like any password file.
- TCP only. UDP/QUIC/IPv6 are not supported (same fail-closed choice as the
  reference design). Android does not guarantee complete leak protection and
  this app does not promise anonymity.
- Target SDK 33 on purpose (avoids Android 14 foreground-service-type rules).
  Google Play now requires a higher target; raise it and add the
  foreground-service type if you publish there.
- QUERY_ALL_PACKAGES is used only to list installed apps for the app filter,
  on the device only.

## Licenses
hev-socks5-tunnel (heiher) – MIT, with bundled lwIP (BSD), libyaml (MIT),
hev-task-system (MIT). AndroidX, Jetpack Compose, Kotlin,
kotlinx.coroutines – Apache 2.0. See each project's LICENSE file.
