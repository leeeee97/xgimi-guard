package local.z7x.adb;

import android.content.Context;
import android.util.Log;

final class AndroidLog {
    private AndroidLog() { }
    static void w(String tag, String msg) { Log.w(tag, msg); }
}
