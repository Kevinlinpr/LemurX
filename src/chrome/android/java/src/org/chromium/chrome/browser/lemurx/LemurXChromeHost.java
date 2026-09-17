package org.chromium.chrome.browser.lemurx;

import android.graphics.Color;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;

import org.chromium.base.ThreadUtils;
import org.chromium.base.lemurx.LemurBaseSp;
import org.chromium.cc.input.BrowserControlsState;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.browser.SwipeRefreshHandler;
import org.chromium.chrome.browser.omnibox.OmniboxFocusReason;
import org.chromium.chrome.browser.omnibox.OmniboxStub;
import org.chromium.chrome.browser.omnibox.UrlFocusChangeListener;
import org.chromium.chrome.browser.profiles.ProfileManager;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabBrowserControlsConstraintsHelper;
import org.chromium.chrome.browser.tab.TabCreationState;
import org.chromium.chrome.browser.tab.TabLaunchType;
import org.chromium.chrome.browser.tab.TabSelectionType;
import org.chromium.chrome.browser.tab.TabUtils;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.tabmodel.TabModelSelectorTabModelObserver;
import org.chromium.chrome.browser.toolbar.ToolbarManager;
import org.chromium.chrome.browser.toolbar.top.ToolbarPhoneLemur;
import org.chromium.chrome.lemurx.base.utils.LemurLogUtils;
import org.chromium.chrome.lemurx.bean.BottomMenuItem;
import org.chromium.chrome.lemurx.dialog.DialogMenuBottom;
import org.chromium.chrome.lemurx.utils.LemurThemeUtils;
import org.chromium.components.browser_ui.site_settings.WebsitePreferenceBridge;
import org.chromium.components.browser_ui.util.BrowserControlsVisibilityDelegate;
import org.chromium.components.content_settings.ContentSettingsType;
import org.chromium.content_public.browser.HostZoomMap;
import org.chromium.content_public.browser.WebContents;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 浏览器外壳：工具栏 / 地址栏 / 底栏菜单 / 返回键 / 站点控制。
 * Lua 通过 {@link LemurXBridge} 调用这里的方法。
 */
public class LemurXChromeHost {
    private static final String TAG = "LemurX";
    static final int ACTION_LUA_BASE = 1000;

    private static ChromeTabbedActivity sActivity;
    private static BrowserControlsVisibilityDelegate sControlsDelegate;
    private static boolean sControlsAdded;
    private static TabModelSelectorTabModelObserver sTabObserver;
    private static OnBackPressedCallback sBackCallback;
    private static UrlFocusChangeListener sOmniboxListener;
    private static boolean sBackIntercept;
    private static boolean sSkipBackIntercept;
    private static boolean sSkipMenuIntercept;
    private static int sNextMenuAction = ACTION_LUA_BASE;
    private static int sControlsState = BrowserControlsState.BOTH;

    private static final List<LuaMenuItem> sLuaMenus = new ArrayList<>();
    private static final Set<String> sHiddenMenuActions = new HashSet<>();
    private static final Set<String> sInterceptMenuActions = new HashSet<>();
    private static final Map<String, Integer> sButtonVis = new HashMap<>();
    private static boolean sHideSideButtons;

    private static class LuaMenuItem {
        String id;
        String title;
        String page;
        int action;
        int iconId;
    }

    static void attach(@Nullable ChromeTabbedActivity activity) {
        ThreadUtils.assertOnUiThread();
        if (activity == null || activity.isDestroyed() || activity.isFinishing()) {
            return;
        }
        if (sActivity != activity) {
            if (sTabObserver != null) {
                sTabObserver.destroy();
                sTabObserver = null;
            }
            sBackCallback = null;
            sOmniboxListener = null;
            sControlsAdded = false;
        }
        sActivity = activity;
        ensureControlsDelegate(activity);
        ensureTabObserver(activity);
        ensureBackCallback(activity);
        ensureOmniboxListener(activity);
        applyButtonVis(activity);
        applySideButtonsVis(activity);
    }

    public static List<BottomMenuItem> decorateMenus(List<BottomMenuItem> base, String page) {
        List<BottomMenuItem> out = new ArrayList<>();
        if (base != null) {
            for (BottomMenuItem item : base) {
                if (item != null
                        && (TextUtils.isEmpty(item.mActionName)
                                || !sHiddenMenuActions.contains(item.mActionName))) {
                    out.add(item);
                }
            }
        }
        for (LuaMenuItem lua : sLuaMenus) {
            if (page.equals(lua.page)) {
                out.add(new BottomMenuItem(lua.iconId, lua.title, lua.action, lua.id));
            }
        }
        if ("main".equals(page)) {
            boolean hasTutorial = false;
            for (BottomMenuItem item : out) {
                if (item != null
                        && (item.mAction == BottomMenuItem.ACTION_LUA_TUTORIAL
                                || "lua_tutorial".equals(item.mActionName))) {
                    hasTutorial = true;
                    break;
                }
            }
            if (!hasTutorial) {
                out.add(
                        0,
                        new BottomMenuItem(
                                R.drawable.lemur_ic_ext_manager,
                                "Lua教程",
                                BottomMenuItem.ACTION_LUA_TUTORIAL,
                                "lua_tutorial"));
            }
        }
        return out;
    }

    public static boolean handleMenuClick(BottomMenuItem data, ChromeTabbedActivity context) {
        if (data == null) {
            return false;
        }
        if (data.mAction == BottomMenuItem.ACTION_LUA_TUTORIAL
                || "lua_tutorial".equals(data.mActionName)) {
            LemurXBridge.openTutorial();
            return true;
        }
        if (sSkipMenuIntercept) {
            return false;
        }
        if (data.mAction >= ACTION_LUA_BASE) {
            LemurXBridge.dispatchUiClickNative("menu:" + data.mActionName);
            JSONObject ev = new JSONObject();
            try {
                ev.put("id", data.mActionName);
                ev.put("title", data.getName());
                ev.put("lua", true);
            } catch (Exception e) {
                // 保持已写入字段
            }
            LemurXBridge.dispatchTabEventNative("menu", ev.toString());
            return true;
        }
        if (!TextUtils.isEmpty(data.mActionName)
                && sInterceptMenuActions.contains(data.mActionName)) {
            JSONObject ev = new JSONObject();
            try {
                ev.put("id", data.mActionName);
                ev.put("title", data.getName());
                ev.put("lua", false);
            } catch (Exception e) {
                // 保持已写入字段
            }
            LemurXBridge.dispatchTabEventNative("menu:" + data.mActionName, ev.toString());
            LemurXBridge.dispatchTabEventNative("menu", ev.toString());
            return true;
        }
        return false;
    }

    static String setControls(String state) {
        return runUi(
                () -> {
                    ChromeTabbedActivity activity = activity();
                    int value = BrowserControlsState.BOTH;
                    if ("hidden".equals(state) || "hide".equals(state)) {
                        value = BrowserControlsState.HIDDEN;
                    } else if ("shown".equals(state) || "show".equals(state)) {
                        value = BrowserControlsState.SHOWN;
                    }
                    sControlsState = value;
                    if (activity != null) {
                        ensureControlsDelegate(activity);
                    }
                    if (sControlsDelegate != null) {
                        sControlsDelegate.set(value);
                    }
                    if (activity != null) {
                        Tab tab = activity.getActivityTab();
                        if (tab != null) {
                            TabBrowserControlsConstraintsHelper.update(tab, value, true);
                        }
                    }
                    return controlsName(value);
                });
    }

    static boolean hideUrlBar(boolean hidden) {
        return runUiBool(
                () -> {
                    ToolbarManager toolbar = toolbar();
                    if (toolbar == null) {
                        return false;
                    }
                    toolbar.setUrlBarHidden(hidden);
                    return true;
                });
    }

    static boolean setUrlBarText(String text) {
        return runUiBool(
                () -> {
                    ToolbarManager toolbar = toolbar();
                    if (toolbar == null) {
                        return false;
                    }
                    toolbar.setUrlBarFocusAndText(
                            true, OmniboxFocusReason.MENU_OR_KEYBOARD_ACTION, text);
                    return true;
                });
    }

    static String getUrlBarText() {
        return runUi(
                () -> {
                    ToolbarManager toolbar = toolbar();
                    if (toolbar == null) {
                        return "";
                    }
                    try {
                        return toolbar.getUrlBarTextWithoutAutocomplete();
                    } catch (Exception e) {
                        return "";
                    }
                });
    }

    static boolean focusUrlBar(boolean focused) {
        return runUiBool(
                () -> {
                    ToolbarManager toolbar = toolbar();
                    if (toolbar == null) {
                        return false;
                    }
                    toolbar.setUrlBarFocus(
                            focused,
                            focused
                                    ? OmniboxFocusReason.MENU_OR_KEYBOARD_ACTION
                                    : OmniboxFocusReason.UNFOCUS);
                    return true;
                });
    }

    static boolean setToolbarColor(int color) {
        return runUiBool(
                () -> {
                    ToolbarManager toolbar = toolbar();
                    if (toolbar == null) {
                        return false;
                    }
                    toolbar.setShouldUpdateToolbarPrimaryColor(true);
                    toolbar.onThemeColorChanged(color, false);
                    toolbar.setShouldUpdateToolbarPrimaryColor(false);
                    return true;
                });
    }

    /** 把工具栏颜色交还给站点主题色 / 默认色（lemurx.theme.reset 用）。 */
    static boolean resetToolbarColor(int defaultColor) {
        return runUiBool(
                () -> {
                    ToolbarManager toolbar = toolbar();
                    if (toolbar == null) {
                        return false;
                    }
                    toolbar.setShouldUpdateToolbarPrimaryColor(true);
                    toolbar.onThemeColorChanged(defaultColor, false);
                    return true;
                });
    }

    static boolean setStatusBarColor(int color) {
        return runUiBool(
                () -> {
                    ChromeTabbedActivity activity = activity();
                    if (activity == null) {
                        return false;
                    }
                    Window window = activity.getWindow();
                    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                    window.setStatusBarColor(color);
                    return true;
                });
    }

    static boolean setDarkMode(boolean dark) {
        return runUiBool(
                () -> {
                    LemurThemeUtils.setDarkMode(dark);
                    return true;
                });
    }

    static boolean isDarkMode() {
        return ThreadUtils.runOnUiThreadBlockingNoException(LemurThemeUtils::isDarkMode);
    }

    static boolean setFullscreen(boolean fullscreen) {
        return runUiBool(
                () -> {
                    ChromeTabbedActivity activity = activity();
                    if (activity == null) {
                        return false;
                    }
                    int value =
                            fullscreen ? BrowserControlsState.HIDDEN : BrowserControlsState.BOTH;
                    sControlsState = value;
                    ensureControlsDelegate(activity);
                    if (sControlsDelegate != null) {
                        sControlsDelegate.set(value);
                    }
                    Tab tab = activity.getActivityTab();
                    if (tab != null) {
                        TabBrowserControlsConstraintsHelper.update(tab, value, true);
                    }
                    Window window = activity.getWindow();
                    if (fullscreen) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                        View decor = window.getDecorView();
                        decor.setSystemUiVisibility(
                                View.SYSTEM_UI_FLAG_FULLSCREEN
                                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                        activity.exitFullscreenIfShowing();
                    }
                    return true;
                });
    }

    static boolean setPullRefresh(boolean enabled) {
        return runUiBool(
                () -> {
                    ChromeTabbedActivity activity = activity();
                    Tab tab = activity == null ? null : activity.getActivityTab();
                    if (tab == null) {
                        return false;
                    }
                    SwipeRefreshHandler.from(tab).setEnabled(enabled);
                    return true;
                });
    }

    static boolean setMenuButtonVisible(boolean visible) {
        return runUiBool(
                () -> {
                    ChromeTabbedActivity activity = activity();
                    if (activity == null) {
                        return false;
                    }
                    int vis = visible ? View.VISIBLE : View.GONE;
                    View lemurMenu = findByName(activity, "menu_button_lemur");
                    if (lemurMenu != null) {
                        lemurMenu.setVisibility(vis);
                    }
                    View wrapper = findByName(activity, "menu_button_wrapper");
                    if (wrapper != null) {
                        wrapper.setVisibility(vis);
                        return true;
                    }
                    return lemurMenu != null;
                });
    }

    static boolean hideBottomToolbar(boolean hidden) {
        return runUiBool(
                () -> {
                    ChromeTabbedActivity activity = activity();
                    if (activity == null) {
                        return false;
                    }
                    sHideSideButtons = hidden;
                    applySideButtonsVis(activity);
                    return true;
                });
    }

    static boolean hideButton(String name, boolean hidden) {
        return runUiBool(
                () -> {
                    ChromeTabbedActivity activity = activity();
                    if (activity == null || TextUtils.isEmpty(name)) {
                        return false;
                    }
                    String[] ids = buttonIdNames(name);
                    if (ids.length == 0) {
                        return false;
                    }
                    int vis = hidden ? View.GONE : View.VISIBLE;
                    sButtonVis.put(name, vis);
                    boolean any = false;
                    for (String id : ids) {
                        View view = findByName(activity, id);
                        if (view != null) {
                            view.setVisibility(vis);
                            any = true;
                        }
                    }
                    return any;
                });
    }

    static boolean chromeBack() {
        return runUiBool(
                () -> {
                    sSkipBackIntercept = true;
                    try {
                        ToolbarManager toolbar = toolbar();
                        if (toolbar != null && toolbar.back()) {
                            return true;
                        }
                        ChromeTabbedActivity activity = activity();
                        Tab tab = activity == null ? null : activity.getActivityTab();
                        if (tab != null && tab.canGoBack()) {
                            tab.goBack();
                            return true;
                        }
                        if (activity != null) {
                            activity.moveTaskToBack(true);
                            return true;
                        }
                        return false;
                    } finally {
                        sSkipBackIntercept = false;
                    }
                });
    }

    static boolean chromeForward() {
        return runUiBool(
                () -> {
                    ToolbarManager toolbar = toolbar();
                    if (toolbar != null && toolbar.forward()) {
                        return true;
                    }
                    ChromeTabbedActivity activity = activity();
                    Tab tab = activity == null ? null : activity.getActivityTab();
                    if (tab != null && tab.canGoForward()) {
                        tab.goForward();
                        return true;
                    }
                    return false;
                });
    }

    static String info() {
        return runUi(
                () -> {
                    JSONObject o = new JSONObject();
                    try {
                        ChromeTabbedActivity activity = activity();
                        ToolbarManager toolbar = toolbar();
                        o.put("controls", controlsName(sControlsState));
                        o.put("darkMode", LemurThemeUtils.isDarkMode());
                        o.put("urlBarFocused", toolbar != null && toolbar.isUrlBarFocused());
                    o.put("barLayout", LemurBaseSp.getBottomToolbarStyle());
                        try {
                            o.put(
                                    "urlBarText",
                                    toolbar == null ? "" : toolbar.getUrlBarTextWithoutAutocomplete());
                        } catch (Exception e) {
                            o.put("urlBarText", "");
                        }
                        Tab tab = activity == null ? null : activity.getActivityTab();
                        o.put(
                                "fullscreen",
                                activity != null
                                        && activity.getFullscreenManager()
                                                .getPersistentFullscreenMode());
                        o.put(
                                "desktop",
                                tab != null
                                        && TabUtils.isUsingDesktopUserAgent(tab.getWebContents()));
                        o.put("zoom", tab == null ? 100.0 : zoomPercent(tab));
                        o.put("javascript", isJavaScriptEnabled());
                        o.put("backIntercept", sBackIntercept);
                        JSONArray hidden = new JSONArray();
                        for (String name : sHiddenMenuActions) {
                            hidden.put(name);
                        }
                        o.put("hiddenMenu", hidden);
                        JSONArray luaMenus = new JSONArray();
                        for (LuaMenuItem item : sLuaMenus) {
                            JSONObject row = new JSONObject();
                            row.put("id", item.id);
                            row.put("title", item.title);
                            row.put("page", item.page);
                            luaMenus.put(row);
                        }
                        o.put("luaMenu", luaMenus);
                    } catch (Exception e) {
                        LemurLogUtils.i(TAG, "chrome.info", e.getMessage());
                    }
                    return o.toString();
                });
    }

    static String menuAdd(String optionsJson) {
        return runUi(
                () -> {
                    try {
                        JSONObject options =
                                TextUtils.isEmpty(optionsJson)
                                        ? new JSONObject()
                                        : new JSONObject(optionsJson);
                        LuaMenuItem item = new LuaMenuItem();
                        item.id = options.optString("id", "");
                        if (TextUtils.isEmpty(item.id)) {
                            item.id = "lua_menu_" + sNextMenuAction;
                        }
                        item.title = options.optString("title", item.id);
                        item.page = options.optString("page", "main");
                        if (!"expand".equals(item.page) && !"second".equals(item.page)) {
                            item.page = "main";
                        }
                        item.iconId = R.drawable.ic_menu_extension_store;
                        item.action = sNextMenuAction++;
                        Iterator<LuaMenuItem> it = sLuaMenus.iterator();
                        while (it.hasNext()) {
                            if (item.id.equals(it.next().id)) {
                                it.remove();
                            }
                        }
                        sLuaMenus.add(item);
                        return item.id;
                    } catch (Exception e) {
                        LemurLogUtils.i(TAG, "menu.add", e.getMessage());
                        return "";
                    }
                });
    }

    static boolean menuRemove(String id) {
        return runUiBool(
                () -> {
                    Iterator<LuaMenuItem> it = sLuaMenus.iterator();
                    boolean removed = false;
                    while (it.hasNext()) {
                        if (id.equals(it.next().id)) {
                            it.remove();
                            removed = true;
                        }
                    }
                    return removed;
                });
    }

    static void menuClear() {
        ThreadUtils.runOnUiThreadBlockingNoException(
                () -> {
                    sLuaMenus.clear();
                    return true;
                });
    }

    static boolean menuHide(String actionName, boolean hidden) {
        return runUiBool(
                () -> {
                    if (TextUtils.isEmpty(actionName)) {
                        return false;
                    }
                    if (hidden) {
                        sHiddenMenuActions.add(actionName);
                    } else {
                        sHiddenMenuActions.remove(actionName);
                    }
                    return true;
                });
    }

    static boolean menuIntercept(String actionName, boolean intercept) {
        return runUiBool(
                () -> {
                    if (TextUtils.isEmpty(actionName)) {
                        return false;
                    }
                    if (intercept) {
                        sInterceptMenuActions.add(actionName);
                    } else {
                        sInterceptMenuActions.remove(actionName);
                    }
                    return true;
                });
    }

    static boolean menuInvoke(String actionName) {
        return runUiBool(
                () -> {
                    ChromeTabbedActivity activity = activity();
                    if (activity == null || TextUtils.isEmpty(actionName)) {
                        return false;
                    }
                    BottomMenuItem item = BottomMenuItem.findByActionName(actionName);
                    if (item == null) {
                        return false;
                    }
                    sSkipMenuIntercept = true;
                    try {
                        DialogMenuBottom.handleOnMenuClick(item, activity);
                        return true;
                    } finally {
                        sSkipMenuIntercept = false;
                    }
                });
    }

    static String menuList() {
        return runUi(
                () -> {
                    JSONObject out = new JSONObject();
                    try {
                        ChromeTabbedActivity activity = activity();
                        JSONArray pages = new JSONArray();
                        boolean desktop = false;
                        boolean bookmarked = false;
                        if (activity != null) {
                            pages.put(
                                    menuPageJson(
                                            "main",
                                            BottomMenuItem.createMenus(
                                                    activity, true, desktop, bookmarked)));
                            pages.put(
                                    menuPageJson(
                                            "expand",
                                            BottomMenuItem.getAdapterExpandData(
                                                    activity, desktop)));
                            pages.put(
                                    menuPageJson(
                                            "second",
                                            BottomMenuItem.getSecondPageData(activity)));
                        }
                        JSONArray lua = new JSONArray();
                        for (LuaMenuItem item : sLuaMenus) {
                            JSONObject o = new JSONObject();
                            o.put("id", item.id);
                            o.put("title", item.title);
                            o.put("page", item.page);
                            o.put("lua", true);
                            lua.put(o);
                        }
                        JSONArray hidden = new JSONArray();
                        for (String name : sHiddenMenuActions) {
                            hidden.put(name);
                        }
                        JSONArray intercept = new JSONArray();
                        for (String name : sInterceptMenuActions) {
                            intercept.put(name);
                        }
                        out.put("ok", true);
                        out.put("pages", pages);
                        out.put("lua", lua);
                        out.put("hidden", hidden);
                        out.put("intercept", intercept);
                    } catch (Exception e) {
                        try {
                            out.put("ok", false);
                            out.put("error", e.getMessage());
                        } catch (Exception ignored) {
                            // 保持已写入字段
                        }
                    }
                    return out.toString();
                });
    }

    private static JSONObject menuPageJson(String page, List<BottomMenuItem> items)
            throws Exception {
        JSONObject o = new JSONObject();
        o.put("page", page);
        JSONArray arr = new JSONArray();
        if (items != null) {
            for (BottomMenuItem item : items) {
                if (item == null) {
                    continue;
                }
                JSONObject row = new JSONObject();
                row.put("id", item.mActionName == null ? "" : item.mActionName);
                row.put("title", item.getName() == null ? "" : item.getName());
                row.put("action", item.mAction);
                row.put("selected", item.mState == BottomMenuItem.STATE_SELECTED);
                arr.put(row);
            }
        }
        o.put("items", arr);
        return o;
    }

    static void setBackIntercept(boolean enabled) {
        ThreadUtils.postOnUiThread(
                () -> {
                    sBackIntercept = enabled;
                    if (sBackCallback != null) {
                        sBackCallback.setEnabled(enabled);
                    }
                });
    }

    static boolean consumeBack() {
        if (sSkipBackIntercept || !sBackIntercept) {
            return false;
        }
        JSONObject ev = new JSONObject();
        try {
            ChromeTabbedActivity activity = activity();
            Tab tab = activity == null ? null : activity.getActivityTab();
            if (tab != null) {
                ev.put("id", tab.getId());
                ev.put("url", tab.getUrl() == null ? "" : tab.getUrl().getSpec());
                ev.put("title", tab.getTitle() == null ? "" : tab.getTitle());
            }
        } catch (Exception e) {
            // 保持已写入字段
        }
        LemurXBridge.dispatchTabEventNative("back", ev.toString());
        return true;
    }

    static boolean setDesktop(int tabId, boolean desktop) {
        return runUiBool(
                () -> {
                    Tab tab = findTab(tabId);
                    if (tab == null || tab.getWebContents() == null) {
                        return false;
                    }
                    TabUtils.switchUserAgent(
                            tab, desktop, true, TabUtils.UseDesktopUserAgentCaller.OTHER);
                    return true;
                });
    }

    static boolean setZoom(int tabId, double percent) {
        return runUiBool(
                () -> {
                    Tab tab = findTab(tabId);
                    WebContents webContents = tab == null ? null : tab.getWebContents();
                    if (webContents == null || webContents.isDestroyed()) {
                        return false;
                    }
                    double level = percent;
                    if (level > 8.0) {
                        level = level / 100.0;
                    }
                    if (level < 0.25) {
                        level = 0.25;
                    }
                    if (level > 5.0) {
                        level = 5.0;
                    }
                    double factor = Math.log(level) / Math.log(HostZoomMap.TEXT_SIZE_MULTIPLIER_RATIO);
                    HostZoomMap.setZoomLevel(webContents, factor);
                    return true;
                });
    }

    static double getZoom(int tabId) {
        Double value =
                ThreadUtils.runOnUiThreadBlockingNoException(
                        () -> {
                            Tab tab = findTab(tabId);
                            return tab == null ? 100.0 : zoomPercent(tab);
                        });
        return value == null ? 100.0 : value;
    }

    static boolean setJavaScript(boolean enabled) {
        return runUiBool(
                () -> {
                    WebsitePreferenceBridge.setContentSettingEnabled(
                            ProfileManager.getLastUsedRegularProfile(),
                            ContentSettingsType.JAVASCRIPT,
                            enabled);
                    return true;
                });
    }

    static boolean isJavaScriptEnabled() {
        Boolean value =
                ThreadUtils.runOnUiThreadBlockingNoException(
                        () ->
                                WebsitePreferenceBridge.isContentSettingEnabled(
                                        ProfileManager.getLastUsedRegularProfile(),
                                        ContentSettingsType.JAVASCRIPT));
        return value != null && value;
    }

    static int parseColor(String text, int fallback) {
        if (TextUtils.isEmpty(text)) {
            return fallback;
        }
        try {
            if (text.startsWith("#")) {
                return Color.parseColor(text);
            }
            return (int) Long.decode(text).intValue();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void ensureControlsDelegate(ChromeTabbedActivity activity) {
        if (sControlsDelegate == null) {
            sControlsDelegate = new BrowserControlsVisibilityDelegate(sControlsState);
        }
        if (!sControlsAdded) {
            try {
                activity.getRootUiCoordinatorForTesting()
                        .getAppBrowserControlsVisibilityDelegate()
                        .addDelegate(sControlsDelegate);
                sControlsAdded = true;
            } catch (Exception e) {
                LemurLogUtils.i(TAG, "controls delegate", e.getMessage());
            }
        }
        sControlsDelegate.set(sControlsState);
    }

    private static void ensureTabObserver(ChromeTabbedActivity activity) {
        TabModelSelector selector = activity.getTabModelSelector();
        if (selector == null || sTabObserver != null) {
            return;
        }
        sTabObserver =
                new TabModelSelectorTabModelObserver(selector) {
                    @Override
                    public void didAddTab(
                            Tab tab,
                            @TabLaunchType int type,
                            @TabCreationState int creationState,
                            boolean markedForSelection) {
                        LemurXBridge.notifyTabEvent("created", tab, tab.getUrl());
                    }

                    @Override
                    public void willCloseTab(Tab tab, boolean didCloseAlone) {
                        LemurXBridge.notifyTabEvent("closed", tab, tab.getUrl());
                    }

                    @Override
                    public void didSelectTab(Tab tab, @TabSelectionType int type, int lastId) {
                        LemurXBridge.notifyTabEvent("selected", tab, tab.getUrl());
                        LemurXWidgetHost.onNativeTabSelected(tab.getId());
                    }
                };
    }

    private static void ensureBackCallback(ChromeTabbedActivity activity) {
        if (sBackCallback != null) {
            return;
        }
        sBackCallback =
                new OnBackPressedCallback(sBackIntercept) {
                    @Override
                    public void handleOnBackPressed() {
                        if (!consumeBack()) {
                            setEnabled(false);
                            try {
                                activity.getOnBackPressedDispatcher().onBackPressed();
                            } finally {
                                setEnabled(sBackIntercept);
                            }
                        }
                    }
                };
        activity.getOnBackPressedDispatcher().addCallback(activity, sBackCallback);
    }

    private static void ensureOmniboxListener(ChromeTabbedActivity activity) {
        if (sOmniboxListener != null) {
            return;
        }
        ToolbarManager toolbar = activity.getToolbarManager();
        if (toolbar == null) {
            return;
        }
        OmniboxStub stub = toolbar.getOmniboxStub();
        if (stub == null) {
            return;
        }
        sOmniboxListener =
                hasFocus -> {
                    JSONObject ev = new JSONObject();
                    try {
                        ev.put("focus", hasFocus);
                        ev.put("text", toolbar.getUrlBarTextWithoutAutocomplete());
                    } catch (Exception e) {
                        // 保持已写入字段
                    }
                    LemurXBridge.dispatchTabEventNative("omnibox", ev.toString());
                };
        stub.addUrlFocusChangeListener(sOmniboxListener);
    }

    private static void applyButtonVis(ChromeTabbedActivity activity) {
        for (Map.Entry<String, Integer> entry : sButtonVis.entrySet()) {
            for (String idName : buttonIdNames(entry.getKey())) {
                View view = findByName(activity, idName);
                if (view != null) {
                    view.setVisibility(entry.getValue());
                }
            }
        }
        applySideButtonsVis(activity);
    }

    private static void applySideButtonsVis(ChromeTabbedActivity activity) {
        int vis = sHideSideButtons ? View.GONE : View.VISIBLE;
        // 现行壳：地址栏左右两侧按钮组。旧 Duet 底栏 id 一并写，有就藏。
        setViewVis(activity, "toolbar_buttons_left", vis);
        setViewVis(activity, "toolbar_buttons", vis);
        setViewVis(activity, "bottom_toolbar_browsing", vis);
        setViewVis(activity, "bottom_toolbar_buttons", vis);
    }

    /** 逻辑名 → 现行 ToolbarPhoneLemur id，附带已下线 Duet 底栏别名。 */
    private static String[] buttonIdNames(String name) {
        if (TextUtils.isEmpty(name)) {
            return new String[0];
        }
        switch (name) {
            case "back":
                return new String[] {"tab_back_button", "bt_web_go_back"};
            case "forward":
                return new String[] {"tab_forward_button", "bt_web_go_forward"};
            case "home":
                return new String[] {"home_button"};
            case "tabs":
            case "switch":
                return new String[] {"tab_switcher_button"};
            case "tools":
            case "tool":
            case "extensions":
                return new String[] {"menu_tools", "bt_extensions"};
            case "settings":
            case "setting":
                return new String[] {"bt_setting"};
            case "search":
                return new String[] {"location_bar", "search_box", "search_accelerator"};
            case "menu":
                return new String[] {"menu_button_lemur", "menu_button_wrapper"};
            default:
                return new String[] {name};
        }
    }

    static String barLayout(String orderJson) {
        return runUi(
                () -> {
                    JSONObject out = new JSONObject();
                    try {
                        String current = LemurBaseSp.getBottomToolbarStyle();
                        if (!TextUtils.isEmpty(orderJson) && !"{}".equals(orderJson)) {
                            JSONObject opt = new JSONObject(orderJson);
                            String layout = opt.optString("layout", "");
                            if (TextUtils.isEmpty(layout) && opt.has("slots")) {
                                layout = slotsToLayout(opt.optJSONArray("slots"));
                            }
                            if (!TextUtils.isEmpty(layout)) {
                                LemurBaseSp.setBottomToolbarStyle(layout);
                                refreshToolbarLayout();
                                current = LemurBaseSp.getBottomToolbarStyle();
                            }
                        }
                        out.put("ok", true);
                        out.put("layout", current);
                        out.put("slots", layoutToSlots(current));
                    } catch (Exception e) {
                        try {
                            out.put("ok", false);
                            out.put("error", e.getMessage());
                        } catch (Exception ignored) {
                            // 保持已写入字段
                        }
                    }
                    return out.toString();
                });
    }

    static boolean setBarLayout(String layout) {
        return runUiBool(
                () -> {
                    if (TextUtils.isEmpty(layout)) {
                        return false;
                    }
                    LemurBaseSp.setBottomToolbarStyle(layout);
                    refreshToolbarLayout();
                    return true;
                });
    }

    private static void refreshToolbarLayout() {
        ChromeTabbedActivity activity = activity();
        if (activity == null) {
            return;
        }
        View toolbar = findByName(activity, "toolbar");
        if (toolbar instanceof ToolbarPhoneLemur) {
            ((ToolbarPhoneLemur) toolbar).updateToolbarButtonsContainer();
        }
        applyButtonVis(activity);
    }

    private static String slotsToLayout(JSONArray slots) {
        if (slots == null || slots.length() != 5) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            if (i > 0) {
                sb.append("-");
            }
            sb.append(slotType(slots.optString(i)));
        }
        return sb.toString();
    }

    private static JSONArray layoutToSlots(String layout) throws Exception {
        JSONArray arr = new JSONArray();
        String[] parts = layout == null ? new String[0] : layout.split("-");
        String[] names = {"home", "tabs", "search", "tools", "menu"};
        int[] types = {1, 2, 3, 4, 5};
        if (parts.length == 5) {
            for (int i = 0; i < 5; i++) {
                try {
                    types[i] = Integer.parseInt(parts[i]);
                    names[i] = typeName(types[i]);
                } catch (Exception ignored) {
                    // 保持默认
                }
            }
        }
        for (int i = 0; i < 5; i++) {
            JSONObject row = new JSONObject();
            row.put("name", names[i]);
            row.put("type", types[i]);
            arr.put(row);
        }
        return arr;
    }

    private static int slotType(String name) {
        switch (name == null ? "" : name) {
            case "home":
            case "1":
                return 1;
            case "tabs":
            case "switch":
            case "2":
                return 2;
            case "search":
            case "3":
                return 3;
            case "tools":
            case "tool":
            case "4":
                return 4;
            case "menu":
            case "5":
                return 5;
            case "back":
            case "6":
                return 6;
            case "forward":
            case "7":
                return 7;
            default:
                return 0;
        }
    }

    private static String typeName(int type) {
        switch (type) {
            case 1:
                return "home";
            case 2:
                return "tabs";
            case 3:
                return "search";
            case 4:
                return "tools";
            case 5:
                return "menu";
            case 6:
                return "back";
            case 7:
                return "forward";
            default:
                return "unknown";
        }
    }

    private static int viewId(ChromeTabbedActivity activity, String name) {
        return activity.getResources().getIdentifier(name, "id", activity.getPackageName());
    }

    private static View findByName(ChromeTabbedActivity activity, String name) {
        if (activity == null || TextUtils.isEmpty(name)) {
            return null;
        }
        int id = viewId(activity, name);
        return id == 0 ? null : activity.findViewById(id);
    }

    private static void setViewVis(ChromeTabbedActivity activity, String name, int vis) {
        View view = findByName(activity, name);
        if (view != null) {
            view.setVisibility(vis);
        }
    }

    private static String controlsName(int state) {
        if (state == BrowserControlsState.HIDDEN) {
            return "hidden";
        }
        if (state == BrowserControlsState.SHOWN) {
            return "shown";
        }
        return "both";
    }

    private static double zoomPercent(Tab tab) {
        WebContents webContents = tab.getWebContents();
        if (webContents == null || webContents.isDestroyed()) {
            return 100.0;
        }
        return Math.pow(HostZoomMap.TEXT_SIZE_MULTIPLIER_RATIO, HostZoomMap.getZoomLevel(webContents))
                * 100.0;
    }

    @Nullable
    private static ChromeTabbedActivity activity() {
        if (sActivity != null && !sActivity.isDestroyed() && !sActivity.isFinishing()) {
            return sActivity;
        }
        return LemurXBridge.currentActivity();
    }

    @Nullable
    private static ToolbarManager toolbar() {
        ChromeTabbedActivity activity = activity();
        return activity == null ? null : activity.getToolbarManager();
    }

    @Nullable
    private static Tab findTab(int tabId) {
        ChromeTabbedActivity activity = activity();
        return LemurXBridge.findTabForLua(activity, tabId);
    }

    private static String runUi(java.util.concurrent.Callable<String> task) {
        String value = ThreadUtils.runOnUiThreadBlockingNoException(task);
        return value == null ? "" : value;
    }

    private static boolean runUiBool(java.util.concurrent.Callable<Boolean> task) {
        Boolean value = ThreadUtils.runOnUiThreadBlockingNoException(task);
        return value != null && value;
    }
}
