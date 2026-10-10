# 小米音乐小助手（MiuiMusicHelper）

小米音乐（`com.miui.player`）的 LSPosed 模块，使用新版 Xposed API（**LSPosed API 102**）。
无界面，在 LSPosed 里启用并勾上作用域 `com.miui.player` 即全自动生效；
在 **4.46.6.1**（小米 14 / Android 16）上验证通过。

## 功能

1. **去开屏广告**
   冷启动 / 热启动 / 运营开屏的判定全部改成「不展示」：`AmsGlobal.isNeedSplashAd`、
   `AmsGlobal.checkHotSplash`、`QQSplashHelper.coldStartCheck`、`QQSplashHelper.hotStartCheck`、
   `SplashHelper.trySplash`；三个广告页再加 `onCreate` 后 `finish()` 兜底。
2. **去播放页进度条上的会员推广文案**
   拦掉 `PlayerSongFragment.setProgressBarFixedEntrance(String)`，并把那个 TextView 清空隐藏。
3. **去「臻品音质」弹窗**
   `ExcellentHeadPhoneManager.checkAndShow()` 直接不展示（弹窗和上面的「30 分钟免费体验」按钮都不出现）。
4. **播放页进入 / 退出动画放慢到 400ms**
   退出把 `player_activity_out` 换成同形状的 `slide_out_down`；进入在 App 进程内把播放页根容器
   从屏幕下方滑回（窗口动画对进入无效，实测是系统硬切）。
5. **主页自动刷新延后**
   跳开屏广告直接进主页时，把 `onVisible` 发出的自动刷新延后 1s 再执行，避免刷新头卡在
   下沉位置不回弹；手动下拉和其它页面不受影响。
6. **去首页「免费听」tab**
   顶部 tab 数据源里把 id 4（免费听）过滤掉：简洁模式顶部只剩「推荐 / 听书」，底部入口也一起消失。
7. **去播放条上方的会员推广气泡**
   拦掉播放条上方那条付费引导气泡（首次播放时的「成为超级会员，10万本好书任意听」等三条场景，
   文案随服务端下发）；免费听 / 登录 / 试听 / 音质音效气泡不受影响。

## 构建 / 安装

需要 JDK 17 和支持 API 102 的 LSPosed（模块 `minSdk 26` / `targetSdk 36`）。

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:assembleDebug          # 没网时加 --offline
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

装完在 LSPosed 里启用模块、勾上 `com.miui.player`，再杀掉音乐 App 进程重开。

## 目录结构

```
app/src/main/java/com/dagu/miimusichelper/Main.java   所有 hook 都在这里
app/src/main/resources/META-INF/xposed/               API 102 入口：java_init.list / module.prop / scope.list
build/                                                反编译与构建产物，不入库
```

## 日志

tag 是 `MIUI-Music-AdFree`（`adb logcat | grep MIUI-Music-AdFree`）。正常能看到
`hooking com.miui.player`、广告时机打出的 `blocked ...`、播放页的 `player enter slide`，
以及主页刷新的 `defer home auto refresh 1000ms` → `deferred auto refresh runs / skipped / dropped`。

## 兼容性

hook 都按 4.46.6.1 的类名 / 方法签名写；App 升级后签名变了对应条目只是静默不生效
（日志会打 `WARN`），不影响启动。
