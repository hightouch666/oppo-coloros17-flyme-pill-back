# 手势条返回 · PillBack

> 把 **Flyme 的「轻点小横条返回」手感**，搬到 OPPO ColorOS 17（Android 16）上。
> 一个小的 Xposed/LSPosed 模块：**竖屏时轻点桌面下方那条小横条，就返回上一层**。
> 全屏、横屏、桌面不生效。带回震动反馈，五档强度可调。

一个 APK，一个功能，没有联网、没有统计、没有后台常驻。

---

## 这是什么

ColorOS 的手势导航里，底部那条**小横条**（navigation handle / pill）原本只能：
上滑回桌面、左右滑切换最近应用、长按呼出 Breeno。

**轻点它，什么都不会发生。**

而在魅族 **Flyme** 上，轻点小横条就是「返回上一层」—— 用过的人都知道那个手感有多顺，
大拇指根本不用挪位置。这个模块就是把 Flyme 的这个习惯搬到 ColorOS 上，
把原本空着的轻点动作填上：**轻点 = 返回上一层**（等效按一下返回键）。

写它的动机很朴素 —— 大屏手机单手操作时，大拇指去够屏幕左边缘做返回手势很别扭，
而小横条就在拇指正下方。多一个顺手的返回入口，日常少一次"手指长途跋涉"。

> 说明：这是「照 Flyme 的思路在 ColorOS 上重新实现」，不是移植、也不是兼容层，
> 没有用 Flyme 的任何代码或组件。模块 hook 的是 ColorOS 自己的 `com.oplus.*` 类。
> 反过来，**Flyme 设备不需要装它** —— 那本来就是原生功能。

## 行为一览

| 场景 | 行为 |
|---|---|
| 竖屏 + 普通应用里，轻点小横条 | 返回上一层 ＋ 震动一下 |
| 竖屏 + 桌面 | 不生效（桌面上"返回"没有意义） |
| 横屏 / 屏幕旋转 90°、270° | 不生效 |
| 全屏 / 沉浸式（小横条已淡出） | 不生效 |
| 长按小横条 | 保持原样：呼出 Breeno |
| 左右滑动小横条 | 保持原样：切换最近应用 |
| 上滑 | 保持原样：回桌面 |

"轻点"的判定：手指按下到抬起 **位移 ≤ 8dp、时长 ≤ 260ms**，而且**只认小横条本体所在的区域**，
屏幕底部左右两侧的空白区域不会触发 —— 想给"返回"留个宽一点的热区，改 `Cfg.java` 里的
`HANDLE_HALF_WIDTH_DP` 就行。

## 适用环境

- **系统**：ColorOS 17（Android 16）实测通过；ColorOS 16 及更早版本类名可能不同，未验证
- **机型**：OPPO 机型实测（PLG110 / ColorOS 17.0.0.105）。一加、真我与 ColorOS 同源，
  理论可用但未验证
- **Flyme**：**不需要本模块**。轻点小横条返回是 Flyme 的原生能力，本项目的灵感就来自它；
  这里做的是在 ColorOS 上补齐同样的体验，代码与 Flyme 无关
- **框架**：LSPosed（Zygisk 版）或任何 API 82+ 的 Xposed 兼容框架
- **权限**：需要 root（临时 root 也可以，装完只需重启 SystemUI，不用整机重启）

## 安装

1. 安装 `PillBack.apk`（本仓库根目录那份，或 Releases 里下载）
2. 打开 **LSPosed 管理器** → 模块 → 启用「手势条返回」
3. 点进模块的**作用域**，**只勾选「系统界面」**（`com.android.systemui`）。
   APK 里已经带 `assets/xposed_scope`，正常情况下会默认推荐这一个
4. **重启系统界面**（不必重启手机）：
   ```bash
   su -c 'pkill -f com.android.systemui'
   ```
   SystemUI 会自动重新 fork，LSPosed 重新注入，临时 root 也不会掉
5. 打开「手势条返回」App，按需调整开关和震动强度

### 让 App 能真正保存设置（可选，但建议）

模块的配置存在系统设置里（`Settings.Global` 的 `pillback_cfg`），SystemUI 直接读它。
App 要写这个键需要有 `WRITE_SECURE_SETTINGS`，而这个权限在 ColorOS 上**只允许 root 授予**
（普通 `adb shell pm grant` 会被 ROM 拦掉）。执行一次即可：

```bash
adb shell su -c 'pm grant com.laotian.pillback android.permission.WRITE_SECURE_SETTINGS'
```

> 没授权也能用 —— 默认值就是"开启 + 震动 + 第 3 档"。
> 只是 App 里改了不会生效。App 界面底部会显示当前写入状态，失败会明确告诉你。

## 关于震动的五档（一个坑）

这颗马达（以及 ColorOS 的触感 HAL）有个脾气：**它不给你调"力度"，只认预置触感 ID**。

实测 `dumpsys vibrator_manager`：

```
capabilitiesFlags = 11        ← 没有 AMPLITUDE_CONTROL 位
supportedEffects   = []       ← 不支持 createPredefined
supportedPrimitives= []       ← 不支持 Composition
```

也就是说 `createOneShot(ms, amplitude)` 里的 amplitude 会被直接丢掉。
更麻烦的是：HAL 会把请求按**时长分桶**塞进固定的预置效果里，实测映射是

| 请求时长 | HAL 实际执行 |
|---|---|
| ≤ 10 ms | 预置 waveformId 7（30ms） |
| 11 ~ 45 ms | 预置 waveformId 2（45ms） |
| 46 ~ 100 ms | 预置 waveformId 6（45ms） |
| ≥ 105 ms | 连续振动，时长精确生效 |

所以早期版本按"18/30/45/83ms"排的五档，后四档全落在同一个桶里 —— **听起来一模一样**。
现在的五档刻意跨了区间：

| 档位 | 请求 | HAL 实际执行 |
|---|---|---|
| 1 很弱 | 8 ms | 预置 7，30ms |
| 2 弱 | 30 ms | 预置 2，45ms |
| 3 中 | 70 ms | 预置 6，45ms |
| 4 强 | 120 ms | 连续 120ms |
| 5 很强 | 200 ms | 连续 200ms |

另外，「在同一段波形里多震几下」这种写法在这台机器上也会被合并成一次，所以没用。
换到支持振幅控制的马达上，模块会走 `createOneShot(duration, amplitude)` 的正常路径。

## 工作原理

反汇编 `SystemUI.apk` 得到的事实（版本不同类名可能变，改代码前请自己确认）：

```
com.oplus.systemui.navigationbar.gesture.sidegesture.SideGestureDetector
    └─ onMotionEventImpl(MotionEvent, Map)      ← 底部手势区的真入口，所有触摸都过这里
         └─ 条件满足时才转发给
            OplusNavigationHandle.handleValidTouchEvent(MotionEvent)
```

几条踩过的结论，写在源码注释里也写在这里：

1. **只 hook `handleValidTouchEvent` 不行**。它是被 `SideGestureDetector` 条件转发的，
   转发条件不满足时你的 hook 一次都不会触发（表现为"模块明明加载了但点了没反应"）。
2. **`MotionEvent` 是窗口坐标**（0..屏宽），不是 View 局部坐标。
   拿 View 自己的 `getWidth()` 当基准去算中心点，必然算错。
3. **小横条的轻点原本是空动作**。它的手势回调 `OplusNavigationHandle$1` 只实现了
   `onDown/onShowPress/onLongPress/onScroll`，**没有 `onSingleTapUp`**。
   所以占用"轻点"不会破坏长按 Breeno、左右滑切应用。
4. **判断全屏不能看 `setNavBarMode`**（普通界面它也是 2），也不能看 `setFadedAnimProgress`
   （那是按压小横条时的淡出动画），也不能看 `sysUiFlags` 的 FULLSCREEN 位
   （edge-to-edge 应用普遍带这个位，会大面积误杀）。
   现在用的是小横条自身的 **`setAlpha()`**：全屏沉浸时 ColorOS 会把它淡出到接近 0，
   普通界面稳定在 1.0。
5. **返回键用注入**：SystemUI 本身持有 `INJECT_EVENTS`（已验证 granted），
   直接反射 `InputManagerGlobal.injectInputEvent` 打一对 down/up 即可，不需要 hack 权限。
6. 桌面判定用 `ActivityTaskManager.getFocusedRootTaskInfo()` 拿前台包名，与 HOME 包名比对。

## 目录结构

```
AndroidManifest.xml          模块声明（xposedmodule / xposeddescription / 作用域）
assets/xposed_init           入口类名
assets/xposed_scope          推荐作用域：com.android.systemui
src/com/laotian/pillback/
    HookEntry.java           Xposed 入口，只对 com.android.systemui 生效
    PillBackHook.java        核心 hook：触摸判定、全屏/桌面/横屏排除、注入返回
    Haptics.java             震动（振幅 / 时长两条路）
    Cfg.java                 常量与档位定义
    ConfigProvider.java      配置兜底通道
    MainActivity.java        前台设置界面
    L.java                   日志封装
build.sh                     无 Gradle 构建脚本（aapt2 + javac + d8 + apksigner）
```

## 自己构建

不需要 Android Studio、不需要 Gradle，只要有 JDK 17 + Android SDK：

```bash
# 1. 准备 Xposed API（只在编译期用，不会打进 APK）
mkdir -p lib
curl -L -o lib/api-82.jar \
  https://repo1.maven.org/maven2/de/robv/android/xposed/api/82/api-82.jar

# 2. 构建
JAVA_HOME=/path/to/jdk17 \
ANDROID_SDK=/path/to/android-sdk \
./build.sh
# 产物：PillBack.apk
```

第一次构建会自己生成 `pillback.keystore`（密码 `android`）。
**这个文件不要提交、也不要公开**，否则别人能用你的签名发布"看起来是官方更新"的包。
想用自备密钥就设置 `PILLBACK_KEYSTORE=/path/to/your.keystore`。

## 隐私

- **不联网**：APK 没有申请 `INTERNET` 权限，物理上不可能上传任何东西
- **不收集**：没有统计、没有崩溃上报、没有设备指纹
- **不申请**存储、位置、通讯录、电话等敏感权限，只申请了两个：
  - `VIBRATE`：震动
  - `WRITE_SECURE_SETTINGS`：把开关和档位写进系统设置，供 SystemUI 读取
- **配置只存在本机**：`Settings.Global` 里的一个字符串 `pillback_cfg`，
  形如 `1|1|2`（启用|震动|档位），卸载后残留可手动清掉：
  ```bash
  adb shell su -c 'settings delete global pillback_cfg'
  ```
- 仓库里的源码已经过脱敏：不含任何设备序列号、账号、内网地址或签名密钥

## 已知限制

- 完全依赖 ColorOS 的内部类名，**系统升级后大概率失效**，需要重新反汇编定位
- 只在 OPPO / ColorOS 17 上验证过，其它机型请自行测试
- 小横条透明度的阈值（0.9）是实测经验值，个别应用的沉浸实现可能导致误判
- 每次成功点击会打一行日志（`adb logcat -s PillBack`），方便排查；
  触摸热路径本身不打日志，也不做 binder 调用（反射对象已缓存），不影响手势流畅度

## 免责声明

本项目仅供学习与个人使用。修改系统界面行为存在风险（可能导致返回手势异常、
SystemUI 崩溃等），请自行评估并做好备份。使用本模块造成的一切后果由使用者自负。

## License

MIT，见 [LICENSE](LICENSE)。
