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

    private Handler handler;
    private Prefs prefs;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;
    private String pendingState = "";
    private long pendingSince = 0;
    private Runnable pendingRunnable;
    private Runnable recheckRunnable;
    private NetworkCapabilities lastCaps;
    private Network lastCapsNetwork;
    private boolean unknownHold = false;

    public static void start(Context context) {
        Intent intent = new Intent(context, SwitchService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, SwitchService.class));
    }

    public static void reevaluate(Context context) {
        Intent intent = new Intent(context, SwitchService.class);
        intent.setAction(ACTION_REEVALUATE);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        handler = new Handler(Looper.getMainLooper());
        cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        createChannel();
        startForeground(NOTIF_ID, buildNotification(readNet()));
        registerCallback();
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
        if (callback != null && cm != null) {
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (Exception e) {
                // already unregistered
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

    private void registerCallback() {
        if (cm == null) {
            return;
        }
        // On Android 12+ (API 31+) the callback must carry
        // FLAG_INCLUDE_LOCATION_INFO, otherwise the WifiInfo delivered
        // to onCapabilitiesChanged is location-redacted ("<unknown ssid>").
        if (Build.VERSION.SDK_INT >= 31) {
            callback = new NetCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO);
        } else {
            // no location flag in the callback before Android 12
            callback = new NetCallback();
        }
        try {
            cm.registerDefaultNetworkCallback(callback);
        } catch (Exception e) {
            // monitoring continues via the one-shot reads and the recheck
        }
    }

    private class NetCallback extends ConnectivityManager.NetworkCallback {
        NetCallback() {
            super();
        }

        NetCallback(int flags) {
            super(flags);
        }

        @Override
        public void onAvailable(Network network) {
            postEvaluate();
        }

        @Override
        public void onLost(Network network) {
            if (network != null && network.equals(lastCapsNetwork)) {
                lastCaps = null;
                lastCapsNetwork = null;
            }
            postEvaluate();
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            lastCapsNetwork = network;
            lastCaps = caps;
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
        String ssid;
    }

    private static boolean capsHaveWifi(NetworkCapabilities caps) {
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private static String ssidFromCaps(NetworkCapabilities caps) {
        if (!capsHaveWifi(caps)) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= 31) {
            Object info = caps.getTransportInfo();
            if (info instanceof WifiInfo) {
                return cleanSsid(((WifiInfo) info).getSSID());
            }
        }
        return null;
    }

    /**
     * The single network-reading path used by every evaluation (service
     * start, network callbacks, the periodic recheck). The callback's
     * latest capabilities are the primary SSID source; one-shot reads of
     * the active network and WifiManager.getConnectionInfo() only
     * supplement it, and any positively known SSID wins over redacted
     * ones. The result has ssid == null while onWifi is true when every
     * source came back redacted; callers must not treat that as evidence
     * of being away from home.
     */
    private NetInfo readNet() {
        NetInfo ni = new NetInfo();
        ni.onWifi = false;
        ni.ssid = null;
        NetworkCapabilities activeCaps = null;
        Network active = null;
        try {
            if (cm != null) {
                active = cm.getActiveNetwork();
                if (active != null) {
                    activeCaps = cm.getNetworkCapabilities(active);
                }
            }
        } catch (Exception e) {
            // fall through to the other candidates
        }
        NetworkCapabilities cbCaps = null;
        if (lastCaps != null && active != null && active.equals(lastCapsNetwork)) {
            cbCaps = lastCaps;
        }
        ni.ssid = ssidFromCaps(cbCaps);
        if (ni.ssid == null) {
            ni.ssid = ssidFromCaps(activeCaps);
        }
        String wmSsid = null;
        boolean wmConnected = false;
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null && info.getNetworkId() != -1) {
                    wmConnected = true;
                    wmSsid = cleanSsid(info.getSSID());
                }
            }
        } catch (Exception e) {
            // no WiFi info available
        }
        if (ni.ssid == null) {
            ni.ssid = wmSsid;
        }
        ni.onWifi = capsHaveWifi(cbCaps) || capsHaveWifi(activeCaps) || wmConnected;
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

    private void evaluate() {
        NetInfo ni = readNet();
        prefs.setLastNet(ni.onWifi, ni.ssid);
        if (ni.onWifi && ni.ssid == null) {
            // Connected to WiFi but the SSID is unreadable (redacted),
            // so the network name is unknown. That is not evidence of
            // being Away: hold the current state, start no debounce and
            // change nothing. Away requires positive evidence (WiFi gone,
            // or a positively read SSID that is not a home network).
            cancelPending();
            pendingState = "";
            pendingSince = 0;
            if (!unknownHold) {
                unknownHold = true;
                prefs.addLog("SSID unknown, holding state");
            }
            updateNotification(ni);
            sendRefresh();
            return;
        }
        unknownHold = false;

        String target = ni.ssid != null && prefs.getHomeSsids().contains(ni.ssid)
                ? Prefs.STATE_HOME
                // not a home network (or no network at all)
                : Prefs.STATE_AWAY;

        String commanded = prefs.getCommandedState();
        if (target.equals(commanded)) {
            pendingState = "";
            pendingSince = 0;
            cancelPending();
        } else {
            long now = System.currentTimeMillis();
            if (!target.equals(pendingState)) {
                pendingState = target;
                pendingSince = now;
                schedulePending(DEBOUNCE_MS);
            } else if (now - pendingSince >= DEBOUNCE_MS) {
                fire(target, ni);
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
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(TS_PACKAGE, TS_RECEIVER));
        intent.setAction(home ? TS_DISCONNECT : TS_CONNECT);
        boolean sent = true;
        try {
            sendBroadcast(intent);
        } catch (Exception e) {
            sent = false;
        }
        prefs.setCommandedState(state);
        String action = home ? "DISCONNECT sent to Tailscale" : "CONNECT sent to Tailscale";
        if (!sent) {
            action = action + " (broadcast failed)";
        }
        prefs.setLastAction(action, System.currentTimeMillis());
        String where;
        if (ni.onWifi) {
            where = ni.ssid == null ? "WiFi (name unknown)" : "WiFi " + ni.ssid;
        } else {
            where = "mobile data";
        }
        prefs.addLog((home ? Prefs.STATE_HOME : Prefs.STATE_AWAY) + " on " + where
                + " -> " + (home ? "DISCONNECT" : "CONNECT"));
        pendingState = "";
        pendingSince = 0;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "VPN Switch status", NotificationManager.IMPORTANCE_LOW);

            ch.setDescription("Shows whether Tailscale is off (home) or on (away)");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification(NetInfo ni) {
        String state = prefs.getCommandedState();
        String where;
        if (ni.onWifi) {
            where = ni.ssid == null ? "WiFi (name unknown)" : ni.ssid;
        } else {
            where = "mobile data";
        }
        String text;
        if (Prefs.STATE_HOME.equals(state)) {
            text = "Home · Tailscale off · " + where;
        } else if (Prefs.STATE_AWAY.equals(state)) {
            text = "Away · Tailscale on · " + where;
        } else {
            text = "Watching · " + where;
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
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(ni));
        }
    }

    private void sendRefresh() {
        Intent intent = new Intent(ACTION_UI_REFRESH);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }
}
