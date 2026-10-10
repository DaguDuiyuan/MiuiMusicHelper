package com.dagu.miimusichelper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 小米音乐（com.miui.player）小助手。五个独立的功能：
 *
 * 1. 去开屏广告。
 * 2. 去掉播放页压在进度条上的会员推广文案。
 * 3. 去掉戴上耳机后每次启动弹出的「臻品音质」弹窗。
 * 4. 播放页进入/退出的过渡动画：进入 400ms 内容级上滑、退出 400ms 窗口下滑。
 * 5. 主页「进页面自动刷新」延后：跳开屏广告后进主页不会再把刷新头卡在半路。
 *
 * 小米音乐 4.46.x 是 QQ 音乐 Lite 的定制版，冷启动链是：
 *   MusicBrowserActivity -> MainActivity.checkColdSplash()
 *     -> TMEAmsAdRoutersKt.toColdSplashAd()
 *        -> MiuiAd.startMIUIAd()            (com.miui.systemAdSolution 的 MIUI 系统开屏)
 *        -> 或 SplashAdActivity             (腾讯 AMS / 优量汇开屏，实测走这条)
 *
 * 热启动（回到前台）由 ActivityLifecycleCallbacks 触发：
 *   AmsLifeCycle -> AmsGlobal.checkHotSplash() -> 同样的两条路
 *
 * 另外还有两套「运营开屏」，各有独立的开关：
 *   ApplicationHelper.onBackToApplication -> QQSplashHelper.checkAD() -> QQSplashActivity
 *   MusicBaseActivity.onCreate            -> QQSplashHelper.coldStartCheck() -> QQSplashActivity
 *   SplashHelper.trySplash()              -> SystemSplashAd.requestAd()（MIUI MSA 系统广告）
 *
 * 所以只需要把「要不要展示」的判定改成否，让 App 走它自己的「没有广告」分支：
 * MainActivity.checkColdSplash() 在非 ENABLE 时会立刻把 isColdSplashEnd 置为 true，
 * 主界面照常显示，不会卡住。三个广告 Activity 的 onCreate 再加一层兜底 finish()。
 */
public final class Main extends XposedModule {

    private static final String TAG = "MIUI-Music-AdFree";
    private static final String PKG = "com.miui.player";

    /** 广告 Activity：万一前面所有开关都失效（比如 App 改版换了类名），至少立刻结束它们。 */
    private static final String[] AD_ACTIVITIES = {
            // 腾讯 AMS / 优量汇冷启动开屏
            "com.tencent.qqmusiclite.activity.SplashAdActivity",
            // 小米音乐运营开屏（Tencent Music 运营开屏，AMS pos 3033877349649638）
            "com.miui.player.splash.QQSplashActivity",
            // 小米自己的开屏/活动图页面
            "com.miui.player.splash.SplashActivity",
    };

    public Main() {
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(android.util.Log.INFO, TAG, "module loaded: process=" + param.getProcessName()
                + " systemServer=" + param.isSystemServer());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!PKG.equals(param.getPackageName())) {
            return;
        }
        ClassLoader cl = param.getDefaultClassLoader();
        log(android.util.Log.INFO, TAG, "hooking " + PKG);
        hookAmsSplash(cl);
        hookOperateSplash(cl);
        hookMiuiSplash(cl);
        hookAdActivityFallbacks(cl);
        hookPlayerVipTips(cl);
        hookHeadphoneQualityPopup(cl);
        hookPlayerTransitions(cl);
        hookPlayerEnterAnimation(cl);
        hookHomeAutoRefresh(cl);
    }

    /**
     * 播放页「退出」的窗口过渡动画。
     *
     * 现状（4.46.6.1）：
     *   - 退出：finish()/onPause() -> PlayerPageSnapshotController.applyActivityExitTransition()
     *       -> overridePendingTransition(R.anim.no_vertical_tanslation, R.anim.player_activity_out)。
     *     player_activity_out 是 config_shortAnimTime(约 200ms) 的 0 -> 140%p 快速下滑。
     * 对比之下，App 自己的下拉关闭手势是 300ms 的内容级动画，所以点进/退出播放页显得生硬。
     *
     * 这里把窗口动画资源换成 App 自己包里时长更合适的 400ms 版本：
     *   player_activity_out -> slide_out_down (0 -> 100%p 下滑，400ms)
     *   player_activity_in  -> search_hot_text_in (100%p -> 0 上滑，400ms)
     * 进入那条只在 Activity 已经 RESUMED 时才可能被系统采纳（比如 onNewIntent），
     * onCreate 里的那次调用会被 WMS 丢掉（见 hookPlayerEnterAnimation 的说明），
     * 正常「点进播放页」的进入动画由 hookPlayerEnterAnimation 在 App 进程内做。
     * 不动任何业务逻辑；其余 overridePendingTransition 调用原样透传。
     */
    private void hookPlayerTransitions(ClassLoader cl) {
        final Class<?> playerActivity = loadClass(cl,
                "com.tencent.qqmusiclite.activity.player.MusicPlayerActivity");
        if (playerActivity == null) {
            log(android.util.Log.WARN, TAG, "MusicPlayerActivity not found");
            return;
        }
        Method override;
        try {
            // 该方法声明在 android.app.Activity 上，getDeclaredMethod 找不到，得用 getMethod。
            override = playerActivity.getMethod("overridePendingTransition", int.class, int.class);
        } catch (Throwable t) {
            override = null;
        }
        if (override == null) {
            log(android.util.Log.WARN, TAG, "Activity.overridePendingTransition(int,int) not found");
            return;
        }
        try {
            hook(override).intercept(chain -> {
                Object self = chain.getThisObject();
                if (!playerActivity.isInstance(self) || !(self instanceof Context)) {
                    return chain.proceed();
                }
                Context context = (Context) self;
                int enter = toInt(chain.getArg(0));
                int exit = toInt(chain.getArg(1));
                int newEnter = mapAnim(context, enter, "player_activity_in", ENTER_ANIM);
                int newExit = mapAnim(context, exit, "player_activity_out", EXIT_ANIM);
                if (newEnter == enter && newExit == exit) {
                    return chain.proceed();
                }
                log(android.util.Log.INFO, TAG, "player transition: enter 0x"
                        + Integer.toHexString(enter) + "->0x" + Integer.toHexString(newEnter)
                        + ", exit 0x" + Integer.toHexString(exit) + "->0x" + Integer.toHexString(newExit));
                return chain.proceed(new Object[]{newEnter, newExit});
            });
            log(android.util.Log.INFO, TAG, "hooked MusicPlayerActivity.overridePendingTransition");
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "hook player transitions failed: " + t);
        }
    }

    /** App 包里同形状但更慢的那一对窗口动画。 */
    private static final String ENTER_ANIM = "search_hot_text_in";
    private static final String EXIT_ANIM = "slide_out_down";

    /** 播放页根容器（music_player_content.xml 根 + @id/container）。 */
    private static final String PLAYER_CONTAINER_CLASS =
            "com.tencent.qqmusiclite.activity.player.PlayerFrameLayout";

    /**
     * 播放页「进入」动画：在 App 进程内做内容级上滑。
     *
     * 为什么不在窗口层面做：App 在 MusicPlayerActivity.onCreate() 里调
     * overridePendingTransition(player_activity_in(50ms), no_vertical_tanslation)，
     * 而 WMS 的旧接口实现（ActivityClientController.overridePendingTransition）
     * 只对「正 RESUMED/PAUSING 的 Activity」生效，onCreate 时状态不满足，调用被直接丢弃；
     * 换成新接口 overrideActivityTransition 也只把动画记进 ActivityRecord.mCustomOpenTransition，
     * MIUI 的转场链路里同样没人消费。实测进入一直是系统默认的硬切。
     *
     * 所以这里直接借用 App 自己那套内容级滑动：播放页根布局就是 PlayerFrameLayout
     * （@id/container，match_parent），它的下拉关闭手势就是把这个容器整体位移/滚动出去的
     * （PlayerFrameLayout.scrollToFinishImmediately/scrollToOriginal，300ms）。
     * 进入时把容器先摆到屏幕下方，再滑回 0，时长由我们定（400ms，比原来的 50ms 慢得多）。
     * 播放页窗口此刻仍是 translucent 的，容器挪下去之后露出来的是后面真实的主界面，
     * 观感和 App 自己的手势一致。
     */
    private static final long ENTER_SLIDE_MS = 400L;

    private void hookPlayerEnterAnimation(ClassLoader cl) {
        Class<?> activityCls = loadClass(cl,
                "com.tencent.qqmusiclite.activity.player.MusicPlayerActivity");
        Method onCreate = activityCls == null ? null : findMethod(activityCls, "onCreate", Bundle.class);
        if (onCreate == null) {
            log(android.util.Log.WARN, TAG, "hook player enter animation: onCreate not found");
            return;
        }
        try {
            hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof Activity) {
                    scheduleEnterSlide((Activity) self);
                }
                return result;
            });
            log(android.util.Log.INFO, TAG, "hooked MusicPlayerActivity.onCreate for enter slide");
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "hook player enter animation failed: " + t);
        }
    }

    /**
     * 第一次量好高度时（onLayout 结束、第一帧画出来之前）把容器整体挪到屏幕下方，
     * 然后滑回 0。此时窗口还是 translucent 的，挪下去露出来的是后面真实的主界面，
     * 看起来就是 App 自己那套「整页从底部推上来」。
     */
    private void scheduleEnterSlide(final Activity activity) {
        View container = findPlayerContainer(activity);
        if (container == null) {
            log(android.util.Log.WARN, TAG, "player enter slide: container not found");
            return;
        }
        final int height = container.getHeight();
        if (height > 0) {
            startEnterSlide(container, height);
            return;
        }
        // onCreate 刚 setContentView 完还没量过，借第一次 onLayout 的回调，正好赶在第一帧前。
        final View target = container;
        final View.OnLayoutChangeListener[] holder = new View.OnLayoutChangeListener[1];
        holder[0] = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                v.removeOnLayoutChangeListener(holder[0]);
                int h = v.getHeight();
                if (h > 0) {
                    startEnterSlide(v, h);
                }
            }
        };
        target.addOnLayoutChangeListener(holder[0]);
    }

    /** 容器从屏幕下方（translationY = 自身高度）滑回原位。 */
    private void startEnterSlide(final View container, int height) {
        container.setTranslationY(height);
        container.animate()
                .translationY(0.0f)
                .setDuration(ENTER_SLIDE_MS)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                .start();
        log(android.util.Log.INFO, TAG, "player enter slide: " + height + "px / "
                + ENTER_SLIDE_MS + "ms");
    }

    /** 播放页根容器 PlayerFrameLayout（@id/container）。 */
    private static View findPlayerContainer(Activity activity) {
        View decor;
        try {
            decor = activity.getWindow().getDecorView();
        } catch (Throwable t) {
            return null;
        }
        // 播放页的根布局就是 PlayerFrameLayout（music_player_content.xml 最外层，
        // 同时也是 @id/container 和 MusicPlayerContentBinding 的根），按类名找最稳。
        View byClass = findViewByClassName(decor, PLAYER_CONTAINER_CLASS);
        if (byClass != null) {
            return byClass;
        }
        try {
            int id = activity.getResources().getIdentifier("container", "id",
                    activity.getPackageName());
            if (id != 0) {
                View view = activity.findViewById(id);
                // @id/container 是个很普通的名字，确认一下别拿到别的页面里的同名 View。
                if (view != null && PLAYER_CONTAINER_CLASS.equals(view.getClass().getName())) {
                    return view;
                }
            }
        } catch (Throwable ignored) {
            // 拿不到就算了。
        }
        return null;
    }

    private static View findViewByClassName(View root, String className) {
        if (root == null) {
            return null;
        }
        if (className.equals(root.getClass().getName())) {
            return root;
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findViewByClassName(group.getChildAt(i), className);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** id 是 App 的 fromName 动画时，换成同包的 toName 动画；否则原样返回。 */
    private static int mapAnim(Context context, int id, String fromName, String toName) {
        if (id == 0) {
            return id;
        }
        try {
            String pkg = context.getPackageName();
            if (id != context.getResources().getIdentifier(fromName, "anim", pkg)) {
                return id;
            }
            int target = context.getResources().getIdentifier(toName, "anim", pkg);
            return target == 0 ? id : target;
        } catch (Throwable t) {
            return id;
        }
    }

    private static int toInt(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    /**
     * 戴上耳机后每次启动 App 都会弹的「臻品音质」弹窗。
     *
     * 链路：MainActivityViewModel.handlePermissionGrantedTasks（启动时）
     *   -> ExcellentHeadPhoneManager.registerDeviceListener 监听/读取蓝牙 A2DP 设备名
     *   -> handleBluetoothConnected(deviceName) -> DebounceThrottle(500ms)
     *   -> ExcellentHeadPhoneManager.checkAndShow()。
     *
     * checkAndShow() 先做一串前置判断（设备名为空、折叠屏外屏、离线、未登录、
     * 已有弹窗、青少年模式、横屏播放、广告页、其它 Alert 互斥……），再请求服务端
     * （GCI: MtPopWindowSvr.GetEarphoneConnectPopup）拿弹窗内容并做频控，最后 show()。
     * 弹窗类型有 TRY（免费体验 30 分钟）/ SVIP / TO_SVIP_PAGE 三种，按钮文案都是
     * 服务端下发的（例如「立即开通」「30分钟免费体验」）。
     *
     * checkAndShow() 是唯一的展示入口，这里让它直接返回 kotlin.Unit.INSTANCE，
     * 等价于它自己所有「不展示」的分支：弹窗（以及它上面那个免费体验按钮）不会再出现，
     * 连带着服务端请求和频控写库也一并省掉。
     */
    private void hookHeadphoneQualityPopup(ClassLoader cl) {
        Class<?> manager = loadClass(cl,
                "com.tencent.qqmusiclite.business.bluetooth.headphone.ExcellentHeadPhoneManager");
        if (manager == null) {
            log(android.util.Log.WARN, TAG, "ExcellentHeadPhoneManager not found");
            return;
        }
        Class<?> continuation = loadClass(cl, "kotlin.coroutines.Continuation");
        Method checkAndShow = continuation == null ? null : findMethod(manager, "checkAndShow", continuation);
        if (checkAndShow == null) {
            checkAndShow = findMethodByName(manager, "checkAndShow");
        }
        Object unit = staticField(loadClass(cl, "kotlin.Unit"), "INSTANCE");
        if (checkAndShow == null || unit == null) {
            log(android.util.Log.WARN, TAG, "ExcellentHeadPhoneManager.checkAndShow not hooked");
            return;
        }
        final Object result = unit;
        hook(checkAndShow).intercept(chain -> {
            log(android.util.Log.INFO, TAG, "blocked earphone quality popup: checkAndShow");
            return result;
        });
        log(android.util.Log.INFO, TAG, "hooked ExcellentHeadPhoneManager.checkAndShow");
    }

    /**
     * 播放页里盖在进度条上的会员推广文案。
     *
     * 播放页布局 play_song_content.xml 里的 tv_song_progress_bar_fixed_entrance 是一个
     * 贴着进度条右端、和进度条同高的 TextView（宽 wrap_content，直接压在进度条上面）。
     * 刷新播放页提示时（refreshPlayTipsImpl -> adjustPlayingTips）会把下发的付费文案塞进去
     * 再显示出来，例如「成为超级会员，10万本好书任意听」「升级超级会员，畅听有声内容」
     * 「免费听歌时长有限，开通会员无限畅享」。文案一长就把进度条右半边盖住了。
     *
     * 这里让 setProgressBarFixedEntrance(String) 整个不执行（它只会被用来展示这类付费入口），
     * 同时把那个 TextView 清空隐藏，进度条就不会再被挡；点它本来也只是跳会员购买页。
     */
    private void hookPlayerVipTips(ClassLoader cl) {
        Class<?> fragment = loadClass(cl, "com.tencent.qqmusiclite.activity.player.song.PlayerSongFragment");
        if (fragment == null) {
            log(android.util.Log.WARN, TAG, "PlayerSongFragment not found");
            return;
        }
        Method entrance = findMethod(fragment, "setProgressBarFixedEntrance", String.class);
        if (entrance == null) {
            log(android.util.Log.WARN, TAG, "PlayerSongFragment.setProgressBarFixedEntrance not found");
            return;
        }
        hook(entrance).intercept(chain -> {
            Object tips = chain.getArgs().isEmpty() ? null : chain.getArg(0);
            log(android.util.Log.INFO, TAG, "blocked progress bar vip tips: " + tips);
            hideProgressBarFixedEntrance(chain.getThisObject());
            return null;
        });
    }

    /**
     * 反射拿到 PlayerSongFragment.binding.tvSongProgressBarFixedEntrance 清空隐藏，
     * 顺便把进度条的裁剪还原（hideProgressBarFixedEntrance 原本做的事）。
     */
    private void hideProgressBarFixedEntrance(Object fragment) {
        try {
            Object binding = readField(fragment, "binding");
            if (binding == null) {
                return;
            }
            Object entranceView = readField(binding, "tvSongProgressBarFixedEntrance");
            if (entranceView instanceof View) {
                final View view = (View) entranceView;
                final TextView textView = entranceView instanceof TextView ? (TextView) entranceView : null;
                view.post(new Runnable() {
                    @Override
                    public void run() {
                        if (textView != null) {
                            textView.setText("");
                        }
                        view.setVisibility(View.GONE);
                    }
                });
            }
            Object seekBar = readField(binding, "songProgressBar");
            if (seekBar instanceof View) {
                final Object bar = seekBar;
                ((View) seekBar).post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            bar.getClass().getMethod("resetClipRect").invoke(bar);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "hide progress bar entrance failed: " + t);
        }
    }

    /** 沿类继承链找字段并取值，找不到返回 null。 */
    private static Object readField(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> cls = target.getClass(); cls != null; cls = cls.getSuperclass()) {
            try {
                Field field = cls.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                // Keep walking up the hierarchy.
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /**
     * 腾讯 AMS 开屏（含被它转发的 MIUI 系统开屏）。
     *
     * isNeedSplashAd(Intent) 是冷启动与热启动共同的闸门，连着让 SplashAdActivity /
     * MiuiAd.startMIUIAd 都不发生；返回一个非 ENABLE 的枚举值即可。
     * checkHotSplash(Activity) 单独再断一次，防它绕过前者。
     */
    private void hookAmsSplash(ClassLoader cl) {
        Class<?> amsGlobal = loadClass(cl, "com.tencent.qqmusiclite.business.splashad.ams.AmsGlobal");
        if (amsGlobal == null) {
            return;
        }

        Object disable = enumConstant(cl,
                "com.tencent.qqmusiclite.business.splashad.data.enums.Enable", "DISABLE_TIME_INTERVAL");
        Method isNeedSplashAd = findMethod(amsGlobal, "isNeedSplashAd", Intent.class);
        if (isNeedSplashAd != null && disable != null) {
            final Object value = disable;
            hook(isNeedSplashAd).intercept(chain -> {
                log(android.util.Log.INFO, TAG, "blocked AmsGlobal.isNeedSplashAd");
                return value;
            });
        } else {
            log(android.util.Log.WARN, TAG, "AmsGlobal.isNeedSplashAd not hooked");
        }

        Method checkHotSplash = findMethod(amsGlobal, "checkHotSplash", Activity.class);
        if (checkHotSplash != null) {
            hook(checkHotSplash).intercept(chain -> {
                log(android.util.Log.INFO, TAG, "blocked AmsGlobal.checkHotSplash");
                return null;
            });
        }
    }

    /**
     * 小米音乐的运营开屏（QQSplashActivity）。
     * coldStartCheck 是 MusicBaseActivity.onCreate 的闸门，hotStartCheck 是
     * ApplicationHelper.onBackToApplication（回到前台）的闸门。
     */
    private void hookOperateSplash(ClassLoader cl) {
        Class<?> helper = loadClass(cl, "com.miui.player.splash.QQSplashHelper");
        if (helper == null) {
            return;
        }
        hookReturnBooleanFalse(helper, "coldStartCheck", Activity.class);
        hookReturnBooleanFalse(helper, "hotStartCheck", Context.class);
    }

    /**
     * MIUI 自己的开屏：SplashHelper.trySplash 决定要不要请求 MSA 系统开屏广告
     * （SystemSplashAd.requestAd），以及要不要显示 SplashActivity。
     */
    private void hookMiuiSplash(ClassLoader cl) {
        Class<?> helper = loadClass(cl, "com.miui.player.splash.SplashHelper");
        if (helper == null) {
            return;
        }
        hookReturnBooleanFalse(helper, "trySplash", Activity.class, boolean.class);

        Method tryAdSplash = findMethod(helper, "tryAdSplash", Activity.class);
        if (tryAdSplash != null) {
            hook(tryAdSplash).intercept(chain -> {
                log(android.util.Log.INFO, TAG, "blocked SplashHelper.tryAdSplash");
                return null;
            });
        }
    }

    /** 兜底：广告 Activity 只要真的被拉起来，onCreate 走完立刻关掉。 */
    private void hookAdActivityFallbacks(ClassLoader cl) {
        for (String name : AD_ACTIVITIES) {
            Class<?> activity = loadClass(cl, name);
            if (activity == null) {
                continue;
            }
            Method onCreate = findMethod(activity, "onCreate", Bundle.class);
            if (onCreate == null) {
                continue;
            }
            hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof Activity) {
                    log(android.util.Log.INFO, TAG, "closing ad activity " + name);
                    ((Activity) self).finish();
                }
                return result;
            });
        }
    }

    /** onVisible 之后多久内到达的 setRefreshing(true) 算「进主页时的自动刷新」。 */
    private static final long HOME_REFRESH_WINDOW_MS = 2000L;
    /** 自动刷新延后多久补发，给首屏缓存/网络数据留出落地时间。 */
    private static final long HOME_REFRESH_DELAY_MS = 1000L;

    /** 主页最近一次 onVisible 的时刻（uptimeMillis），0 表示还没见过。 */
    private static volatile long sHomeVisibleAtMs = 0L;
    /** 主页 Fragment 弱引用，用于延后结束时重判数据是否已就绪。 */
    private static volatile WeakReference<Object> sHomeFragmentRef = new WeakReference<>(null);
    /** 延迟任务里重新调用 setRefreshing 时置位，避免被自己的 hook 二次延后。 */
    private static volatile boolean sDeferBypass = false;

    /**
     * 主页「进页面自动刷一下」延后。
     *
     * 背景（4.46.6.1）：跳过（或干脆没有）开屏广告时，onVisible 在首屏数据还没落地时
     * 就发出自动刷新（HomeFragment.onVisible -> mRecyclerView.post -> setRefreshing(true)），
     * 刷新控件在展开动画中途又收到收尾调用，状态机停在中间态：页头卡在下沉位置不回弹。
     * 手动下拉能拉回来，因为手指释放那条路径不经过 setRefreshing；看了广告的启动晚几秒，
     * 自动刷新等数据落定后才执行，就一路正常。
     *
     * 做法：记录 HomeFragment.onVisible 的时刻；把紧跟在主页可见之后、控件空闲时到达的
     * setRefreshing(true)（唯一漏斗重载，两个单参重载最终都调它）挪到
     * HOME_REFRESH_DELAY_MS 之后再执行。到点时重判：
     *   - 控件已被别的刷新占用（status != 0）或页面已销毁 → 丢弃这次自动刷新；
     *   - 数据已就绪（非空且不是缓存）→ 跳过刷新（等价于「看了广告」的那条路径）；
     *   - 否则用原参数补一次 setRefreshing(true, dur)，走 App 自己的正常流程。
     * 只认主页那个 RecyclerView，其它页面同名控件不受影响；手动下拉不受影响。
     */
    private void hookHomeAutoRefresh(ClassLoader cl) {
        Class<?> home = loadClass(cl, "com.tencent.qqmusiclite.fragment.home.HomeFragment");
        Class<?> recycler = loadClass(cl,
                "com.tencent.qqmusic.business.timeline.ui.refreshable.RefreshableRecyclerViewNew");
        Method onVisible = home == null ? null : findMethod(home, "onVisible");
        Method getMRecyclerView = home == null ? null : findMethod(home, "getMRecyclerView");
        Method getViewModel = home == null ? null : findMethod(home, "getViewModel");
        Method setRefreshing = recycler == null ? null
                : findMethod(recycler, "setRefreshing", boolean.class, int.class);
        Method getStatus = recycler == null ? null : findMethod(recycler, "getStatus");
        if (onVisible == null || getMRecyclerView == null || getViewModel == null
                || setRefreshing == null || getStatus == null) {
            log(android.util.Log.WARN, TAG, "home auto refresh hooks not found"
                    + " (onVisible=" + (onVisible != null)
                    + " getMRecyclerView=" + (getMRecyclerView != null)
                    + " getViewModel=" + (getViewModel != null)
                    + " setRefreshing=" + (setRefreshing != null)
                    + " getStatus=" + (getStatus != null) + ")");
            return;
        }

        hook(onVisible).intercept(chain -> {
            Object result = chain.proceed();
            Object self = chain.getThisObject();
            if (home.isInstance(self)) {
                sHomeVisibleAtMs = SystemClock.uptimeMillis();
                sHomeFragmentRef = new WeakReference<>(self);
            }
            return result;
        });

        hook(setRefreshing).intercept(chain -> {
            if (sDeferBypass) {
                // 延迟任务补发的那次，直接放行。
                sDeferBypass = false;
                return chain.proceed();
            }
            Object self = chain.getThisObject();
            if (!(self instanceof View)
                    || !Boolean.TRUE.equals(chain.getArg(0))
                    || !withinHomeRefreshWindow()
                    || !isHomeRecycler(self, getMRecyclerView)
                    || statusOf(self, getStatus) != 0) {
                return chain.proceed();
            }
            final View view = (View) self;
            final int duration = toInt(chain.getArg(1));
            log(android.util.Log.INFO, TAG, "defer home auto refresh " + HOME_REFRESH_DELAY_MS + "ms");
            view.postDelayed(() -> runDeferredHomeRefresh(view, duration, setRefreshing,
                    getStatus, getViewModel), HOME_REFRESH_DELAY_MS);
            return null;
        });
        log(android.util.Log.INFO, TAG,
                "hooked HomeFragment.onVisible + setRefreshing for auto refresh deferral");
    }

    private static boolean withinHomeRefreshWindow() {
        long at = sHomeVisibleAtMs;
        if (at == 0L) {
            return false;
        }
        long since = SystemClock.uptimeMillis() - at;
        return since >= 0L && since <= HOME_REFRESH_WINDOW_MS;
    }

    private static boolean isHomeRecycler(Object recyclerView, Method getMRecyclerView) {
        Object fragment = sHomeFragmentRef.get();
        if (fragment == null) {
            return false;
        }
        try {
            return getMRecyclerView.invoke(fragment) == recyclerView;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int statusOf(Object recyclerView, Method getStatus) {
        try {
            Object status = getStatus.invoke(recyclerView);
            return status instanceof Number ? ((Number) status).intValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 延后到点：重新判断这次自动刷新还该不该发。 */
    private void runDeferredHomeRefresh(View view, int duration, Method setRefreshing,
                                        Method getStatus, Method getViewModel) {
        if (!view.isAttachedToWindow()) {
            log(android.util.Log.INFO, TAG, "deferred auto refresh dropped: view detached");
            return;
        }
        if (statusOf(view, getStatus) != 0) {
            log(android.util.Log.INFO, TAG, "deferred auto refresh dropped: refresh already running");
            return;
        }
        if (homeDataReady(getViewModel)) {
            log(android.util.Log.INFO, TAG, "deferred auto refresh skipped: data already loaded");
            return;
        }
        log(android.util.Log.INFO, TAG, "deferred auto refresh runs");
        sDeferBypass = true;
        try {
            setRefreshing.invoke(view, true, duration);
        } catch (Throwable t) {
            sDeferBypass = false;
            log(android.util.Log.WARN, TAG, "deferred auto refresh failed: " + t);
        }
    }

    /** 数据已就绪 = 非空且不是缓存；判断不了时按未就绪处理（照常刷新）。 */
    private static boolean homeDataReady(Method getViewModel) {
        Object fragment = sHomeFragmentRef.get();
        if (fragment == null) {
            return false;
        }
        try {
            Object viewModel = getViewModel.invoke(fragment);
            if (viewModel == null) {
                return false;
            }
            Object empty = viewModel.getClass().getMethod("empty").invoke(viewModel);
            Object cached = viewModel.getClass().getMethod("isCachedData").invoke(viewModel);
            return Boolean.FALSE.equals(empty) && Boolean.FALSE.equals(cached);
        } catch (Throwable t) {
            return false;
        }
    }

    private void hookReturnBooleanFalse(Class<?> cls, String name, Class<?>... params) {
        Method method = findMethod(cls, name, params);
        if (method == null) {
            log(android.util.Log.WARN, TAG, cls.getSimpleName() + "." + name + " not found");
            return;
        }
        hook(method).intercept(chain -> {
            log(android.util.Log.INFO, TAG, "blocked " + cls.getSimpleName() + "." + name);
            return Boolean.FALSE;
        });
    }

    private static Class<?> loadClass(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            return cls.getDeclaredMethod(name, params);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按方法名找第一个同名方法，作为参数类型对不上时的兜底。 */
    private static Method findMethodByName(Class<?> cls, String name) {
        for (Method method : cls.getDeclaredMethods()) {
            if (name.equals(method.getName())) {
                return method;
            }
        }
        return null;
    }

    /** 取静态字段（例如 kotlin.Unit.INSTANCE），失败返回 null。 */
    private static Object staticField(Class<?> cls, String name) {
        if (cls == null) {
            return null;
        }
        try {
            Field field = cls.getField(name);
            return field.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(ClassLoader cl, String className, String field) {
        Class<?> cls = loadClass(cl, className);
        if (cls == null) {
            return null;
        }
        try {
            Field f = cls.getField(field);
            return f.get(null);
        } catch (Throwable ignored) {
            // Fall through to the Enum API below.
        }
        try {
            return Enum.valueOf((Class<? extends Enum>) cls.asSubclass(Enum.class), field);
        } catch (Throwable t) {
            return null;
        }
    }
}
