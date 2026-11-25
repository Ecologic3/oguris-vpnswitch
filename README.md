# Oguri VPN Switch

Watches which WiFi the phone is on and switches Tailscale off on Adam's
home networks and on everywhere else.

- Switching: explicit broadcasts to `com.tailscale.ipn/.IPNReceiver`
  (`com.tailscale.ipn.CONNECT_VPN` / `com.tailscale.ipn.DISCONNECT_VPN`),
  sent only on state transitions, after the new state has been stable
  for ~30 s. The `USE_EXIT_NODE` broadcast is never used (clearing an
  exit node that way is a documented crash-loop poison pill). When
  Tailscale reconnects it restores the selected exit node by itself.
- Monitoring runs in a foreground service (`specialUse` type, the same
  pattern as Oguri's Steps), restarted by a BOOT_COMPLETED receiver.
- SSID reading needs ACCESS_FINE_LOCATION plus location services on.
  The WiFi network is tracked by a dedicated NetworkCallback registered
  with a TRANSPORT_WIFI request and FLAG_INCLUDE_LOCATION_INFO, because
  reads keyed off the default network lose the SSID once a VPN is up
  (the default network is then the VPN itself). An unreadable SSID while
  WiFi stays connected holds the current state; Away requires positive
  evidence (WiFi gone, or a positively read non-home SSID).
- Build: `./build.sh` (manual aapt2/javac/d8 pipeline, Corretto 17 from
  ~/workspace/android-apk/toolchain). Signed with the per-app keystore
  `oguris-vpnswitch.keystore` (alias `oguris-vpnswitch`), the same
  per-app keystore convention as the other Oguri apps; future updates
  of this app must keep using it.
- Package `app.oguri.vpnswitch`, version 1.4 (versionCode 5),
  minSdk 29, targetSdk 34.
- v1.4: the foreground-service status notification moved to a new
  IMPORTANCE_MIN channel (`vpnswitch_status_min`; the v1.0-v1.3 channel
  `vpnswitch` is deleted on service start) with a short state-only
  title, so it shows no status-bar icon and sits minimized at the
  bottom of the shade. Switching logic unchanged.
