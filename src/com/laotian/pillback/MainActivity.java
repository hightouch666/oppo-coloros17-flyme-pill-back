package com.laotian.pillback;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.CompoundButton;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 前台设置界面：总开关、震动开关、五档强度（拖动即震动预览）。
 */
public class MainActivity extends Activity {

    private SharedPreferences sp;
    private Switch swEnabled;
    private Switch swVibrate;
    private SeekBar sbStrength;
    private TextView tvLevel;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = getSharedPreferences(Cfg.SP_NAME, MODE_PRIVATE);
        setContentView(buildUi());
        bind();
    }

    // ------------------------------------------------------------ UI

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("手势条返回");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTextColor(0xFF111111);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("竖屏时轻点底部小横条 = 返回上一层。全屏、横屏、桌面不生效。");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setTextColor(0xFF777777);
        sub.setPadding(0, dp(8), 0, dp(22));
        sub.setLineSpacing(dp(3), 1f);
        root.addView(sub);

        root.addView(divider());

        swEnabled = new Switch(this);
        root.addView(row("启用模块", swEnabled));

        swVibrate = new Switch(this);
        root.addView(row("轻点时震动", swVibrate));

        root.addView(divider());

        LinearLayout levelHead = new LinearLayout(this);
        levelHead.setOrientation(LinearLayout.HORIZONTAL);
        levelHead.setGravity(Gravity.CENTER_VERTICAL);
        levelHead.setPadding(0, dp(16), 0, dp(6));

        TextView lvTitle = new TextView(this);
        lvTitle.setText("震动强度");
        lvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        lvTitle.setTextColor(0xFF111111);
        levelHead.addView(lvTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvLevel = new TextView(this);
        tvLevel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvLevel.setTextColor(0xFF2E6BE6);
        levelHead.addView(tvLevel);

        root.addView(levelHead);

        sbStrength = new SeekBar(this);
        sbStrength.setMax(Cfg.LEVEL_LABELS.length - 1);
        sbStrength.setPadding(0, dp(4), 0, dp(4));
        root.addView(sbStrength);

        LinearLayout ticks = new LinearLayout(this);
        ticks.setOrientation(LinearLayout.HORIZONTAL);
        for (String s : Cfg.LEVEL_LABELS) {
            TextView t = new TextView(this);
            t.setText(s);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            t.setTextColor(0xFF999999);
            t.setGravity(Gravity.CENTER);
            ticks.addView(t, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(ticks);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvStatus.setTextColor(0xFF888888);
        tvStatus.setPadding(0, dp(10), 0, 0);
        tvStatus.setText("配置写入状态：检查中…");
        root.addView(tvStatus);

        root.addView(divider());

        TextView help = new TextView(this);
        help.setText("使用步骤\n"
                + "1. LSPosed 里启用本模块，作用域勾选「系统界面」\n"
                + "2. 重启系统界面（或重启框架），无需整机重启\n"
                + "3. 回到本页调开关和强度，改完立即生效\n\n"
                + "说明：只响应小横条本体，左右两侧空白区域不触发；"
                + "长按呼出 Breeno、左右滑动切换最近应用均保持原样。");
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        help.setTextColor(0xFF666666);
        help.setLineSpacing(dp(4), 1f);
        help.setPadding(0, dp(20), 0, 0);
        root.addView(help);

        return scroll;
    }

    private View row(String label, View control) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(0xFF111111);
        row.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        control.setPadding(dp(8), 0, 0, 0);
        row.addView(control);
        return row;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0xFFEEEEEE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(2);
        v.setLayoutParams(lp);
        return v;
    }

    // ------------------------------------------------------------ 逻辑

    private void bind() {
        boolean e = sp.getBoolean(Cfg.K_ENABLED, true);
        boolean vb = sp.getBoolean(Cfg.K_VIBRATE, true);
        int level = sp.getInt(Cfg.K_STRENGTH, 2);

        // 以 Settings.Global 里的实际值为准。
        // App 只是编辑入口，绝不能用 SP 里的旧值去覆盖系统当前值 ——
        // 否则一打开 App 就把用户刚改的档位顶回去了（实测过：1|1|0 被覆盖成 1|1|4）。
        String raw = getGlobal();
        if (raw != null) {
            String[] p = raw.split("\\|");
            if (p.length >= 3) {
                e = "1".equals(p[0].trim());
                vb = "1".equals(p[1].trim());
                try {
                    level = Integer.parseInt(p[2].trim());
                } catch (Throwable ignored) {
                    // 保持 SP 里的值
                }
            }
        }

        if (level < 0) level = 0;
        if (level > Cfg.LEVEL_LABELS.length - 1) level = Cfg.LEVEL_LABELS.length - 1;

        swEnabled.setChecked(e);
        swVibrate.setChecked(vb);
        sbStrength.setProgress(level);
        tvLevel.setText(Cfg.labelOf(level) + "（第 " + (level + 1) + " 档）");
        sp.edit()
                .putBoolean(Cfg.K_ENABLED, e)
                .putBoolean(Cfg.K_VIBRATE, vb)
                .putInt(Cfg.K_STRENGTH, level)
                .apply();

        swEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean checked) {
                sp.edit().putBoolean(Cfg.K_ENABLED, checked).apply();
                persist();
            }
        });

        swVibrate.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean checked) {
                sp.edit().putBoolean(Cfg.K_VIBRATE, checked).apply();
                persist();
                if (checked) preview(sbStrength.getProgress());
            }
        });

        sbStrength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvLevel.setText(Cfg.labelOf(progress) + "（第 " + (progress + 1) + " 档）");
                if (fromUser) {
                    sp.edit().putInt(Cfg.K_STRENGTH, progress).apply();
                    persist();
                    preview(progress);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 回写一次相同的值：既是首装初始化，也顺便校验写权限是否还在（结果会显示在状态栏）
        persist();
    }

    /**
     * 把配置写进 Settings.Global，SystemUI 侧直接读它（跨包访问 provider 在 SystemUI 上不稳定）。
     *
     * 直写需要 WRITE_SECURE_SETTINGS。这个权限被 ROM 归为 development 级，
     * 普通 shell 的 pm grant 会被拦，但 root 下可以授予 —— 所以直写失败时先尝试
     * 用 su 给自己补上权限再重试，最后才降级成 root 直写 settings。
     * 结果会显示在界面上，避免"改了半天其实没写进去"这种无声失效。
     */
    private void persist() {
        final String cfg = (swEnabled.isChecked() ? "1" : "0")
                + "|" + (swVibrate.isChecked() ? "1" : "0")
                + "|" + sbStrength.getProgress();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String status = writeCfg(cfg);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (tvStatus == null) return;
                        tvStatus.setText("配置写入状态：" + status);
                        tvStatus.setTextColor(status.startsWith("写入失败") ? 0xFFD93025 : 0xFF1E7D32);
                    }
                });
            }
        }).start();
    }

    /** 返回一句人话，直接显示到界面上。 */
    private String writeCfg(String cfg) {
        if (putGlobal(cfg)) return "已生效";

        // 权限可能因为重装/清数据丢了，用 root 自动补一次
        execSu("pm grant " + getPackageName()
                + " android.permission.WRITE_SECURE_SETTINGS");
        if (putGlobal(cfg)) return "已生效（已自动补授权限）";

        // 最后兜底：直接让 root 去写
        execSu("settings put global " + Cfg.SETTINGS_KEY + " '" + cfg + "'");
        if (cfg.equals(getGlobal())) return "已生效（经 root 写入）";

        return "写入失败，请在 root shell 里执行一次：pm grant "
                + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS";
    }

    private boolean putGlobal(String cfg) {
        try {
            android.provider.Settings.Global.putString(
                    getContentResolver(), Cfg.SETTINGS_KEY, cfg);
        } catch (Throwable t) {
            return false;
        }
        return cfg.equals(getGlobal());
    }

    private String getGlobal() {
        try {
            return android.provider.Settings.Global.getString(
                    getContentResolver(), Cfg.SETTINGS_KEY);
        } catch (Throwable t) {
            return null;
        }
    }

    private void execSu(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();
        } catch (Throwable ignored) {
        }
    }

    /** 拖动时立刻震一下，让用户直接感知强度差异。 */
    private void preview(int level) {
        Haptics.play(this, level);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
