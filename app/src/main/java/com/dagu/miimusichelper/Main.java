package com.dagu.miimusichelper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 小米音乐（com.miui.player）小助手。七项功能，都能在 App 自己的「设置」页里单独开关：
 *
 * 1. 去开屏广告。
 * 2. 去掉播放页压在进度条上的会员推广文案。
 * 3. 去掉戴上耳机后每次启动弹出的「臻品音质」弹窗。
 * 4. 播放页进入/退出的过渡动画：进入 400ms 内容级上滑、退出 400ms 窗口下滑。
 * 5. 主页「进页面自动刷新」延后：跳开屏广告后进主页不会再把刷新头卡在半路。
 * 6. 首页去掉「免费听」tab（顶部入口和底部入口都不再出现）。
 * 7. 去掉播放条上方的会员推广气泡（「成为超级会员，10万本好书任意听」那条）。
 *
 * 设置入口不注册 Activity。两种音源的设置页长得不一样，各插各的：
 *   - 小米音源：宿主用 androidx.preference 的 BasicSettingsXFragment / AdvanceSettingsXFragment，
 *     在 onCreatePreferences 跑完后往页面里插一个「小米音乐小助手」分类（见 hookSettingsPage）。
 *   - QQ 音源（设备默认）：「更多」设置页整页是 Compose 画的，没有 Preference、也没有
 *     原生 View 可插；但列表每一行都是宿主 SettingComposeBaseKt 里的 @Composable 函数画的，
 *     所以在「通用设置」下面插一行入口「小米音乐小助手」（见 hookSettingsHubEntry），
 *     点它用宿主自己的导航（holderFragment + 自定义 fragment_label）新开一页，
 *     页面内容同样是宿主的 Compose 行：标题栏 + 七个开关 + 「重启小米音乐」（见 hookHelperPage）。
 * 两套入口都是同一组开关；模块自己没有 Activity、没有自己的 res，也不弹对话框
 * （做法参考 Zhiliao 的「往宿主设置页插条目」）。
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
        hookAppContext(cl);
        hookSettingsPage(cl);
        hookSettingsHubEntry(cl);
        hookHelperPage(cl);
        hookAmsSplash(cl);
        hookOperateSplash(cl);
        hookMiuiSplash(cl);
        hookAdActivityFallbacks(cl);
        hookPlayerVipTips(cl);
        hookHeadphoneQualityPopup(cl);
        hookPlayerTransitions(cl);
        hookPlayerEnterAnimation(cl);
        hookHomeAutoRefresh(cl);
        hookHomeQuickPlayTab(cl);
        hookMinibarVipBubble(cl);
    }

    // ------------------------------------------------------------------
    // 设置页面：把开关插进宿主自己的设置页（不注册 Activity，参考 Zhiliao）
    // ------------------------------------------------------------------

    /** 开关值存放的 SharedPreferences 文件名（写在宿主的私有数据目录里）。 */
    private static final String PREFS_NAME = "miui_music_helper";

    /** 七个开关的 key，同时也是 SharedPreferences 里的键。 */
    private static final String KEY_SPLASH = "helper_splash_ad";
    private static final String KEY_VIP_TIPS = "helper_vip_tips";
    private static final String KEY_HEADPHONE_POPUP = "helper_headphone_popup";
    private static final String KEY_PLAYER_ANIM = "helper_player_anim";
    private static final String KEY_HOME_REFRESH = "helper_home_refresh";
    private static final String KEY_QUICK_PLAY_TAB = "helper_quick_play_tab";
    private static final String KEY_MINIBAR_BUBBLE = "helper_minibar_bubble";

    private static final String SETTINGS_CATEGORY_KEY = "helper_category";
    private static final String SETTINGS_CATEGORY_TITLE = "小米音乐小助手";

    /** 七个开关的展示文案，顺序和 KEY_* 声明一致：{key, 标题, 说明}。 */
    private static final String[][] HELPER_FEATURES = {
            {KEY_SPLASH, "去开屏广告", "冷启动 / 热启动 / 运营开屏的广告都不展示"},
            {KEY_VIP_TIPS, "去播放页会员文案", "去掉压在进度条上的付费引导文案"},
            {KEY_HEADPHONE_POPUP, "去「臻品音质」弹窗", "戴上耳机后不再弹音质推广弹窗"},
            {KEY_PLAYER_ANIM, "播放页进出动画", "进入 / 退出换成 400ms 的整页滑动"},
            {KEY_HOME_REFRESH, "主页自动刷新延后", "跳过开屏广告进主页时刷新头不再卡住"},
            {KEY_QUICK_PLAY_TAB, "去首页「免费听」tab", "顶部和底部的免费听入口都不再出现"},
            {KEY_MINIBAR_BUBBLE, "去播放条会员气泡", "首次播放时的付费引导气泡不再出现"},
    };

    /** 宿主 Application context，读开关用（见 hookAppContext）。 */
    private static volatile Context sAppContext;

    /** 有设置列表的两个页面：基础设置（我的 → 设置）和更多设置（我的 → 设置 → 更多设置）。 */
    private static final String[] SETTINGS_FRAGMENTS = {
            "com.miui.player.phone.ui.BasicSettingsXFragment",
            "com.miui.player.phone.ui.AdvanceSettingsXFragment",
    };

    /**
     * 提前拿到宿主 Application，让各 hook 随时能读开关（不能等用户进设置页）。
     * Instrumentation.callApplicationOnCreate 是 App 启动必经之路，这时 Application 已就绪。
     */
    private void hookAppContext(ClassLoader cl) {
        Class<?> instrumentation = loadClass(cl, "android.app.Instrumentation");
        Method callApplicationOnCreate = instrumentation == null ? null
                : findMethod(instrumentation, "callApplicationOnCreate", android.app.Application.class);
        if (callApplicationOnCreate == null) {
            log(android.util.Log.WARN, TAG, "Instrumentation.callApplicationOnCreate not found");
            return;
        }
        hook(callApplicationOnCreate).intercept(chain -> {
            Object result = chain.proceed();
            Object app = chain.getArg(0);
            if (app instanceof Context) {
                sAppContext = ((Context) app).getApplicationContext();
                log(android.util.Log.INFO, TAG, "music source = " + musicSource(cl));
            }
            return result;
        });
    }

    /** 当前音源：0 = 小米音源，1 = QQ 音源（-1 = 读不到）。音源决定「设置」页是哪一套。 */
    private static int musicSource(ClassLoader cl) {
        Class<?> utils = loadClass(cl, "com.miui.player.content.preference.MusicSourceUtils");
        Method get = utils == null ? null : findMethod(utils, "getMusicSource", Context.class);
        Context context = sAppContext;
        if (get == null || context == null) {
            return -1;
        }
        try {
            Object source = get.invoke(null, context);
            return source instanceof Number ? ((Number) source).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 模块自己的设置；宿主 Application 还没拿到时返回 null。 */
    private static SharedPreferences prefs() {
        Context context = sAppContext;
        if (context == null) {
            return null;
        }
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 开关状态；没写过 / 读不到时按开启处理，保持模块原来的默认行为。 */
    private static boolean isEnabled(String key) {
        SharedPreferences p = prefs();
        return p == null || p.getBoolean(key, true);
    }

    private void writeSetting(String key, boolean value) {
        SharedPreferences p = prefs();
        if (p == null) {
            return;
        }
        p.edit().putBoolean(key, value).apply();
        log(android.util.Log.INFO, TAG, "setting " + key + " = " + value);
    }

    /**
     * 往宿主设置页里插一块自己的设置。
     *
     * Zhiliao 那套（自造资源 id + 换掉 PreferenceInflater 的 XML、再用 AssetManager
     * 加载模块自己的 res）这里用不上：宿主自带完整的 androidx.preference，直接反射
     * new 控件加进页面就行，不需要模块资源。开关值存进模块自己的 prefs。
     */
    private void hookSettingsPage(ClassLoader cl) {
        for (String name : SETTINGS_FRAGMENTS) {
            Class<?> fragment = loadClass(cl, name);
            if (fragment == null) {
                continue;
            }
            Method onCreatePreferences = findMethod(fragment,
                    "onCreatePreferences", Bundle.class, String.class);
            if (onCreatePreferences == null) {
                log(android.util.Log.WARN, TAG, name + ".onCreatePreferences not found");
                continue;
            }
            hook(onCreatePreferences).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    addHelperPreferences(chain.getThisObject());
                } catch (Throwable t) {
                    log(android.util.Log.WARN, TAG, "add helper preferences failed: " + t);
                }
                return result;
            });
            log(android.util.Log.INFO, TAG, "hooked " + name + " for settings entry");
        }
    }

    /** 在设置页最上面加一个「小米音乐小助手」分类：七个开关 + 重启入口。 */
    private void addHelperPreferences(Object fragment) throws Throwable {
        Object screen = fragment.getClass().getMethod("getPreferenceScreen").invoke(fragment);
        if (screen == null) {
            return;
        }
        Context context = (Context) screen.getClass().getMethod("getContext").invoke(screen);
        if (context == null) {
            return;
        }
        if (sAppContext == null) {
            sAppContext = context.getApplicationContext();
        }

        Prefs ui = new Prefs(screen, context);
        if (ui.findPreference.invoke(screen, SETTINGS_CATEGORY_KEY) != null) {
            return; // 同一个页面已经插过了
        }

        Object category = ui.newPreference(ui.categoryCls);
        ui.setTitle.invoke(category, SETTINGS_CATEGORY_TITLE);
        ui.setKey.invoke(category, SETTINGS_CATEGORY_KEY);
        ui.setPersistent.invoke(category, false);
        ui.setOrder.invoke(category, -1); // order 比宿主所有条目都小 → 排在最上面

        for (String[] feature : HELPER_FEATURES) {
            ui.addSwitch(category, feature[0], feature[1], feature[2]);
        }
        ui.addRestartRow(category);

        ui.addPreference.invoke(screen, category);
        log(android.util.Log.INFO, TAG,
                "settings entry added to " + fragment.getClass().getSimpleName());
    }

    /**
     * androidx.preference 的反射包装：模块编译期只有 compileOnly 的 LSPosed API，
     * 没有 androidx 依赖，设置页里的控件只能反射构造（宿主进程里这些类都在）。
     */
    private final class Prefs {
        final Context context;
        final Class<?> preferenceCls;
        final Class<?> categoryCls;
        final Class<?> switchCls;
        final Class<?> changeListenerCls;
        final Class<?> clickListenerCls;
        final Method setTitle;
        final Method setSummary;
        final Method setKey;
        final Method setOrder;
        final Method setPersistent;
        final Method setChecked;
        final Method setOnPreferenceChangeListener;
        final Method setOnPreferenceClickListener;
        final Method addPreference;
        final Method findPreference;

        Prefs(Object screen, Context context) throws Throwable {
            ClassLoader cl = screen.getClass().getClassLoader();
            this.context = context;
            this.preferenceCls = Class.forName("androidx.preference.Preference", false, cl);
            this.categoryCls = Class.forName("androidx.preference.PreferenceCategory", false, cl);
            this.switchCls = Class.forName("androidx.preference.SwitchPreference", false, cl);
            this.changeListenerCls = Class.forName(
                    "androidx.preference.Preference$OnPreferenceChangeListener", false, cl);
            this.clickListenerCls = Class.forName(
                    "androidx.preference.Preference$OnPreferenceClickListener", false, cl);
            this.setTitle = preferenceCls.getMethod("setTitle", CharSequence.class);
            this.setSummary = preferenceCls.getMethod("setSummary", CharSequence.class);
            this.setKey = preferenceCls.getMethod("setKey", String.class);
            this.setOrder = preferenceCls.getMethod("setOrder", int.class);
            this.setPersistent = preferenceCls.getMethod("setPersistent", boolean.class);
            this.setChecked = switchCls.getMethod("setChecked", boolean.class);
            this.setOnPreferenceChangeListener = preferenceCls.getMethod(
                    "setOnPreferenceChangeListener", changeListenerCls);
            this.setOnPreferenceClickListener = preferenceCls.getMethod(
                    "setOnPreferenceClickListener", clickListenerCls);
            this.addPreference = screen.getClass().getMethod("addPreference", preferenceCls);
            this.findPreference = screen.getClass().getMethod("findPreference", CharSequence.class);
        }

        Object newPreference(Class<?> cls) throws Throwable {
            Constructor<?> constructor = cls.getConstructor(Context.class);
            return constructor.newInstance(context);
        }

        void addSwitch(Object group, String key, String title, String summary) throws Throwable {
            Object preference = newPreference(switchCls);
            setKey.invoke(preference, key);
            setTitle.invoke(preference, title);
            setSummary.invoke(preference, summary);
            setPersistent.invoke(preference, false);
            setChecked.invoke(preference, isEnabled(key));
            setOnPreferenceChangeListener.invoke(preference, changeListener(key));
            addPreference.invoke(group, preference);
        }

        void addRestartRow(Object group) throws Throwable {
            Object preference = newPreference(preferenceCls);
            setKey.invoke(preference, "helper_restart");
            setTitle.invoke(preference, "重启小米音乐");
            setSummary.invoke(preference, "部分改动重启后完全生效");
            setPersistent.invoke(preference, false);
            setOnPreferenceClickListener.invoke(preference, clickListener());
            addPreference.invoke(group, preference);
        }

        /** 开关回调：返回 true 让 UI 跟着动，同时把值写进模块自己的 prefs。 */
        private Object changeListener(final String key) {
            return Proxy.newProxyInstance(changeListenerCls.getClassLoader(),
                    new Class<?>[]{changeListenerCls}, (proxy, method, args) -> {
                        if ("onPreferenceChange".equals(method.getName())) {
                            Object value = args != null && args.length > 1 ? args[1] : null;
                            if (value instanceof Boolean) {
                                writeSetting(key, (Boolean) value);
                            }
                            return Boolean.TRUE;
                        }
                        return objectMethod(proxy, method, args);
                    });
        }

        /** 「重启小米音乐」点击回调。 */
        private Object clickListener() {
            return Proxy.newProxyInstance(clickListenerCls.getClassLoader(),
                    new Class<?>[]{clickListenerCls}, (proxy, method, args) -> {
                        if ("onPreferenceClick".equals(method.getName())) {
                            restartHost(context);
                            return Boolean.TRUE;
                        }
                        return objectMethod(proxy, method, args);
                    });
        }
    }

    /** Proxy 的 equals / hashCode / toString 兜底。 */
    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals":
                return args != null && args.length > 0 && proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "MiuiMusicHelperPreferenceListener";
            default:
                return null;
        }
    }

    /** 反射调用的异常都包了一层 InvocationTargetException，日志里打到根因（LSPosed 日志每行截断）。 */
    private void logFailure(String what, Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        StackTraceElement[] frames = cause.getStackTrace();
        log(android.util.Log.WARN, TAG, what + ": " + cause
                + (frames.length > 0 ? " @ " + frames[0] : ""));
    }

    /** 重启宿主：先拉起启动页，再结束当前进程（同 Zhiliao 的「重启知乎」按钮）。 */
    private void restartHost(Context context) {
        if (context == null) {
            log(android.util.Log.WARN, TAG, "restart host skipped: no context");
            return;
        }
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(PKG);
            if (launch != null && launch.getComponent() != null) {
                Intent restart = Intent.makeRestartActivityTask(launch.getComponent());
                restart.setPackage(PKG);
                context.startActivity(restart);
            }
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "restart host failed: " + t);
        }
        System.exit(0);
    }

    // ------------------------------------------------------------------
    // QQ 音源设置首页（「更多」页）：整套设置都用宿主自己的 Compose 行画
    // ------------------------------------------------------------------

    /** 认位置用的锚点文案；优先读宿主资源，读不到就用中文原文。 */
    private static final String HUB_ANCHOR_TITLE = "通用设置";

    /** 锚点文案解析一次就缓存，免得每渲染一行都去查一次资源。 */
    private static volatile String sHubAnchorTitle;

    private static String hubAnchorTitle() {
        String cached = sHubAnchorTitle;
        if (cached != null) {
            return cached;
        }
        Context context = sAppContext;
        if (context != null) {
            int id = context.getResources().getIdentifier("setting_normal_title", "string", PKG);
            if (id != 0) {
                try {
                    String title = context.getString(id);
                    sHubAnchorTitle = title;
                    return title;
                } catch (Throwable ignored) {
                    // 用下面的中文兜底
                }
            }
        }
        return HUB_ANCHOR_TITLE;
    }

    /** Compose 里 remember 状态用的分组 key，自己编的，同一层里不撞就行。 */
    private static final int GROUP_REVISION = 0x48454c51;

    /** 独立设置页在宿主导航里用的 fragment_label；自定义值，宿主自己不会用。 */
    private static final String HELPER_PAGE_LABEL = "miui_helper";

    /** 宿主 BaseHolderFragment 从 arguments 里读的 key（见其 onCreate / EmbeddingNavigationHelper）。 */
    private static final String KEY_FRAGMENT_LABEL = "fragment_label";
    private static final String KEY_FRAGMENT_BACK = "fragment_back";

    /** 宿主二级设置页统一的 action type（宿主自己调 toSettingFragment 时也传 8）。 */
    private static final String KEY_ACTION_TYPE = "KEY_ACTION_TYPE";

    /** 「更多」页实例：点入口时拿它的 NavController 导航；onCreateView 时记下（弱引用）。 */
    private static volatile WeakReference<Object> sMoreSettingsFragment;

    /** 宿主导航图里 holderFragment 目的地的资源 id，查一次就缓存。 */
    private static volatile int sHolderFragmentId;

    /** 渲染设置行用的反射门面（hookSettingsHubEntry 里建好，两个页面共用）。 */
    private static volatile ComposeApi sComposeApi;

    /** 独立设置页的反射门面（第一次打开页面时建好）。 */
    private static volatile HelperPageApi sHelperPageApi;

    /**
     * QQ 音源下「我的 → 更多/设置」进来的是 MoreSettingsFragment 这一页（标题「更多」），
     * 整页 Compose：没有 Preference，也没有原生 View 可插，列表每一行都是
     * SettingComposeBaseKt 里的 @Composable 函数画的。
     *
     * 所以 hook MoreSettingItemWithTextArrow，宿主渲染完「通用设置」那一行之后，借它这次
     * 传进来的 Composer 接着渲染一行 MoreSettingItemWithTextArrow「小米音乐小助手」；
     * 点它走宿主自己的导航新开一页（见 hookHelperPage），不再把开关挤在这一页里。
     * 行样式、夜间模式、点击水波纹全跟宿主一致，也不用弹对话框。
     *
     * Compose 按调用位置记节点，重组时会复用同一行，不会越插越多。
     */
    private void hookSettingsHubEntry(ClassLoader cl) {
        Class<?> kt = loadClass(cl, "com.tencent.qqmusiclite.ui.settings.SettingComposeBaseKt");
        Class<?> function0 = loadClass(cl, "kotlin.jvm.functions.Function0");
        Class<?> composer = loadClass(cl, "androidx.compose.runtime.Composer");
        Method row = kt == null || function0 == null || composer == null ? null
                : findSettingRow(kt, composer, "MoreSettingItemWithTextArrow",
                        String.class, String.class, boolean.class, function0);
        if (row == null) {
            log(android.util.Log.WARN, TAG, "SettingComposeBaseKt.MoreSettingItemWithTextArrow not found");
            return;
        }
        ComposeApi api;
        try {
            api = new ComposeApi(cl, kt);
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "helper compose rows not available: " + t);
            return;
        }
        sComposeApi = api;
        hook(row).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Object title = chain.getArg(0);
                if (title instanceof String && hubAnchorTitle().equals(title)) {
                    addHelperRows(api, chain.getArg(4));
                }
            } catch (Throwable t) {
                log(android.util.Log.WARN, TAG, "insert helper rows failed: " + t);
            }
            return result;
        });
        log(android.util.Log.INFO, TAG, "hooked settings hub row composable");
    }

    /**
     * 在 SettingComposeBaseKt 里按名字和参数表找那个 @Composable 行函数：
     * 参数表 = leading 里列的形状 + Composer + changed（+ defaults）。
     */
    private static Method findSettingRow(Class<?> kt, Class<?> composer, String name,
                                         Class<?>... leading) {
        for (Method method : kt.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (!name.equals(method.getName())
                    || params.length < leading.length + 2
                    || params.length > leading.length + 3
                    || params[leading.length] != composer) {
                continue;
            }
            boolean match = true;
            for (int i = 0; i < leading.length && match; i++) {
                match = params[i] == leading[i];
            }
            if (match) {
                return method;
            }
        }
        return null;
    }

    /** 在「通用设置」那一行后面插入口行。 */
    private void addHelperRows(ComposeApi api, Object composer) throws Throwable {
        api.arrowRow(composer, SETTINGS_CATEGORY_TITLE, "只对 QQ 音源生效",
                api.openPageClick());
    }

    /**
     * 宿主的 Compose 运行时 + SettingComposeBaseKt 里的行函数，全反射调：
     * 模块编译期只有 compileOnly 的 LSPosed API，没有 androidx.compose 依赖。
     */
    private final class ComposeApi {
        private final Object composerEmpty;
        private final Object unit;
        /** 结构相等策略；mutableStateOf 收不到策略会 NPE，必须给一个。 */
        private final Object equalityPolicy;
        private final Class<?> function0;
        private final Class<?> function1;
        private final Method startReplaceableGroup;
        private final Method endReplaceableGroup;
        private final Method rememberedValue;
        private final Method updateRememberedValue;
        private final Method mutableStateOf;
        private final Method stateGet;
        private final Method stateSet;
        /** 普通行：MoreSettingItemWithTextArrow(title, subtitle, 红点, onClick, composer, changed, defaults)。 */
        private final Method arrowItem;
        /** 开关行：SettingTipTextTextWithSwitch(title, tip, checked, onItemClick, composer, changed)。 */
        private final Method switchItem;

        ComposeApi(ClassLoader cl, Class<?> settingKt) throws Throwable {
            Class<?> composer = Class.forName("androidx.compose.runtime.Composer", false, cl);
            Class<?> state = Class.forName("androidx.compose.runtime.MutableState", false, cl);
            Class<?> snapshotState = Class.forName("androidx.compose.runtime.SnapshotStateKt", false, cl);
            Class<?> policy = Class.forName("androidx.compose.runtime.SnapshotMutationPolicy", false, cl);
            this.function0 = Class.forName("kotlin.jvm.functions.Function0", false, cl);
            this.function1 = Class.forName("kotlin.jvm.functions.Function1", false, cl);
            this.startReplaceableGroup = composer.getMethod("startReplaceableGroup", int.class);
            this.endReplaceableGroup = composer.getMethod("endReplaceableGroup");
            this.rememberedValue = composer.getMethod("rememberedValue");
            this.updateRememberedValue = composer.getMethod("updateRememberedValue", Object.class);
            this.mutableStateOf = snapshotState.getMethod("mutableStateOf", Object.class, policy);
            this.equalityPolicy = snapshotState.getMethod("structuralEqualityPolicy").invoke(null);
            this.stateGet = state.getMethod("getValue");
            this.stateSet = state.getMethod("setValue", Object.class);
            Object companion = composer.getField("Companion").get(null);
            this.composerEmpty = companion.getClass().getMethod("getEmpty").invoke(companion);
            this.unit = staticField(Class.forName("kotlin.Unit", false, cl), "INSTANCE");
            this.arrowItem = findSettingRow(settingKt, composer,
                    "MoreSettingItemWithTextArrow", String.class, String.class, boolean.class, function0);
            this.switchItem = findSettingRow(settingKt, composer,
                    "SettingTipTextTextWithSwitch", String.class, String.class, boolean.class, function1);
            if (arrowItem == null || switchItem == null) {
                throw new NoSuchMethodException("SettingComposeBaseKt helper rows not found");
            }
        }

        /** 在组合里 remember 一个 MutableState（开关变化的重组信号用它）。 */
        Object rememberState(Object composer, int key, Object initial) throws Throwable {
            startReplaceableGroup.invoke(composer, key);
            Object remembered = rememberedValue.invoke(composer);
            if (remembered == composerEmpty) {
                remembered = mutableStateOf.invoke(null, initial, equalityPolicy);
                updateRememberedValue.invoke(composer, remembered);
            }
            endReplaceableGroup.invoke(composer);
            return remembered;
        }

        int readInt(Object state) throws Throwable {
            Object value = stateGet.invoke(state);
            return value instanceof Number ? ((Number) value).intValue() : 0;
        }

        void arrowRow(Object composer, String title, String subtitle, Object onClick)
                throws Throwable {
            arrowItem.invoke(null, title, subtitle, Boolean.FALSE, onClick, composer, 0, 0);
        }

        void switchRow(Object composer, String title, String tip, boolean checked,
                       Object onToggle) throws Throwable {
            switchItem.invoke(null, title, tip, checked, onToggle, composer, 0);
        }

        /** 点入口行：用宿主导航开独立设置页。 */
        Object openPageClick() {
            return Proxy.newProxyInstance(function0.getClassLoader(), new Class<?>[]{function0},
                    (proxy, method, args) -> {
                        if ("invoke".equals(method.getName())) {
                            try {
                                openHelperPage();
                            } catch (Throwable t) {
                                logFailure("open helper page failed", t);
                            }
                            return unit;
                        }
                        return objectMethod(proxy, method, args);
                    });
        }

        /** 开关行回调：宿主把新值传进来，写进 prefs，再改 revision 让这一页重组。 */
        Object switchClick(final String key, final Object revisionState) {
            return Proxy.newProxyInstance(function1.getClassLoader(), new Class<?>[]{function1},
                    (proxy, method, args) -> {
                        if ("invoke".equals(method.getName())) {
                            try {
                                if (args != null && args.length > 0 && args[0] instanceof Boolean) {
                                    writeSetting(key, (Boolean) args[0]);
                                }
                                stateSet.invoke(revisionState, readInt(revisionState) + 1);
                            } catch (Throwable t) {
                                logFailure("toggle " + key + " failed", t);
                            }
                            return unit;
                        }
                        return objectMethod(proxy, method, args);
                    });
        }

        /** 「重启小米音乐」点击回调。 */
        Object restartClick() {
            return Proxy.newProxyInstance(function0.getClassLoader(), new Class<?>[]{function0},
                    (proxy, method, args) -> {
                        if ("invoke".equals(method.getName())) {
                            restartHost(sAppContext);
                            return unit;
                        }
                        return objectMethod(proxy, method, args);
                    });
        }
    }

    // ------------------------------------------------------------------
    // 「小米音乐小助手」独立页：借宿主的壳（holderFragment）装自己的 Compose 内容
    // ------------------------------------------------------------------

    /**
     * 宿主二级设置页（通用设置、关于……）都是 Navigation 里同一个 holderFragment 目的地，
     * 由 BaseHolderFragment 读 arguments 里的 fragment_label 决定壳里装哪一页。
     *
     * 这里复刻同一条路：
     *   1. 记下「更多」页实例，点入口时用它的 NavController 导航到 holderFragment，
     *      label 传 HELPER_PAGE_LABEL（宿主不认识的字符串，宿主自己的 FragmentLabel.from
     *      会把它兜底成 SETTING，所以必须靠下面这一步拦住）；
     *   2. hook BaseHolderFragment.onCreateView：label 是我们的时候不跑宿主实现，
     *      直接返回自己 new 的 ComposeView。返回键、进场动画、minibar 还是宿主那套，
     *      BaseHolderFragment 的返回回调照样 popBackStack。
     */
    private void hookHelperPage(ClassLoader cl) {
        Class<?> more = loadClass(cl, "com.tencent.qqmusiclite.ui.settings.MoreSettingsFragment");
        Method moreOnCreateView = more == null ? null : findMethodByName(more, "onCreateView");
        if (moreOnCreateView != null) {
            hook(moreOnCreateView).intercept(chain -> {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (self != null) {
                    sMoreSettingsFragment = new WeakReference<>(self);
                }
                return result;
            });
        }

        Class<?> holder = loadClass(cl, "com.tencent.qqmusiclite.fragment.BaseHolderFragment");
        Method onCreateView = holder == null ? null : findMethodByName(holder, "onCreateView");
        if (onCreateView == null) {
            log(android.util.Log.WARN, TAG, "BaseHolderFragment.onCreateView not found");
            return;
        }
        hook(onCreateView).intercept(chain -> {
            Object self = chain.getThisObject();
            Object inflater = chain.getArg(0);
            if (self != null && inflater instanceof android.view.LayoutInflater
                    && HELPER_PAGE_LABEL.equals(argumentLabel(self))) {
                try {
                    View page = helperPageApi(cl).createView(((android.view.LayoutInflater) inflater).getContext());
                    log(android.util.Log.INFO, TAG, "helper page shown");
                    return page;
                } catch (Throwable t) {
                    logFailure("build helper page failed", t);
                }
            }
            return chain.proceed();
        });
        log(android.util.Log.INFO, TAG, "hooked helper page host");
    }

    private HelperPageApi helperPageApi(ClassLoader cl) throws Throwable {
        HelperPageApi api = sHelperPageApi;
        if (api == null) {
            synchronized (Main.class) {
                if (sHelperPageApi == null) {
                    sHelperPageApi = new HelperPageApi(cl);
                }
                api = sHelperPageApi;
            }
        }
        return api;
    }

    /**
     * 点入口行：用宿主自己的导航开新页。
     * 优先 NavController.navigate(holderFragment, {label...})，和宿主点「通用设置」同一条路；
     * 拿不到 NavController 时退回宿主自己的 EmbeddingNavigationHelper.toSettingFragment。
     */
    private void openHelperPage() {
        Object fragment = sMoreSettingsFragment == null ? null : sMoreSettingsFragment.get();
        if (fragment == null) {
            log(android.util.Log.WARN, TAG, "helper page: MoreSettingsFragment not captured yet");
            return;
        }
        Bundle args = new Bundle();
        args.putString(KEY_FRAGMENT_LABEL, HELPER_PAGE_LABEL);
        args.putBoolean(KEY_FRAGMENT_BACK, false);
        args.putInt(KEY_ACTION_TYPE, 8);

        ClassLoader cl = fragment.getClass().getClassLoader();
        try {
            Class<?> fragmentKt = loadClass(cl, "androidx.navigation.fragment.FragmentKt");
            Class<?> fragmentCls = loadClass(cl, "androidx.fragment.app.Fragment");
            Method find = fragmentKt == null || fragmentCls == null ? null
                    : findMethod(fragmentKt, "findNavController", fragmentCls);
            Object nav = find == null ? null : find.invoke(null, fragment);
            Method navigate = nav == null ? null
                    : nav.getClass().getMethod("navigate", int.class, Bundle.class);
            int id = holderFragmentId(cl);
            if (navigate != null && id != 0) {
                navigate.invoke(nav, id, args);
                log(android.util.Log.INFO, TAG, "helper page: navigate via NavController");
                return;
            }
        } catch (Throwable t) {
            logFailure("helper page: NavController route failed", t);
        }
        try {
            Class<?> helper = loadClass(cl, "com.tencent.qqmusiclite.fragment.EmbeddingNavigationHelper");
            Object instance = staticField(helper, "INSTANCE");
            Object activity = fragment.getClass().getMethod("requireActivity").invoke(fragment);
            Method to = findMethod(helper, "toSettingFragment",
                    loadClass(cl, "androidx.fragment.app.FragmentActivity"), String.class, boolean.class);
            if (to == null) {
                to = findMethodByName(helper, "toSettingFragment"); // 参数类型对不上时的兜底
            }
            if (to != null) {
                to.invoke(instance, activity, HELPER_PAGE_LABEL, false);
                log(android.util.Log.INFO, TAG, "helper page: navigate via toSettingFragment");
                return;
            }
            log(android.util.Log.WARN, TAG, "helper page: no navigate route found");
        } catch (Throwable t) {
            logFailure("helper page: fallback route failed", t);
        }
    }

    /** holderFragment 的 id：优先查资源表，宿主 R 类被 R8 裁掉时也还能用。 */
    private static int holderFragmentId(ClassLoader cl) {
        int cached = sHolderFragmentId;
        if (cached != 0) {
            return cached;
        }
        Context context = sAppContext;
        if (context != null) {
            cached = context.getResources().getIdentifier("holderFragment", "id", PKG);
        }
        if (cached == 0) {
            Object value = staticField(loadClass(cl, "com.tencent.qqmusiclite.R$id"), "holderFragment");
            if (value instanceof Number) {
                cached = ((Number) value).intValue();
            }
        }
        if (cached != 0) {
            sHolderFragmentId = cached;
        }
        return cached;
    }

    private static String argumentLabel(Object fragment) {
        try {
            Object args = fragment.getClass().getMethod("getArguments").invoke(fragment);
            return args instanceof Bundle ? ((Bundle) args).getString(KEY_FRAGMENT_LABEL) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Activity unwrapActivity(Context context) {
        Context current = context;
        while (current != null) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            current = current instanceof android.content.ContextWrapper
                    ? ((android.content.ContextWrapper) current).getBaseContext() : null;
        }
        return null;
    }

    /** 给宿主的 @Composable lambda 用的小包装（Proxy 里跑正文）。 */
    private interface ComposeBody {
        void emit(Object[] args) throws Throwable;
    }

    /**
     * 独立设置页的整页 Compose 内容，反射搭出宿主 MoreSettingsFragment.onCreateView 那套包裹：
     *
     *   QQMusicTheme {
     *     CompositionLocalProvider(ActiveDimens provides Dimens(config)) {
     *       Column(fillMaxSize + 页面底色 + verticalScroll) {
     *         StatusBar(卡片色)
     *         TitleBar("小米音乐小助手", 返回)
     *         七个 SettingTipTextTextWithSwitch 开关行
     *         MoreSettingItemWithTextArrow("重启小米音乐")
     *       }
     *     }
     *   }
     *
     * 行函数和开关回调复用 ComposeApi（和「更多」页那行入口是同一套）。
     */
    private final class HelperPageApi {
        private final ClassLoader cl;
        private final Class<?> function0;
        private final Class<?> function2;
        private final Class<?> function3;
        private final Constructor<?> composeViewCtor;
        private final Constructor<?> dimensCtor;
        private final Method setContent;
        private final Method qqMusicTheme;
        private final Method compositionLocalProvider;
        private final Method getActiveDimens;
        private final Method provides;
        private final Method column;
        private final Method statusBar;
        private final Method titleBar;
        private final Method fillMaxWidth;
        private final Method fillMaxSize;
        private final Method heightDp;
        private final Method background;
        private final Method verticalScroll;
        private final Method rememberScrollState;
        private final Method materialThemeGetColors;
        private final Method getCardBgColor;
        private final Method getFloorColor;
        private final Method dp;
        private final Object modifierCompanion;
        private final Object arrangementTop;
        private final Object alignmentCenterHorizontally;
        private final Object materialTheme;
        private final Object unit;

        HelperPageApi(ClassLoader cl) throws Throwable {
            this.cl = cl;
            this.function0 = Class.forName("kotlin.jvm.functions.Function0", false, cl);
            this.function2 = Class.forName("kotlin.jvm.functions.Function2", false, cl);
            this.function3 = Class.forName("kotlin.jvm.functions.Function3", false, cl);
            Class<?> composer = Class.forName("androidx.compose.runtime.Composer", false, cl);
            Class<?> modifier = Class.forName("androidx.compose.ui.Modifier", false, cl);
            Class<?> shape = Class.forName("androidx.compose.ui.graphics.Shape", false, cl);

            Class<?> composeView = Class.forName("androidx.compose.ui.platform.ComposeView", false, cl);
            this.composeViewCtor = composeView.getConstructor(Context.class);
            this.setContent = composeView.getMethod("setContent", function2);

            Class<?> themeKt = Class.forName("com.tencent.qqmusiclite.ui.theme.ThemeKt", false, cl);
            this.qqMusicTheme = requireMethod(findMethod(themeKt, "QQMusicTheme",
                    boolean.class, function2, composer, int.class, int.class), "QQMusicTheme");

            Class<?> compositionLocalKt = Class.forName("androidx.compose.runtime.CompositionLocalKt", false, cl);
            this.compositionLocalProvider = requireMethod(findMethod(compositionLocalKt,
                    "CompositionLocalProvider",
                    Class.forName("androidx.compose.runtime.ProvidedValue", false, cl),
                    function2, composer, int.class), "CompositionLocalProvider");

            this.getActiveDimens = requireMethod(findMethod(
                    Class.forName("com.tencent.qqmusiclite.DimensKt", false, cl), "getActiveDimens"),
                    "getActiveDimens");
            this.provides = requireMethod(findMethod(
                    Class.forName("androidx.compose.runtime.ProvidableCompositionLocal", false, cl),
                    "provides", Object.class), "provides");
            this.dimensCtor = Class.forName("com.tencent.qqmusiclite.Dimens", false, cl)
                    .getConstructor(Class.forName("android.content.res.Configuration", false, cl));

            Class<?> columnKt = Class.forName("androidx.compose.foundation.layout.ColumnKt", false, cl);
            this.column = requireMethod(findMethod(columnKt, "Column", modifier,
                    Class.forName("androidx.compose.foundation.layout.Arrangement$Vertical", false, cl),
                    Class.forName("androidx.compose.ui.Alignment$Horizontal", false, cl),
                    function3, composer, int.class, int.class), "Column");

            this.statusBar = requireMethod(findMethod(
                    Class.forName("com.tencent.qqmusiclite.ui.UIExtensionKt", false, cl),
                    "StatusBar-Iv8Zu3U", long.class, composer, int.class, int.class), "StatusBar");
            this.titleBar = requireMethod(findMethod(
                    Class.forName("com.tencent.qqmusiclite.ui.widget.TitleBarKt", false, cl),
                    "TitleBar-JHQioms", modifier, function0, String.class,
                    long.class, composer, int.class, int.class), "TitleBar");

            Class<?> sizeKt = Class.forName("androidx.compose.foundation.layout.SizeKt", false, cl);
            this.fillMaxWidth = requireMethod(findMethod(sizeKt, "fillMaxWidth", modifier, float.class),
                    "fillMaxWidth");
            this.fillMaxSize = requireMethod(findMethod(sizeKt, "fillMaxSize", modifier, float.class),
                    "fillMaxSize");
            this.heightDp = requireMethod(findMethod(sizeKt, "height-3ABfNKs", modifier, float.class),
                    "height");
            this.dp = requireMethod(findMethod(Class.forName("androidx.compose.ui.unit.Dp", false, cl),
                    "constructor-impl", float.class), "Dp");

            this.background = requireMethod(findMethod(
                    Class.forName("androidx.compose.foundation.BackgroundKt", false, cl),
                    "background-bw27NRU$default", modifier, long.class, shape, int.class, Object.class),
                    "background");

            Class<?> scrollKt = Class.forName("androidx.compose.foundation.ScrollKt", false, cl);
            this.rememberScrollState = requireMethod(findMethod(scrollKt, "rememberScrollState",
                    int.class, composer, int.class, int.class), "rememberScrollState");
            this.verticalScroll = requireMethod(findMethod(scrollKt, "verticalScroll", modifier,
                    Class.forName("androidx.compose.foundation.ScrollState", false, cl), boolean.class,
                    Class.forName("androidx.compose.foundation.gestures.FlingBehavior", false, cl),
                    boolean.class), "verticalScroll");

            Class<?> materialThemeCls = Class.forName("androidx.compose.material.MaterialTheme", false, cl);
            this.materialTheme = requireValue(staticField(materialThemeCls, "INSTANCE"), "MaterialTheme");
            this.materialThemeGetColors = requireMethod(findMethod(materialThemeCls, "getColors",
                    composer, int.class), "MaterialTheme.getColors");
            Class<?> colors = Class.forName("androidx.compose.material.Colors", false, cl);
            Class<?> themeColorExtKt = Class.forName("com.tencent.qqmusiclite.ui.theme.ThemeColorExtKt", false, cl);
            this.getCardBgColor = requireMethod(findMethod(themeColorExtKt, "getCardBgColor",
                    colors, composer, int.class), "getCardBgColor");
            this.getFloorColor = requireMethod(findMethod(themeColorExtKt, "getFloorColor",
                    colors, composer, int.class), "getFloorColor");

            this.modifierCompanion = requireValue(staticField(modifier, "Companion"), "Modifier.Companion");
            Class<?> arrangement = Class.forName("androidx.compose.foundation.layout.Arrangement", false, cl);
            Object arrangementInstance = requireValue(staticField(arrangement, "INSTANCE"), "Arrangement");
            this.arrangementTop = arrangementInstance.getClass().getMethod("getTop").invoke(arrangementInstance);
            Class<?> alignment = Class.forName("androidx.compose.ui.Alignment", false, cl);
            Object alignmentCompanion = requireValue(staticField(alignment, "Companion"), "Alignment.Companion");
            this.alignmentCenterHorizontally = alignmentCompanion.getClass()
                    .getMethod("getCenterHorizontally").invoke(alignmentCompanion);
            this.unit = staticField(Class.forName("kotlin.Unit", false, cl), "INSTANCE");
        }

        private Method requireMethod(Method method, String name) throws NoSuchMethodException {
            if (method == null) {
                throw new NoSuchMethodException("host compose method not found: " + name);
            }
            return method;
        }

        private Object requireValue(Object value, String name) throws NoSuchMethodException {
            if (value == null) {
                throw new NoSuchMethodException("host compose field not found: " + name);
            }
            return value;
        }

        /** 页面 View：FrameLayout 套一个 ComposeView（宿主页面也是 ComposeView 当根）。 */
        View createView(Context context) throws Throwable {
            FrameLayout root = new FrameLayout(context);
            Object composeView = composeViewCtor.newInstance(context);
            setContent.invoke(composeView, lambda(function2, args -> composePage(context, args[0])));
            root.addView((View) composeView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            return root;
        }

        /** QQMusicTheme { CompositionLocalProvider(ActiveDimens) { ... } }，和宿主同一套包裹。 */
        private void composePage(final Context context, final Object composer) throws Throwable {
            qqMusicTheme.invoke(null, Boolean.FALSE, lambda(function2, args -> {
                Object c = args[0];
                Object dimens = dimensCtor.newInstance(context.getResources().getConfiguration());
                Object active = getActiveDimens.invoke(null);
                Object provided = provides.invoke(active, dimens);
                compositionLocalProvider.invoke(null, provided,
                        lambda(function2, inner -> composeColumn(context, inner[0])), c, 0);
            }), composer, 48, 1);
        }

        /** 整页一个 Column：填满 + 页面底色 + 整体可滚（宿主设置页的排布方式）。 */
        private void composeColumn(Context context, Object composer) throws Throwable {
            Object modifier = fillMaxSize.invoke(null, modifierCompanion, 1f);
            long floor = (Long) getFloorColor.invoke(null, themeColors(composer), composer, 0);
            modifier = background.invoke(null, modifier, floor, null, 2, null);
            Object scrollState = rememberScrollState.invoke(null, 0, composer, 0, 1);
            modifier = verticalScroll.invoke(null, modifier, scrollState, false, null, false);
            column.invoke(null, modifier, arrangementTop, alignmentCenterHorizontally,
                    lambda(function3, args -> emitItems(context, args[1])), composer, 0, 0);
        }

        /** 标题栏 + 七个开关行 + 重启行。 */
        private void emitItems(Context context, Object composer) throws Throwable {
            long card = (Long) getCardBgColor.invoke(null, themeColors(composer), composer, 0);
            statusBar.invoke(null, card, composer, 0, 0);
            Object titleModifier = heightDp.invoke(null,
                    fillMaxWidth.invoke(null, modifierCompanion, 1f), dp.invoke(null, 45f));
            titleBar.invoke(null, titleModifier, backClick(context), SETTINGS_CATEGORY_TITLE,
                    0L, composer, 0, 0);

            ComposeApi rows = sComposeApi;
            if (rows == null) {
                log(android.util.Log.WARN, TAG, "helper page: compose rows unavailable");
                return;
            }
            Object revision = rows.rememberState(composer, GROUP_REVISION, 0);
            rows.readInt(revision); // 订阅重组：开关写进 prefs 后靠它刷新
            for (String[] feature : HELPER_FEATURES) {
                rows.switchRow(composer, feature[1], feature[2], isEnabled(feature[0]),
                        rows.switchClick(feature[0], revision));
            }
            rows.arrowRow(composer, "重启小米音乐", "部分改动重启后完全生效", rows.restartClick());
        }

        private Object themeColors(Object composer) throws Throwable {
            return materialThemeGetColors.invoke(materialTheme, composer, 0);
        }

        /** 标题栏返回键：转交给宿主 Activity 的返回分发，BaseHolderFragment 会 popBackStack。 */
        private Object backClick(final Context context) {
            return Proxy.newProxyInstance(cl, new Class<?>[]{function0}, (proxy, method, args) -> {
                if ("invoke".equals(method.getName())) {
                    try {
                        Activity activity = unwrapActivity(context);
                        if (activity != null) {
                            activity.onBackPressed();
                        }
                    } catch (Throwable t) {
                        logFailure("helper page back failed", t);
                    }
                    return unit;
                }
                return objectMethod(proxy, method, args);
            });
        }

        private Object lambda(Class<?> type, final ComposeBody body) {
            return Proxy.newProxyInstance(cl, new Class<?>[]{type}, (proxy, method, args) -> {
                if ("invoke".equals(method.getName())) {
                    try {
                        body.emit(args);
                    } catch (Throwable t) {
                        logFailure("helper page render failed", t);
                    }
                    return unit;
                }
                return objectMethod(proxy, method, args);
            });
        }
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
                if (!isEnabled(KEY_PLAYER_ANIM)) {
                    return chain.proceed();
                }
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
                if (!isEnabled(KEY_PLAYER_ANIM)) {
                    return chain.proceed();
                }
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
            if (!isEnabled(KEY_HEADPHONE_POPUP)) {
                return chain.proceed();
            }
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
            if (!isEnabled(KEY_VIP_TIPS)) {
                return chain.proceed();
            }
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
                if (!isEnabled(KEY_SPLASH)) {
                    return chain.proceed();
                }
                log(android.util.Log.INFO, TAG, "blocked AmsGlobal.isNeedSplashAd");
                return value;
            });
        } else {
            log(android.util.Log.WARN, TAG, "AmsGlobal.isNeedSplashAd not hooked");
        }

        Method checkHotSplash = findMethod(amsGlobal, "checkHotSplash", Activity.class);
        if (checkHotSplash != null) {
            hook(checkHotSplash).intercept(chain -> {
                if (!isEnabled(KEY_SPLASH)) {
                    return chain.proceed();
                }
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
        hookReturnBooleanFalse(KEY_SPLASH, helper, "coldStartCheck", Activity.class);
        hookReturnBooleanFalse(KEY_SPLASH, helper, "hotStartCheck", Context.class);
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
        hookReturnBooleanFalse(KEY_SPLASH, helper, "trySplash", Activity.class, boolean.class);

        Method tryAdSplash = findMethod(helper, "tryAdSplash", Activity.class);
        if (tryAdSplash != null) {
            hook(tryAdSplash).intercept(chain -> {
                if (!isEnabled(KEY_SPLASH)) {
                    return chain.proceed();
                }
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
                if (!isEnabled(KEY_SPLASH)) {
                    return chain.proceed();
                }
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
            if (!isEnabled(KEY_HOME_REFRESH)) {
                return chain.proceed();
            }
            Object result = chain.proceed();
            Object self = chain.getThisObject();
            if (home.isInstance(self)) {
                sHomeVisibleAtMs = SystemClock.uptimeMillis();
                sHomeFragmentRef = new WeakReference<>(self);
            }
            return result;
        });

        hook(setRefreshing).intercept(chain -> {
            if (!isEnabled(KEY_HOME_REFRESH)) {
                return chain.proceed();
            }
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

    /** 免费听 tab 只在第一次真正被去掉时记一条日志，避免每次读 tab 列表都刷屏。 */
    private static volatile boolean sQuickPlayTabDropped = false;

    /**
     * 首页「免费听」入口。
     *
     * 顶部 tab 列表只有两个来源：优先用 Ctry 从 SharedPreferences（"top_tab_display"）
     * 反序列化出来的 List<TopTab>，为空再退到 ManufacturerSpecImpl.getDefaultTopTabs()
     * （默认列表里只有 1/2/3，本来就没有免费听）。id 4 = QuickPlayFragment = 免费听，
     * BaseHomeFragment.updateTabs 拼顶部 tab 用的是它，MainActivityViewModel
     * .updateBottomTabVisibility 判断底部入口用的也是它，所以直接在这个数据出口把 id 4
     * 过滤掉最干净：简洁模式顶部只剩「推荐 / 听书」，非简洁模式底部入口也一起消失，
     * 推荐 / 听书 / K歌 这些 tab 和它们的下标、默认选中都不受影响。
     *
     * 4.46.6.1 里这个类连类名、方法名都被混淆成了 Java 关键字（类 try、方法 abstract），
     * 没法按名字写出来，只能按「无参 + 返回 List」的特征找。返回的是过滤后的副本，
     * 不动 App 自己的数据（列表每次都是 Gson 新解析的，也不会影响 SharedPreferences）。
     */
    private void hookHomeQuickPlayTab(ClassLoader cl) {
        Class<?> ctry = loadClass(cl, "com.tencent.qqmusicplayerprocess.servicenew.try");
        Method getTopTabs = findTopTabsGetter(ctry);
        if (getTopTabs == null) {
            log(android.util.Log.WARN, TAG, "Ctry top tabs getter not found");
            return;
        }
        hook(getTopTabs).intercept(chain -> {
            if (!isEnabled(KEY_QUICK_PLAY_TAB)) {
                return chain.proceed();
            }
            Object tabs = chain.proceed();
            Object filtered = withoutQuickPlay(tabs);
            if (filtered != tabs && !sQuickPlayTabDropped) {
                sQuickPlayTabDropped = true;
                log(android.util.Log.INFO, TAG, "removed quick play tab from home top tabs");
            }
            return filtered;
        });
        log(android.util.Log.INFO, TAG, "hooked top tabs to remove quick play tab");
    }

    /**
     * Ctry 里的顶部 tab 数据源：无参、返回 List 的那个方法（4.46.6.1 上名字是 abstract，
     * 属于类 try，源码里写不出来，只能扫签名）。该类里满足这个特征的只有这一个方法。
     */
    private static Method findTopTabsGetter(Class<?> ctry) {
        if (ctry == null) {
            return null;
        }
        for (Method method : ctry.getDeclaredMethods()) {
            if (method.getParameterCount() == 0
                    && !Modifier.isStatic(method.getModifiers())
                    && List.class.isAssignableFrom(method.getReturnType())) {
                return method;
            }
        }
        return null;
    }

    /** 去掉 id == 4（免费听）的 TopTab；没有可去掉的就原样返回，方便调用方比较。 */
    private static Object withoutQuickPlay(Object tabs) {
        if (!(tabs instanceof List)) {
            return tabs;
        }
        List<?> list = (List<?>) tabs;
        boolean hasQuickPlay = false;
        for (Object tab : list) {
            if (isQuickPlayTab(tab)) {
                hasQuickPlay = true;
                break;
            }
        }
        if (!hasQuickPlay) {
            return tabs;
        }
        List<Object> filtered = new ArrayList<>(list.size());
        for (Object tab : list) {
            if (!isQuickPlayTab(tab)) {
                filtered.add(tab);
            }
        }
        return filtered;
    }

    /** TopTab.getId() == 4 即免费听（App 自己判断 QuickPlay 用的也是 4）。 */
    private static boolean isQuickPlayTab(Object topTab) {
        try {
            Object id = topTab.getClass().getMethod("getId").invoke(topTab);
            return id instanceof Number && ((Number) id).intValue() == 4;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 播放条上方三条会员推广气泡的场景名（BubbleState 的 scene 字符串）。
     * 括号里是 4.46.6.1 内置付费配置的默认文案，实际文案以服务端下发为准。
     */
    private static final String[] MINIBAR_VIP_SCENES = {
            "vip_user",             // 66 升级超级会员，任意用音质全家桶特权（本机实测撞的就是这条）
            "SCENE_NON_VIP_SONG",   // 67 订阅会员，上亿有声内容任你听
            "long_audio",           // 95 成为超级会员，10万本好书任意听
    };
    /** 只记第一条日志，避免每次刷新气泡状态都刷屏。 */
    private static volatile boolean sMinibarVipBubbleBlocked = false;

    /**
     * 播放条上方那条会员推广气泡（首次播放时弹出来的「成为超级会员，10万本好书任意听」）。
     *
     * 4.46.6.1：三条付费引导场景共用同一个 MinibarBubble（播放条上方带箭头的横条）：
     * vip_user（绿钻用户，本机实测撞的是这条）、SCENE_NON_VIP_SONG（非会员）、long_audio
     * （首次播放长音频）。文案、图标、底色都来自 payTip 配置（服务端可改），所以只能按
     * 场景名判断。气泡的展示链路上两道都拦：
     *   - MinibarBubbleManager.tryShowBubble(SongInfo, String, int, String)：请求层，
     *     场景名在第 4 个参数，拦下后 App 不会进入这个气泡状态；
     *   - MiniBarController.setMinibarBubbleVisibility(Boolean, BubbleState)：唯一的显示
     *     出口（只有 show=true 才会画出来），兜底。
     * 只拦这三条场景的展示；免费听 / 登录 / 试听 / 音质音效气泡以及所有隐藏调用原样透传，
     * 被拦下的气泡也不占高度。
     */
    private void hookMinibarVipBubble(ClassLoader cl) {
        Class<?> manager = loadClass(cl,
                "com.tencent.qqmusiclite.ui.minibar.MinibarBubbleManager");
        Method tryShowBubble = findTryShowBubble(manager);
        if (tryShowBubble == null) {
            log(android.util.Log.WARN, TAG, "MinibarBubbleManager.tryShowBubble not found");
        } else {
            hook(tryShowBubble).intercept(chain -> {
                if (!isEnabled(KEY_MINIBAR_BUBBLE)) {
                    return chain.proceed();
                }
                Object scene = chain.getArg(3);
                if (isMinibarVipScene(scene)) {
                    logBlockedMinibarVipBubble("request", (String) scene);
                    return null;
                }
                return chain.proceed();
            });
            log(android.util.Log.INFO, TAG, "hooked MinibarBubbleManager.tryShowBubble");
        }

        Class<?> controller = loadClass(cl,
                "com.tencent.qqmusiclite.ui.minibar.MiniBarController");
        Class<?> bubbleState = loadClass(cl,
                "com.tencent.qqmusiclite.ui.minibar.BubbleState");
        Method setVisible = controller == null || bubbleState == null ? null
                : findMethod(controller, "setMinibarBubbleVisibility", Boolean.class, bubbleState);
        if (setVisible == null) {
            log(android.util.Log.WARN, TAG,
                    "MiniBarController.setMinibarBubbleVisibility not found");
            return;
        }
        hook(setVisible).intercept(chain -> {
            if (!isEnabled(KEY_MINIBAR_BUBBLE)) {
                return chain.proceed();
            }
            String scene = sceneOf(chain.getArg(1));
            if (Boolean.TRUE.equals(chain.getArg(0)) && isMinibarVipScene(scene)) {
                logBlockedMinibarVipBubble("display", scene);
                return null;
            }
            return chain.proceed();
        });
        log(android.util.Log.INFO, TAG,
                "hooked MiniBarController.setMinibarBubbleVisibility");
    }

    private static boolean isMinibarVipScene(Object scene) {
        if (!(scene instanceof String)) {
            return false;
        }
        for (String vipScene : MINIBAR_VIP_SCENES) {
            if (vipScene.equals(scene)) {
                return true;
            }
        }
        return false;
    }

    /** BubbleState.getScene()；取不到就返回 null（宁可不拦）。 */
    private static String sceneOf(Object bubbleState) {
        if (bubbleState == null) {
            return null;
        }
        try {
            Object scene = bubbleState.getClass().getMethod("getScene").invoke(bubbleState);
            return scene instanceof String ? (String) scene : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void logBlockedMinibarVipBubble(String from, String scene) {
        if (!sMinibarVipBubbleBlocked) {
            sMinibarVipBubbleBlocked = true;
            log(android.util.Log.INFO, TAG, "blocked minibar vip bubble (" + from + "): " + scene);
        }
    }

    /** tryShowBubble(SongInfo, String, int, String)：场景名在第 4 个参数。 */
    private static Method findTryShowBubble(Class<?> manager) {
        if (manager == null) {
            return null;
        }
        for (Method method : manager.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if ("tryShowBubble".equals(method.getName())
                    && params.length == 4
                    && params[1] == String.class
                    && params[2] == int.class
                    && params[3] == String.class) {
                return method;
            }
        }
        return null;
    }

    private void hookReturnBooleanFalse(String key, Class<?> cls, String name, Class<?>... params) {
        Method method = findMethod(cls, name, params);
        if (method == null) {
            log(android.util.Log.WARN, TAG, cls.getSimpleName() + "." + name + " not found");
            return;
        }
        hook(method).intercept(chain -> {
            if (!isEnabled(key)) {
                return chain.proceed();
            }
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
