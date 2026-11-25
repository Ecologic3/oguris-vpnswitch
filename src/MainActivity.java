package app.oguri.vpnswitch;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * VPN Switch's own "midnight indigo" theme (since 1.9): deep
 * blue-slate surfaces, pale text, and a signal-cyan accent that is
 * used sparingly (the monitoring-on state, the add-current action,
 * section accents). Deliberately not shared with the other Oguri
 * apps or the mnemosyne dashboard. All styling is programmatic:
 * colors, rounded GradientDrawable backgrounds and padding in code.
 */
public class MainActivity extends Activity {
    private static final int REQ_LOCATION = 1;
    private static final int REQ_NOTIFICATIONS = 2;
    private static final String TS_PACKAGE = "com.tailscale.ipn";

    private static final int COL_BG = 0xFF0F1626;
    private static final int COL_SURFACE = 0xFF1A2338;
    private static final int COL_RAISED = 0xFF232F4D;
    private static final int COL_BORDER = 0xFF2C3B5F;
    private static final int COL_TEXT = 0xFFEDF2FA;
    private static final int COL_MUTED = 0xFF9FB0CC;
    private static final int COL_FAINT = 0xFF5F7194;
    private static final int COL_ACCENT = 0xFF45D8FF;
    private static final int COL_ACCENT_PRESSED = 0xFF8AE9FF;
    private static final int COL_ON_ACCENT = 0xFF08222E;
    private static final int COL_BTN = 0xFF232F4D;
    private static final int COL_BTN_PRESSED = 0xFF2E3D63;
    private static final int COL_BTN_DISABLED = 0xFF1A2338;
    private static final int COL_REMOVE = 0xFFFF8A8A;
    private static final int COL_REMOVE_BG = 0xFF2C3B5F;
    private static final int COL_REMOVE_BG_PRESSED = 0xFF46324A;

    private Prefs prefs;
    private TextView statusSsid;
    private TextView statusState;
    private TextView statusLastAction;
    private TextView hintUnknown;
    private TextView noteNoTailscale;
    private TextView logView;
    private Button toggleButton;
    private Button logToggleButton;
    private Button addCurrentButton;
    private LinearLayout networksContainer;
    private BroadcastReceiver refreshReceiver;
    private boolean receiverRegistered = false;
    private boolean logOpen = false;
    // SSID of the network the phone is on right now, from the latest
    // one-shot read in refresh(); null when not on WiFi or unreadable.
    private String currentSsid = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        buildUi();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
        }
        if (prefs.isMonitoring()) {
            SwitchService.start(this);
        }
        refresh();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams lp(int topMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topMarginDp);
        return p;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private StateListDrawable buttonBg(int normal, int pressed, int disabled) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(pressed, 10));
        s.addState(new int[]{-android.R.attr.state_enabled}, rounded(disabled, 10));
        s.addState(new int[]{}, rounded(normal, 10));
        return s;
    }

    private LinearLayout makeCard(int topMarginDp) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = rounded(COL_SURFACE, 14);
        bg.setStroke(dp(1), COL_BORDER);
        card.setBackground(bg);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setLayoutParams(lp(topMarginDp));
        return card;
    }

    private void addSectionHeader(LinearLayout parent, String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(16);
        t.setTextColor(COL_TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        parent.addView(t);
        View bar = new View(this);
        bar.setBackgroundColor(COL_ACCENT);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(30), dp(2));
        blp.topMargin = dp(5);
        bar.setLayoutParams(blp);
        parent.addView(bar);
    }

    private Button makeButton(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setTextColor(COL_TEXT);
        b.setBackground(buttonBg(COL_BTN, COL_BTN_PRESSED, COL_BTN_DISABLED));
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        if (listener != null) {
            b.setOnClickListener(listener);
        }
        return b;
    }

    private TextView makeText(String text, float size, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private void buildUi() {
        getWindow().setBackgroundDrawable(new ColorDrawable(COL_BG));
        getWindow().setStatusBarColor(COL_BG);
        getWindow().setNavigationBarColor(COL_BG);
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COL_BG);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));

        TextView title = makeText("Oguri VPN Switch", 26, COL_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = makeText("Tailscale off on your home WiFi, on everywhere else.",
                14, COL_MUTED);
        sub.setPadding(0, dp(4), 0, 0);
        root.addView(sub);

        // Status block.
        LinearLayout statusCard = makeCard(16);
        statusSsid = makeText("", 16, COL_TEXT);
        statusCard.addView(statusSsid);
        statusState = makeText("", 16, COL_TEXT);
        statusState.setPadding(0, dp(3), 0, 0);
        statusCard.addView(statusState);
        statusLastAction = makeText("", 14, COL_MUTED);
        statusLastAction.setPadding(0, dp(3), 0, 0);
        statusCard.addView(statusLastAction);
        root.addView(statusCard);

        // Home networks: a list with per-row remove controls and an
        // add-current-network button. No text editor, no save button:
        // every edit rewrites the stored list at once (the same
        // newline-joined Prefs text the service reads per evaluation).
        LinearLayout homeCard = makeCard(12);
        addSectionHeader(homeCard, "Home networks");
        networksContainer = new LinearLayout(this);
        networksContainer.setOrientation(LinearLayout.VERTICAL);
        homeCard.addView(networksContainer);
        addCurrentButton = makeButton("Add current network",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String ssid = currentSsid;
                        if (ssid == null) {
                            return;
                        }
                        List<String> home = prefs.getHomeSsids();
                        if (!home.contains(ssid)) {
                            home.add(ssid);
                            writeHomeSsids(home);
                            prefs.addLog("Home network added: " + ssid);
                            if (prefs.isMonitoring()) {
                                SwitchService.reevaluate(MainActivity.this);
                            }
                        }
                        refresh();
                    }
                });
        addCurrentButton.setLayoutParams(lp(12));
        homeCard.addView(addCurrentButton);
        root.addView(homeCard);

        // Monitoring toggle, above the permission buttons.
        toggleButton = makeButton("", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !prefs.isMonitoring();
                prefs.setMonitoring(on);
                prefs.addLog(on ? "Monitoring started" : "Monitoring stopped");
                if (on) {
                    SwitchService.start(MainActivity.this);
                } else {
                    SwitchService.stop(MainActivity.this);
                }
                refresh();
            }
        });
        toggleButton.setLayoutParams(lp(14));
        root.addView(toggleButton);

        // Permissions: explanation text, then the two buttons.
        LinearLayout permCard = makeCard(12);
        TextView permInfo = makeText("Android requires location permission to read "
                + "the WiFi name. Without it the app cannot tell home WiFi from "
                + "any other network.", 14, COL_MUTED);
        permCard.addView(permInfo);

        Button permButton = makeButton("Grant location permission",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                                == PackageManager.PERMISSION_GRANTED) {
                            Toast.makeText(MainActivity.this,
                                    "Location permission already granted",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            requestPermissions(
                                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                                    REQ_LOCATION);
                        }
                    }
                });
        permButton.setLayoutParams(lp(10));
        permCard.addView(permButton);

        Button batteryButton = makeButton("Battery exemption",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            Intent i = new Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            i.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this,
                                    "Could not open battery settings",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
        batteryButton.setLayoutParams(lp(10));
        permCard.addView(batteryButton);
        root.addView(permCard);

        hintUnknown = makeText("WiFi name is hidden right now. Android only reveals "
                + "it with location permission granted and location services "
                + "switched on. While the name is unreadable the app cannot tell "
                + "home from away, so it holds its current state and does not "
                + "switch.", 14, COL_MUTED);
        hintUnknown.setLayoutParams(lp(12));
        root.addView(hintUnknown);

        noteNoTailscale = makeText("Tailscale is not installed on this phone, so "
                + "there is nothing to switch. Install Tailscale and sign in first.",
                14, COL_MUTED);
        noteNoTailscale.setLayoutParams(lp(12));
        root.addView(noteNoTailscale);

        // Event log: hidden until the toggle at the bottom opens it.
        logToggleButton = makeButton("Show log", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logOpen = !logOpen;
                logView.setVisibility(logOpen ? View.VISIBLE : View.GONE);
                logToggleButton.setText(logOpen ? "Hide log" : "Show log");
            }
        });
        logToggleButton.setLayoutParams(lp(14));
        root.addView(logToggleButton);

        logView = makeText("", 13, COL_MUTED);
        logView.setTypeface(Typeface.MONOSPACE);
        GradientDrawable logBg = rounded(COL_SURFACE, 14);
        logBg.setStroke(dp(1), COL_BORDER);
        logView.setBackground(logBg);
        logView.setPadding(dp(14), dp(12), dp(14), dp(12));
        logView.setLayoutParams(lp(10));
        logView.setVisibility(View.GONE);
        root.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COL_BG);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refresh();
            }
        };
        IntentFilter filter = new IntentFilter(SwitchService.ACTION_UI_REFRESH);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(refreshReceiver, filter);
        }
        receiverRegistered = true;
        refresh();
    }

    @Override
    protected void onPause() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(refreshReceiver);
            } catch (Exception e) {
                // already unregistered
            }
            receiverRegistered = false;
        }
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            prefs.addLog("Location permission granted");
            if (prefs.isMonitoring()) {
                SwitchService.reevaluate(this);
            }
        }
        refresh();
    }

    private boolean tailscaleInstalled() {
        try {
            getPackageManager().getPackageInfo(TS_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Persists the home list in the established newline-joined format. */
    private void writeHomeSsids(List<String> ssids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ssids.size(); i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(ssids.get(i));
        }
        prefs.setHomeSsidsText(sb.toString());
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

    private static class CurrentNet {
        boolean onWifi;
        String ssid; // null when not on WiFi or the name is unreadable
    }

    /**
     * One-shot read of the network the phone is on right now, for the
     * add-current-network button. Mirrors the supplement reads in
     * SwitchService.readNet(): the active network's capabilities
     * transport info and WifiManager.getConnectionInfo() (the app
     * holds location permission). WifiManager is what settles "on
     * WiFi" while a VPN is the active network.
     */
    private CurrentNet readCurrentNet() {
        CurrentNet cn = new CurrentNet();
        String ssid = null;
        boolean wifiTransport = false;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            try {
                Network active = cm.getActiveNetwork();
                if (active != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                    if (caps != null) {
                        wifiTransport = caps.hasTransport(
                                NetworkCapabilities.TRANSPORT_WIFI);
                        if (Build.VERSION.SDK_INT >= 31) {
                            Object ti = caps.getTransportInfo();
                            if (ti instanceof WifiInfo) {
                                ssid = cleanSsid(((WifiInfo) ti).getSSID());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // fall through to WifiManager
            }
        }
        boolean wmConnected = false;
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null && wi.getNetworkId() != -1) {
                    wmConnected = true;
                    if (ssid == null) {
                        ssid = cleanSsid(wi.getSSID());
                    }
                }
            }
        } catch (Exception e) {
            // no WiFi info available
        }
        cn.onWifi = wifiTransport || wmConnected;
        cn.ssid = ssid;
        return cn;
    }

    /** Rebuilds the home-network rows and the add-current button state. */
    private void rebuildNetworks() {
        networksContainer.removeAllViews();
        final List<String> home = prefs.getHomeSsids();
        if (home.isEmpty()) {
            TextView empty = makeText("No home networks yet. Connect to one "
                    + "and add it below.", 14, COL_MUTED);
            empty.setLayoutParams(lp(10));
            networksContainer.addView(empty);
        }
        for (int i = 0; i < home.size(); i++) {
            final String ssid = home.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(rounded(COL_RAISED, 10));
            row.setPadding(dp(14), dp(8), dp(8), dp(8));
            row.setLayoutParams(lp(8));

            TextView name = makeText(ssid, 15, COL_TEXT);
            name.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(name);

            Button remove = new Button(this);
            remove.setText("Remove");
            remove.setTextSize(13);
            remove.setTextColor(COL_REMOVE);
            remove.setBackground(buttonBg(COL_REMOVE_BG, COL_REMOVE_BG_PRESSED,
                    COL_BTN_DISABLED));
            remove.setPadding(dp(12), dp(6), dp(12), dp(6));
            remove.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    List<String> list = prefs.getHomeSsids();
                    if (list.remove(ssid)) {
                        writeHomeSsids(list);
                        prefs.addLog("Home network removed: " + ssid);
                        if (prefs.isMonitoring()) {
                            SwitchService.reevaluate(MainActivity.this);
                        }
                    }
                    refresh();
                }
            });
            row.addView(remove);
            networksContainer.addView(row);
        }

        CurrentNet cn = readCurrentNet();
        currentSsid = cn.ssid;
        if (!cn.onWifi) {
            addCurrentButton.setEnabled(false);
            addCurrentButton.setText("Add current network (not on WiFi)");
            styleAddButton(false);
        } else if (cn.ssid == null) {
            addCurrentButton.setEnabled(false);
            addCurrentButton.setText(
                    "Add current network (name not readable right now)");
            styleAddButton(false);
        } else if (home.contains(cn.ssid)) {
            addCurrentButton.setEnabled(false);
            addCurrentButton.setText("Already a home network: " + cn.ssid);
            styleAddButton(false);
        } else {
            addCurrentButton.setEnabled(true);
            addCurrentButton.setText("Add current network: " + cn.ssid);
            styleAddButton(true);
        }
    }

    private void styleAddButton(boolean primary) {
        if (primary) {
            addCurrentButton.setBackground(buttonBg(COL_ACCENT,
                    COL_ACCENT_PRESSED, COL_BTN_DISABLED));
            addCurrentButton.setTextColor(COL_ON_ACCENT);
        } else {
            addCurrentButton.setBackground(buttonBg(COL_BTN,
                    COL_BTN_PRESSED, COL_BTN_DISABLED));
            addCurrentButton.setTextColor(COL_FAINT);
        }
    }

    private void refresh() {
        if (statusSsid == null) {
            return;
        }
        boolean onWifi = prefs.getLastOnWifi();
        String ssid = prefs.getLastSsid();
        if (!onWifi) {
            statusSsid.setText("WiFi: not on WiFi (mobile data)");
        } else if (ssid.length() == 0) {
            statusSsid.setText("WiFi: connected, name unknown");
        } else {
            statusSsid.setText("WiFi: " + ssid);
        }

        String state = prefs.getCommandedState();
        if (Prefs.STATE_HOME.equals(state)) {
            statusState.setText("State: Home (Tailscale off)");
        } else if (Prefs.STATE_AWAY.equals(state)) {
            statusState.setText("State: Away (Tailscale on)");
        } else {
            statusState.setText("State: not switched yet");
        }

        String last = prefs.getLastAction();
        if (last.length() == 0) {
            statusLastAction.setText("Last action: none yet");
        } else {
            statusLastAction.setText("Last action: " + last + " at "
                    + Prefs.formatTime(prefs.getLastActionTime()));
        }

        hintUnknown.setVisibility(onWifi && ssid.length() == 0 ? View.VISIBLE : View.GONE);
        noteNoTailscale.setVisibility(tailscaleInstalled() ? View.GONE : View.VISIBLE);

        boolean monitoring = prefs.isMonitoring();
        toggleButton.setText(monitoring
                ? "Monitoring: ON (tap to stop)" : "Monitoring: OFF (tap to start)");
        if (monitoring) {
            toggleButton.setBackground(buttonBg(COL_ACCENT, COL_ACCENT_PRESSED,
                    COL_BTN_DISABLED));
            toggleButton.setTextColor(COL_ON_ACCENT);
        } else {
            toggleButton.setBackground(buttonBg(COL_BTN, COL_BTN_PRESSED,
                    COL_BTN_DISABLED));
            toggleButton.setTextColor(COL_TEXT);
        }

        rebuildNetworks();

        List<String> log = prefs.getLog();
        if (log.isEmpty()) {
            logView.setText("(no events yet)");
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < log.size(); i++) {
                if (i > 0) {
                    sb.append("\n");
                }
                sb.append(log.get(i));
            }
            logView.setText(sb.toString());
        }
    }
}
