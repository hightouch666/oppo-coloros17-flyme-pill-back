package com.laotian.pillback;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * 震动工具。
 *
 * 关键：很多线性马达（包括 ColorOS 17 上这台 PLG110，capabilitiesFlags=11）
 * **没有 AMPLITUDE_CONTROL 能力**，此时 createOneShot(ms, amplitude) 里的 amplitude 会被忽略，
 * 五档听起来完全一样。所以分两条路：
 *   - 支持振幅：用 amplitude + 时长
 *   - 不支持：用「脉冲数量 + 单脉冲时长」表达强弱（1下短 → 1下长 → 两声 → 三声）
 */
public final class Haptics {

    /** 独立开关：本类也会在 App 进程里跑，不能引用 PillBackHook（那里有 Xposed 依赖）。 */
    static volatile boolean DEBUG = false;

    private Haptics() {}

    /** 支持振幅控制时的时长（ms），随档位递增。 */
    private static final int[] DUR_AMP = {10, 16, 24, 32, 40};

    /** 支持振幅控制时的振幅。 */
    private static final int[] AMP = {40, 90, 150, 210, 255};

    /**
     * 不支持振幅控制时的时长（ms）。**必须落在这台机器 HAL 的不同映射区间里**，
     * 否则会被合并成同一个预置效果，五档听感完全一样。
     *
     * PLG110 + ColorOS 17 实测（`dumpsys vibrator_manager` + aidl_vibrator 日志）：
     *   ≤10ms      → 预置 waveformId=7（30ms）
     *   11~45ms    → 预置 waveformId=2（45ms）
     *   46~100ms   → 预置 waveformId=6（45ms，另一种驱动波形）
     *   ≥105ms     → 走连续振动通道 `Vibrator on for timeoutMs: N`，时长精确生效
     *
     * 注意：同一段波形里的多个短脉冲（如 {0,45,8,30}）会被 HAL 合并成一次预置效果，
     * 所以「多震几下」的写法在这台机器上无效，只能靠单次时长跨区间。
     */
    private static final long[] DUR_NO_AMP = {8, 30, 70, 120, 200};

    public static void play(Context ctx, int level) {
        if (level < 0) level = 0;
        if (level >= DUR_NO_AMP.length) level = DUR_NO_AMP.length - 1;
        try {
            Vibrator v = vibrator(ctx);
            if (v == null) {
                L.w("震动跳过：拿不到 Vibrator");
                return;
            }
            if (!v.hasVibrator()) {
                L.w("震动跳过：设备无马达");
                return;
            }
            boolean ampOk = v.hasAmplitudeControl();

            VibrationEffect effect;
            if (ampOk) {
                effect = VibrationEffect.createOneShot(DUR_AMP[level], AMP[level]);
            } else {
                effect = VibrationEffect.createOneShot(
                        DUR_NO_AMP[level], VibrationEffect.DEFAULT_AMPLITUDE);
            }
            // 标成「触摸反馈」用途：系统会按触感策略处理（跟随系统触感强度/静音策略），
            // 不会被当成通知震动，也避免落到 USAGE_UNKNOWN 被某些省电策略吞掉。
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, touchAttrs());
            } else {
                v.vibrate(effect);
            }
            if (DEBUG) {
                L.i("震动 level=" + level + " ampControl=" + ampOk
                        + " " + (ampOk ? ("dur=" + DUR_AMP[level] + " amp=" + AMP[level])
                        : ("dur=" + DUR_NO_AMP[level] + "ms")));
            }
        } catch (Throwable t) {
            L.w("震动失败: " + t);
        }
    }

    private static volatile android.os.VibrationAttributes attrs;

    /** VibrationAttributes.USAGE_TOUCH，惰性创建并复用，避免每次点击都 new。 */
    private static android.os.VibrationAttributes touchAttrs() {
        android.os.VibrationAttributes a = attrs;
        if (a != null) return a;
        a = android.os.VibrationAttributes.createForUsage(
                android.os.VibrationAttributes.USAGE_TOUCH);
        attrs = a;
        return a;
    }

    private static Vibrator vibrator(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm =
                    (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm != null) return vm.getDefaultVibrator();
        }
        return (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
    }
}
