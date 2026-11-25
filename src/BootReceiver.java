package app.oguri.vpnswitch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the monitoring service after a reboot, if monitoring is on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Prefs prefs = new Prefs(context);
            if (prefs.isMonitoring()) {
                SwitchService.start(context);
            }
        }
    }
}
