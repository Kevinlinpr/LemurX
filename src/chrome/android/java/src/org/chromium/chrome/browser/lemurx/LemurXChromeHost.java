// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.appcompat.content.res.AppCompatResources;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.Nullable;
import org.chromium.cc.input.BrowserControlsState;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.browser.SwipeRefreshHandler;
import org.chromium.chrome.browser.app.appmenu.AppMenuItemTheme;
import org.chromium.chrome.browser.app.appmenu.AppMenuItemUtils;
import org.chromium.chrome.browser.app.appmenu.AppMenuPropertiesDelegateImpl;
import org.chromium.chrome.browser.night_mode.GlobalNightModeStateProviderHolder;
import org.chromium.chrome.browser.night_mode.ThemeType;
import org.chromium.chrome.browser.omnibox.OmniboxStub;
import org.chromium.chrome.browser.omnibox.UrlFocusChangeListener;
import org.chromium.chrome.browser.preferences.ChromePreferenceKeys;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
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
import org.chromium.chrome.browser.toolbar.bottom.BottomControlsCoordinator;
import org.chromium.chrome.browser.ui.appmenu.AppMenuHandler;
import org.chromium.chrome.browser.ui.appmenu.AppMenuItemProperties;
import org.chromium.chrome.browser.ui.appmenu.AppMenuItemWithSubmenuProperties;
import org.chromium.components.browser_ui.site_settings.WebsitePreferenceBridge;
import org.chromium.components.browser_ui.util.BrowserControlsVisibilityDelegate;
import org.chromium.components.content_settings.ContentSettingsType;
import org.chromium.components.omnibox.AutocompleteInput;
import org.chromium.components.omnibox.OmniboxFocusReason;
import org.chromium.content_public.browser.HostZoomMap;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.modelutil.MVCListAdapter;
import org.chromium.ui.modelutil.MVCListAdapter.ListItem;
import org.chromium.ui.modelutil.PropertyModel;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 浏览器外壳：工具栏 / 地址栏 / 三点菜单 / 返回键 / 站点控制。
 * Lua 通过 {@link LemurXBridge} 调用这里的方法。
 *
 * <p>lemurx.menu.* 直接挂在 Chromium 原生三点菜单（app menu）上：
 * {@link #decorateAppMenu} 由 TabbedAppMenuPropertiesDelegate 在构建菜单模型时调用，
 * {@link #handleAppMenuClick} 由 ChromeTabbedActivity.onMenuOrKeyboardAction 首先调用。
 * 菜单项的「动作名」就是 R.id 的资源名（如 new_tab_menu_id、preferences_id）。
 */
public class LemurXChromeHost {
    private static final String TAG = "LemurX";
    /** 内建的「Lua 教程」菜单项动作名（可用 lemurx.menu.hide 隐藏）。 */
    static final String LUA_TUTORIAL_ACTION = "lua_tutorial";

    private static ChromeTabbedActivity sActivity;
    private static BrowserControlsVisibilityDelegate sControlsDelegate;
    private static boolean sControlsAdded;
    private static TabModelSelectorTabModelObserver sTabObserver;
    private static UrlFocusChangeListener sOmniboxListener;
    private static OmniboxStub sOmniboxListenerStub;
    private static boolean sBackIntercept;
    private static boolean sSkipBackIntercept;
    private static boolean sSkipMenuIntercept;
    private static int sControlsState = BrowserControlsState.BOTH;
    private static int sLuaTutorialViewId;
    private static boolean sBottomToolbarHidden;

    private static final List<LuaMenuItem> sLuaMenus = new ArrayList<>();
    private static final Set<String> sHiddenMenuActions = new HashSet<>();
    private static final Set<String> sInterceptMenuActions = new HashSet<>();
    /** 原生动作名 → Lua 注册拦截时传入的原始名字集合（含别名）。 */
    private static final Map<String, Set<String>> sInterceptRawNames = new HashMap<>();
    private static final Map<String, Integer> sButtonVis = new HashMap<>();

    /** 原生三点菜单：动作名（R.id 资源名）→ id。懒加载，R.id 在 library 构建里不是编译期常量。 */
    private static @Nullable Map<String, Integer> sStockMenuIds;

    /** 常用别名 → 原生动作名。 */
    private static final Map<String, String> MENU_ALIASES = new HashMap<>();

    static {
        MENU_ALIASES.put("new_tab", "new_tab_menu_id");
        MENU_ALIASES.put("newtab", "new_tab_menu_id");
        MENU_ALIASES.put("incognito", "new_incognito_tab_menu_id");
        MENU_ALIASES.put("new_incognito_tab", "new_incognito_tab_menu_id");
        MENU_ALIASES.put("new_window", "new_window_menu_id");
        MENU_ALIASES.put("bookmark", "bookmark_this_page_id");
        MENU_ALIASES.put("bookmarks", "all_bookmarks_menu_id");
        MENU_ALIASES.put("history", "open_history_menu_id");
        MENU_ALIASES.put("downloads", "downloads_menu_id");
        MENU_ALIASES.put("download_page", "offline_page_id");
        MENU_ALIASES.put("recent_tabs", "recent_tabs_menu_id");
        MENU_ALIASES.put("share", "share_menu_id");
        MENU_ALIASES.put("find", "find_in_page_id");
        MENU_ALIASES.put("find_in_page", "find_in_page_id");
        MENU_ALIASES.put("desktop", "request_desktop_site_id");
        MENU_ALIASES.put("request_desktop_site", "request_desktop_site_id");
        MENU_ALIASES.put("settings", "preferences_id");
        MENU_ALIASES.put("preferences", "preferences_id");
        MENU_ALIASES.put("help", "help_id");
        MENU_ALIASES.put("reload", "reload_menu_id");
        MENU_ALIASES.put("refresh", "reload_menu_id");
        MENU_ALIASES.put("forward", "forward_menu_id");
        MENU_ALIASES.put("back", "back_menu_id");
        MENU_ALIASES.put("info", "info_menu_id");
        MENU_ALIASES.put("page_info", "info_menu_id");
        MENU_ALIASES.put("translate", "translate_id");
        MENU_ALIASES.put("print", "print_id");
        MENU_ALIASES.put("zoom", "page_zoom_id");
        MENU_ALIASES.put("reader", "reader_mode_menu_id");
        MENU_ALIASES.put("reader_mode", "reader_mode_menu_id");
        MENU_ALIASES.put("add_to_home", "universal_install");
        MENU_ALIASES.put("add_to_homescreen", "universal_install");
        MENU_ALIASES.put("quick_delete", "quick_delete_menu_id");
        MENU_ALIASES.put("clear_data", "quick_delete_menu_id");
        MENU_ALIASES.put("open_with", "open_with_id");
        MENU_ALIASES.put("home", "homepage_menu_id");
        MENU_ALIASES.put("homepage", "homepage_menu_id");
        MENU_ALIASES.put("extensions", "manage_extensions_menu_id");
        MENU_ALIASES.put("auto_dark", "auto_dark_web_contents_id");
        MENU_ALIASES.put("close_all_tabs", "close_all_tabs_menu_id");
        MENU_ALIASES.put("select_tabs", "menu_select_tabs");
        MENU_ALIASES.put("new_tab_group", "new_tab_group_menu_id");
        MENU_ALIASES.put("add_to_group", "add_to_group_menu_id");
        MENU_ALIASES.put("reading_list", "add_to_reading_list_menu_id");
        MENU_ALIASES.put("read_aloud", "readaloud_menu_id");
        MENU_ALIASES.put("about", "about_chrome_menu_id");
        MENU_ALIASES.put("tutorial", LUA_TUTORIAL_ACTION);
    }

    private static class LuaMenuItem {
        String id;
        String title;
        String icon;
        String page;
        /** View.generateViewId() 分配的稳定 id，作为 AppMenuItemProperties.MENU_ITEM_ID。 */
        int viewId;
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
            if (sOmniboxListenerStub != null && sOmniboxListener != null) {
                try {
                    sOmniboxListenerStub.removeUrlFocusChangeListener(sOmniboxListener);
                } catch (Exception e) {
                    // 旧窗口可能已销毁
                }
            }
            sOmniboxListener = null;
            sOmniboxListenerStub = null;
            sControlsAdded = false;
        }
        sActivity = activity;
        ensureControlsDelegate(activity);
        ensureTabObserver(activity);
        ensureOmniboxListener(activity);
        applyButtonVis(activity);
        applyBottomToolbarVis(activity);
    }

    // ---------------------------------------------------------------- 三点菜单（app menu）

    /**
     * TabbedAppMenuPropertiesDelegate.buildMenuModelList() 收尾时调用（UI 线程）。
     * 1) 删除 Lua 隐藏的原生项（含图标行里的按钮、子菜单里的项）；
     * 2) 在页面菜单末尾追加「Lua 教程」和 Lua 注册的菜单项。
     *
     * @param modelList 已构建好的菜单模型
     * @param menuGroup AppMenuPropertiesDelegateImpl.MenuGroup.*
     */
    public static void decorateAppMenu(MVCListAdapter.ModelList modelList, int menuGroup) {
        if (modelList == null) {
            return;
        }
        try {
            if (!sHiddenMenuActions.isEmpty()) {
                removeHiddenMenuItems(modelList);
            }
            boolean pageMenu = menuGroup == AppMenuPropertiesDelegateImpl.MenuGroup.PAGE_MENU;
            boolean overviewMenu =
                    menuGroup == AppMenuPropertiesDelegateImpl.MenuGroup.OVERVIEW_MODE_MENU;
            if (!pageMenu && !overviewMenu) {
                return;
            }
            ChromeTabbedActivity activity = activity();
            Context context = activity != null ? activity : ContextUtils.getApplicationContext();
            AppMenuItemTheme theme = null;
            TabModelSelector selector = selectorOf(activity);
            if (selector != null) {
                theme = new AppMenuItemTheme(context, selector);
            }
            List<ListItem> extra = new ArrayList<>();
            if (pageMenu && !sHiddenMenuActions.contains(LUA_TUTORIAL_ACTION)) {
                if (sLuaTutorialViewId == 0) {
                    sLuaTutorialViewId = View.generateViewId();
                }
                extra.add(
                        buildLuaListItem(
                                context, theme, sLuaTutorialViewId, "Lua 教程", null));
            }
            for (LuaMenuItem lua : sLuaMenus) {
                boolean wantOverview = "overview".equals(lua.page);
                if (pageMenu == wantOverview) {
                    continue;
                }
                if (sHiddenMenuActions.contains(lua.id)) {
                    continue;
                }
                extra.add(buildLuaListItem(context, theme, lua.viewId, lua.title, lua.icon));
            }
            if (extra.isEmpty()) {
                return;
            }
            AppMenuItemUtils.maybeAddDividerLine(modelList, R.id.divider_line_id);
            for (ListItem item : extra) {
                modelList.add(item);
            }
        } catch (Exception e) {
            logi("decorateAppMenu", e.getMessage());
        }
    }

    private static ListItem buildLuaListItem(
            Context context,
            @Nullable AppMenuItemTheme theme,
            int viewId,
            String title,
            @Nullable String iconName) {
        PropertyModel.Builder builder;
        if (theme != null) {
            builder = AppMenuItemUtils.buildBaseModelForTextItem(theme, viewId, false);
        } else {
            builder =
                    new PropertyModel.Builder(AppMenuItemProperties.ALL_KEYS)
                            .with(AppMenuItemProperties.MENU_ITEM_ID, viewId)
                            .with(AppMenuItemProperties.ENABLED, true)
                            .with(AppMenuItemProperties.MENU_ICON_AT_START, false);
        }
        PropertyModel model =
                builder.with(AppMenuItemProperties.TITLE, title == null ? "" : title).build();
        Drawable icon = resolveDrawable(context, iconName);
        if (icon != null) {
            model.set(AppMenuItemProperties.ICON, icon);
        }
        return new ListItem(AppMenuHandler.AppMenuItemType.STANDARD, model);
    }

    private static @Nullable Drawable resolveDrawable(Context context, @Nullable String name) {
        if (TextUtils.isEmpty(name)) {
            return null;
        }
        try {
            int resId =
                    context.getResources()
                            .getIdentifier(name, "drawable", context.getPackageName());
            return resId == 0 ? null : AppCompatResources.getDrawable(context, resId);
        } catch (Exception e) {
            return null;
        }
    }

    private static void removeHiddenMenuItems(MVCListAdapter.ModelList modelList) {
        for (int i = modelList.size() - 1; i >= 0; i--) {
            ListItem item = modelList.get(i);
            PropertyModel model = item.model;
            if (model == null) {
                continue;
            }
            if (isHiddenMenuModel(model)) {
                modelList.removeAt(i);
                continue;
            }
            // 图标行（前进/书签/下载/信息/刷新）：按钮在 ADDITIONAL_ICONS 里
            if (model.containsKey(AppMenuItemProperties.ADDITIONAL_ICONS)) {
                MVCListAdapter.ModelList icons = model.get(AppMenuItemProperties.ADDITIONAL_ICONS);
                if (icons != null) {
                    for (int j = icons.size() - 1; j >= 0; j--) {
                        ListItem icon = icons.get(j);
                        if (icon.model != null && isHiddenMenuModel(icon.model)) {
                            icons.removeAt(j);
                        }
                    }
                    if (icons.isEmpty()
                            && item.type == AppMenuHandler.AppMenuItemType.BUTTON_ROW) {
                        modelList.removeAt(i);
                        continue;
                    }
                }
            }
            // 子菜单：包一层 Supplier，展开时再过滤
            if (model.containsKey(AppMenuItemWithSubmenuProperties.SUBMENU_PROVIDER)) {
                Supplier<List<ListItem>> provider =
                        model.get(AppMenuItemWithSubmenuProperties.SUBMENU_PROVIDER);
                if (provider != null) {
                    model.set(
                            AppMenuItemWithSubmenuProperties.SUBMENU_PROVIDER,
                            () -> {
                                List<ListItem> items = provider.get();
                                if (items == null) {
                                    return items;
                                }
                                List<ListItem> out = new ArrayList<>();
                                for (ListItem sub : items) {
                                    if (sub == null
                                            || sub.model == null
                                            || !isHiddenMenuModel(sub.model)) {
                                        out.add(sub);
                                    }
                                }
                                return out;
                            });
                }
            }
        }
    }

    private static boolean isHiddenMenuModel(PropertyModel model) {
        if (!model.containsKey(AppMenuItemProperties.MENU_ITEM_ID)) {
            return false;
        }
        int id = model.get(AppMenuItemProperties.MENU_ITEM_ID);
        if (id == 0) {
            return false;
        }
        return sHiddenMenuActions.contains(menuActionName(id));
    }

    /**
     * ChromeTabbedActivity.onMenuOrKeyboardAction 的第一条语句（UI 线程）。
     *
     * @return true 表示已被 Lua 消费（Lua 自己的菜单项、教程入口，或被 lemurx.menu.on 拦截的原生项）
     */
    public static boolean handleAppMenuClick(int id) {
        if (id == 0) {
            return false;
        }
        try {
            if (sLuaTutorialViewId != 0 && id == sLuaTutorialViewId) {
                LemurXBridge.openTutorial();
                return true;
            }
            LuaMenuItem lua = findLuaMenuByViewId(id);
            if (lua != null) {
                if (sSkipMenuIntercept) {
                    return false;
                }
                LemurXBridge.dispatchUiClickNative("menu:" + lua.id);
                JSONObject ev = new JSONObject();
                try {
                    ev.put("id", lua.id);
                    ev.put("title", lua.title);
                    ev.put("lua", true);
                } catch (Exception e) {
                    // 保持已写入字段
                }
                LemurXBridge.dispatchTabEventNative("menu", ev.toString());
                return true;
            }
            if (sSkipMenuIntercept || sInterceptMenuActions.isEmpty()) {
                return false;
            }
            String actionName = menuActionName(id);
            if (TextUtils.isEmpty(actionName) || !sInterceptMenuActions.contains(actionName)) {
                return false;
            }
            JSONObject ev = new JSONObject();
            try {
                ev.put("id", actionName);
                ev.put("title", actionName);
                ev.put("lua", false);
            } catch (Exception e) {
                // 保持已写入字段
            }
            String json = ev.toString();
            // 既按原生资源名发，也按 Lua 注册时用的原始名字（别名）发
            Set<String> fired = new HashSet<>();
            fired.add(actionName);
            LemurXBridge.dispatchTabEventNative("menu:" + actionName, json);
            Set<String> raw = sInterceptRawNames.get(actionName);
            if (raw != null) {
                for (String name : raw) {
                    if (fired.add(name)) {
                        LemurXBridge.dispatchTabEventNative("menu:" + name, json);
                    }
                }
            }
            LemurXBridge.dispatchTabEventNative("menu", json);
            return true;
        } catch (Exception e) {
            logi("handleAppMenuClick", e.getMessage());
            return false;
        }
    }

    private static @Nullable LuaMenuItem findLuaMenuByViewId(int viewId) {
        for (LuaMenuItem item : sLuaMenus) {
            if (item.viewId == viewId) {
                return item;
            }
        }
        return null;
    }

    private static @Nullable LuaMenuItem findLuaMenuById(String id) {
        if (TextUtils.isEmpty(id)) {
            return null;
        }
        for (LuaMenuItem item : sLuaMenus) {
            if (id.equals(item.id)) {
                return item;
            }
        }
        return null;
    }

    /** 原生菜单 R.id 表（资源名 → id）。只列 154 里确实存在的 id。 */
    private static Map<String, Integer> stockMenuIds() {
        if (sStockMenuIds != null) {
            return sStockMenuIds;
        }
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("new_tab_menu_id", R.id.new_tab_menu_id);
        m.put("new_incognito_tab_menu_id", R.id.new_incognito_tab_menu_id);
        m.put("new_window_menu_id", R.id.new_window_menu_id);
        m.put("new_incognito_window_menu_id", R.id.new_incognito_window_menu_id);
        m.put("move_to_other_window_menu_id", R.id.move_to_other_window_menu_id);
        m.put("manage_all_windows_menu_id", R.id.manage_all_windows_menu_id);
        m.put("back_menu_id", R.id.back_menu_id);
        m.put("forward_menu_id", R.id.forward_menu_id);
        m.put("bookmark_this_page_id", R.id.bookmark_this_page_id);
        m.put("offline_page_id", R.id.offline_page_id);
        m.put("info_menu_id", R.id.info_menu_id);
        m.put("reload_menu_id", R.id.reload_menu_id);
        m.put("open_history_menu_id", R.id.open_history_menu_id);
        m.put("quick_delete_menu_id", R.id.quick_delete_menu_id);
        m.put("homepage_menu_id", R.id.homepage_menu_id);
        m.put("downloads_menu_id", R.id.downloads_menu_id);
        m.put("all_bookmarks_menu_id", R.id.all_bookmarks_menu_id);
        m.put("recent_tabs_menu_id", R.id.recent_tabs_menu_id);
        m.put("manage_extensions_menu_id", R.id.manage_extensions_menu_id);
        m.put("page_zoom_id", R.id.page_zoom_id);
        m.put("share_menu_id", R.id.share_menu_id);
        m.put("download_page_id", R.id.download_page_id);
        m.put("print_id", R.id.print_id);
        m.put("find_in_page_id", R.id.find_in_page_id);
        m.put("translate_id", R.id.translate_id);
        m.put("readaloud_menu_id", R.id.readaloud_menu_id);
        m.put("reader_mode_menu_id", R.id.reader_mode_menu_id);
        m.put("open_with_id", R.id.open_with_id);
        m.put("universal_install", R.id.universal_install);
        m.put("open_in_app_menu_id", R.id.open_in_app_menu_id);
        m.put("request_desktop_site_id", R.id.request_desktop_site_id);
        m.put("auto_dark_web_contents_id", R.id.auto_dark_web_contents_id);
        m.put("paint_preview_show_id", R.id.paint_preview_show_id);
        m.put("get_image_descriptions_id", R.id.get_image_descriptions_id);
        m.put("preferences_id", R.id.preferences_id);
        m.put("help_id", R.id.help_id);
        m.put("add_to_group_menu_id", R.id.add_to_group_menu_id);
        m.put("add_to_reading_list_menu_id", R.id.add_to_reading_list_menu_id);
        m.put("about_chrome_menu_id", R.id.about_chrome_menu_id);
        m.put("more_tools_menu_id", R.id.more_tools_menu_id);
        m.put("close_all_tabs_menu_id", R.id.close_all_tabs_menu_id);
        m.put("close_all_incognito_tabs_menu_id", R.id.close_all_incognito_tabs_menu_id);
        m.put("menu_select_tabs", R.id.menu_select_tabs);
        m.put("new_tab_group_menu_id", R.id.new_tab_group_menu_id);
        m.put("lens_overlay_menu_id", R.id.lens_overlay_menu_id);
        m.put("enable_price_tracking_menu_id", R.id.enable_price_tracking_menu_id);
        m.put("disable_price_tracking_menu_id", R.id.disable_price_tracking_menu_id);
        m.put("ntp_customization_id", R.id.ntp_customization_id);
        m.put("bookmarks_parent_menu_id", R.id.bookmarks_parent_menu_id);
        m.put("history_parent_menu_id", R.id.history_parent_menu_id);
        m.put("save_and_share_parent_menu_id", R.id.save_and_share_parent_menu_id);
        m.put("help_parent_menu_id", R.id.help_parent_menu_id);
        m.put("extensions_parent_menu_id", R.id.extensions_parent_menu_id);
        m.put("tab_groups_parent_menu_id", R.id.tab_groups_parent_menu_id);
        m.put("update_menu_id", R.id.update_menu_id);
        m.put("managed_by_menu_id", R.id.managed_by_menu_id);
        m.put("task_manager", R.id.task_manager);
        m.put("dev_tools", R.id.dev_tools);
        m.put("glic_menu_id", R.id.glic_menu_id);
        m.put("listen_to_feed_id", R.id.listen_to_feed_id);
        m.put("icon_row_menu_id", R.id.icon_row_menu_id);
        sStockMenuIds = m;
        return m;
    }

    /** 动作名 → 菜单 id。支持：Lua 项 id、别名、原生资源名、任意 R.id 资源名（运行时查找）。 */
    static int resolveMenuId(String actionName) {
        if (TextUtils.isEmpty(actionName)) {
            return 0;
        }
        LuaMenuItem lua = findLuaMenuById(actionName);
        if (lua != null) {
            return lua.viewId;
        }
        if (LUA_TUTORIAL_ACTION.equals(actionName)) {
            return sLuaTutorialViewId;
        }
        String name = actionName;
        String alias = MENU_ALIASES.get(name);
        if (alias != null) {
            name = alias;
        }
        Integer stock = stockMenuIds().get(name);
        if (stock != null && stock != 0) {
            return stock;
        }
        try {
            Context ctx = ContextUtils.getApplicationContext();
            return ctx.getResources().getIdentifier(name, "id", ctx.getPackageName());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 菜单 id → 动作名（R.id 资源名）。 */
    static String menuActionName(int id) {
        if (id == 0) {
            return "";
        }
        LuaMenuItem lua = findLuaMenuByViewId(id);
        if (lua != null) {
            return lua.id;
        }
        if (sLuaTutorialViewId != 0 && id == sLuaTutorialViewId) {
            return LUA_TUTORIAL_ACTION;
        }
        for (Map.Entry<String, Integer> entry : stockMenuIds().entrySet()) {
            if (entry.getValue() == id) {
                return entry.getKey();
            }
        }
        try {
            return ContextUtils.getApplicationContext().getResources().getResourceEntryName(id);
        } catch (Exception e) {
            return "id_" + id;
        }
    }

    // ---------------------------------------------------------------- lemurx.chrome.*

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
                    // 154：地址栏输入会话统一走 AutocompleteInput
                    AutocompleteInput input =
                            new AutocompleteInput(OmniboxFocusReason.MENU_OR_KEYBOARD_ACTION);
                    input.setUserText(text == null ? "" : text);
                    toolbar.beginFuseboxInput(input);
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
                    if (focused) {
                        toolbar.beginFuseboxInput(
                                new AutocompleteInput(OmniboxFocusReason.MENU_OR_KEYBOARD_ACTION));
                    } else {
                        toolbar.endFuseboxInput();
                    }
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

    /** 深色模式：写原生 UI_THEME_SETTING 偏好，GlobalNightModeStateController 会监听并切换。 */
    static boolean setDarkMode(boolean dark) {
        return runUiBool(
                () -> {
                    ChromeSharedPreferences.getInstance()
                            .writeInt(
                                    ChromePreferenceKeys.UI_THEME_SETTING,
                                    dark ? ThemeType.DARK : ThemeType.LIGHT);
                    return true;
                });
    }

    /** 深色模式改回「跟随系统」。 */
    static boolean setDarkModeSystemDefault() {
        return runUiBool(
                () -> {
                    ChromeSharedPreferences.getInstance()
                            .writeInt(
                                    ChromePreferenceKeys.UI_THEME_SETTING,
                                    ThemeType.SYSTEM_DEFAULT);
                    return true;
                });
    }

    static boolean isDarkMode() {
        return runUiBool(
                () -> GlobalNightModeStateProviderHolder.getInstance().isInNightMode());
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
                    sButtonVis.put("menu", vis);
                    boolean any = false;
                    ToolbarManager toolbar = toolbar();
                    View menuButton = toolbar == null ? null : toolbar.getMenuButtonView();
                    if (menuButton != null) {
                        menuButton.setVisibility(vis);
                        any = true;
                    }
                    View wrapper = findByName(activity, "menu_button_wrapper");
                    if (wrapper != null) {
                        wrapper.setVisibility(vis);
                        any = true;
                    }
                    return any;
                });
    }

    /**
     * 原生 154 手机版唯一的「底栏」是标签组条（BottomControls / TabGroupUi）。
     * 这里控制它的显隐；LemurXSkinHost / LemurXWidgetHost 也会调用。
     */
    static boolean hideBottomToolbar(boolean hidden) {
        return runUiBool(
                () -> {
                    ChromeTabbedActivity activity = activity();
                    if (activity == null) {
                        return false;
                    }
                    sBottomToolbarHidden = hidden;
                    return applyBottomToolbarVis(activity);
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
                    if ("menu".equals(name)) {
                        ToolbarManager toolbar = toolbar();
                        View menuButton = toolbar == null ? null : toolbar.getMenuButtonView();
                        if (menuButton != null) {
                            menuButton.setVisibility(vis);
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
                        o.put(
                                "darkMode",
                                GlobalNightModeStateProviderHolder.getInstance().isInNightMode());
                        o.put("urlBarFocused", toolbar != null && toolbar.isUrlBarFocused());
                        o.put("bottomToolbarHidden", sBottomToolbarHidden);
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
                        o.put("javascript", isJavaScriptEnabledOnUi());
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
                        logi("chrome.info", e.getMessage());
                    }
                    return o.toString();
                });
    }

    // ---------------------------------------------------------------- lemurx.menu.*

    /**
     * options: {id, title, icon(drawable 资源名，可选), page("main"|"overview"，默认 main)}。
     * 返回菜单项 id（字符串）；对应的点击通过 dispatchUiClick("menu:"+id) 回到 Lua。
     */
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
                        item.viewId = View.generateViewId();
                        if (TextUtils.isEmpty(item.id)) {
                            item.id = "lua_menu_" + item.viewId;
                        }
                        item.title = options.optString("title", item.id);
                        item.icon = options.optString("icon", "");
                        item.page = options.optString("page", "main");
                        if (!"overview".equals(item.page)) {
                            item.page = "main";
                        }
                        Iterator<LuaMenuItem> it = sLuaMenus.iterator();
                        while (it.hasNext()) {
                            LuaMenuItem old = it.next();
                            if (item.id.equals(old.id)) {
                                // 同 id 重注册：沿用旧的 viewId，保持稳定
                                item.viewId = old.viewId;
                                it.remove();
                            }
                        }
                        sLuaMenus.add(item);
                        return item.id;
                    } catch (Exception e) {
                        logi("menu.add", e.getMessage());
                        return "";
                    }
                });
    }

    static boolean menuRemove(String id) {
        return runUiBool(
                () -> {
                    if (TextUtils.isEmpty(id)) {
                        return false;
                    }
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
        runUiBool(
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
                    String name = MENU_ALIASES.containsKey(actionName)
                            ? MENU_ALIASES.get(actionName)
                            : actionName;
                    if (hidden) {
                        sHiddenMenuActions.add(name);
                    } else {
                        sHiddenMenuActions.remove(name);
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
                    String name = MENU_ALIASES.containsKey(actionName)
                            ? MENU_ALIASES.get(actionName)
                            : actionName;
                    if (intercept) {
                        sInterceptMenuActions.add(name);
                        // Lua 侧 lemurx.menu.on(name) 用的是原始名字（可能是别名）注册回调，
                        // 点击时要按原始名字回发 "menu:<name>"，这里记住它
                        Set<String> raw = sInterceptRawNames.get(name);
                        if (raw == null) {
                            raw = new HashSet<>();
                            sInterceptRawNames.put(name, raw);
                        }
                        raw.add(actionName);
                    } else {
                        sInterceptMenuActions.remove(name);
                        sInterceptRawNames.remove(name);
                    }
                    return true;
                });
    }

    /** 触发一条原生菜单动作（等价于用户点了三点菜单里的这一项）；Lua 拦截在此期间不生效。 */
    static boolean menuInvoke(String actionName) {
        return runUiBool(
                () -> {
                    ChromeTabbedActivity activity = activity();
                    if (activity == null || TextUtils.isEmpty(actionName)) {
                        return false;
                    }
                    if (LUA_TUTORIAL_ACTION.equals(actionName)) {
                        LemurXBridge.openTutorial();
                        return true;
                    }
                    int id = resolveMenuId(actionName);
                    if (id == 0) {
                        return false;
                    }
                    LuaMenuItem lua = findLuaMenuByViewId(id);
                    if (lua != null) {
                        // Lua 自己的项：直接走 Lua 回调
                        return handleAppMenuClick(id);
                    }
                    sSkipMenuIntercept = true;
                    try {
                        return activity.onMenuOrKeyboardAction(
                                id, /* fromMenu= */ true, null, null);
                    } finally {
                        sSkipMenuIntercept = false;
                    }
                });
    }

    /** {ok, stock:[{id,title?}], lua:[{id,title,page,lua:true}], hidden:[], intercept:[]} */
    static String menuList() {
        return runUi(
                () -> {
                    JSONObject out = new JSONObject();
                    try {
                        JSONArray stock = new JSONArray();
                        for (Map.Entry<String, Integer> entry : stockMenuIds().entrySet()) {
                            if (entry.getValue() == 0) {
                                continue;
                            }
                            JSONObject row = new JSONObject();
                            row.put("id", entry.getKey());
                            row.put("action", entry.getValue());
                            row.put("hidden", sHiddenMenuActions.contains(entry.getKey()));
                            row.put("intercept", sInterceptMenuActions.contains(entry.getKey()));
                            stock.put(row);
                        }
                        JSONArray aliases = new JSONArray();
                        for (Map.Entry<String, String> entry : MENU_ALIASES.entrySet()) {
                            JSONObject row = new JSONObject();
                            row.put("alias", entry.getKey());
                            row.put("id", entry.getValue());
                            aliases.put(row);
                        }
                        JSONArray lua = new JSONArray();
                        for (LuaMenuItem item : sLuaMenus) {
                            JSONObject o = new JSONObject();
                            o.put("id", item.id);
                            o.put("title", item.title);
                            o.put("page", item.page);
                            o.put("action", item.viewId);
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
                        out.put("stock", stock);
                        out.put("aliases", aliases);
                        out.put("lua", lua);
                        out.put("hidden", hidden);
                        out.put("intercept", intercept);
                        out.put("tutorial", LUA_TUTORIAL_ACTION);
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

    // ---------------------------------------------------------------- 返回键

    static void setBackIntercept(boolean enabled) {
        ThreadUtils.postOnUiThread(() -> sBackIntercept = enabled);
    }

    /**
     * 无副作用地回答"此刻 Lua 会不会吞掉返回"。BackPressManager 在预测返回手势 *开始* 时
     * 就问这个：Lua 要拦的话，Chrome 自带的 handler（TabOnBackGestureHandler 会把页面
     * 跟手滑出去）根本不启动，否则提交时被 Lua 吞掉，那个过渡动画既没提交也没取消，
     * 页面就卡在左边漏一条的位置，下一次手势也回不去。
     */
    static boolean isBackInterceptActive() {
        return sBackIntercept && !sSkipBackIntercept;
    }

    /**
     * BackPressManager 每次返回键先问这里（UI 线程）。
     * Lua 没开启拦截时必须极快、无副作用。
     */
    static boolean consumeBack() {
        if (!isBackInterceptActive()) {
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

    // ---------------------------------------------------------------- 站点控制

    static boolean setDesktop(int tabId, boolean desktop) {
        return runUiBool(
                () -> {
                    Tab tab = findTab(tabId);
                    if (tab == null || tab.getWebContents() == null) {
                        return false;
                    }
                    TabUtils.switchUserAgent(tab, desktop);
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
                runUiValue(
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
        return runUiBool(LemurXChromeHost::isJavaScriptEnabledOnUi);
    }

    private static boolean isJavaScriptEnabledOnUi() {
        return WebsitePreferenceBridge.isContentSettingEnabled(
                ProfileManager.getLastUsedRegularProfile(), ContentSettingsType.JAVASCRIPT);
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

    // ---------------------------------------------------------------- 内部

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
                logi("controls delegate", e.getMessage());
            }
        }
        sControlsDelegate.set(sControlsState);
    }

    private static void ensureTabObserver(ChromeTabbedActivity activity) {
        TabModelSelector selector = selectorOf(activity);
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
                        safeNotify("created", tab);
                    }

                    @Override
                    public void willCloseTab(Tab tab, boolean didCloseAlone) {
                        safeNotify("closed", tab);
                    }

                    @Override
                    public void didSelectTab(Tab tab, @TabSelectionType int type, int lastId) {
                        safeNotify("selected", tab);
                        if (tab != null) {
                            LemurXWidgetHost.onNativeTabSelected(tab.getId());
                        }
                    }

                    // 这些回调跑在 TabModel 观察者遍历里：LemurX 这边的任何异常都
                    // 不能抛回 Chromium，否则整个 Activity 崩掉。
                    private void safeNotify(String name, Tab tab) {
                        if (tab == null) {
                            return;
                        }
                        try {
                            LemurXBridge.notifyTabEvent(name, tab, tab.getUrl());
                        } catch (Throwable e) {
                            logi("tab observer", name, e.toString());
                        }
                    }
                };
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
        sOmniboxListenerStub = stub;
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
            if ("menu".equals(entry.getKey())) {
                ToolbarManager toolbar = activity.getToolbarManager();
                View menuButton = toolbar == null ? null : toolbar.getMenuButtonView();
                if (menuButton != null) {
                    menuButton.setVisibility(entry.getValue());
                }
            }
        }
    }

    private static boolean applyBottomToolbarVis(ChromeTabbedActivity activity) {
        ToolbarManager toolbar = activity.getToolbarManager();
        if (toolbar == null) {
            return false;
        }
        try {
            BottomControlsCoordinator bottom =
                    toolbar.getTabGroupUiBottomControlsCoordinatorForTesting();
            if (bottom == null) {
                return false;
            }
            bottom.setBottomControlsVisible(!sBottomToolbarHidden);
            return true;
        } catch (Exception e) {
            logi("bottom controls", e.getMessage());
            return false;
        }
    }

    /** 逻辑名 → 原生工具栏 view id（chrome/android/java/res/layout/toolbar_phone.xml 等）。 */
    private static String[] buttonIdNames(String name) {
        if (TextUtils.isEmpty(name)) {
            return new String[0];
        }
        switch (name) {
            case "back":
                return new String[] {"back_button"};
            case "forward":
                return new String[] {"forward_button"};
            case "home":
                return new String[] {"home_button"};
            case "tabs":
            case "switch":
                return new String[] {"tab_switcher_button"};
            case "reload":
            case "refresh":
                return new String[] {"refresh_button"};
            case "bookmark":
                return new String[] {"bookmark_button"};
            case "search":
                return new String[] {"location_bar", "search_box"};
            case "menu":
                return new String[] {"menu_button_wrapper"};
            case "optional":
            case "toolbar_button":
                return new String[] {"optional_toolbar_button"};
            default:
                return new String[] {name};
        }
    }

    private static int viewId(ChromeTabbedActivity activity, String name) {
        return activity.getResources().getIdentifier(name, "id", activity.getPackageName());
    }

    private static @Nullable View findByName(@Nullable ChromeTabbedActivity activity, String name) {
        if (activity == null || TextUtils.isEmpty(name)) {
            return null;
        }
        int id = viewId(activity, name);
        return id == 0 ? null : activity.findViewById(id);
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

    private static @Nullable ChromeTabbedActivity activity() {
        if (sActivity != null && !sActivity.isDestroyed() && !sActivity.isFinishing()) {
            return sActivity;
        }
        return LemurXBridge.currentActivity();
    }

    private static @Nullable ToolbarManager toolbar() {
        ChromeTabbedActivity activity = activity();
        return activity == null ? null : activity.getToolbarManager();
    }

    private static @Nullable Tab findTab(int tabId) {
        ChromeTabbedActivity activity = activity();
        return LemurXBridge.findTabForLua(activity, tabId);
    }

    private static void logi(String... parts) {
        Log.i(TAG, "%s", TextUtils.join(" ", parts));
    }

    /** 在 UI 线程同步执行；异常吞掉返回 null（154 没有 runOnUiThreadBlockingNoException 了）。 */
    private static <T> @Nullable T runUiValue(java.util.concurrent.Callable<T> task) {
        try {
            return ThreadUtils.runOnUiThreadBlocking(task);
        } catch (Exception e) {
            logi("ui task", e.getMessage());
            return null;
        }
    }

    private static String runUi(java.util.concurrent.Callable<String> task) {
        String value = runUiValue(task);
        return value == null ? "" : value;
    }

    private static boolean runUiBool(java.util.concurrent.Callable<Boolean> task) {
        Boolean value = runUiValue(task);
        return value != null && value;
    }
}
