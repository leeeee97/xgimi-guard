package local.z7x.adb;

import java.io.IOException;

/**
 * Ensures the Aerial "home guard" accessibility service stays enabled.
 * The check and the fix both run through the device's own adbd (127.0.0.1:5555)
 * using shell, so no WRITE_SECURE_SETTINGS permission is needed by this app.
 */
public final class GuardController {
    public static final String GUARD_COMPONENT = "app.orchard.tv/.guard.HomeGuardService";
    public static final String AERIAL_PACKAGE = "app.orchard.tv";
    /** XGIMI voice service that must be preserved whenever we rewrite the list. */
    private static final String PRESERVED_COMPONENT =
            "com.xgimi.duertts/com.xgimi.duertts.MonitorService";

    private GuardController() { }

    public static final class Result {
        public final boolean changed;
        public final String detail;
        Result(boolean changed, String detail) { this.changed = changed; this.detail = detail; }
    }

    /** Checks the current state and repairs it when Aerial's guard is missing. */
    public static Result ensure(android.content.Context context) {
        try (AdbShellClient adb = AdbShellClient.connect(5555, AdbKeyManager.load(context))) {
            String current = adb.shell(
                    "settings get secure enabled_accessibility_services").trim();
            if ("null".equals(current) || "none".equals(current)) current = "";

            if (current.contains(AERIAL_PACKAGE)) {
                String enabled = adb.shell(
                        "settings get secure accessibility_enabled").trim();
                if ("1".equals(enabled)) {
                    return new Result(false, "Aerial 守护已启用，无需修复");
                }
            }

            StringBuilder merged = new StringBuilder();
            for (String part : new String[]{current, PRESERVED_COMPONENT, GUARD_COMPONENT}) {
                for (String item : part.split(":")) {
                    item = item.trim();
                    if (item.isEmpty()) continue;
                    if (merged.indexOf(item) >= 0) continue;
                    if (merged.length() > 0) merged.append(':');
                    merged.append(item);
                }
            }
            String list = merged.toString();
            // settings put with a value containing no spaces needs no quoting,
            // but quoting is harmless and safer against odd shell parsing.
            adb.shell("settings put secure enabled_accessibility_services \"" + list + "\"");
            adb.shell("settings put secure accessibility_enabled 1");

            String verify = adb.shell(
                    "settings get secure enabled_accessibility_services").trim();
            if (!verify.contains(AERIAL_PACKAGE)) {
                return new Result(true, "写入后复查未生效，可能被系统立刻回收（稍后重试）");
            }
            return new Result(true, "已恢复 Aerial 守护；当前启用: " + verify);
        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("等待授权") || msg.contains("AUTH_REQUIRED"))) {
                return new Result(false, msg
                        + "\n投影仪屏幕出现调试授权弹窗时，请用遥控器勾选「始终允许」并确认；"
                        + "确认后按「重新开启并检查」重试（密钥会被记住，之后不再弹窗）");
            }
            return new Result(false, "本机 ADB 连接失败: " + (msg == null ? e.toString() : msg));
        }
    }
}
