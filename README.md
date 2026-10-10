# 小米音乐小助手（MiuiMusicHelper）

针对小米音乐（`com.miui.player`）的 LSPosed 模块，用的是新版 Xposed API（**LSPosed API 102**）。
模块没有任何界面，装好后在 LSPosed 里启用并勾上作用域 `com.miui.player` 就行，五个功能全自动生效。

在小米音乐 **4.46.6.1**（小米 14 / Android 16 / KernelSU + Zygisk-LSPosed）上验证通过。

## 功能

### 1. 去开屏广告

冷启动 / 热启动 / 运营开屏各有自己的开关，全部改成「不展示」，让 App 走它自己的无广告分支：

| 关掉的判定 | 管住的东西 |
| --- | --- |
| `AmsGlobal.isNeedSplashAd(Intent)` | 腾讯 AMS / 优量汇冷启动开屏（`SplashAdActivity`） |
| `AmsGlobal.checkHotSplash(Activity)` | 热启动（回到前台）时的同一套开屏 |
| `QQSplashHelper.coldStartCheck(Activity)` | 小米音乐运营开屏（`QQSplashActivity`） |
| `QQSplashHelper.hotStartCheck(Context)` | 回到前台时的运营开屏 |
| `SplashHelper.trySplash(Activity, boolean)` / `tryAdSplash(Activity)` | MIUI 系统开屏广告（`SystemSplashAd` / MSA） |

另外对 `SplashAdActivity`、`QQSplashActivity`、`SplashActivity` 三个广告页做了兜底：真被拉起来就在
`onCreate` 走完后立刻 `finish()`。关掉判定不会卡启动 —— `MainActivity.checkColdSplash()` 在非 ENABLE
分支里会立刻把 `isColdSplashEnd` 置为 true。

### 2. 去播放页进度条上的会员推广文案

播放页右下角贴着进度条的那个 `tv_song_progress_bar_fixed_entrance`（文案由服务端下发，比如
「升级超级会员，畅听有声内容」，长了就把进度条压掉一半）：

- `PlayerSongFragment.setProgressBarFixedEntrance(String)` 整个拦掉（返回 `null`）；
- 反射把那个 TextView 清空 + `GONE`，并把进度条被文案挤出来的裁剪还原（`resetClipRect`）。

### 3. 去戴上耳机后弹的「臻品音质」弹窗

`ExcellentHeadPhoneManager.checkAndShow()` 是唯一的展示入口，直接返回 `kotlin.Unit.INSTANCE`，
等价于它自己所有「不展示」的分支：弹窗、以及弹窗上那个「30 分钟免费体验」按钮都不会再出现，
连服务端请求和频控写库也一并省掉。

### 4. 播放页进入 / 退出动画（都放慢到 400ms）

原状：进入是 `player_activity_in`（50ms 的 100%p 位移，肉眼约等于硬切），退出是
`player_activity_out`（`config_shortAnimTime` ≈ 200ms 的快速下滑），所以点进 / 退出播放页很生硬。

- **退出**：hook `MusicPlayerActivity.overridePendingTransition(int, int)`，把 `player_activity_out`
  换成 App 自己包里同形状但更慢的 `slide_out_down`（0 → 100%p 下滑，400ms）。
- **进入**：窗口动画这条路对「进入」无效 —— App 是在 `MusicPlayerActivity.onCreate()` 里调
  `overridePendingTransition(player_activity_in, no_vertical_tanslation)`，此时 `ActivityRecord`
  还没到 RESUMED / PAUSING，WMS 的 `ActivityClientController.overridePendingTransition` 会把这次调用
  直接丢掉（换成新接口 `overrideActivityTransition`，记下来的 `mCustomOpenTransition` 在 MIUI 的转场
  链路里同样没人消费），实测进入一直是系统默认硬切。所以进入动画改成在 App 进程内做**内容级滑动**：
  `onCreate` 之后拿到播放页根容器 `PlayerFrameLayout`（就是下拉关闭手势整体位移的那个 View，
  `@id/container`），在它第一次量好高度、画出第一帧之前把容器挪到屏幕下方（`translationY = 高度`），
  再用 400ms `DecelerateInterpolator(1.5f)` 滑回 0。播放页窗口此刻仍是 translucent 的，
  露出的是后面真实的主界面，观感和 App 自己的手势是同一条路数。

### 5. 主页「进页面自动刷新」延后（修：跳过开屏广告后刷新头卡住）

跳过（或干脆没有）开屏广告时，`HomeFragment.onVisible` 会在首屏数据（缓存 / 网络）还没落地时
就发出自动刷新（`mRecyclerView.post { setRefreshing(true) }`），刷新控件在展开动画中途又收到
收尾调用，状态机停在中间态 —— 页头卡在下沉位置不回弹（手动下拉能拉回来，因为手指释放那条
路径不经过 `setRefreshing`）。看了广告的启动晚几秒，自动刷新等到数据落定后才执行，就没有这个问题。

处理：记录 `HomeFragment.onVisible` 的时刻，把紧跟在主页可见之后（2s 内）、控件空闲（`status==0`）
时到达的 `setRefreshing(true, int)` 延后 1s 再执行；到点重新判断 —— 控件已被别的刷新占用或页面
已销毁就丢弃，数据已就绪（非空且非缓存）就跳过（等价于「看了广告」的那条路径），否则以原参数
补一次 `setRefreshing(true, 400)`，走 App 自己的正常流程。只作用于主页那个 RecyclerView，
手动下拉和其它页面不受影响。

## 环境要求

- 支持 **API 102** 的 LSPosed（新版 libxposed API）；
- 模块 `minSdk 26` / `targetSdk 36`，编译需要 **JDK 17**；
- 作用域写死在 `scope.list` 里（`staticScope=true`），只在 `com.miui.player` 进程里加载。

## 构建 / 安装

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # 需要 JDK 17
./gradlew :app:assembleDebug                        # 没网时加 --offline
# 产物：app/build/outputs/apk/debug/app-debug.apk

adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

装完在 LSPosed 里启用模块、勾上 `com.miui.player`，然后**杀掉音乐 App 进程**再打开 ——
更新模块后必须重启目标 App 进程，LSPosed 才会重新注入新版本。

## 目录结构

```
app/src/main/java/com/dagu/miimusichelper/Main.java   唯一的逻辑文件，所有 hook 都在这里
app/src/main/AndroidManifest.xml                      没有 Activity，只有 xposed 的 meta-data
app/src/main/res/values/arrays.xml                    xposedscope = com.miui.player
app/src/main/res/values/strings.xml                   模块名 / 描述（LSPosed 列表里显示的）
app/src/main/resources/META-INF/xposed/               API 102 的入口声明：java_init.list、module.prop、scope.list
build/                                               反编译产物 / 构建产物，不入库
apk/                                                  （可选）目标 App 的安装包，只作分析用，不入库
```

## 日志

排查时看 `MIUI-Music-AdFree` 这个 tag：

```bash
adb logcat | grep MIUI-Music-AdFree
```

正常运行时应该能看到这些：

- `hooking com.miui.player`
- `blocked AmsGlobal.isNeedSplashAd` / `blocked QQSplashHelper.coldStartCheck` 之类（有广告时机才打）
- `blocked earphone quality popup: checkAndShow`
- `player enter slide: 2400px / 400ms`
- `player transition: enter 0x... -> 0x..., exit 0x... -> 0x...`
- `defer home auto refresh 1000ms`，以及到点的 `deferred auto refresh runs / skipped / dropped`

## 兼容性

所有 hook 都按 4.46.6.1 的类名 / 方法签名写，目标 App 升级后如果签名变了，
对应那条只是静默不生效（找不到目标时会在日志里打一条 `WARN`），不会影响 App 启动。
