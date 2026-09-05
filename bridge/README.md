# sim-use-device-bridge

Kotlin Android app that runs an in-process `AccessibilityService` + HTTP server inside the device, driven from the host by the sim-use CLI over `adb forward`.

## Building

Prerequisites:
- Android SDK with platform-tools + `compileSdk=35` installed, and `$ANDROID_HOME` (or `$ANDROID_SDK_ROOT`) set.
- JDK 17–21. Android Studio's bundled JBR (21) works; `brew install openjdk@17` also works. JDK 22+ is rejected by the bundled Gradle 8.7 with a cryptic version error.
- Gradle wrapper is committed at `bridge/gradlew`; no need to install Gradle system-wide.

To produce the debug-signed release APK consumed by the Swift `AndroidBackend` SPM resource:

```bash
cd bridge
./gradlew :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk \
  ../Sources/AndroidBackend/Resources/sim-use-device-bridge.apk
```

The `scripts/build-bridge.sh` helper at the repo root drives the same flow and auto-detects JDK/SDK paths.

## Layout

```
bridge/
├── build.gradle.kts          # Top-level plugins
├── settings.gradle.kts       # `:app` include
├── gradlew                   # Wrapper
├── gradle/wrapper/           # Gradle wrapper jar + properties
└── app/
    ├── build.gradle.kts      # Android app module
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── xml/accessibility_service_config.xml
        │   └── xml/data_extraction_rules.xml
        └── java/com/linecorp/simuse/devicebridge/
            ├── config/
            │   ├── AuthManager.kt                 # Bearer token mint + persist
            │   └── BridgeSettings.kt              # Persisted bind-all flag (fork)
            ├── handler/                           # One file per verb family
            │   ├── CaptureHandler.kt              # /screenshot
            │   ├── GestureHandler.kt              # /swipe, /gesture
            │   ├── InputHandler.kt                # /keyboard/input, /keyboard/key
            │   ├── KeyboardStateHandler.kt        # /keyboard/state
            │   ├── PasteHandler.kt                # /paste
            │   └── TreeHandler.kt                 # /a11y_tree_full
            ├── model/ElementNode.kt               # Wire shape for tree responses
            ├── util/NetworkAddresses.kt           # LAN IPv4 discovery (fork)
            ├── server/                            # Raw HTTP server
            │   ├── ActionRouter.kt                # (method, path) dispatch + auth
            │   ├── HttpResponse.kt
            │   └── HttpServer.kt                  # ServerSocket accept-loop
            └── service/
                ├── BridgeKeepAliveService.kt      # Foreground keep-alive + LAN locks
                ├── SimuseAccessibilityService.kt  # A11y service lifecycle
                └── SimuseContentProvider.kt       # adb-shell-gated bootstrap
```

## Wi-Fi mode (fork addition)

By default the HTTP listener binds `127.0.0.1` only, exactly as upstream:
the sim-use CLI reaches it through `adb forward`, so a cable is required
and no LAN peer can see the bridge. A phone farm has no cable, so this
fork adds a persisted **"listen on all interfaces"** flag. When it is on,
the listener binds `0.0.0.0` instead.

There is no in-app UI — the bridge ships no Activity — so the toggle is
adb-only, gated to the shell/root UID by `SimuseContentProvider`.

### Bootstrap once over USB

With the phone on USB, and the app installed and its accessibility
service enabled:

```bash
# 1. Mint / read the bearer token (unchanged from upstream).
adb shell content query --uri content://com.linecorp.simuse.devicebridge/auth_token

# 2. Turn on Wi-Fi mode. The listener restarts immediately.
adb shell content call --uri content://com.linecorp.simuse.devicebridge \
  --method set_bind_all --arg true

# 3. Read back the state, including the address to dial.
adb shell content call --uri content://com.linecorp.simuse.devicebridge \
  --method status
```

`--arg` accepts `true|1|yes|on|enable[d]` and `false|0|no|off|disable[d]`
(case-insensitive). Anything else is rejected with an explicit
`invalid_arg` error rather than being read as "false" — a typo must not
silently leave the bridge on loopback while you believe it is on the LAN.

`status` (also available as `--method get_bind_all` for just the flag,
or as `content query --uri content://com.linecorp.simuse.devicebridge/bridge_status`
if you prefer a cursor) returns:

```json
{"status":"success","result":{
  "bind_all": true,               // what is persisted
  "bound_all": true,              // what the live listener is bound to
  "server_running": true,
  "accessibility_service_connected": true,
  "port": 8080,
  "lan_ipv4": "192.168.1.42"
}}
```

`bind_all` and `bound_all` differ only in the window between a toggle
and the listener restart, or when the accessibility service is not yet
enabled (the flag is persisted and applied on the next connect).

### Find the IP

`lan_ipv4` above is the answer. It is derived from `NetworkInterface`
(no location permission needed), preferring `wlan*`, then `eth*`/`usb*`/
`rndis*`, and ignoring cellular `rmnet*` — a carrier address is not
reachable from a farm controller. The same address is logged at listener
start:

```bash
adb logcat -s SimuseHttpServer:I SimuseA11yService:I
# HTTP server started on 0.0.0.0:8080 — point the farm at http://192.168.1.42:8080
```

### Point a client at it

```bash
curl http://192.168.1.42:8080/ping
# {"status":"success","result":"pong","protocol_version":2,
#  "bridge_version":"0.14.0","bind_all":true}

curl -H "Authorization: Bearer <token>" http://192.168.1.42:8080/screenshot
```

`/ping` stays unauthenticated so a health check needs no secret. It now
also reports `bind_all`, so a controller can tell a device that is
genuinely on the LAN from one that fell back to loopback. It carries no
auth material.

### Keep-alive with the screen off

When Wi-Fi mode is on, `BridgeKeepAliveService` additionally holds a
`PARTIAL_WAKE_LOCK` and a Wi-Fi lock (`WIFI_MODE_FULL_LOW_LATENCY`,
falling back to `WIFI_MODE_FULL_HIGH_PERF` below API 29), so Doze and
Wi-Fi power-save do not stall the listening socket on a screen-off,
cable-free device. This costs battery, so the locks are taken *only* in
Wi-Fi mode — USB deployments keep upstream behaviour untouched. The new
`ACCESS_WIFI_STATE` permission is normal, not dangerous: no runtime
prompt.

### Security notes

* **The bearer token is still mandatory** on every route except `/ping`.
  Wi-Fi mode changes *where* the socket listens, nothing about auth.
* The token is the only credential, and traffic is **plain HTTP** — no
  TLS. Treat the bridge as trusted-network-only: put the farm on a
  dedicated Wi-Fi SSID or VLAN with no route to the internet and no
  untrusted clients. Anyone who can reach port 8080 *and* holds the
  token has full accessibility-level control of the device.
* The token is only handed out over `adb shell` (UID 2000/0). The same
  guard covers `set_bind_all`, so an installed app cannot put the bridge
  on the network behind your back.
* **Turning it off:**

  ```bash
  adb shell content call --uri content://com.linecorp.simuse.devicebridge \
    --method set_bind_all --arg false
  ```

  or, to reset the token and the bind mode together (both live in the
  same SharedPreferences file), `adb shell pm clear
  com.linecorp.simuse.devicebridge` — which restores stock upstream
  behaviour wholesale.

## Test

```bash
cd bridge
./gradlew :app:testReleaseUnitTest
```
