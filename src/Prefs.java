package app.oguri.vpnswitch;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** SharedPreferences storage: home SSIDs, last commanded state, event log. */
public class Prefs {
    public static final String STATE_HOME = "HOME";
    public static final String STATE_AWAY = "AWAY";
    private static final int MAX_LOG = 30;

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getSharedPreferences("vpnswitch", Context.MODE_PRIVATE);
    }

    public boolean isMonitoring() {
        return sp.getBoolean("monitoring", true);
    }

    public void setMonitoring(boolean on) {
        sp.edit().putBoolean("monitoring", on).apply();
    }

    public String getHomeSsidsText() {
        return sp.getString("home_ssids", "");
    }

    public void setHomeSsidsText(String text) {
        sp.edit().putString("home_ssids", text).apply();
    }

    public List<String> getHomeSsids() {
        List<String> out = new ArrayList<String>();
        String text = getHomeSsidsText();
        if (text != null) {
            String[] lines = text.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String s = lines[i].trim();
                if (s.length() > 0) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** Last state actually commanded to Tailscale: HOME, AWAY, or "" if none yet. */
    public String getCommandedState() {
        return sp.getString("commanded_state", "");
    }

    public void setCommandedState(String state) {
        sp.edit().putString("commanded_state", state).apply();
    }

    public String getLastAction() {
        return sp.getString("last_action", "");
    }

    public long getLastActionTime() {
        return sp.getLong("last_action_time", 0);
    }

    public void setLastAction(String action, long timeMillis) {
        sp.edit().putString("last_action", action)
                .putLong("last_action_time", timeMillis).apply();
    }

    public boolean getLastOnWifi() {
        return sp.getBoolean("last_on_wifi", false);
    }

    /** Last seen SSID; "" means none (not on WiFi, or name unknown). */
    public String getLastSsid() {
        return sp.getString("last_ssid", "");
    }

    public void setLastNet(boolean onWifi, String ssid) {
        sp.edit().putBoolean("last_on_wifi", onWifi)
                .putString("last_ssid", ssid == null ? "" : ssid).apply();
    }

    public void addLog(String entry) {
        String old = sp.getString("log", "");
        String line = System.currentTimeMillis() + "|" + entry;
        String combined = (old == null || old.length() == 0) ? line : line + "\n" + old;
        String[] lines = combined.split("\n");
        StringBuilder sb = new StringBuilder();
        int max = Math.min(lines.length, MAX_LOG);
        for (int i = 0; i < max; i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(lines[i]);
        }
        sp.edit().putString("log", sb.toString()).apply();
    }

    /** Log entries, newest first, with formatted timestamps. */
    public List<String> getLog() {
        List<String> out = new ArrayList<String>();
        String raw = sp.getString("log", "");
        if (raw != null && raw.length() > 0) {
            SimpleDateFormat fmt = new SimpleDateFormat("dd.MM HH:mm:ss", Locale.US);
            String[] lines = raw.split("\n");
            for (int i = 0; i < lines.length; i++) {
                int bar = lines[i].indexOf('|');
                if (bar > 0) {
                    try {
                        long t = Long.parseLong(lines[i].substring(0, bar));
                        out.add(fmt.format(new Date(t)) + "  " + lines[i].substring(bar + 1));
                    } catch (NumberFormatException e) {
                        out.add(lines[i]);
                    }
                } else {
                    out.add(lines[i]);
                }
            }
        }
        return out;
    }

    public static String formatTime(long t) {
        if (t <= 0) {
            return "never";
        }
        return new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US).format(new Date(t));
    }
}
