package app.oguri.vpnswitch;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_LOCATION = 1;
    private static final int REQ_NOTIFICATIONS = 2;
    private static final String TS_PACKAGE = "com.tailscale.ipn";

    private Prefs prefs;
    private TextView statusSsid;
    private TextView statusState;
    private TextView statusLastAction;
    private TextView hintUnknown;
    private TextView noteNoTailscale;
    private TextView logView;
    private Button toggleButton;
    private EditText ssidEditor;
    private BroadcastReceiver refreshReceiver;
    private boolean receiverRegistered = false;

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

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("Oguri VPN Switch");
        title.setTextSize(24);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Tailscale off on your home WiFi, on everywhere else.\n");
        sub.setTextSize(14);
        root.addView(sub);

        statusSsid = new TextView(this);
        statusSsid.setTextSize(16);
        root.addView(statusSsid);

        statusState = new TextView(this);
        statusState.setTextSize(16);
        root.addView(statusState);

        statusLastAction = new TextView(this);
        statusLastAction.setTextSize(14);
        root.addView(statusLastAction);

        hintUnknown = new TextView(this);
        hintUnknown.setText("\nWiFi name is hidden right now. Android only reveals it with "
                + "location permission granted and location services switched on. Until then "
                + "this counts as Away, so Tailscale stays on (the safe direction).\n");
        hintUnknown.setTextSize(14);
        root.addView(hintUnknown);

        noteNoTailscale = new TextView(this);
        noteNoTailscale.setText("\nTailscale is not installed on this phone, so there is "
                + "nothing to switch. Install Tailscale and sign in first.\n");
        noteNoTailscale.setTextSize(14);
        root.addView(noteNoTailscale);

        TextView editorLabel = new TextView(this);
        editorLabel.setText("\nHome networks (one WiFi name per line):");
        editorLabel.setTextSize(16);
        root.addView(editorLabel);

        ssidEditor = new EditText(this);
        ssidEditor.setMinLines(2);
        ssidEditor.setText(prefs.getHomeSsidsText());
        root.addView(ssidEditor);

        Button saveButton = new Button(this);
        saveButton.setText("Save home networks");
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setHomeSsidsText(ssidEditor.getText().toString());
                prefs.addLog("Home networks saved");
                if (prefs.isMonitoring()) {
                    SwitchService.reevaluate(MainActivity.this);
                }
                Toast.makeText(MainActivity.this, "Saved", Toast.LENGTH_SHORT).show();
                refresh();
            }
        });
        root.addView(saveButton);

        TextView permInfo = new TextView(this);
        permInfo.setText("\nAndroid requires location permission to read the WiFi name. "
                + "Without it the app cannot tell home WiFi from any other network.");
        permInfo.setTextSize(14);
        root.addView(permInfo);

        Button permButton = new Button(this);
        permButton.setText("Grant location permission");
        permButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(MainActivity.this, "Location permission already granted",
                            Toast.LENGTH_SHORT).show();
                } else {
                    requestPermissions(
                            new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                            REQ_LOCATION);
                }
            }
        });
        root.addView(permButton);

        Button batteryButton = new Button(this);
        batteryButton.setText("Battery exemption");
        batteryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "Could not open battery settings", Toast.LENGTH_SHORT).show();
                }
            }
        });
        root.addView(batteryButton);

        toggleButton = new Button(this);
        toggleButton.setOnClickListener(new View.OnClickListener() {
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
        root.addView(toggleButton);

        TextView logLabel = new TextView(this);
        logLabel.setText("\nRecent events:");
        logLabel.setTextSize(16);
        root.addView(logLabel);

        logView = new TextView(this);
        logView.setTextSize(13);
        root.addView(logView);

        ScrollView scroll = new ScrollView(this);
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
        toggleButton.setText(prefs.isMonitoring()
                ? "Monitoring: ON (tap to stop)" : "Monitoring: OFF (tap to start)");

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
