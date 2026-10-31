package local.z7x.adb;
import android.content.*;
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || !context.getSharedPreferences("settings",0).getBoolean("boot",true)
                || context.getSharedPreferences("settings",0).getBoolean("manual_only",false)) return;
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                // 1) Make sure adbd listens on 5555 (XGIMI may reset it on reboot).
                try { AdbController.save(app, AdbController.enable()); }
                catch (Throwable t) { AndroidLog.w("guard", "boot: adb enable failed: " + t); }
                // 2) Repair the Aerial guard through the device's own adbd.
                try {
                    GuardController.Result r = GuardController.ensure();
                    AndroidLog.w("guard", "boot: " + r.detail);
                } catch (Throwable t) { AndroidLog.w("guard", "boot: guard failed: " + t); }
                // 3) Keep the periodic safety net armed.
                AlarmReceiver.schedule(app);
            } finally { pending.finish(); }
        }, "boot-guard").start();
    }
}
