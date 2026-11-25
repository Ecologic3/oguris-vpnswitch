# Oguri VPN Switch

Watches which WiFi the phone is on and switches Tailscale off on Adam's
home networks and on everywhere else.

- Switching: explicit broadcasts to `com.tailscale.ipn/.IPNReceiver`
  (`com.tailscale.ipn.CONNECT_VPN` / `com.tailscale.ipn.DISCONNECT_VPN`),
  sent only on state transitions. The service is fully event-driven:
  network callbacks trigger an evaluation and a definite new state
  fires its broadcast in that same evaluation, with no standing
  polling loop and no stability delay (the one exception is the
  unknown-hold re-check below: a bounded 3 s burst, then a 20 s
  re-check for as long as the name stays unreadable). The `USE_EXIT_NODE` broadcast is never used (clearing an
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
- Package `app.oguri.vpnswitch`, version 1.9 (versionCode 10),
  minSdk 29, targetSdk 34.
- v1.4: the foreground-service status notification moved to a new
  IMPORTANCE_MIN channel (`vpnswitch_status_min`; the v1.0-v1.3 channel
  `vpnswitch` is deleted on service start) with a short state-only
  title, so it shows no status-bar icon and sits minimized at the
  bottom of the shade. Switching logic unchanged.
- v1.5: fully event-driven timing. The 60 s periodic recheck and the
  ~30 s stability debounce are gone; evaluations run only on service
  start, on WiFi/cellular callback events and on UI-triggered
  re-evaluation, and a definite new state fires its transition
  broadcast in that same evaluation. The unreadable-SSID hold is
  unchanged.
- v1.6: bounded re-read burst for the unreadable-SSID hold. In the
  field, WiFi -> mobile data switched instantly, but on return the
  WiFi was back at 14:49:37 with its SSID still unreadable, and with
  all polling gone nothing re-read it until a WifiManager fallback
  read landed 30 s later. Now, while (and only while) the phone is
  connected to a WiFi whose name is unreadable, the service
  re-evaluates every 3 s, at most 12 attempts (~36 s), cancelling the
  moment a name is read, the WiFi drops, a switch fires, or the
  service stops. Outside that burst there is still no timer anywhere
  in the service.
- v1.7: fewer WiFi-name reads, no behavior change. Every WiFi-info
  read counts as a location access on Android, and the network
  callbacks used to evaluate on every onCapabilitiesChanged delivery,
  including pure signal-strength and link-speed churn while the phone
  sits still on one network. The WiFi and cellular callbacks now
  distill each delivery into an identity signature (network handle,
  SSID, BSSID; no signal or speed fields) and only a changed
  signature evaluates, while onAvailable/onLost still always do, so
  a delivery that newly carries a readable SSID is never missed.
  readNet() also short-circuits: when the tracker's delivered
  WifiInfo already yields a known SSID it returns without the
  one-shot capabilities read and without the WifiManager read. The
  1.6 burst, the hold semantics and the transition-only broadcasts
  are untouched; the trim only removes stationary flicker reads.
- v1.8: the unknown-hold re-check becomes two-phase. The 1.7 gate
  removed the flicker evaluations that used to keep re-running
  readNet() after the 1.6 burst ended, and in the field the
  callback-delivered WifiInfo can stay redacted for minutes at a
  time, so when the burst ended (~36 s) nothing was left to catch
  the name once it finally surfaced via the WifiManager fallback:
  the phone sat on home WiFi with Tailscale still on. The re-check
  now runs the 1.6 fast burst first (3 s, at most 12 attempts),
  then, if the name is still unreadable, keeps re-evaluating every
  20 s with no attempt cap for as long as the unknown-hold state
  persists, logging one line when the slow phase begins. It still
  cancels the moment a name is read, the WiFi drops, a switch
  fires, or the service stops. The 1.7 gate, the readNet()
  short-circuit, the hold semantics and the transition-only
  broadcasts are unchanged.
- v1.9: MainActivity redesign, switching logic untouched. The
  home-networks text editor and its save button are gone: saved
  networks show as a list with a remove control per row, and an
  "add current network" button adds the WiFi the phone is on
  (disabled, with the reason on the button, when not on WiFi, when
  the name is unreadable, or when it is already saved). Every edit
  rewrites the same newline-joined Prefs list at once, and since
  the service reads that list on every evaluation, edits apply
  live. The monitoring toggle moved above the permission buttons,
  the "hide the status notification" button is removed, and the
  event log now starts hidden behind a Show log / Hide log toggle
  at the bottom. The UI has its own "midnight indigo" theme (deep
  blue-slate surfaces, pale text, a signal-cyan accent), drawn
  programmatically like before, plus a matching launcher icon (a
  toggle in the ON position) in the sibling apps' adaptive-icon
  format: `res/mipmap-anydpi-v26/ic_launcher.xml` over
  `@color/ic_bg`, per-density foreground and legacy PNGs, drawn by
  `icon-src/generate_icon.py` (preview in `icon-preview-1.9.png`).
