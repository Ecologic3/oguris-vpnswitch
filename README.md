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
  The SSID comes from the active network's capabilities (its
  TransportInfo on Android 12+), with WifiManager.getConnectionInfo()
  as a fallback. If the SSID cannot be read, the phone counts as Away.
- Build: `./build.sh` (manual aapt2/javac/d8 pipeline, Corretto 17 from
  ~/workspace/android-apk/toolchain). Signed with the per-app keystore
  `oguris-vpnswitch.keystore` (alias `oguris-vpnswitch`), the same
  per-app keystore convention as the other Oguri apps; future updates
  of this app must keep using it.
- Package `app.oguri.vpnswitch`, version 1.0 (versionCode 1),
  minSdk 29, targetSdk 34.
