package com.laotian.pillback;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 核心 hook：ColorOS 手势小横条（底部小条）轻点 → 返回上一层。
 *
 * 真实触摸入口（反汇编 ColorOS 17 PLG110_17.0.0.105 实测）：
 *   SideGestureDetector.onMotionEventImpl(MotionEvent, Map)   ← 整块底部手势区的触摸都从这里过
 *     ├─ 上滑回桌面 / 左右滑切最近应用 / 边缘返回
 *     └─ 条件满足时才转发给 OplusNavigationHandle.handleValidTouchEvent()
 *
 * 踩过的坑（别再走）：
 *   1) 只 hook handleValidTouchEvent 不够：它是"被转发"的，转发条件不满足就收不到触摸。
 *   2) MotionEvent 是窗口坐标（0..屏宽），不是 View 局部坐标。
 *   3) setNavBarMode 传 2 是常态（半透明），不能当全屏判据。
 *   4) setFadedAnimProgress 是"按压手势条时淡出"的反馈动画，不是沉浸标志。
 */
public final class PillBackHook {

    /**
     * 调试日志开关。正式使用务必 false：
     * onMotionEventImpl 是底部手势区的热路径，手指每次抬起/滑动都会进这里，
     * 开着日志会给 SystemUI 持续写 logcat（耗电 + 可能掉帧）。
     */
    static final boolean DEBUG = false;

    private static final String CLS_SIDE_DETECTOR =
            "com.oplus.systemui.navigationbar.gesture.sidegesture.SideGestureDetector";
    private static final String CLS_HANDLE =
            "com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle";
    private static final String CLS_NAV_BAR_VIEW =
            "com.oplusos.systemui.navigationbar.OplusNavigationBarView";

    private PillBackHook() {}

    // ---------------------------------------------------------------- 状态

    private static boolean pending;
    private static int downPointerCount;
    private static float downX;
    private static float downY;
    private static long downTime;
    private static long lastFire;

    private static volatile long sysUiFlags;
    private static volatile int navBarMode = -1;
    private static volatile float fadedProgress;

    /** 小横条自身的 alpha。普通界面 1.0，沉浸/全屏会淡出。 */
    private static volatile float handleAlpha = 1f;

    private static volatile int scrW;
    private static volatile int scrH;
    private static volatile String homePkg;

    // ---------------------------------------------------------------- 安装

    public static void install(ClassLoader cl) {
        hookSideDetector(cl);
        hookHandleTouch(cl);
        hookHandleAlpha(cl);
        hookSysUiFlags(cl);
        hookNavBarMode(cl);
    }

    /** 记录小横条透明度，作为全屏/沉浸判据。 */
    private static void hookHandleAlpha(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_HANDLE, cl);
            for (final Method m : cls.getDeclaredMethods()) {
                if (!"setAlpha".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 1
                        || m.getParameterTypes()[0] != float.class) continue;

                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        float v = (Float) param.args[0];
                        if (Math.abs(v - handleAlpha) > 0.05f) {
                            handleAlpha = v;
                            if (DEBUG) L.i("handleAlpha -> " + v);
                        }
                    }
                });
                L.i("已 hook setAlpha");
                return;
            }
            L.w("OplusNavigationHandle 上没有 setAlpha(float)");
        } catch (Throwable t) {
            L.w("hook setAlpha 失败: " + t);
        }
    }

    /** 主 hook：底部手势区的真实触摸入口。 */
    private static void hookSideDetector(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_SIDE_DETECTOR, cl);
            for (final Method m : cls.getDeclaredMethods()) {
                if (!"onMotionEventImpl".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 2 || pt[0] != MotionEvent.class) continue;
                if (pt[1] != Map.class) continue;

                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            onTouch((MotionEvent) param.args[0], "detector");
                        } catch (Throwable t) {
                            L.e("onTouch 异常", t);
                        }
                    }
                });
                L.i("已 hook SideGestureDetector.onMotionEventImpl");
                return;
            }
            L.w("SideGestureDetector 上没有 onMotionEventImpl(MotionEvent,Map)");
        } catch (Throwable t) {
            L.e("hook SideGestureDetector 失败", t);
        }
    }

    /** 备用 hook：小横条被转发到的事件（某些场景只有这条路径）。 */
    private static void hookHandleTouch(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_HANDLE, cl);
            for (final Method m : cls.getDeclaredMethods()) {
                if (!"handleValidTouchEvent".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 1
                        || m.getParameterTypes()[0] != MotionEvent.class) continue;

                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            onTouch((MotionEvent) param.args[0], "handle");
                        } catch (Throwable t) {
                            L.e("onTouch 异常", t);
                        }
                    }
                });
                L.i("已 hook handleValidTouchEvent（备用路径）");
                return;
            }
        } catch (Throwable t) {
            L.w("hook handleValidTouchEvent 失败: " + t);
        }
    }

    /** 记录 SystemUI 的 flags，用作全屏判据。 */
    private static void hookSysUiFlags(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_SIDE_DETECTOR, cl);
            for (final Method m : cls.getDeclaredMethods()) {
                if (!"updateSysUiFlags".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 1
                        || m.getParameterTypes()[0] != long.class) continue;

                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        long v = (Long) param.args[0];
                        if (v != sysUiFlags) {
                            sysUiFlags = v;
                            if (DEBUG) L.i("sysUiFlags -> 0x" + Long.toHexString(v));
                        }
                    }
                });
                L.i("已 hook updateSysUiFlags");
                return;
            }
            L.w("SideGestureDetector 上没有 updateSysUiFlags(long)");
        } catch (Throwable t) {
            L.w("hook updateSysUiFlags 失败: " + t);
        }
    }

    private static void hookNavBarMode(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_NAV_BAR_VIEW, cl);
            for (final Method m : cls.getDeclaredMethods()) {
                if (!"setNavBarMode".equals(m.getName())
                        || m.getParameterTypes().length < 1
                        || m.getParameterTypes()[0] != int.class) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int v = (Integer) param.args[0];
                        if (v != navBarMode) {
                            navBarMode = v;
                            if (DEBUG) L.i("navBarMode -> " + v);
                        }
                    }
                });
                L.i("已 hook setNavBarMode" + java.util.Arrays.toString(m.getParameterTypes()));
                return;
            }
        } catch (Throwable t) {
            L.w("hook setNavBarMode 失败: " + t);
        }
    }

    // ---------------------------------------------------------------- 触摸

    private static void onTouch(MotionEvent ev, String from) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                downTime = ev.getEventTime();
                downPointerCount = ev.getPointerCount();
                pending = true;
                if (DEBUG) {
                    L.i("DOWN[" + from + "] x=" + downX + " y=" + downY
                            + " screen=" + screenW() + "x" + screenH());
                }
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                pending = false;
                break;

            case MotionEvent.ACTION_UP:
                if (!pending) return;
                pending = false;
                if (ev.getPointerCount() > 1 || downPointerCount > 1) {
                    if (DEBUG) L.i("跳过：多指");
                    return;
                }
                handleTap(ev, from);
                break;

            case MotionEvent.ACTION_CANCEL:
                pending = false;
                break;

            default:
                break;
        }
    }

    private static void handleTap(MotionEvent ev, String from) {
        Context ctx = appContext();
        if (ctx == null) {
            L.w("拿不到 Context，跳过");
            return;
        }

        long dur = ev.getEventTime() - downTime;
        float dx = Math.abs(ev.getX() - downX);
        float dy = Math.abs(ev.getY() - downY);
        float slop = dp(ctx, Cfg.TAP_SLOP_DP);

        if (dur > Cfg.TAP_TIMEOUT_MS) {
            if (DEBUG) L.i("跳过：时长 " + dur + "ms");
            return;
        }
        if (dx > slop || dy > slop) {
            if (DEBUG) L.i("跳过：位移 dx=" + dx + " dy=" + dy + " slop=" + slop);
            return;
        }
        if (!inHandleArea(ctx, ev.getX(), ev.getY())) {
            if (DEBUG) {
                L.i("跳过：不在小横条本体 x=" + ev.getX() + " y=" + ev.getY()
                        + " 中心=" + (screenW() / 2f) + " 底界=" + (screenH() - dp(ctx, Cfg.HANDLE_ZONE_BOTTOM_DP)));
            }
            return;
        }
        if (!isPortrait(ctx)) {
            if (DEBUG) L.i("跳过：非竖屏 rot=" + rotationOf(ctx));
            return;
        }
        if (isLauncher(ctx)) {
            if (DEBUG) L.i("跳过：桌面");
            return;
        }
        if (isImmersive()) {
            if (DEBUG) {
                L.i("跳过：全屏/沉浸 handleAlpha=" + handleAlpha
                        + " mode=" + navBarMode + " flags=0x" + Long.toHexString(sysUiFlags));
            }
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (now - lastFire < 300) {
            if (DEBUG) L.i("跳过：去重 " + (now - lastFire) + "ms");
            return;
        }
        lastFire = now;

        if (!ConfigClient.enabled) {
            if (DEBUG) L.i("跳过：模块已关闭");
            return;
        }

        boolean ok = injectBack();
        if (!ok) return;

        // 每次成功点击只打一行（不是每个触摸事件都打），既方便排查也不会有日志开销问题。
        final String f = from;
        final long d = dur;
        final boolean fired = ok;

        // 返回键已经发出，震动等配置读完再补（最多几十毫秒，用户无感），
        // 这样刚在 App 里调完强度，下一次点击就能用上新值。
        ConfigClient.refreshThen(ctx, new Runnable() {
            @Override
            public void run() {
                boolean vib = ConfigClient.vibrate;
                int lv = ConfigClient.strength;
                L.i("轻点生效[" + f + "] dur=" + d + "ms back=" + fired
                        + " vibrate=" + vib + " level=" + lv);
                if (vib) {
                    Haptics.play(ctx, lv);
                }
            }
        });
    }

    /**
     * 小横条本体判定（窗口坐标）：
     *   x 距屏幕中心不超过 HANDLE_HALF_WIDTH_DP，y 落在屏幕底部热区内。
     * ColorOS 17 实测：屏 1272x2772，条 View 宽 420px（约 120dp）、高 154px。
     */
    private static boolean inHandleArea(Context ctx, float x, float y) {
        int sw = screenW();
        int sh = screenH();
        if (sw <= 0 || sh <= 0) return true;

        float halfW = dp(ctx, Cfg.HANDLE_HALF_WIDTH_DP);
        float bottom = dp(ctx, Cfg.HANDLE_ZONE_BOTTOM_DP);
        return Math.abs(x - sw / 2f) <= halfW && y >= (sh - bottom);
    }

    private static boolean isPortrait(Context ctx) {
        int r = rotationOf(ctx);
        return r == Surface.ROTATION_0 || r == Surface.ROTATION_180;
    }

    private static volatile WindowManager wmCache;

    private static int rotationOf(Context ctx) {
        try {
            WindowManager wm = wmCache;
            if (wm == null) {
                wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                wmCache = wm;
            }
            return wm.getDefaultDisplay().getRotation();
        } catch (Throwable t) {
            return Surface.ROTATION_0;
        }
    }

    /**
     * 全屏 / 沉浸判定。
     *
     * 试过但不用的判据（都有实测反例）：
     *   - setNavBarMode：普通界面就是 2，全屏也是 2，没有区分度。
     *   - setFadedAnimProgress：按压小横条时就会升到 1.0（按压反馈动画）。
     *   - updateSysUiFlags：edge-to-edge 应用普遍带 FULLSCREEN/HIDE_NAVIGATION 位，
     *     LSPosed Manager 这种普通界面就会误判成全屏。
     *
     * 现在用 handle 自身透明度：沉浸/全屏时 ColorOS 会把手势条淡出（alpha 明显下降），
     * 普通界面稳定在 1.0。阈值取 0.9，宁可少拦也不错杀。
     */
    private static boolean isImmersive() {
        return handleAlpha < 0.9f;
    }

    private static boolean isLauncher(Context ctx) {
        String top = topPackage();
        String home = homePackage(ctx);
        if (top == null || home == null) return false;
        return home.equals(top);
    }

    private static String homePackage(Context ctx) {
        if (homePkg != null) return homePkg;
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = ctx.getPackageManager()
                    .resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) {
                homePkg = ri.activityInfo.packageName;
            }
        } catch (Throwable t) {
            L.w("取桌面包名失败: " + t);
        }
        return homePkg;
    }

    private static volatile Object atmService;
    private static volatile Method mFocusedTask;
    private static volatile Field fTopActivity;

    /**
     * 取前台包名，用来排除桌面。
     * 反射的 Method/Field 全部缓存：这段跑在 SystemUI 的输入线程上，
     * 每次点击都重新 getMethod/getField 会有明显开销。
     */
    private static String topPackage() {
        try {
            if (atmService == null) {
                Class<?> c = Class.forName("android.app.ActivityTaskManager");
                Object s = c.getMethod("getService").invoke(null);
                if (s == null) return null;
                atmService = s;
            }
            Method gm = mFocusedTask;
            if (gm == null) {
                gm = atmService.getClass().getMethod("getFocusedRootTaskInfo");
                mFocusedTask = gm;
            }
            Object info = gm.invoke(atmService);
            if (info == null) return null;

            Field f = fTopActivity;
            if (f == null) {
                f = info.getClass().getField("topActivity");
                fTopActivity = f;
            }
            Object cn = f.get(info);
            return cn instanceof ComponentName ? ((ComponentName) cn).getPackageName() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- Context / 尺寸

    private static volatile Context appCtx;

    private static Context appContext() {
        if (appCtx != null) return appCtx;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                appCtx = (Context) app;
            }
        } catch (Throwable t) {
            L.w("取 Application 失败: " + t);
        }
        return appCtx;
    }

    private static int screenW() {
        ensureScreen();
        return scrW;
    }

    private static int screenH() {
        ensureScreen();
        return scrH;
    }

    private static void ensureScreen() {
        if (scrW > 0 && scrH > 0) return;
        Context ctx = appContext();
        if (ctx == null) return;
        try {
            WindowManager wm = wmCache;
            if (wm == null) {
                wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                wmCache = wm;
            }
            Point p = new Point();
            wm.getDefaultDisplay().getRealSize(p);
            scrW = p.x;
            scrH = p.y;
        } catch (Throwable t) {
            L.w("取屏幕尺寸失败: " + t);
        }
    }

    private static float dp(Context ctx, float v) {
        return v * ctx.getResources().getDisplayMetrics().density;
    }

    // ---------------------------------------------------------------- 动作

    /** 注入返回键。SystemUI 有 INJECT_EVENTS 权限（dumpsys 已验证 granted=true）。 */
    private static boolean injectBack() {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0);
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0);

        if (inject("android.hardware.input.InputManagerGlobal", down, up)) return true;
        if (inject("android.hardware.input.InputManager", down, up)) return true;
        return false;
    }

    private static volatile Object imInstance;
    private static volatile Method imInject;
    private static volatile String imClass;

    private static boolean inject(String clsName, KeyEvent down, KeyEvent up) {
        try {
            Object im = imInstance;
            Method m = imInject;
            if (im == null || m == null || !clsName.equals(imClass)) {
                Class<?> c = Class.forName(clsName);
                im = c.getMethod("getInstance").invoke(null);
                m = c.getMethod("injectInputEvent", InputEvent.class, int.class);
                imInstance = im;
                imInject = m;
                imClass = clsName;
            }
            m.invoke(im, down, 0); // INJECT_INPUT_EVENT_MODE_ASYNC
            m.invoke(im, up, 0);
            return true;
        } catch (Throwable t) {
            if (DEBUG) L.w("注入失败(" + clsName + "): " + t);
            return false;
        }
    }

    // ---------------------------------------------------------------- 配置

    /** 通过文件直读 + ContentProvider 兜底读 App 侧配置，异步 + 2 秒缓存。 */
    static final class ConfigClient {
        /** 不做缓存：每次轻点都重新读，避免"改完档位第一次点还是旧值"。读取在后台线程，不卡触摸。 */
        static final long TTL = 0L;
        private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

        /** App 的 SharedPreferences 文件。SystemUI 以 system uid 运行，通常可读。 */
        private static final String SP_PATH =
                "/data/data/com.laotian.pillback/shared_prefs/" + Cfg.SP_NAME + ".xml";

        private static final java.util.regex.Pattern P_ENABLED =
                java.util.regex.Pattern.compile("name=\"enabled\"\\s+value=\"(true|false)\"");
        private static final java.util.regex.Pattern P_VIBRATE =
                java.util.regex.Pattern.compile("name=\"vibrate\"\\s+value=\"(true|false)\"");
        private static final java.util.regex.Pattern P_STRENGTH =
                java.util.regex.Pattern.compile("name=\"strength\"\\s+value=\"(\\d+)\"");

        static volatile boolean enabled = true;
        static volatile boolean vibrate = true;
        static volatile int strength = 2;

        private static volatile long lastRequest;
        private static volatile boolean fileOk;
        private static volatile boolean providerOk;

        /**
         * 读配置，读完（或命中缓存）后执行 after。
         * after 里做震动这类"晚一点也没关系"的动作，避免读到上一轮的旧值。
         */
        static void refreshThen(Context ctx, final Runnable after) {
            final Context app = ctx.getApplicationContext() != null
                    ? ctx.getApplicationContext() : ctx;
            long now = SystemClock.elapsedRealtime();
            if (now - lastRequest < TTL) {
                runQuietly(after);
                return;
            }
            lastRequest = now;
            try {
                EXEC.submit(new Runnable() {
                    @Override
                    public void run() {
                        if (loadFromSettings(app)) {
                            // 主通道成功
                        } else if (loadFromFile()) {
                            fileOk = true;
                        } else if (!fileOk) {
                            loadFromProvider(app);
                        }
                        runQuietly(after);
                    }
                });
            } catch (Throwable t) {
                if (DEBUG) L.w("提交配置刷新失败: " + t);
                runQuietly(after);
            }
        }

        private static void runQuietly(Runnable r) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }

        /** 主通道：读 Settings.Global。SystemUI 是系统身份，不存在跨包可见性问题。 */
        private static boolean loadFromSettings(Context ctx) {
            try {
                String v = android.provider.Settings.Global.getString(
                        ctx.getContentResolver(), Cfg.SETTINGS_KEY);
                if (DEBUG) L.i("Settings 原始值=[" + v + "]");
                if (v == null || v.isEmpty()) return false;
                String[] p = v.split("\\|");
                if (p.length < 3) return false;
                boolean e = "1".equals(p[0].trim());
                boolean vb = "1".equals(p[1].trim());
                int st;
                try {
                    st = Integer.parseInt(p[2].trim());
                } catch (Throwable t) {
                    st = 2;
                }
                if (DEBUG && (e != enabled || vb != vibrate || st != strength)) {
                    L.i("Settings 读到 enabled=" + e + " vibrate=" + vb + " strength=" + st);
                }
                enabled = e;
                vibrate = vb;
                strength = st;
                return true;
            } catch (Throwable t) {
                if (DEBUG) L.w("读 Settings 失败: " + t);
                return false;
            }
        }

        /** 直读 XML。格式固定，正则足够，避免拉 XmlPullParser。 */
        private static boolean loadFromFile() {
            java.io.FileInputStream in = null;
            try {
                java.io.File f = new java.io.File(SP_PATH);
                if (!f.exists() || !f.canRead()) return false;
                in = new java.io.FileInputStream(f);
                byte[] buf = new byte[(int) Math.min(f.length(), 8192)];
                int n = in.read(buf);
                if (n <= 0) return false;
                String s = new String(buf, 0, n, "UTF-8");

                java.util.regex.Matcher m = P_ENABLED.matcher(s);
                if (m.find()) enabled = "true".equals(m.group(1));
                m = P_VIBRATE.matcher(s);
                if (m.find()) vibrate = "true".equals(m.group(1));
                m = P_STRENGTH.matcher(s);
                if (m.find()) {
                    try {
                        strength = Integer.parseInt(m.group(1));
                    } catch (Throwable ignored) {
                    }
                }
                if (!fileOk && DEBUG) L.i("配置来源：文件直读");
                return true;
            } catch (Throwable t) {
                if (DEBUG && !fileOk) L.w("文件直读失败: " + t);
                return false;
            } finally {
                if (in != null) {
                    try { in.close(); } catch (Throwable ignored) {}
                }
            }
        }

        private static void loadFromProvider(Context app) {
            try {
                ContentResolver cr = app.getContentResolver();
                Bundle b = cr.call(Uri.parse(Cfg.URI_STR), Cfg.METHOD_GET, null, null);
                if (b == null) {
                    if (DEBUG) L.w("provider 返回 null（跨包调用被拦）");
                    return;
                }
                boolean e = b.getBoolean(Cfg.K_ENABLED, true);
                boolean vb = b.getBoolean(Cfg.K_VIBRATE, true);
                int st = b.getInt(Cfg.K_STRENGTH, 2);
                if (DEBUG && (!providerOk || st != strength)) {
                    L.i("provider 读到 enabled=" + e + " vibrate=" + vb + " strength=" + st);
                }
                enabled = e;
                vibrate = vb;
                strength = st;
                providerOk = true;
            } catch (Throwable t) {
                if (DEBUG && !providerOk) L.w("读配置失败(用默认值): " + t);
            }
        }
    }
}
