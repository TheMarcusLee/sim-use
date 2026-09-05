# FARM-NOTES

Notes for the phone-farm maintainers on this fork of
[`lycorp-jp/sim-use`](https://github.com/lycorp-jp/sim-use).

Everything here concerns the Android **device bridge** (`bridge/`) only.
The Swift CLI is untouched.

---

## Why this fork exists

Upstream reaches the bridge over `adb forward`, which lands on the
device's `127.0.0.1`. That requires a USB cable per device. The farm
drives phones over Wi-Fi with nothing attached, so the bridge needs to
listen on `0.0.0.0` and stay reachable with the screen off.

## What changed vs upstream

Branch: `feat/bridge-wifi-bind`.

| Area | Change |
| --- | --- |
| `config/BridgeSettings.kt` *(new)* | Persisted `bind_all_interfaces` flag in the same SharedPreferences file as the auth token, plus strict parsing of the adb `--arg` value. |
| `util/NetworkAddresses.kt` *(new)* | LAN IPv4 discovery via `NetworkInterface` (no location permission), preferring `wlan*`, then `eth*`/`usb*`/`rndis*`, ignoring cellular `rmnet*`. |
| `server/HttpServer.kt` | New `bindAll` constructor flag. Binds `0.0.0.0` when set, `127.0.0.1` otherwise, and logs the dialable `http://<ip>:<port>` in LAN mode. |
| `server/ActionRouter.kt` | `/ping` gained a `bind_all` boolean alongside the existing `protocol_version` / `bridge_version`. Still unauthenticated, still carries no auth material. Auth on all other routes is unchanged. |
| `service/SimuseAccessibilityService.kt` | Reads the flag when starting the listener; new `restartServer()` so a toggle applies immediately; exposes the live bind mode for `/ping`. |
| `service/SimuseContentProvider.kt` | New `call()` methods `set_bind_all` / `get_bind_all` / `status`, and a `bridge_status` query path. All behind the existing shell/root UID guard. |
| `service/BridgeKeepAliveService.kt` | Holds a `PARTIAL_WAKE_LOCK` and a Wi-Fi lock (`WIFI_MODE_FULL_LOW_LATENCY`, `WIFI_MODE_FULL_HIGH_PERF` below API 29) **only while LAN mode is on**. |
| `AndroidManifest.xml` | Added `ACCESS_WIFI_STATE` (normal permission, no runtime prompt). |
| Tests | `BridgeSettingsTest` (new, 8 cases) plus additions to `ActionRouterTest`, `BridgePureLogicTest`, `SimuseContentProviderTest`. 66 JVM tests total, no emulator required. |

**Default behaviour is unchanged.** A device that never runs
`set_bind_all` behaves exactly like upstream: loopback bind, no wake
lock, no Wi-Fi lock, `/ping` merely gains a `bind_all: false` field.

**No new third-party dependencies.**

## Wire-protocol impact

`PROTOCOL_VERSION` was **not** bumped. `/ping` gained one additive field;
every existing field and every other route is byte-identical. Clients
pinned to protocol 2 keep working.

---

## Per-device provisioning (once, over USB)

```bash
BRIDGE=com.linecorp.simuse.devicebridge
SERIAL=<adb serial>          # from `adb devices`

# 1. Install.
adb -s "$SERIAL" install -r bridge/app/build/outputs/apk/debug/app-debug.apk

# 2. Enable the accessibility service.
adb -s "$SERIAL" shell settings put secure enabled_accessibility_services \
  "$BRIDGE/$BRIDGE.service.SimuseAccessibilityService"
adb -s "$SERIAL" shell settings put secure accessibility_enabled 1

# 3. Mint / read the bearer token.
adb -s "$SERIAL" shell content query \
  --uri content://com.linecorp.simuse.devicebridge/auth_token
# → Row: 0 result={"status":"success","result":"1f0a...-...."}

# 4. Enable Wi-Fi mode (listener restarts immediately).
adb -s "$SERIAL" shell content call \
  --uri content://com.linecorp.simuse.devicebridge \
  --method set_bind_all --arg true

# 5. Read the IP back.
adb -s "$SERIAL" shell content call \
  --uri content://com.linecorp.simuse.devicebridge --method status
# → Bundle[{result={"status":"success","result":{"bind_all":true,
#     "bound_all":true,"server_running":true,
#     "accessibility_service_connected":true,
#     "port":8080,"lan_ipv4":"192.168.1.42"}}}]

# 6. Verify over the LAN, cable now removable.
curl http://192.168.1.42:8080/ping
```

Steps 1–3 are what upstream's `sim-use android init` already does (see
`Sources/AndroidBackend/Verbs/AndroidDeviceController.swift`); run that
instead if you have the CLI to hand, then do steps 4–6. Two gotchas:

* Step 2 clobbers any other enabled accessibility service on the device.
  If the phone already has one you care about, append to the existing
  colon-separated value instead of overwriting it.
* On many real devices Android's **Restricted Settings** blocks
  adb-installed apps from being granted accessibility, and the `settings
  put` silently does not stick. Grant it by hand once under
  *Settings → Accessibility → sim-use device bridge*, then re-run.

A healthy device answers step 6 with:

```json
{"status":"success","result":"pong","protocol_version":2,
 "bridge_version":"0.14.0","bind_all":true}
```

If `bind_all` comes back `false`, the listener is still on loopback —
re-run step 4 and check that the accessibility service is actually
connected (`accessibility_service_connected` in `status`).

## What to put in the farm's `devices.json`

Port is `8080` (`SimuseAccessibilityService.SERVER_PORT`; not currently
configurable). Use the `lan_ipv4` from step 5 and the token from step 3:

```json
{
  "devices": [
    {
      "id": "pixel-07",
      "android": {
        "bridgeUrl": "http://192.168.1.42:8080",
        "bridgeToken": "1f0a3c2e-9b41-4d77-8a10-6c5e2f0b9d84"
      }
    }
  ]
}
```

* `android.bridgeUrl` — scheme + host + port, **no trailing slash and no
  path**. The farm appends `/ping`, `/screenshot`, `/tap`, etc.
* `android.bridgeToken` — the raw UUID from the `result` field of the
  `auth_token` query (not the surrounding JSON envelope). Sent as
  `Authorization: Bearer <token>`.

The token is per-device and stable. It rotates only on
`pm clear com.linecorp.simuse.devicebridge` (which also resets the
bind-all flag), so re-run steps 3–5 after any wipe or reinstall that
clears app data.

**Give every device a DHCP reservation or a static lease.** Nothing in
the bridge announces an address change; if a phone's IP moves, its
`bridgeUrl` goes stale and the farm sees connection refusals until
someone re-reads `status` over USB.

## Security posture — read before deploying

* Auth is unchanged and still mandatory: every route except `/ping`
  requires `Authorization: Bearer <token>`.
* Traffic is **plain HTTP**. No TLS. The token crosses the network in
  cleartext on every request.
* Anyone who can reach `<ip>:8080` **and** holds the token has full
  accessibility-level control of the phone: screen contents, input,
  clipboard.
* Therefore: put the farm on a dedicated SSID/VLAN, isolated from guest
  and corporate networks, with no inbound route from the internet.
  Client isolation between devices is fine — the farm controller is the
  only thing that needs to reach them.
* The APK is **debug-signed** (upstream's deliberate choice for internal
  tooling — see `AGENTS.md`). The signature carries no authenticity
  guarantee; the trusted channel is your own `adb`.
* To take a device back off the network:
  `adb shell content call --uri content://com.linecorp.simuse.devicebridge --method set_bind_all --arg false`

## Building

```bash
cd bridge
export JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
echo "sdk.dir=$ANDROID_HOME" > local.properties   # gitignored
./gradlew :app:assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 66 JVM tests, no emulator
```

JDK 17–21 only; the bundled Gradle 8.7 rejects JDK 22+. For the
release-shaped APK the Swift side bundles, use upstream's
`scripts/build-bridge.sh` / `./gradlew :app:assembleRelease`.
