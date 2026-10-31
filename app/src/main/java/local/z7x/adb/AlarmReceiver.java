package local.z7x.adb;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Periodic safety net: re-checks the guard every 10 minutes via AlarmManager,
 * independent of boot. Each firing reschedules the next one.
 */
public final class AlarmReceiver extends BroadcastReceiver {
    static final long INTERVAL_MS = 10 * 60 * 1000L;
    public static final String ACTION_RUN = "local.z7x.adb.RUN_GUARD";

    @Override public void onReceive(Context context, Intent intent) {
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                GuardController.Result r = GuardController.ensure();
                AndroidLog.w("guard", "periodic: " + r.detail);
            } catch (Throwable t) {
                AndroidLog.w("guard", "periodic failed: " + t);
            } finally {
                schedule(app);
                pending.finish();
            }
        }, "guard-periodic").start();
    }

    static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(context, AlarmReceiver.class).setAction(ACTION_RUN);
        PendingIntent pi = PendingIntent.getBroadcast(context, 1001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVAL_MS, pi);
    }
}
