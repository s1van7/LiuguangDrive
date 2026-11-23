package cn.liuguang.drive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Optional Android 9 boot launcher. Disabled by default; accept system boot broadcasts only. */
public final class BootReceiver extends BroadcastReceiver {
    private static final String PREFS = "liuguang";
    private static final String KEY_AUTO_START = "autoStart";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_AUTO_START, false)) return;

        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            context.startActivity(launch);
        } catch (Exception ignored) {
            // Some vendor firmware blocks background activity launches; manual launch remains available.
        }
    }
}
