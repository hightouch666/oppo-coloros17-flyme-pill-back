package com.laotian.pillback;

/**
 * 全局常量。App 与 SystemUI 两侧共用（同一份代码打进同一个 APK）。
 */
public final class Cfg {

    private Cfg() {}

    /** App 侧 SharedPreferences 文件名。 */
    public static final String SP_NAME = "pillback";

    /** 配置项 key。 */
    public static final String K_ENABLED = "enabled";
    public static final String K_VIBRATE = "vibrate";
    public static final String K_STRENGTH = "strength";

    /** 跨进程读取配置的 ContentProvider authority（兜底通道）。 */
    public static final String AUTHORITY = "com.laotian.pillback.config";
    public static final String URI_STR = "content://" + AUTHORITY;
    public static final String METHOD_GET = "get";

    /**
     * 主通道：Settings.Global 里的配置串，格式 "enabled|vibrate|strength"，例如 "1|1|4"。
     * SystemUI 读 Settings.Global 有系统权限，比跨包访问 provider 稳定得多。
     * App 侧写入需要 WRITE_SECURE_SETTINGS（用 pm grant 授予，不需要 root）。
     */
    public static final String SETTINGS_KEY = "pillback_cfg";
    public static final String DEFAULT_CFG = "1|1|2";

    /** 五档名称。震动实现见 {@link Haptics}。 */
    public static final String[] LEVEL_LABELS = {"很弱", "弱", "中", "强", "很强"};

    /** 轻点判定：最大位移（dp）与最大时长（ms）。实测手点约 300ms、抖动 10dp，留余量。 */
    public static final float TAP_SLOP_DP = 12f;
    public static final long TAP_TIMEOUT_MS = 350L;

    /** 小横条本体的半宽上限（dp）。ColorOS 的 handle View 宽约 120dp。 */
    public static final float HANDLE_HALF_WIDTH_DP = 64f;

    /** 底部热区高度（dp）：手指落点距屏幕底部多少以内算"按在小横条上"。 */
    public static final float HANDLE_ZONE_BOTTOM_DP = 50f;

    /** SystemUI 包名。 */
    public static final String PKG_SYSTEMUI = "com.android.systemui";

    public static String labelOf(int level) {
        if (level < 0) level = 0;
        if (level >= LEVEL_LABELS.length) level = LEVEL_LABELS.length - 1;
        return LEVEL_LABELS[level];
    }
}
