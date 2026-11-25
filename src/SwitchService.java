package app.oguri.vpnswitch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Foreground service that watches the connected WiFi network and switches
 * Tailscale off on home networks and on everywhere else.
 *
 * Switching uses explicit broadcasts to Tailscale's IPNReceiver:
 *   com.tailscale.ipn.CONNECT_VPN    -> connect
 *   com.tailscale.ipn.DISCONNECT_VPN -> disconnect
 * The USE_EXIT_NODE broadcast is never used (clearing an exit node that way
 * is a documented crash-loop poison pill). A broadcast is sent only on a
 * state transition, and only after the new state has been stable for about
 * 30 seconds, so brief WiFi drops do not flap the VPN. A redacted SSID read
 * while WiFi stays connected is not a transition either: the current state
 * is held until a read produces positive evidence.
 *
 * Network truth comes from dedicated callbacks, not from the default
 * network: once a VPN is up the default network IS the VPN, so reads keyed
 * off it lose the WiFi SSID. A callback registered with a TRANSPORT_WIFI
 * request plus FLAG_INCLUDE_LOCATION_INFO tracks the WiFi network itself
 * and delivers unredacted WifiInfo; a second TRANSPORT_CELLULAR callback
 * provides the positive "on mobile data" evidence for Away.
 */
public class SwitchService extends Service {
    public static final String ACTION_REEVALUATE = "app.oguri.vpnswitch.REEVALUATE";
    public static final String ACTION_UI_REFRESH = "app.oguri.vpnswitch.UI_REFRESH";

    private static final String CHANNEL_ID = "vpnswitch";
    private static final int NOTIF_ID = 1;
    private static final long DEBOUNCE_MS = 30000;
    private static final long RECHECK_MS = 60000;

    private static final String TS_PACKAGE = "com.tailscale.ipn";
    private static final String TS_RECEIVER = "com.tailscale.ipn.IPNReceiver";
    private static final String TS_CONNECT = "com.tailscale.ipn.CONNECT_VPN";
    private static final String TS_DISCONNECT = "com.tailscale.ipn.DISCONNECT_VPN";

    private static final String SRC_WIFI_CALLBACK = "wifi-callback";
    private static final String SRC_ONESHOT = "oneshot";
    private static final String SRC_WIFIMANAGER = "wifimanager";
    private static final String SRC_NONE = "none";

    private Handler handler;
    private Prefs prefs;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private ConnectivityManager.NetworkCallback cellCallback;
    // Written from the ConnectivityManager callback threads, read on the
    // main thread in readNet(); volatile for visibility.
    private volatile Network wifiNetwork;
    private volatile NetworkCapabilities wifiCaps;
    private volatile Network cellNetwork;
    private String pendingState = "";
    private long pendingSince = 0;
    private Runnable pendingRunnable;
    private Runnable recheckRunnable;
    private boolean ssidUnknownLogged = false;
    private String lastEvalSig = null;

    public static void start(Context ctx) {
        Intent svc = new Intent(ctx, SwitchService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(svc);
        } else {
            ctx.startService(svc);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, SwitchService.class));
    }

    public static void reevaluate(Context ctx) {
        Intent svc = new Intent(ctx, SwitchService.class);
        svc.setAction(ACTION_REEVALUATE);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(svc);
        } else {
            ctx.startService(svc);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        handler = new Handler(Looper.getMainLooper());
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        createChannel();
        registerCallbacks();
        startForeground(NOTIF_ID, buildNotification(readNet()));
        scheduleRecheck();
        evaluate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_REEVALUATE.equals(intent.getAction())) {
            evaluate();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (cm != null) {
            if (wifiCallback != null) {
                try {
                    cm.unregisterNetworkCallback(wifiCallback);
                } catch (Exception e) {
                    // already unregistered
                }
            }
            if (cellCallback != null) {
                try {
                    cm.unregisterNetworkCallback(cellCallback);
                } catch (Exception e) {
                    // already unregistered
                }
            }
        }
        if (handler != null) {
            if (pendingRunnable != null) {
                handler.removeCallbacks(pendingRunnable);
            }
            if (recheckRunnable != null) {
                handler.removeCallbacks(recheckRunnable);
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerCallbacks() {
        if (cm == null) {
            return;
        }
        // On Android 12+ (API 31+) the callback must carry
        // FLAG_INCLUDE_LOCATION_INFO, otherwise the WifiInfo delivered to
        // onCapabilitiesChanged is location-redacted ("<unknown ssid>").
        if (Build.VERSION.SDK_INT >= 31) {
            wifiCallback = new WifiCallback(
                    ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO);
        } else {
            wifiCallback = new WifiCallback();
        }
        cellCallback = new CellCallback();
        try {
            // No NET_CAPABILITY_INTERNET requirement: a home WiFi whose
            // uplink is down is still home.
            NetworkRequest wifiReq = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            cm.registerNetworkCallback(wifiReq, wifiCallback);
        } catch (Exception e) {
            // monitoring continues via the supplements and the recheck
        }
        try {
            NetworkRequest cellReq = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .build();
            cm.registerNetworkCallback(cellReq, cellCallback);
        } catch (Exception e) {
            // the recheck's one-shot reads still detect mobile data
        }
    }

    /** Tracks the WiFi network itself, independent of the default route. */
    private class WifiCallback extends ConnectivityManager.NetworkCallback {
        WifiCallback() {
            super();
        }

        WifiCallback(int flags) {
            super(flags);
        }

        @Override
        public void onAvailable(Network network) {
            wifiNetwork = network;
            postEvaluate();
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            wifiNetwork = network;
            wifiCaps = caps;
            postEvaluate();
        }

        @Override
        public void onLost(Network network) {
            if (network != null && network.equals(wifiNetwork)) {
                wifiNetwork = null;
                wifiCaps = null;
            }
            postEvaluate();
        }
    }

    /** Tracks whether any cellular network is present (Away evidence). */
    private class CellCallback extends ConnectivityManager.NetworkCallback {
        @Override
        public void onAvailable(Network network) {
            cellNetwork = network;
            postEvaluate();
        }

        @Override
        public void onLost(Network network) {
            if (network != null && network.equals(cellNetwork)) {
                cellNetwork = null;
            }
            postEvaluate();
        }
    }

    private void postEvaluate() {
        if (handler != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    evaluate();
                }
            });
        }
    }

    private void scheduleRecheck() {
        recheckRunnable = new Runnable() {
            @Override
            public void run() {
                evaluate();
                handler.postDelayed(recheckRunnable, RECHECK_MS);
            }
        };
        handler.postDelayed(recheckRunnable, RECHECK_MS);
    }

    private static class NetInfo {
        boolean onWifi;
        boolean onCellular;
        String ssid; // null when not on WiFi or the name is unknown
        String ssidSource = SRC_NONE; // which read produced ssid
    }

    private static boolean capsHaveWifi(NetworkCapabilities caps) {
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private static boolean capsHaveCellular(NetworkCapabilities caps) {
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
    }

    private static String ssidFromCaps(NetworkCapabilities caps) {
        if (!capsHaveWifi(caps)) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= 31) {
            Object ti = caps.getTransportInfo();
            if (ti instanceof WifiInfo) {
                return cleanSsid(((WifiInfo) ti).getSSID());
            }
        }
        return null;
    }

    /**
     * The single network-reading path used by every evaluation (service
     * start, network callbacks, the periodic recheck). The WiFi tracker's
     * latest WifiInfo is the primary SSID source; one-shot capabilities
     * reads and WifiManager.getConnectionInfo() only supplement it, and
     * any positively known SSID wins over redacted ones. "On WiFi" comes
     * from the tracker, never from the default network (which is the VPN
     * itself while Tailscale is up). The result has ssid == null while
     * onWifi is true when every source came back redacted; callers must
     * not treat that as evidence of being away from home.
     */
    private NetInfo readNet() {
        NetInfo ni = new NetInfo();
        ni.onWifi = false;
        ni.onCellular = cellNetwork != null;
        ni.ssid = null;

        // 1) WiFi tracker (primary).
        Network tracked = wifiNetwork;
        if (tracked != null) {
            ni.onWifi = true;
            String s = ssidFromCaps(wifiCaps);
            if (s != null) {
                ni.ssid = s;
                ni.ssidSource = SRC_WIFI_CALLBACK;
            }
        }

        // One-shot capabilities of the active (default) network, used for
        // the cellular supplement and as a last-resort WiFi supplement.
        NetworkCapabilities activeCaps = null;
        if (cm != null) {
            try {
                Network active = cm.getActiveNetwork();
                if (active != null) {
                    activeCaps = cm.getNetworkCapabilities(active);
                }
            } catch (Exception e) {
                // fall through to the other candidates
            }
        }
        if (capsHaveCellular(activeCaps)) {
            ni.onCellular = true;
        }

        // 2) One-shot capabilities of the tracked WiFi network.
        if (ni.ssid == null && tracked != null && cm != null) {
            try {
                String s = ssidFromCaps(cm.getNetworkCapabilities(tracked));
                if (s != null) {
                    ni.ssid = s;
                    ni.ssidSource = SRC_ONESHOT;
                    ni.onWifi = true;
                }
            } catch (Exception e) {
                // fall through
            }
        }

        // 3) WifiManager (supplements both the SSID and "on WiFi").
        boolean wmConnected = false;
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null && wi.getNetworkId() != -1) {
                    wmConnected = true;
                    if (ni.ssid == null) {
                        String s = cleanSsid(wi.getSSID());
                        if (s != null) {
                            ni.ssid = s;
                            ni.ssidSource = SRC_WIFIMANAGER;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // no WiFi info available
        }
        if (wmConnected) {
            ni.onWifi = true;
        }

        // 4) Last resort for "on WiFi": the active network's transports.
        // (Its SSID via one-shot caps is redacted too often to lead with,
        // but the transport bit is reliable.)
        if (!ni.onWifi && capsHaveWifi(activeCaps)) {
            ni.onWifi = true;
            if (ni.ssid == null) {
                String s = ssidFromCaps(activeCaps);
                if (s != null) {
                    ni.ssid = s;
                    ni.ssidSource = SRC_ONESHOT;
                }
            }
        }
        return ni;
    }

    private static String cleanSsid(String s) {
        if (s == null) {
            return null;
        }
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.length() == 0 || "<unknown ssid>".equals(s)) {
            return null;
        }
        return s;
    }

    private static String describe(NetInfo ni) {
        if (ni.onWifi) {
            return ni.ssid == null ? "WiFi, name unknown" : "WiFi " + ni.ssid;
        }
        return ni.onCellular ? "no WiFi, mobile data" : "no network";
    }

    private static String where(NetInfo ni) {
        if (ni.onWifi) {
            return ni.ssid == null ? "WiFi (name unknown)" : "WiFi " + ni.ssid;
        }
        return ni.onCellular ? "mobile data" : "no network";
    }

    private void evaluate() {
        NetInfo ni = readNet();
        prefs.setLastNet(ni.onWifi, ni.ssid);

        // Diagnostic trail: log whenever the reading itself changes (which
        // network, which name), with the source that produced the SSID, so
        // a misread is visible in the log without a state change having to
        // happen first. Repeated identical readings log nothing.
        String sig = ni.onWifi
                ? "W|" + (ni.ssid == null ? "" : ni.ssid)
                : (ni.onCellular ? "CELL" : "NONE");
        if (!sig.equals(lastEvalSig)) {
            lastEvalSig = sig;
            prefs.addLog("Eval: " + describe(ni) + " (via " + ni.ssidSource + ")");
        }

        if (ni.ssid != null) {
            // A positively read name ends any unknown stretch; only this
            // resets the hold-log throttle (a merely unreadable or absent
            // WiFi in between must not re-arm it).
            ssidUnknownLogged = false;
        }
        if (ni.onWifi && ni.ssid == null) {
            // Connected to WiFi but every read came back redacted, so the
            // network name is unknown. That is not evidence of being Away:
            // hold the current state, start no debounce, change nothing.
            // Away requires positive evidence (WiFi gone, or a positively
            // read SSID that is not a home network).
            cancelPending();
            pendingState = "";
            pendingSince = 0;
            if (!ssidUnknownLogged) {
                ssidUnknownLogged = true;
                prefs.addLog("SSID unknown, holding state (via " + ni.ssidSource + ")");
            }
            updateNotification(ni);
            sendRefresh();
            return;
        }
        String raw;
        if (ni.ssid != null && prefs.getHomeSsids().contains(ni.ssid)) {
            raw = Prefs.STATE_HOME;
        } else {
            raw = Prefs.STATE_AWAY;
        }
        String commanded = prefs.getCommandedState();
        if (raw.equals(commanded)) {
            pendingState = "";
            pendingSince = 0;
            cancelPending();
        } else {
            long now = System.currentTimeMillis();
            if (!raw.equals(pendingState)) {
                pendingState = raw;
                pendingSince = now;
                schedulePending(DEBOUNCE_MS);
            } else if (now - pendingSince >= DEBOUNCE_MS) {
                fire(raw, ni);
            } else {
                schedulePending(DEBOUNCE_MS - (now - pendingSince));
            }
        }
        updateNotification(ni);
        sendRefresh();
    }

    private void schedulePending(long delayMs) {
        cancelPending();
        pendingRunnable = new Runnable() {
            @Override
            public void run() {
                evaluate();
            }
        };
        handler.postDelayed(pendingRunnable, Math.max(delayMs, 1000));
    }

    private void cancelPending() {
        if (pendingRunnable != null && handler != null) {
            handler.removeCallbacks(pendingRunnable);
            pendingRunnable = null;
        }
    }

    private void fire(String state, NetInfo ni) {
        boolean home = Prefs.STATE_HOME.equals(state);
        Intent i = new Intent();
        i.setComponent(new ComponentName(TS_PACKAGE, TS_RECEIVER));
        i.setAction(home ? TS_DISCONNECT : TS_CONNECT);
        boolean sent = true;
        try {
            sendBroadcast(i);
        } catch (Exception e) {
            sent = false;
        }
        prefs.setCommandedState(state);
        String action = home ? "DISCONNECT sent to Tailscale" : "CONNECT sent to Tailscale";
        if (!sent) {
            action = action + " (broadcast failed)";
        }
        prefs.setLastAction(action, System.currentTimeMillis());
        prefs.addLog((home ? "HOME" : "AWAY") + " on " + where(ni)
                + " (via " + ni.ssidSource + ") -> "
                + (home ? "DISCONNECT" : "CONNECT"));
        pendingState = "";
        pendingSince = 0;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "VPN Switch status", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shows whether Tailscale is off (home) or on (away)");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification(NetInfo ni) {
        String state = prefs.getCommandedState();
        String text;
        if (Prefs.STATE_HOME.equals(state)) {
            text = "Home · Tailscale off · " + where(ni);
        } else if (Prefs.STATE_AWAY.equals(state)) {
            text = "Away · Tailscale on · " + where(ni);
        } else {
            text = "Watching · " + where(ni);
        }
        long last = prefs.getLastActionTime();
        if (last > 0) {
            text = text + " · last switch " + Prefs.formatTime(last);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("Oguri VPN Switch");
        b.setContentText(text);
        b.setSmallIcon(android.R.drawable.ic_dialog_info);
        b.setContentIntent(pi);
        b.setOngoing(true);
        return b.build();
    }

    private void updateNotification(NetInfo ni) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(ni));
        }
    }

    private void sendRefresh() {
        Intent i = new Intent(ACTION_UI_REFRESH);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }
}
