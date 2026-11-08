package com.laotian.pillback;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 入口。只在 SystemUI 进程里安装 hook。
 */
public class HookEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !Cfg.PKG_SYSTEMUI.equals(lpparam.packageName)) {
            return;
        }
        L.i("进入 SystemUI，开始安装 hook");
        try {
            PillBackHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            L.e("安装 hook 失败", t);
        }
    }
}
