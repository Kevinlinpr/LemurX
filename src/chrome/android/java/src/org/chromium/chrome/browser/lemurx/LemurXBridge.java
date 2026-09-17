// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.text.TextUtils;

import org.chromium.base.ApplicationStatus;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.base.version_info.VersionInfo;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.browser.back_press.BackPressManager;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabHidingType;
import org.chromium.chrome.browser.tab.TabLaunchType;
import org.chromium.chrome.browser.tab.TabSelectionType;
import org.chromium.chrome.browser.tabmodel.TabClosureParams;
import org.chromium.chrome.browser.tabmodel.TabList;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.tabmodel.TabModelSelectorTabObserver;
import org.chromium.content_public.browser.ChildProcessImportance;
import org.chromium.content_public.browser.JavaScriptCallback;
import org.chromium.content_public.browser.RenderFrameHost;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.widget.Toast;
import org.chromium.url.GURL;
import org.jni_zero.CalledByNative;
import org.jni_zero.NativeMethods;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LemurX底层接口的 Lua 桥。Lua 脚本通过全局表 {@code lemurx} 调用这些方法。
 */
public class LemurXBridge {
    private static final String TAG = "LemurX";
    private static final String PREF_NAME = "lemurx_storage";
    private static final String USER_SCRIPT_DIR = "lua";
    private static final String INIT_ASSET = "lua/init.lua";
    private static final String TUTORIAL_ASSET = "lua/tutorial.lua";
    private static final String TUTORIAL_FILE = "00_tutorial.lua";
    // luakit 兼容运行时：apk assets/luakit/** 首次（或版本变化）解包到 filesDir/luakit/，
    // 这就是 luakit.install_paths.install_dir；config/ 目录只在缺失时种子化，属于用户。
    private static final String LUAKIT_ASSET_DIR = "luakit";
    private static final String LUAKIT_INSTALL_DIR = "luakit";
    private static final String LUAKIT_KERNEL_ASSET = "luakit/kernel/init.lua";
    private static final String LUAKIT_STAMP_FILE = ".installed_version";
    // Lua 注入用的隔离世界。RenderFrameHostImpl::ExecuteJavaScriptInIsolatedWorld 是
    // CHECK_GT(world_id, ISOLATED_WORLD_ID_GLOBAL) && CHECK_LE(world_id, ISOLATED_WORLD_ID_MAX)，
    // 其中 ISOLATED_WORLD_ID_MAX = ISOLATED_WORLD_ID_CONTENT_END + 10 = 11；越界直接 SIGTRAP
    // 打崩浏览器进程（真机复现：打开标签后教程脚本 inject → 崩）。之前的 100 就是这个死因。
    // Chrome 自己用到 5（TRANSLATE/INDIGO/CHROME_INTERNAL/EXTENSIONS 起点），LemurX 关掉了
    // 扩展系统，取 10 避开它们并留在合法范围内。
    private static final int LUA_ISOLATED_WORLD_ID = 10;
    private static final String DB_NAME = "lemurx.db";
    private static final Set<String> ALLOWED_INTENT_ACTIONS =
            new HashSet<>(
                    Arrays.asList(
                            Intent.ACTION_VIEW,
                            Intent.ACTION_SEND,
                            Intent.ACTION_WEB_SEARCH,
                            Intent.ACTION_MAIN,
                            Intent.ACTION_SENDTO));
    private static final Set<String> ALLOWED_URI_SCHEMES =
            new HashSet<>(
                    Arrays.asList("http", "https", "content", "market", "lemurx", "mailto", "geo"));
    private static boolean sStarted;
    private static SQLiteDatabase sDb;
    /** 页面生命周期事件（started / loaded / document）→ Lua；随窗口重建。 */
    private static TabModelSelectorTabObserver sPageObserver;
    private static ChromeTabbedActivity sPageObserverActivity;

    public static void start() {
        if (sStarted) {
            return;
        }
        sStarted = true;
        LemurXBridgeJni.get().start();
        evalAsset(INIT_ASSET);
        // luakit 兼容内核紧跟 init.lua：先把 lib/ 解包到 install_dir，再跑内核，
        // 内核负责注入 luakit/widget/soup/... 全局并（按开关）执行 config/rc.lua。
        seedLuakit();
        evalAsset(LUAKIT_KERNEL_ASSET);
        evalUserScripts();
        ThreadUtils.postOnUiThread(() -> LemurXChromeHost.attach(getActivity()));
        ThreadUtils.postOnUiThreadDelayed(
                () -> {
                    if ("0".equals(prefs().getString("lua_tutorial_hint", "1"))) {
                        return;
                    }
                    showToast("Lua教程入口：打开右上角三点菜单，底部有「Lua 教程」");
                },
                1800);
    }

    /**
     * ChromeTabbedActivity.finishNativeInitialization() 调用：
     * 挂接各宿主、注册页面事件观察者和返回键拦截器（全部在 UI 线程）。
     */
    public static void attachChrome(ChromeTabbedActivity activity) {
        ThreadUtils.postOnUiThread(
                () -> {
                    LemurXChromeHost.attach(activity);
                    LemurXUiHost.reattach(activity);
                    LemurXSkinHost.reattach(activity);
                    LemurXWidgetHost.reattach(activity);
                    ensurePageObserver(activity);
                    // 返回键：BackPressManager 每次先问 Lua（未开启拦截时 consumeChromeBack 立即返回 false）
                    BackPressManager.setLemurXInterceptor(LemurXBridge::consumeChromeBack);
                    if (sStarted) {
                        eval(
                                "if lemurx and lemurx.tutorial and lemurx.tutorial.redraw then lemurx.tutorial.redraw() end",
                                "chrome_ready");
                    }
                });
    }

    /** 用 TabModelSelectorTabObserver 覆盖窗口里所有 Tab，发 started / loaded / document 三个事件。 */
    private static void ensurePageObserver(@Nullable ChromeTabbedActivity activity) {
        ThreadUtils.assertOnUiThread();
        if (activity == null || activity.isDestroyed() || activity.isFinishing()) {
            return;
        }
        if (sPageObserver != null && sPageObserverActivity == activity) {
            return;
        }
        if (sPageObserver != null) {
            sPageObserver.destroy();
            sPageObserver = null;
            sPageObserverActivity = null;
        }
        TabModelSelector selector = selectorOf(activity);
        if (selector == null) {
            // Tab 模型尚未就绪：等 supplier 有值再挂
            activity.getTabModelSelectorSupplier()
                    .addSyncObserverAndCallIfNonNull(
                            ready -> {
                                if (sPageObserver == null
                                        && !activity.isDestroyed()
                                        && !activity.isFinishing()) {
                                    ensurePageObserver(activity);
                                }
                            });
            return;
        }
        sPageObserverActivity = activity;
        sPageObserver =
                new TabModelSelectorTabObserver(selector) {
                    @Override
                    public void onPageLoadStarted(Tab tab, GURL url) {
                        notifyTabEvent("started", tab, url);
                    }

                    @Override
                    public void onPageLoadFinished(Tab tab, GURL url) {
                        notifyTabEvent("loaded", tab, url);
                    }

                    @Override
                    public void didFirstVisuallyNonEmptyPaint(Tab tab) {
                        notifyTabEvent("document", tab, tab.getUrl());
                    }
                };
    }

    public static void openTutorial() {
        if (!sStarted) {
            start();
        }
        prefs().edit().putString("lua_tutorial_panel", "1").putString("lua_tutorial_hint", "0").apply();
        eval(
                "if lemurx and lemurx.tutorial and lemurx.tutorial.open then lemurx.tutorial.open() else print('tutorial missing') end",
                "open_tutorial");
        ThreadUtils.postOnUiThread(
                () -> {
                    LemurXUiHost.reattach(getActivity());
                    showToast("教程已打开，看屏幕右下角绿色按钮");
                });
    }

    /** BackPressManager 的 LemurX 拦截器；Lua 未开启返回拦截时无副作用、立即返回 false。 */
    public static boolean consumeChromeBack() {
        return LemurXChromeHost.consumeBack();
    }

    /** UI 线程弹 Toast（可从任意线程调用）。 */
    static void showToast(String message) {
        if (TextUtils.isEmpty(message)) {
            return;
        }
        ThreadUtils.runOnUiThread(
                () -> {
                    try {
                        Toast.makeText(
                                        ContextUtils.getApplicationContext(),
                                        message,
                                        Toast.LENGTH_SHORT)
                                .show();
                    } catch (Exception e) {
                        logi("toast", e.getMessage());
                    }
                });
    }

    /** 日志统一走 org.chromium.base.Log，TAG=LemurX；各段用空格拼接。 */
    static void logi(Object... parts) {
        StringBuilder sb = new StringBuilder();
        if (parts != null) {
            for (Object part : parts) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(part);
            }
        }
        Log.i(TAG, "%s", sb.toString());
    }

    static String versionName() {
        try {
            return VersionInfo.getProductVersion();
        } catch (Exception e) {
            return "";
        }
    }

    static long versionCode() {
        try {
            Context ctx = ContextUtils.getApplicationContext();
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return info.getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 同步跑在 UI 线程；异常吞掉返回 fallback（154 没有 runOnUiThreadBlockingNoException 了）。 */
    private static <T> T runUi(Callable<T> task, T fallback) {
        try {
            T value = ThreadUtils.runOnUiThreadBlocking(task);
            return value == null ? fallback : value;
        } catch (Exception e) {
            logi("ui task", e.getMessage());
            return fallback;
        }
    }

    private static @Nullable TabModelSelector selectorOf(@Nullable ChromeTabbedActivity activity) {
        if (activity == null || !activity.areTabModelsInitialized()) {
            return null;
        }
        try {
            return activity.getTabModelSelector();
        } catch (Exception e) {
            return null;
        }
    }

    public static void eval(String chunk, String name) {
        if (TextUtils.isEmpty(chunk)) {
            return;
        }
        if (!sStarted) {
            start();
        }
        LemurXBridgeJni.get().eval(chunk, name == null ? "" : name, true);
    }

    public static void eval(String chunk, String name, boolean privileged) {
        if (TextUtils.isEmpty(chunk)) {
            return;
        }
        if (!sStarted) {
            start();
        }
        LemurXBridgeJni.get().eval(chunk, name == null ? "" : name, privileged);
    }

    private static void evalAsset(String assetName) {
        try (InputStream in =
                ContextUtils.getApplicationContext().getAssets().open(assetName)) {
            eval(readStream(in), assetName);
        } catch (Exception e) {
            logi("skip asset", assetName, e.getMessage());
        }
    }

    private static void evalUserScripts() {
        File dir = new File(ContextUtils.getApplicationContext().getFilesDir(), USER_SCRIPT_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            logi("cannot create lua dir", dir.getAbsolutePath());
            return;
        }
        seedTutorialScript(dir);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".lua"));
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                try (FileInputStream in = new FileInputStream(file)) {
                    eval(readStream(in), file.getName(), true);
                } catch (Exception e) {
                    logi("load failed", file.getName(), e.getMessage());
                }
            }
        }
        File ugcDir = new File(dir, "ugc");
        if (!ugcDir.exists() && !ugcDir.mkdirs()) {
            logi("cannot create lua/ugc dir", ugcDir.getAbsolutePath());
            return;
        }
        File[] ugcFiles = ugcDir.listFiles((d, name) -> name.endsWith(".lua"));
        if (ugcFiles == null || ugcFiles.length == 0) {
            return;
        }
        Arrays.sort(ugcFiles);
        for (File file : ugcFiles) {
            try (FileInputStream in = new FileInputStream(file)) {
                eval(readStream(in), "ugc/" + file.getName(), false);
            } catch (Exception e) {
                logi("ugc load failed", file.getName(), e.getMessage());
            }
        }
    }

    private static void seedTutorialScript(File dir) {
        File out = new File(dir, TUTORIAL_FILE);
        boolean stale = true;
        if (out.exists()) {
            try (FileInputStream in = new FileInputStream(out)) {
                stale = !readStream(in).contains("Lua 魔改教程 v1.12.1");
            } catch (Exception e) {
                stale = true;
            }
        }
        if (!stale) {
            return;
        }
        try (InputStream in =
                        ContextUtils.getApplicationContext().getAssets().open(TUTORIAL_ASSET);
                FileOutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                os.write(buf, 0, n);
            }
            logi("seeded tutorial", out.getAbsolutePath());
        } catch (Exception e) {
            logi("seed tutorial failed", e.getMessage());
        }
    }

    private static File luakitInstallDir() {
        return new File(ContextUtils.getApplicationContext().getFilesDir(), LUAKIT_INSTALL_DIR);
    }

    private static String luakitStamp() {
        // 同版本号的开发包也要重新解包：附带 apk 安装/更新时间
        long updated = 0;
        try {
            Context ctx = ContextUtils.getApplicationContext();
            updated = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
        } catch (Exception ignored) {
        }
        return versionName() + "/" + versionCode() + "/" + updated;
    }

    /** 把 assets/luakit/** 解包到 filesDir/luakit/。lib/kernel/resources 随版本覆盖，config 只补缺。 */
    private static void seedLuakit() {
        File root = luakitInstallDir();
        File stampFile = new File(root, LUAKIT_STAMP_FILE);
        String stamp = luakitStamp();
        boolean fresh = true;
        if (stampFile.exists()) {
            try (FileInputStream in = new FileInputStream(stampFile)) {
                fresh = !stamp.equals(readStream(in).trim());
            } catch (Exception e) {
                fresh = true;
            }
        }
        if (!fresh) {
            return;
        }
        try {
            copyAssetTree(LUAKIT_ASSET_DIR, root);
            new File(root, "data").mkdirs();
            new File(root, "cache").mkdirs();
            try (FileOutputStream os = new FileOutputStream(stampFile)) {
                os.write(stamp.getBytes(StandardCharsets.UTF_8));
            }
            logi("luakit seeded", root.getAbsolutePath(), stamp);
        } catch (Exception e) {
            logi("luakit seed failed", e.getMessage());
        }
    }

    private static void copyAssetTree(String assetPath, File dest) throws Exception {
        android.content.res.AssetManager am =
                ContextUtils.getApplicationContext().getAssets();
        String[] children = am.list(assetPath);
        if (children == null || children.length == 0) {
            // 叶子：文件
            boolean userOwned = assetPath.startsWith(LUAKIT_ASSET_DIR + "/config/");
            if (userOwned && dest.exists()) {
                return;
            }
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (InputStream in = am.open(assetPath);
                    FileOutputStream os = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    os.write(buf, 0, n);
                }
            }
            return;
        }
        if (!dest.exists()) {
            dest.mkdirs();
        }
        for (String child : children) {
            copyAssetTree(assetPath + "/" + child, new File(dest, child));
        }
    }

    /** luakit 内核需要的环境：目录、版本、包名、外部公共目录（xdg.*）。 */
    @CalledByNative
    public static String luakitEnv() {
        try {
            Context ctx = ContextUtils.getApplicationContext();
            File root = luakitInstallDir();
            JSONObject o = new JSONObject();
            o.put("install_dir", root.getAbsolutePath());
            o.put("config_dir", new File(root, "config").getAbsolutePath());
            o.put("data_dir", new File(root, "data").getAbsolutePath());
            o.put("cache_dir", new File(ctx.getCacheDir(), LUAKIT_INSTALL_DIR).getAbsolutePath());
            o.put("files_dir", ctx.getFilesDir().getAbsolutePath());
            o.put("lua_dir", new File(ctx.getFilesDir(), USER_SCRIPT_DIR).getAbsolutePath());
            o.put("package", ctx.getPackageName());
            o.put("version_name", versionName());
            o.put("version_code", versionCode());
            o.put("chromium_version", VersionInfo.getProductVersion());
            o.put("locale", Locale.getDefault().toString());
            o.put("verbose", Log.isLoggable(TAG, android.util.Log.VERBOSE));
            JSONObject xdg = new JSONObject();
            xdg.put("desktop", pubDir(android.os.Environment.DIRECTORY_DOCUMENTS));
            xdg.put("documents", pubDir(android.os.Environment.DIRECTORY_DOCUMENTS));
            xdg.put("download", pubDir(android.os.Environment.DIRECTORY_DOWNLOADS));
            xdg.put("music", pubDir(android.os.Environment.DIRECTORY_MUSIC));
            xdg.put("pictures", pubDir(android.os.Environment.DIRECTORY_PICTURES));
            xdg.put("videos", pubDir(android.os.Environment.DIRECTORY_MOVIES));
            xdg.put("public_share", pubDir(android.os.Environment.DIRECTORY_DOWNLOADS));
            xdg.put("templates", new File(root, "templates").getAbsolutePath());
            File ext = ctx.getExternalFilesDir(null);
            xdg.put("external", ext == null ? "" : ext.getAbsolutePath());
            o.put("xdg", xdg);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String pubDir(String type) {
        try {
            File f = android.os.Environment.getExternalStoragePublicDirectory(type);
            return f == null ? "" : f.getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }

    private static String readStream(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    @CalledByNative
    public static void log(String message) {
        Log.i(TAG, "%s", message == null ? "" : message);
    }

    @CalledByNative
    public static void toast(String message) {
        showToast(message);
    }

    /** {isLemurX, versionName, versionCode, chromiumVersion} */
    @CalledByNative
    public static String browserInfo() {
        try {
            JSONObject o = new JSONObject();
            o.put("isLemurX", true);
            o.put("versionName", versionName());
            o.put("versionCode", versionCode());
            o.put("chromiumVersion", VersionInfo.getProductVersion());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @CalledByNative
    public static String listTabs() {
        return runUi(
                () -> {
                    JSONArray array = new JSONArray();
                    TabModelSelector selector = selectorOf(getActivity());
                    if (selector == null) {
                        return array.toString();
                    }
                    appendTabs(array, selector.getModel(false));
                    appendTabs(array, selector.getModel(true));
                    return array.toString();
                },
                "[]");
    }

    @CalledByNative
    public static String currentTab() {
        return runUi(
                () -> {
                    TabModelSelector selector = selectorOf(getActivity());
                    if (selector == null) {
                        return "";
                    }
                    Tab tab = selector.getCurrentTab();
                    return tab == null ? "" : tabToJson(tab).toString();
                },
                "");
    }

    @CalledByNative
    public static int openTab(String url, String optionsJson) {
        return runUi(
                () -> {
                    ChromeTabbedActivity activity = getActivity();
                    if (activity == null
                            || !activity.areTabModelsInitialized()
                            || TextUtils.isEmpty(url)) {
                        return Tab.INVALID_TAB_ID;
                    }
                    boolean background = false;
                    boolean incognito = false;
                    boolean hidden = false;
                    try {
                        if (!TextUtils.isEmpty(optionsJson)) {
                            JSONObject options = new JSONObject(optionsJson);
                            background = options.optBoolean("background", false);
                            incognito = options.optBoolean("incognito", false);
                            hidden = options.optBoolean("hidden", false);
                        }
                    } catch (Exception e) {
                        logi("openTab options", e.getMessage());
                    }
                    @TabLaunchType
                    int launchType =
                            (background || hidden)
                                    ? TabLaunchType.FROM_LONGPRESS_BACKGROUND
                                    : TabLaunchType.FROM_CHROME_UI;
                    Tab tab = activity.getTabCreator(incognito).launchUrl(url, launchType);
                    if (tab != null && hidden) {
                        tab.hide(TabHidingType.CHANGED_TABS);
                        setImportance(tab, ChildProcessImportance.NORMAL);
                    }
                    return tab == null ? Tab.INVALID_TAB_ID : tab.getId();
                },
                Tab.INVALID_TAB_ID);
    }

    @CalledByNative
    public static boolean closeTab(int tabId) {
        return runUi(
                () -> {
                    ChromeTabbedActivity activity = getActivity();
                    Tab tab = findTab(activity, tabId);
                    TabModelSelector selector = selectorOf(activity);
                    if (selector == null || tab == null) {
                        return false;
                    }
                    // 154：TabModel.closeTab(Tab) 已移除，统一走 TabRemover + TabClosureParams
                    selector.getModel(tab.isIncognito())
                            .getTabRemover()
                            .closeTabs(
                                    TabClosureParams.closeTab(tab).allowUndo(false).build(),
                                    /* allowDialog= */ false);
                    return true;
                },
                false);
    }

    @CalledByNative
    public static boolean selectTab(int tabId) {
        return runUi(
                () -> {
                    ChromeTabbedActivity activity = getActivity();
                    Tab tab = findTab(activity, tabId);
                    TabModelSelector selector = selectorOf(activity);
                    if (selector == null || tab == null) {
                        return false;
                    }
                    TabModel model = selector.getModel(tab.isIncognito());
                    int index = model.indexOf(tab);
                    if (index == TabList.INVALID_TAB_INDEX) {
                        return false;
                    }
                    model.setIndex(index, TabSelectionType.FROM_USER);
                    return true;
                },
                false);
    }

    @CalledByNative
    public static boolean navigateTab(int tabId, String url) {
        return runUi(
                () -> {
                    Tab tab = findTab(getActivity(), tabId);
                    if (tab == null || TextUtils.isEmpty(url)) {
                        return false;
                    }
                    tab.loadUrl(LemurXMoatHost.buildLoadUrl(tabId, url));
                    return true;
                },
                false);
    }

    @CalledByNative
    public static boolean reloadTab(int tabId) {
        return runUi(
                () -> {
                    Tab tab = findTab(getActivity(), tabId);
                    if (tab == null) {
                        return false;
                    }
                    tab.reload();
                    return true;
                },
                false);
    }

    @CalledByNative
    public static boolean goBack(int tabId) {
        return runUi(
                () -> {
                    Tab tab = findTab(getActivity(), tabId);
                    if (tab == null || !tab.canGoBack()) {
                        return false;
                    }
                    tab.goBack();
                    return true;
                },
                false);
    }

    @CalledByNative
    public static boolean goForward(int tabId) {
        return runUi(
                () -> {
                    Tab tab = findTab(getActivity(), tabId);
                    if (tab == null || !tab.canGoForward()) {
                        return false;
                    }
                    tab.goForward();
                    return true;
                },
                false);
    }

    /** 154：WebContents.setImportance 改为 setPrimaryPageImportance(主框架, 子框架)。 */
    private static void setImportance(Tab tab, @ChildProcessImportance int importance) {
        WebContents webContents = tab.getWebContents();
        if (webContents != null && !webContents.isDestroyed()) {
            webContents.setPrimaryPageImportance(importance, importance);
        }
    }

    @CalledByNative
    public static String evalJavaScript(int tabId, String script) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("");
        ThreadUtils.postOnUiThread(
                () -> {
                    Tab tab = findTab(getActivity(), tabId);
                    WebContents webContents = tab == null ? null : tab.getWebContents();
                    if (webContents == null || webContents.isDestroyed()) {
                        latch.countDown();
                        return;
                    }
                    try {
                        webContents.evaluateJavaScript(
                                script,
                                value -> {
                                    result.set(value == null ? "" : value);
                                    latch.countDown();
                                });
                    } catch (Exception e) {
                        logi("eval js", e.getMessage());
                        latch.countDown();
                    }
                });
        try {
            latch.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    @CalledByNative
    public static String injectJavaScript(
            int tabId, String script, boolean isolated, boolean allFrames) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("");
        ThreadUtils.postOnUiThread(
                () -> {
                    Tab tab = findTab(getActivity(), tabId);
                    WebContents webContents = tab == null ? null : tab.getWebContents();
                    if (webContents == null || webContents.isDestroyed()) {
                        latch.countDown();
                        return;
                    }
                    if (!allFrames && !isolated) {
                        try {
                            webContents.evaluateJavaScript(
                                    script,
                                    value -> {
                                        result.set(value == null ? "" : value);
                                        latch.countDown();
                                    });
                        } catch (Exception e) {
                            logi("inject main", e.getMessage());
                            latch.countDown();
                        }
                        return;
                    }
                    RenderFrameHost main = webContents.getMainFrame();
                    if (main == null || !main.isRenderFrameLive()) {
                        latch.countDown();
                        return;
                    }
                    java.util.List<RenderFrameHost> frames = new java.util.ArrayList<>();
                    if (allFrames) {
                        java.util.List<RenderFrameHost> all = main.getAllRenderFrameHosts();
                        if (all != null) {
                            for (RenderFrameHost frame : all) {
                                if (frame != null && frame.isRenderFrameLive()) {
                                    frames.add(frame);
                                }
                            }
                        }
                    }
                    if (frames.isEmpty()) {
                        frames.add(main);
                    }
                    JSONArray values = new JSONArray();
                    java.util.concurrent.atomic.AtomicInteger pending =
                            new java.util.concurrent.atomic.AtomicInteger(frames.size());
                    for (RenderFrameHost frame : frames) {
                        JavaScriptCallback callback =
                                value -> {
                                    try {
                                        JSONObject row = new JSONObject();
                                        row.put(
                                                "url",
                                                frame.getLastCommittedURL() == null
                                                        ? ""
                                                        : frame.getLastCommittedURL().getSpec());
                                        row.put("value", value == null ? "" : value);
                                        synchronized (values) {
                                            values.put(row);
                                        }
                                    } catch (Exception e) {
                                        logi("inject frame", e.getMessage());
                                    }
                                    if (pending.decrementAndGet() <= 0) {
                                        result.set(values.toString());
                                        latch.countDown();
                                    }
                                };
                        try {
                            if (isolated) {
                                frame.executeJavaScriptInIsolatedWorld(
                                        script, LUA_ISOLATED_WORLD_ID, callback);
                            } else if (frame == main) {
                                webContents.evaluateJavaScript(script, callback);
                            } else {
                                // 子 frame 没有公开的主世界 API；隔离世界仍共享 DOM。
                                // 禁止 worldId=0，那会打崩渲染进程。
                                frame.executeJavaScriptInIsolatedWorld(
                                        script, LUA_ISOLATED_WORLD_ID, callback);
                            }
                        } catch (Exception e) {
                            logi("inject dispatch", e.getMessage());
                            if (pending.decrementAndGet() <= 0) {
                                result.set(values.toString());
                                latch.countDown();
                            }
                        }
                    }
                });
        try {
            latch.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    @CalledByNative
    public static boolean hideTab(int tabId) {
        return runUi(
                () -> {
                    Tab tab = findTab(getActivity(), tabId);
                    if (tab == null) {
                        return false;
                    }
                    tab.hide(TabHidingType.CHANGED_TABS);
                    setImportance(tab, ChildProcessImportance.NORMAL);
                    return true;
                },
                false);
    }

    @CalledByNative
    public static boolean showTab(int tabId) {
        return runUi(
                () -> {
                    Tab tab = findTab(getActivity(), tabId);
                    if (tab == null) {
                        return false;
                    }
                    // 154：Tab.show 只剩 TabSelectionType 一个参数
                    tab.show(TabSelectionType.FROM_USER);
                    setImportance(tab, ChildProcessImportance.IMPORTANT);
                    return true;
                },
                false);
    }

    @CalledByNative
    public static boolean freezeTab(int tabId) {
        return runUi(
                () -> {
                    Tab tab = findTab(getActivity(), tabId);
                    if (tab == null) {
                        return false;
                    }
                    tab.hide(TabHidingType.CHANGED_TABS);
                    setImportance(tab, ChildProcessImportance.NORMAL);
                    tab.freezeNativePage();
                    return true;
                },
                false);
    }

    @CalledByNative
    public static void setClipboard(String text) {
        ThreadUtils.runOnUiThread(
                () -> {
                    ClipboardManager clipboard =
                            (ClipboardManager)
                                    ContextUtils.getApplicationContext()
                                            .getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("lemurx", text));
                    }
                });
    }

    @CalledByNative
    public static String getClipboard() {
        return runUi(
                () -> {
                    ClipboardManager clipboard =
                            (ClipboardManager)
                                    ContextUtils.getApplicationContext()
                                            .getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard == null || !clipboard.hasPrimaryClip()) {
                        return "";
                    }
                    ClipData clip = clipboard.getPrimaryClip();
                    if (clip == null || clip.getItemCount() == 0) {
                        return "";
                    }
                    CharSequence text = clip.getItemAt(0).coerceToText(
                            ContextUtils.getApplicationContext());
                    return text == null ? "" : text.toString();
                },
                "");
    }

    @CalledByNative
    public static String storageGet(String key) {
        if (TextUtils.isEmpty(key)) {
            return "";
        }
        return prefs().getString(key, "");
    }

    @CalledByNative
    public static void storageSet(String key, String value) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        prefs().edit().putString(key, value == null ? "" : value).apply();
    }

    @CalledByNative
    public static void storageDelete(String key) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        prefs().edit().remove(key).apply();
    }

    @CalledByNative
    public static String storageList() {
        JSONArray array = new JSONArray();
        for (String key : prefs().getAll().keySet()) {
            array.put(key);
        }
        return array.toString();
    }

    @CalledByNative
    public static String uiShow(String optionsJson) {
        return LemurXUiHost.show(optionsJson);
    }

    @CalledByNative
    public static boolean uiRemove(String overlayId) {
        return LemurXUiHost.remove(overlayId);
    }

    @CalledByNative
    public static void uiClear() {
        LemurXUiHost.clear();
    }

    @CalledByNative
    public static String uiOp(String action, String json) {
        return LemurXUiHost.op(action, json);
    }

    static void notifyUiClick(String overlayId) {
        notifyUiClick(overlayId, "{}");
    }

    static void notifyUiClick(String overlayId, String json) {
        if (TextUtils.isEmpty(overlayId) || !sStarted) {
            return;
        }
        LemurXBridgeJni.get().dispatchUiClick(overlayId, json == null ? "{}" : json);
    }

    @CalledByNative
    public static String dbExec(String sql, String argsJson) {
        JSONObject result = new JSONObject();
        try {
            if (!isSafeSql(sql)) {
                result.put("ok", false);
                result.put("error", "sql rejected");
                return result.toString();
            }
            SQLiteDatabase db = openDb();
            String[] args = jsonToStringArray(argsJson);
            android.database.sqlite.SQLiteStatement statement = db.compileStatement(sql);
            try {
                bindStatementArgs(statement, args);
                int rows = statement.executeUpdateDelete();
                result.put("ok", true);
                result.put("rowsAffected", rows);
            } finally {
                statement.close();
            }
        } catch (Exception e) {
            try {
                result.put("ok", false);
                result.put("error", e.getMessage() == null ? "db error" : e.getMessage());
            } catch (Exception ignored) {
                return "{\"ok\":false}";
            }
        }
        return result.toString();
    }

    @CalledByNative
    public static String dbQuery(String sql, String argsJson) {
        JSONObject result = new JSONObject();
        try {
            if (!isSafeSql(sql)) {
                result.put("ok", false);
                result.put("error", "sql rejected");
                return result.toString();
            }
            SQLiteDatabase db = openDb();
            String[] args = jsonToStringArray(argsJson);
            JSONArray rows = new JSONArray();
            try (Cursor cursor = db.rawQuery(sql, args)) {
                String[] names = cursor.getColumnNames();
                while (cursor.moveToNext()) {
                    JSONObject row = new JSONObject();
                    for (int i = 0; i < names.length; i++) {
                        putCursorValue(row, names[i], cursor, i);
                    }
                    rows.put(row);
                }
            }
            result.put("ok", true);
            result.put("rows", rows);
        } catch (Exception e) {
            try {
                result.put("ok", false);
                result.put("error", e.getMessage() == null ? "db error" : e.getMessage());
            } catch (Exception ignored) {
                return "{\"ok\":false}";
            }
        }
        return result.toString();
    }

    @CalledByNative
    public static boolean startActivity(String optionsJson, boolean privileged) {
        return runUi(
                () -> {
                    try {
                        Intent intent = buildIntent(optionsJson, privileged);
                        if (intent == null) {
                            return false;
                        }
                        Activity activity = ApplicationStatus.getLastTrackedFocusedActivity();
                        if (activity != null) {
                            activity.startActivity(intent);
                        } else {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ContextUtils.getApplicationContext().startActivity(intent);
                        }
                        return true;
                    } catch (ActivityNotFoundException e) {
                        logi("startActivity not found", e.getMessage());
                        return false;
                    } catch (Exception e) {
                        logi("startActivity", e.getMessage());
                        return false;
                    }
                },
                false);
    }

    @CalledByNative
    public static boolean sendBroadcast(String optionsJson, boolean privileged) {
        return runUi(
                () -> {
                    try {
                        Intent intent = buildIntent(optionsJson, privileged);
                        if (intent == null) {
                            return false;
                        }
                        ContextUtils.getApplicationContext().sendBroadcast(intent);
                        return true;
                    } catch (Exception e) {
                        logi("sendBroadcast", e.getMessage());
                        return false;
                    }
                },
                false);
    }

    public static void notifyTabEvent(String name, Tab tab, GURL url) {
        if (!sStarted || tab == null) {
            return;
        }
        try {
            JSONObject o = tabToJson(tab);
            if (url != null) {
                o.put("eventUrl", url.getSpec());
            }
            o.put("event", name);
            LemurXMoatHost.onTabEvent(name, tab);
            dispatchTabEventNative(name, o.toString());
        } catch (Exception e) {
            logi("notifyTabEvent", e.getMessage());
        }
    }

    static void dispatchTabEventNative(String name, String json) {
        if (!sStarted || TextUtils.isEmpty(name)) {
            return;
        }
        LemurXBridgeJni.get().dispatchTabEvent(name, json == null ? "{}" : json);
    }

    static void dispatchUiClickNative(String overlayId) {
        if (!sStarted || TextUtils.isEmpty(overlayId)) {
            return;
        }
        LemurXBridgeJni.get().dispatchUiClick(overlayId, "{}");
    }

    // ---------------------------------------------------------------- luakit 控件树（P3）

    /** Lua 线程 → 控件宿主：op ∈ create/destroy/set/get/call，返回 JSON。 */
    @CalledByNative
    public static String luakitWidget(String op, int id, String json) {
        return LemurXWidgetHost.op(op == null ? "" : op, id, json);
    }

    /** 控件事件（UI 线程）→ Lua 线程：__luakit_dispatch("widget", id, json, 0)。 */
    static void dispatchLuakitWidgetNative(int id, String json) {
        if (!sStarted) {
            return;
        }
        LemurXBridgeJni.get().dispatchLuakitWidget(id, json == null ? "{}" : json);
    }

    /** 需要答复的控件事件（key-press 是否被 Lua 吃掉）：投给 Lua 线程，结果回 onLuakitWidgetSyncResult。 */
    static boolean postLuakitWidgetSync(int id, String json, int token) {
        if (!sStarted) {
            return false;
        }
        LemurXBridgeJni.get().dispatchLuakitWidgetSync(id, json == null ? "{}" : json, token);
        return true;
    }

    @CalledByNative
    public static void onLuakitWidgetSyncResult(int token, String verdict) {
        LemurXWidgetHost.onSyncResult(token, verdict == null ? "" : verdict);
    }

    /** notebook 切页 → 切原生 Tab（已在 UI 线程）。 */
    static void selectTabForLuakit(int tabId) {
        ChromeTabbedActivity activity = getActivity();
        Tab tab = findTab(activity, tabId);
        TabModelSelector selector = selectorOf(activity);
        if (selector == null || tab == null) {
            return;
        }
        TabModel model = selector.getModel(tab.isIncognito());
        int index = model.indexOf(tab);
        if (index == TabList.INVALID_TAB_INDEX || model.index() == index) {
            return;
        }
        model.setIndex(index, TabSelectionType.FROM_USER);
    }

    /** image:set_favicon_for_uri(uri)：找 URL 匹配的 Tab 拿它当前的 favicon。 */
    static android.graphics.Bitmap faviconForUri(String uri) {
        ChromeTabbedActivity activity = getActivity();
        TabModelSelector selector = selectorOf(activity);
        if (selector == null || TextUtils.isEmpty(uri)) {
            return null;
        }
        for (TabModel model : selector.getModels()) {
            for (int i = 0; i < model.getCount(); i++) {
                Tab tab = model.getTabAt(i);
                if (tab != null && uri.equals(tab.getUrl().getSpec())) {
                    return org.chromium.chrome.browser.tab.TabFavicon.getBitmap(tab);
                }
            }
        }
        return null;
    }

    static ChromeTabbedActivity currentActivity() {
        return getActivity();
    }

    static Tab findTabForLua(ChromeTabbedActivity activity, int tabId) {
        return findTab(activity, tabId);
    }

    @CalledByNative
    public static String queryHistory(String query) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("[]");
        ThreadUtils.postOnUiThread(
                () -> {
                    try {
                        org.chromium.chrome.browser.profiles.Profile profile =
                                org.chromium.chrome.browser.profiles.ProfileManager
                                        .getLastUsedRegularProfile();
                        org.chromium.chrome.browser.history.BrowsingHistoryBridge bridge =
                                new org.chromium.chrome.browser.history.BrowsingHistoryBridge(
                                        profile);
                        bridge.setObserver(
                                new org.chromium.chrome.browser.history.HistoryProvider
                                        .BrowsingHistoryObserver() {
                                    @Override
                                    public void onQueryHistoryComplete(
                                            java.util.List<
                                                            org.chromium.chrome.browser.history
                                                                    .HistoryItem>
                                                    items,
                                            boolean hasMorePotentialMatches) {
                                        JSONArray array = new JSONArray();
                                        if (items != null) {
                                            for (org.chromium.chrome.browser.history.HistoryItem
                                                    item : items) {
                                                JSONObject o = new JSONObject();
                                                try {
                                                    o.put(
                                                            "url",
                                                            item.getUrl() == null
                                                                    ? ""
                                                                    : item.getUrl().getSpec());
                                                    o.put("title", item.getTitle());
                                                    o.put("domain", item.getDomain());
                                                    o.put("timestamp", item.getTimestamp());
                                                    array.put(o);
                                                } catch (Exception e) {
                                                    // 保持已写入字段
                                                }
                                            }
                                        }
                                        result.set(array.toString());
                                        bridge.destroy();
                                        latch.countDown();
                                    }

                                    @Override
                                    public void onHistoryDeleted() {}

                                    @Override
                                    public void hasOtherFormsOfBrowsingData(
                                            boolean hasOtherForms) {}

                                    @Override
                                    public void onQueryAppsComplete(
                                            java.util.List<String> items) {}
                                });
                        bridge.queryHistory(query == null ? "" : query, null);
                    } catch (Exception e) {
                        logi("queryHistory", e.getMessage());
                        latch.countDown();
                    }
                });
        try {
            latch.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    @CalledByNative
    public static String listDownloads() {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("[]");
        ThreadUtils.postOnUiThread(
                () -> {
                    try {
                        org.chromium.chrome.browser.download.DownloadManagerService service =
                                org.chromium.chrome.browser.download.DownloadManagerService
                                        .getDownloadManagerService();
                        org.chromium.chrome.browser.download.DownloadManagerService.DownloadObserver
                                observer =
                                        new org.chromium.chrome.browser.download
                                                .DownloadManagerService.DownloadObserver() {
                                            @Override
                                            public void onAllDownloadsRetrieved(
                                                    java.util.List<
                                                                    org.chromium.chrome.browser
                                                                            .download.DownloadItem>
                                                            list,
                                                    org.chromium.chrome.browser.profiles.ProfileKey
                                                            profileKey) {
                                                JSONArray array = new JSONArray();
                                                if (list != null) {
                                                    for (org.chromium.chrome.browser.download
                                                                    .DownloadItem
                                                            item : list) {
                                                        array.put(downloadToJson(item));
                                                    }
                                                }
                                                result.set(array.toString());
                                                service.removeDownloadObserver(this);
                                                latch.countDown();
                                            }

                                            @Override
                                            public void onDownloadItemCreated(
                                                    org.chromium.chrome.browser.download
                                                                    .DownloadItem
                                                            item) {}

                                            @Override
                                            public void onDownloadItemUpdated(
                                                    org.chromium.chrome.browser.download
                                                                    .DownloadItem
                                                            item) {}

                                            @Override
                                            public void onDownloadItemRemoved(String guid) {}

                                            @Override
                                            public void
                                                    onAddOrReplaceDownloadSharedPreferenceEntry(
                                                            org.chromium.components
                                                                            .offline_items_collection
                                                                            .ContentId
                                                                    id) {}
                                        };
                        service.addDownloadObserver(observer);
                        service.getAllDownloads(null);
                    } catch (Exception e) {
                        logi("listDownloads", e.getMessage());
                        latch.countDown();
                    }
                });
        try {
            latch.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    @CalledByNative
    public static boolean enqueueDownload(String url, int tabId) {
        return runUi(
                () -> {
                    ChromeTabbedActivity activity = getActivity();
                    Tab tab = tabId == 0 ? currentTabOf(activity) : findTab(activity, tabId);
                    if (tab == null || TextUtils.isEmpty(url)) {
                        return false;
                    }
                    org.chromium.chrome.browser.download.DownloadController.downloadUrl(url, tab);
                    return true;
                },
                false);
    }

    private static JSONObject downloadToJson(
            org.chromium.chrome.browser.download.DownloadItem item) {
        JSONObject o = new JSONObject();
        try {
            org.chromium.chrome.browser.download.DownloadInfo info = item.getDownloadInfo();
            o.put("id", item.getId());
            if (info != null) {
                o.put("url", info.getUrl() == null ? "" : info.getUrl().getSpec());
                o.put("fileName", info.getFileName() == null ? "" : info.getFileName());
                o.put("filePath", info.getFilePath() == null ? "" : info.getFilePath());
                o.put("mime", info.getMimeType() == null ? "" : info.getMimeType());
                o.put("bytesReceived", info.getBytesReceived());
                o.put("bytesTotal", info.getBytesTotalSize());
                o.put("state", info.state());
            }
        } catch (Exception e) {
            // 保持已写入字段
        }
        return o;
    }

    private static android.content.SharedPreferences prefs() {
        return ContextUtils.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static ChromeTabbedActivity getActivity() {
        Activity activity = ApplicationStatus.getLastTrackedFocusedActivity();
        if (activity instanceof ChromeTabbedActivity) {
            return (ChromeTabbedActivity) activity;
        }
        return null;
    }

    private static @Nullable Tab findTab(@Nullable ChromeTabbedActivity activity, int tabId) {
        TabModelSelector selector = selectorOf(activity);
        if (selector == null) {
            return null;
        }
        Tab tab = selector.getModel(false).getTabById(tabId);
        if (tab != null) {
            return tab;
        }
        return selector.getModel(true).getTabById(tabId);
    }

    private static @Nullable Tab currentTabOf(@Nullable ChromeTabbedActivity activity) {
        TabModelSelector selector = selectorOf(activity);
        return selector == null ? null : selector.getCurrentTab();
    }

    private static void appendTabs(JSONArray array, TabModel model) {
        if (model == null) {
            return;
        }
        for (int i = 0; i < model.getCount(); i++) {
            Tab tab = model.getTabAt(i);
            if (tab != null) {
                array.put(tabToJson(tab));
            }
        }
    }

    private static JSONObject tabToJson(Tab tab) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", tab.getId());
            o.put("url", tab.getUrl() == null ? "" : tab.getUrl().getSpec());
            o.put("title", tab.getTitle() == null ? "" : tab.getTitle());
            o.put("incognito", tab.isIncognito());
            o.put("loading", tab.isLoading());
            o.put("canGoBack", tab.canGoBack());
            o.put("canGoForward", tab.canGoForward());
            WebContents webContents = tab.getWebContents();
            if (webContents != null) {
                o.put("muted", webContents.isAudioMuted());
            }
        } catch (Exception e) {
            // 保持已写入字段
        }
        return o;
    }

    private static SQLiteDatabase openDb() {
        if (sDb != null && sDb.isOpen()) {
            return sDb;
        }
        File dir = new File(ContextUtils.getApplicationContext().getFilesDir(), USER_SCRIPT_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create lua dir");
        }
        sDb = SQLiteDatabase.openOrCreateDatabase(new File(dir, DB_NAME), null);
        sDb.setForeignKeyConstraintsEnabled(true);
        return sDb;
    }

    private static boolean isSafeSql(String sql) {
        if (TextUtils.isEmpty(sql)) {
            return false;
        }
        String upper = sql.toUpperCase(Locale.US);
        return !upper.contains("ATTACH")
                && !upper.contains("DETACH")
                && !upper.contains("LOAD_EXTENSION");
    }

    private static String[] jsonToStringArray(String json) {
        if (TextUtils.isEmpty(json) || "null".equals(json) || "{}".equals(json)) {
            return new String[0];
        }
        try {
            JSONArray array = new JSONArray(json);
            String[] args = new String[array.length()];
            for (int i = 0; i < array.length(); i++) {
                args[i] = array.isNull(i) ? null : String.valueOf(array.get(i));
            }
            return args;
        } catch (Exception e) {
            return new String[0];
        }
    }

    private static void bindStatementArgs(
            android.database.sqlite.SQLiteStatement statement, String[] args) {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null) {
                statement.bindNull(i + 1);
            } else {
                statement.bindString(i + 1, args[i]);
            }
        }
    }

    private static void putCursorValue(JSONObject row, String name, Cursor cursor, int index)
            throws Exception {
        switch (cursor.getType(index)) {
            case Cursor.FIELD_TYPE_NULL:
                row.put(name, JSONObject.NULL);
                break;
            case Cursor.FIELD_TYPE_INTEGER:
                row.put(name, cursor.getLong(index));
                break;
            case Cursor.FIELD_TYPE_FLOAT:
                row.put(name, cursor.getDouble(index));
                break;
            default:
                row.put(name, cursor.getString(index));
                break;
        }
    }

    private static Intent buildIntent(String optionsJson, boolean privileged) throws Exception {
        if (TextUtils.isEmpty(optionsJson)) {
            return null;
        }
        JSONObject options = new JSONObject(optionsJson);
        String action = options.optString("action", Intent.ACTION_VIEW);
        if (!privileged && !isAllowedIntentAction(action)) {
            logi("intent action rejected", action);
            return null;
        }
        Intent intent = new Intent(action);
        String url = options.optString("url", options.optString("data", ""));
        if (!TextUtils.isEmpty(url)) {
            Uri uri = Uri.parse(url);
            if (!privileged && !isAllowedUri(uri)) {
                logi("intent uri rejected", url);
                return null;
            }
            String mime = options.optString("mime", options.optString("type", ""));
            if (TextUtils.isEmpty(mime)) {
                intent.setData(uri);
            } else {
                intent.setDataAndType(uri, mime);
            }
        }
        String pkg = options.optString("package", "");
        if (!TextUtils.isEmpty(pkg)) {
            intent.setPackage(pkg);
        }
        String component = options.optString("component", "");
        if (privileged && !TextUtils.isEmpty(component)) {
            intent.setComponent(android.content.ComponentName.unflattenFromString(component));
        }
        if (privileged && options.has("flags")) {
            intent.addFlags(options.optInt("flags"));
        }
        JSONObject extras = options.optJSONObject("extras");
        if (extras != null) {
            Iterator<String> keys = extras.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = extras.get(key);
                if (value instanceof Boolean) {
                    intent.putExtra(key, (Boolean) value);
                } else if (value instanceof Integer) {
                    intent.putExtra(key, (Integer) value);
                } else if (value instanceof Long) {
                    intent.putExtra(key, (Long) value);
                } else if (value instanceof Double) {
                    intent.putExtra(key, (Double) value);
                } else if (value != JSONObject.NULL) {
                    intent.putExtra(key, String.valueOf(value));
                }
            }
        }
        String extraText = options.optString("text", "");
        if (!TextUtils.isEmpty(extraText) && Intent.ACTION_SEND.equals(action)) {
            intent.putExtra(Intent.EXTRA_TEXT, extraText);
            if (intent.getType() == null) {
                intent.setType("text/plain");
            }
        }
        return intent;
    }

    private static boolean isAllowedIntentAction(String action) {
        if (ALLOWED_INTENT_ACTIONS.contains(action)) {
            return true;
        }
        return action.startsWith("lemurx.")
                || action.startsWith("org.chromium.chrome.lemurx.");
    }

    private static boolean isAllowedUri(Uri uri) {
        if (uri == null || TextUtils.isEmpty(uri.getScheme())) {
            return false;
        }
        return ALLOWED_URI_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.US));
    }

    @CalledByNative
    public static String chromeSetControls(String state) {
        return LemurXChromeHost.setControls(state);
    }

    @CalledByNative
    public static boolean chromeHideUrlBar(boolean hidden) {
        return LemurXChromeHost.hideUrlBar(hidden);
    }

    @CalledByNative
    public static boolean chromeSetUrlBarText(String text) {
        return LemurXChromeHost.setUrlBarText(text);
    }

    @CalledByNative
    public static String chromeGetUrlBarText() {
        return LemurXChromeHost.getUrlBarText();
    }

    @CalledByNative
    public static boolean chromeFocusUrlBar(boolean focused) {
        return LemurXChromeHost.focusUrlBar(focused);
    }

    @CalledByNative
    public static boolean chromeSetToolbarColor(String color) {
        return LemurXChromeHost.setToolbarColor(
                LemurXChromeHost.parseColor(color, 0xFF212121));
    }

    @CalledByNative
    public static boolean chromeSetStatusBarColor(String color) {
        return LemurXChromeHost.setStatusBarColor(
                LemurXChromeHost.parseColor(color, 0xFF000000));
    }

    @CalledByNative
    public static boolean chromeSetDarkMode(boolean dark) {
        return LemurXChromeHost.setDarkMode(dark);
    }

    @CalledByNative
    public static boolean chromeIsDarkMode() {
        return LemurXChromeHost.isDarkMode();
    }

    @CalledByNative
    public static boolean chromeSetFullscreen(boolean fullscreen) {
        return LemurXChromeHost.setFullscreen(fullscreen);
    }

    @CalledByNative
    public static boolean chromeSetPullRefresh(boolean enabled) {
        return LemurXChromeHost.setPullRefresh(enabled);
    }

    @CalledByNative
    public static boolean chromeSetMenuButtonVisible(boolean visible) {
        return LemurXChromeHost.setMenuButtonVisible(visible);
    }

    @CalledByNative
    public static boolean chromeHideBottomToolbar(boolean hidden) {
        return LemurXChromeHost.hideBottomToolbar(hidden);
    }

    @CalledByNative
    public static boolean chromeHideButton(String name, boolean hidden) {
        return LemurXChromeHost.hideButton(name, hidden);
    }

    @CalledByNative
    public static boolean chromeBack() {
        return LemurXChromeHost.chromeBack();
    }

    @CalledByNative
    public static boolean chromeForward() {
        return LemurXChromeHost.chromeForward();
    }

    @CalledByNative
    public static String chromeInfo() {
        return LemurXChromeHost.info();
    }

    @CalledByNative
    public static String chromeMenuAdd(String optionsJson) {
        return LemurXChromeHost.menuAdd(optionsJson);
    }

    @CalledByNative
    public static boolean chromeMenuRemove(String id) {
        return LemurXChromeHost.menuRemove(id);
    }

    @CalledByNative
    public static void chromeMenuClear() {
        LemurXChromeHost.menuClear();
    }

    @CalledByNative
    public static boolean chromeMenuHide(String actionName, boolean hidden) {
        return LemurXChromeHost.menuHide(actionName, hidden);
    }

    @CalledByNative
    public static boolean chromeMenuIntercept(String actionName, boolean intercept) {
        return LemurXChromeHost.menuIntercept(actionName, intercept);
    }

    @CalledByNative
    public static boolean chromeMenuInvoke(String actionName) {
        return LemurXChromeHost.menuInvoke(actionName);
    }

    @CalledByNative
    public static String chromeMenuList() {
        return LemurXChromeHost.menuList();
    }

    @CalledByNative
    public static void chromeSetBackIntercept(boolean enabled) {
        LemurXChromeHost.setBackIntercept(enabled);
    }

    @CalledByNative
    public static boolean tabSetDesktop(int tabId, boolean desktop) {
        return LemurXChromeHost.setDesktop(tabId, desktop);
    }

    @CalledByNative
    public static boolean tabSetZoom(int tabId, double percent) {
        return LemurXChromeHost.setZoom(tabId, percent);
    }

    @CalledByNative
    public static double tabGetZoom(int tabId) {
        return LemurXChromeHost.getZoom(tabId);
    }

    @CalledByNative
    public static boolean tabSetJavaScript(boolean enabled) {
        return LemurXChromeHost.setJavaScript(enabled);
    }

    @CalledByNative
    public static boolean tabIsJavaScript() {
        return LemurXChromeHost.isJavaScriptEnabled();
    }

    @CalledByNative
    public static String httpFetch(String url, String optionsJson, boolean privileged) {
        return LemurXMoatHost.httpFetch(url, optionsJson, privileged);
    }

    /**
     * 异步版：在 ThreadPool 里跑同一份 httpFetch，完事把结果投回 Lua 线程，
     * Lua 线程不被网络阻塞，其他脚本的回调照常跑。
     */
    @CalledByNative
    public static void httpFetchAsync(
            String url, String optionsJson, boolean privileged, int requestId) {
        PostTask.postTask(
                TaskTraits.USER_VISIBLE_MAY_BLOCK,
                () -> {
                    String json;
                    try {
                        json = LemurXMoatHost.httpFetch(url, optionsJson, privileged);
                    } catch (Throwable t) {
                        json = "{\"ok\":false,\"error\":\"" + String.valueOf(t.getMessage())
                                .replace("\"", "'") + "\"}";
                    }
                    if (sStarted) {
                        LemurXBridgeJni.get().dispatchHttpResult(requestId, json);
                    }
                });
    }

    @CalledByNative
    public static String fsRoot(boolean privileged) {
        return LemurXMoatHost.fsRoot(privileged);
    }

    @CalledByNative
    public static String fsRead(String path, String optionsJson, boolean privileged) {
        return LemurXMoatHost.fsRead(path, optionsJson, privileged);
    }

    @CalledByNative
    public static boolean fsWrite(
            String path, String data, String optionsJson, boolean privileged) {
        return LemurXMoatHost.fsWrite(path, data, optionsJson, privileged);
    }

    @CalledByNative
    public static String fsList(String path, boolean privileged) {
        return LemurXMoatHost.fsList(path, privileged);
    }

    @CalledByNative
    public static boolean fsExists(String path, boolean privileged) {
        return LemurXMoatHost.fsExists(path, privileged);
    }

    @CalledByNative
    public static boolean fsMkdir(String path, boolean privileged) {
        return LemurXMoatHost.fsMkdir(path, privileged);
    }

    @CalledByNative
    public static boolean fsRemove(String path, boolean privileged) {
        return LemurXMoatHost.fsRemove(path, privileged);
    }

    @CalledByNative
    public static String tabScreenshot(int tabId, String optionsJson, boolean privileged) {
        return LemurXMoatHost.screenshot(tabId, optionsJson, privileged);
    }

    @CalledByNative
    public static boolean tabMute(int tabId, boolean muted) {
        return LemurXMoatHost.mute(tabId, muted);
    }

    @CalledByNative
    public static boolean tabIsMuted(int tabId) {
        return LemurXMoatHost.isMuted(tabId);
    }

    @CalledByNative
    public static boolean tabStop(int tabId) {
        return LemurXMoatHost.stop(tabId);
    }

    @CalledByNative
    public static String tabHtml(int tabId) {
        return LemurXMoatHost.html(tabId);
    }

    @CalledByNative
    public static boolean tabSetUserAgent(int tabId, String ua) {
        return LemurXMoatHost.setUserAgent(tabId, ua);
    }

    @CalledByNative
    public static boolean tabSetHeaders(int tabId, String headersJson) {
        return LemurXMoatHost.setHeaders(tabId, headersJson);
    }

    @CalledByNative
    public static boolean inputTap(String optionsJson) {
        return LemurXMoatHost.tap(optionsJson);
    }

    @CalledByNative
    public static boolean inputSwipe(String optionsJson) {
        return LemurXMoatHost.swipe(optionsJson);
    }

    @CalledByNative
    public static boolean inputType(String text, int tabId) {
        return LemurXMoatHost.typeText(text, tabId);
    }

    @CalledByNative
    public static boolean inputKey(String name, int tabId) {
        return LemurXMoatHost.key(name, tabId);
    }

    @CalledByNative
    public static boolean permSet(String origin, String type, String value) {
        return LemurXMoatHost.permSet(origin, type, value);
    }

    @CalledByNative
    public static String permGet(String origin, String type) {
        return LemurXMoatHost.permGet(origin, type);
    }

    @CalledByNative
    public static String tabNavHistory(int tabId) {
        return LemurXMoatHost.navHistory(tabId);
    }

    @CalledByNative
    public static boolean tabNavGo(int tabId, int index) {
        return LemurXMoatHost.navGo(tabId, index);
    }

    @CalledByNative
    public static boolean tabNavOffset(int tabId, int offset) {
        return LemurXMoatHost.navOffset(tabId, offset);
    }

    @CalledByNative
    public static boolean tabReloadBypassCache(int tabId) {
        return LemurXMoatHost.reloadBypassCache(tabId);
    }

    @CalledByNative
    public static String tabFrames(int tabId) {
        return LemurXMoatHost.frames(tabId);
    }

    @CalledByNative
    public static String prefsGet(String name, String type) {
        return LemurXMoatHost.prefsGet(name, type);
    }

    @CalledByNative
    public static boolean prefsSet(String name, String type, String value) {
        return LemurXMoatHost.prefsSet(name, type, value);
    }

    @CalledByNative
    public static boolean prefsClear(String name) {
        return LemurXMoatHost.prefsClear(name);
    }

    @CalledByNative
    public static String dataClear(String typesJson, String period) {
        return LemurXMoatHost.dataClear(typesJson, period);
    }

    @CalledByNative
    public static boolean featureEnabled(String name) {
        return LemurXMoatHost.featureEnabled(name);
    }

    @CalledByNative
    public static String featureParam(String feature, String param) {
        return LemurXMoatHost.featureParam(feature, param);
    }

    @CalledByNative
    public static WebContents webContentsForTab(int tabId) {
        ChromeTabbedActivity activity = getActivity();
        Tab tab = tabId <= 0 ? (activity == null ? null : activity.getActivityTab())
                             : findTab(activity, tabId);
        return tab == null ? null : tab.getWebContents();
    }

    @NativeMethods
    interface Natives {
        void start();

        void eval(String chunk, String name, boolean privileged);

        void dispatchUiClick(String overlayId, String json);

        void dispatchTabEvent(String name, String json);

        void dispatchHttpResult(int requestId, String json);

        void dispatchLuakitWidget(int id, String json);

        void dispatchLuakitWidgetSync(int id, String json, int token);
    }
}
