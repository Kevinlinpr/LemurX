package org.chromium.chrome.browser.lemurx;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.chromium.base.ContextUtils;
import org.chromium.base.ThreadUtils;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.lemurx.base.utils.LemurLogUtils;
import org.chromium.chrome.lemurx.dialog.BottomToolbarStyleManger;
import org.chromium.chrome.lemurx.utils.LemurThemeUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 让浏览器外壳真正可 Lua 编程：
 * <ul>
 *   <li>{@code lemurx.theme.set/get/reset}：一份持久化的皮肤（工具栏/底栏/状态栏颜色、图标色、
 *       暗色、底栏布局、隐藏按钮……），每次 Activity attach 自动重放。</li>
 *   <li>{@code lemurx.ui.render(slot, tree)}：把一棵声明式控件树挂到外壳的挂载点上
 *       （toolbar.start/end、bottom.bar/start、page.top/bottom/center/float、view:&lt;id&gt;）。
 *       节点上的 onClick/onChange/onLongClick 由 C++ 收进注册表，这里按 id 回调。</li>
 *   <li>{@code lemurx.ui.style/replace/insert/detach/move/on}：对任意原生 View 动刀，
 *       dump/find 扫 WindowManager 里所有窗口（工具抽屉 Dialog 也在内）。</li>
 * </ul>
 * 所有入口都由 Lua 线程调用，内部切到 UI 线程同步执行。
 */
public class LemurXSkinHost {
    private static final String TAG = "LemurX";
    private static final String PREFS = "lemurx_skin";
    private static final String KEY_THEME = "theme";
    private static final String SLOT_TAG_PREFIX = "lemurx_slot:";

    /** 一次 render / replace / insert 的结果。 */
    private static class Mount {
        String slot;
        JSONObject tree;
        View view;
        PopupWindow popup;
        final List<String> ids = new ArrayList<>();
        // 手术模式：null=挂载点 / "replace"=替换原生 View / "insert"=插到原生 View 旁
        String mode;
        JSONObject query;
        JSONObject opts = new JSONObject();
        // replace 时被换下来的原生 View，unmount 时放回去
        View replaced;
        ViewGroup replacedParent;
        int replacedIndex;
        ViewGroup.LayoutParams replacedLp;
    }

    /** ui.detach 摘下来的原生 View，restore 时放回。 */
    private static class Detached {
        View view;
        ViewGroup parent;
        int index;
        ViewGroup.LayoutParams lp;
    }

    private static final Map<String, Mount> sMounts = new LinkedHashMap<>();
    /** key = query json，value = {query, style} */
    private static final Map<String, JSONObject> sStyles = new LinkedHashMap<>();
    /** key = query json，value = 摘下的 View 列表；Activity 重建后按 query 重放 */
    private static final Map<String, List<Detached>> sDetached = new LinkedHashMap<>();
    /** key = query json，value = move 参数；Activity 重建后重放 */
    private static final Map<String, JSONObject> sMoves = new LinkedHashMap<>();
    /** key = 回调 key（on:n），value = {query, event, opts}；Activity 重建后重放 */
    private static final Map<String, JSONObject> sListeners = new LinkedHashMap<>();
    /** key = 回调 key，value = 已装监听的 View（弱引用）及 TextWatcher */
    private static final Map<String, List<Object[]>> sListenerViews = new LinkedHashMap<>();
    private static int sSurgeryCounter;
    private static JSONObject sTheme = new JSONObject();
    private static boolean sThemeLoaded;
    private static boolean sStyleListenerAdded;
    private static Drawable sBottomBarOriginalBg;
    private static boolean sBottomBarCaptured;
    private static WeakReference<Activity> sActivity = new WeakReference<>(null);

    static final String[] SLOTS = {
        "toolbar.start", "toolbar.end", "bottom.start", "bottom.bar",
        "page.top", "page.bottom", "page.center", "page.float", "view:<id>"
    };

    // ---------------------------------------------------------------- 入口

    static String op(String action, String json) {
        String result =
                ThreadUtils.runOnUiThreadBlockingNoException(
                        () -> {
                            try {
                                return opOnUi(action, LemurXUiHost.parseJson(json)).toString();
                            } catch (Exception e) {
                                LemurLogUtils.i(TAG, "skin op failed", action, e.getMessage());
                                return error(e.getMessage());
                            }
                        });
        return result == null ? "{\"ok\":false,\"error\":\"ui thread\"}" : result;
    }

    private static JSONObject opOnUi(String action, JSONObject opt) throws Exception {
        loadTheme();
        Activity activity = LemurXUiHost.hostActivity();
        if (activity != null && !activity.isDestroyed()) {
            sActivity = new WeakReference<>(activity);
        } else {
            activity = sActivity.get();
        }
        JSONObject out = new JSONObject();
        switch (action) {
            case "theme_set": {
                Iterator<String> keys = opt.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = opt.opt(key);
                    if (value == null || value == JSONObject.NULL) {
                        sTheme.remove(key);
                    } else {
                        sTheme.put(key, value);
                    }
                }
                saveTheme();
                boolean applied = activity != null && applyTheme(activity, sTheme);
                out.put("ok", applied);
                out.put("theme", sTheme);
                return out;
            }
            case "theme_get": {
                out.put("ok", true);
                out.put("theme", new JSONObject(sTheme.toString()));
                out.put("dark", LemurThemeUtils.isDarkMode());
                return out;
            }
            case "theme_reset": {
                JSONObject old = sTheme;
                sTheme = new JSONObject();
                saveTheme();
                if (activity != null) {
                    restoreTheme(activity, old);
                }
                out.put("ok", true);
                return out;
            }
            case "slots": {
                JSONArray slots = new JSONArray();
                for (String slot : SLOTS) {
                    JSONObject item = new JSONObject();
                    item.put("slot", slot);
                    item.put("available", slot.startsWith("page.") || slot.startsWith("view:")
                            || (activity != null && slotContainer(activity, slot) != null));
                    item.put("mounted", sMounts.containsKey(slot));
                    slots.put(item);
                }
                out.put("ok", true);
                out.put("slots", slots);
                return out;
            }
            case "render": {
                String slot = opt.optString("slot", "page.float");
                JSONObject tree = opt.optJSONObject("tree");
                if (tree == null) {
                    return errorObj("tree required");
                }
                if (activity == null) {
                    return errorObj("no activity");
                }
                Mount old = sMounts.remove(slot);
                JSONArray removed = new JSONArray();
                if (old != null) {
                    unmountView(old);
                    for (String id : old.ids) {
                        removed.put(id);
                    }
                }
                Mount mount = new Mount();
                mount.slot = slot;
                mount.tree = tree;
                sMounts.put(slot, mount);
                boolean ok = mountOnUi(activity, mount);
                if (!ok) {
                    // 容器可能还没 inflate（底栏在 native 就绪后才有），稍后再试一次
                    scheduleRemount(activity, 1200);
                }
                ensureStyleListener();
                out.put("ok", true);
                out.put("mounted", ok);
                out.put("slot", slot);
                out.put("id", tree.optString("id", ""));
                out.put("ids", new JSONArray(mount.ids));
                out.put("replaced", removed);
                return out;
            }
            case "unmount": {
                String slot = opt.optString("slot", "*");
                JSONArray ids = new JSONArray();
                List<String> slots = new ArrayList<>();
                if ("*".equals(slot) || TextUtils.isEmpty(slot)) {
                    slots.addAll(sMounts.keySet());
                } else if (sMounts.containsKey(slot)) {
                    slots.add(slot);
                } else {
                    // 也允许按根节点 id 卸载
                    for (Mount m : sMounts.values()) {
                        if (slot.equals(m.tree.optString("id", ""))) {
                            slots.add(m.slot);
                        }
                    }
                }
                for (String key : slots) {
                    Mount m = sMounts.remove(key);
                    if (m == null) {
                        continue;
                    }
                    unmountView(m);
                    for (String id : m.ids) {
                        ids.put(id);
                    }
                }
                out.put("ok", true);
                out.put("count", slots.size());
                out.put("ids", ids);
                return out;
            }
            case "style": {
                JSONObject query = opt.optJSONObject("query");
                JSONObject style = opt.optJSONObject("style");
                if (query == null || style == null) {
                    return errorObj("query and style required");
                }
                if (activity == null) {
                    return errorObj("no activity");
                }
                int count = applyStyleQuery(activity, query, style);
                if (opt.optBoolean("persist", true) && !style.optBoolean("once", false)) {
                    JSONObject record = new JSONObject();
                    record.put("query", query);
                    record.put("style", style);
                    sStyles.put(query.toString(), record);
                }
                out.put("ok", true);
                out.put("count", count);
                return out;
            }
            // ---------------- 对任意原生 View 动刀 ----------------
            case "replace":
            case "insert": {
                JSONObject query = opt.optJSONObject("query");
                JSONObject tree = opt.optJSONObject("tree");
                if (query == null || tree == null) {
                    return errorObj("query and tree required");
                }
                if (activity == null) {
                    return errorObj("no activity");
                }
                String rootId = tree.optString("id", "");
                String slot = action + ":" + (TextUtils.isEmpty(rootId)
                        ? String.valueOf(++sSurgeryCounter) : rootId);
                Mount old = sMounts.remove(slot);
                JSONArray removed = new JSONArray();
                if (old != null) {
                    unmountView(old);
                    for (String id : old.ids) {
                        removed.put(id);
                    }
                }
                Mount mount = new Mount();
                mount.slot = slot;
                mount.tree = tree;
                mount.mode = action;
                mount.query = query;
                JSONObject opts = opt.optJSONObject("opts");
                mount.opts = opts == null ? new JSONObject() : opts;
                sMounts.put(slot, mount);
                boolean ok = mountOnUi(activity, mount);
                if (!ok) {
                    scheduleRemount(activity, 1200);
                }
                ensureStyleListener();
                out.put("ok", true);
                out.put("mounted", ok);
                out.put("slot", slot);
                out.put("id", rootId);
                out.put("ids", new JSONArray(mount.ids));
                out.put("replaced", removed);
                return out;
            }
            case "detach": {
                JSONObject query = opt.optJSONObject("query");
                if (query == null) {
                    return errorObj("query required");
                }
                if (activity == null) {
                    return errorObj("no activity");
                }
                int count = detachQuery(activity, query);
                out.put("ok", true);
                out.put("count", count);
                return out;
            }
            case "restore": {
                JSONObject query = opt.optJSONObject("query");
                int count = restoreDetached(activity, query == null ? null : query.toString());
                out.put("ok", true);
                out.put("count", count);
                return out;
            }
            case "move": {
                JSONObject query = opt.optJSONObject("query");
                if (query == null) {
                    return errorObj("query required");
                }
                if (activity == null) {
                    return errorObj("no activity");
                }
                sMoves.put(query.toString(), opt);
                int count = moveQuery(activity, opt);
                out.put("ok", count > 0);
                out.put("count", count);
                return out;
            }
            case "children": {
                JSONObject query = opt.optJSONObject("query");
                if (query == null) {
                    return errorObj("query required");
                }
                List<View> hits = LemurXUiHost.findViews(query, 1);
                if (hits.isEmpty()) {
                    return errorObj("view not found");
                }
                View target = hits.get(0);
                JSONArray children = new JSONArray();
                if (target instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) target;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        JSONObject item = LemurXUiHost.describe(group.getChildAt(i), false);
                        item.put("index", i);
                        item.put("childCount", group.getChildAt(i) instanceof ViewGroup
                                ? ((ViewGroup) group.getChildAt(i)).getChildCount() : 0);
                        children.put(item);
                    }
                }
                out.put("ok", true);
                out.put("view", LemurXUiHost.describe(target, false));
                out.put("children", children);
                return out;
            }
            case "on": {
                JSONObject query = opt.optJSONObject("query");
                String key = opt.optString("key", "");
                if (query == null || TextUtils.isEmpty(key)) {
                    return errorObj("query and key required");
                }
                if (activity == null) {
                    return errorObj("no activity");
                }
                sListeners.put(key, opt);
                int count = installListeners(activity, key, opt);
                out.put("ok", true);
                out.put("count", count);
                out.put("key", key);
                return out;
            }
            case "off": {
                String key = opt.optString("key", "");
                if ("*".equals(key) || TextUtils.isEmpty(key)) {
                    List<String> keys = new ArrayList<>(sListeners.keySet());
                    for (String k : keys) {
                        uninstallListeners(k);
                    }
                    out.put("count", keys.size());
                } else {
                    out.put("count", uninstallListeners(key) ? 1 : 0);
                }
                out.put("ok", true);
                return out;
            }
            case "shell": {
                if (activity == null) {
                    return errorObj("no activity");
                }
                String[][] catalog = {
                    {"bar", "toolbar", "顶栏根（ToolbarPhoneLemur）"},
                    {"bar", "toolbar_buttons_left", "地址栏左侧按钮组"},
                    {"bar", "toolbar_buttons", "地址栏右侧按钮组"},
                    {"bar", "home_button", "主页"},
                    {"bar", "tab_switcher_button", "标签"},
                    {"bar", "tab_back_button", "后退"},
                    {"bar", "tab_forward_button", "前进"},
                    {"bar", "menu_tools", "工具抽屉"},
                    {"bar", "menu_button_lemur", "主菜单抽屉"},
                    {"search", "location_bar", "地址栏整体"},
                    {"search", "url_bar", "地址栏输入框"},
                    {"ntp", "search_box", "新标签页搜索框"},
                    {"ntp", "search_box_text", "新标签页搜索文字"},
                    {"ntp", "ivLogo", "新标签页 Logo"},
                    {"ntp", "ntp_favorites", "新标签页收藏"},
                };
                JSONArray views = new JSONArray();
                for (String[] row : catalog) {
                    JSONObject item = new JSONObject();
                    item.put("group", row[0]);
                    item.put("id", row[1]);
                    item.put("title", row[2]);
                    View view = LemurXUiHost.findByIdName(row[1]);
                    item.put("found", view != null);
                    if (view != null) {
                        item.put("view", LemurXUiHost.describe(view, false));
                    }
                    views.put(item);
                }
                out.put("ok", true);
                out.put("views", views);
                out.put("windows", LemurXUiHost.allWindowRoots().size());
                return out;
            }
            default:
                return errorObj("unknown skin op " + action);
        }
    }

    // ---------------------------------------------------------------- 原生 View 手术

    private static int clampIndex(ViewGroup parent, int index) {
        if (index < 0 || index > parent.getChildCount()) {
            return parent.getChildCount();
        }
        return index;
    }

    /** replace / insert 模式的挂载：找到目标原生 View，换掉或插在旁边。 */
    private static boolean mountSurgery(Activity activity, Mount mount, View view) {
        List<View> hits = LemurXUiHost.findViews(mount.query, 4);
        View target = null;
        for (View hit : hits) {
            // 别把自己挂的树当目标
            if (hit != view && !(hit.getTag() instanceof String
                    && ((String) hit.getTag()).startsWith(SLOT_TAG_PREFIX + mount.mode))) {
                target = hit;
                break;
            }
        }
        if (target == null || !(target.getParent() instanceof ViewGroup)) {
            return false;
        }
        ViewGroup parent = (ViewGroup) target.getParent();
        int index = parent.indexOfChild(target);
        JSONObject tree = mount.tree;
        if ("replace".equals(mount.mode)) {
            ViewGroup.LayoutParams lp = target.getLayoutParams();
            mount.replaced = target;
            mount.replacedParent = parent;
            mount.replacedIndex = index;
            mount.replacedLp = lp;
            parent.removeViewAt(index);
            ViewGroup.LayoutParams newLp;
            if (tree.has("width") || tree.has("height") || tree.has("weight") || lp == null) {
                newLp = layoutParamsFor(activity, parent, tree);
            } else {
                // 默认原样接管目标的位置和尺寸
                newLp = lp;
            }
            parent.addView(view, index, newLp);
        } else {
            String position = mount.opts.optString("position", "after");
            ViewGroup container;
            int at;
            if ("into".equals(position)) {
                if (!(target instanceof ViewGroup)) {
                    return false;
                }
                container = (ViewGroup) target;
                at = mount.opts.optInt("index", container.getChildCount());
            } else {
                container = parent;
                at = "before".equals(position) ? index : index + 1;
            }
            ViewGroup.LayoutParams lp = layoutParamsFor(activity, container, tree);
            ViewGroup.LayoutParams tlp = target.getLayoutParams();
            if (!"into".equals(position) && tlp != null && !tree.has("width")
                    && !tree.has("height") && !tree.has("weight")) {
                // 兄弟默认跟目标同尺寸同权重，塞进底栏/工具栏就是「多一个一样大的按钮」
                lp.width = tlp.width;
                lp.height = tlp.height;
                if (lp instanceof LinearLayout.LayoutParams
                        && tlp instanceof LinearLayout.LayoutParams) {
                    ((LinearLayout.LayoutParams) lp).weight =
                            ((LinearLayout.LayoutParams) tlp).weight;
                }
            }
            container.addView(view, clampIndex(container, at), lp);
        }
        mount.view = view;
        mount.popup = null;
        return true;
    }

    private static int detachQuery(Activity activity, JSONObject query) {
        List<View> hits = LemurXUiHost.findViews(query, query.optInt("max", 20));
        List<Detached> list = new ArrayList<>();
        for (View view : hits) {
            if (!(view.getParent() instanceof ViewGroup)) {
                continue;
            }
            ViewGroup parent = (ViewGroup) view.getParent();
            Detached d = new Detached();
            d.view = view;
            d.parent = parent;
            d.index = parent.indexOfChild(view);
            d.lp = view.getLayoutParams();
            parent.removeView(view);
            list.add(d);
        }
        List<Detached> old = sDetached.get(query.toString());
        if (old != null && !list.isEmpty()) {
            // 同一 query 重复 detach：旧记录里还活着的也留着，restore 一起放回
            for (Detached d : old) {
                if (d.view.getContext() == activity) {
                    list.add(d);
                }
            }
        }
        sDetached.put(query.toString(), list.isEmpty() && old != null ? old : list);
        return hits.size();
    }

    private static int restoreDetached(Activity activity, String queryKey) {
        int count = 0;
        List<String> keys = new ArrayList<>();
        if (queryKey == null) {
            keys.addAll(sDetached.keySet());
        } else if (sDetached.containsKey(queryKey)) {
            keys.add(queryKey);
        }
        for (String key : keys) {
            List<Detached> list = sDetached.remove(key);
            if (list == null) {
                continue;
            }
            for (Detached d : list) {
                if (d.view.getParent() != null
                        || (activity != null && d.view.getContext() != activity)) {
                    continue;
                }
                try {
                    d.parent.addView(d.view, clampIndex(d.parent, d.index), d.lp);
                    count++;
                } catch (Exception e) {
                    LemurLogUtils.i(TAG, "restore failed", e.getMessage());
                }
            }
        }
        return count;
    }

    private static int moveQuery(Activity activity, JSONObject opt) {
        JSONObject query = opt.optJSONObject("query");
        List<View> hits = LemurXUiHost.findViews(query, 1);
        if (hits.isEmpty() || !(hits.get(0).getParent() instanceof ViewGroup)) {
            return 0;
        }
        View view = hits.get(0);
        ViewGroup oldParent = (ViewGroup) view.getParent();
        ViewGroup container = oldParent;
        JSONObject parentQuery = opt.optJSONObject("parent");
        if (parentQuery != null) {
            List<View> parents = LemurXUiHost.findViews(parentQuery, 1);
            if (parents.isEmpty() || !(parents.get(0) instanceof ViewGroup)) {
                return 0;
            }
            container = (ViewGroup) parents.get(0);
        }
        int index = opt.optInt("index", -1);
        JSONObject before = opt.optJSONObject("before");
        JSONObject after = opt.optJSONObject("after");
        if (before != null || after != null) {
            List<View> anchors = LemurXUiHost.findViews(before != null ? before : after, 1);
            if (!anchors.isEmpty() && anchors.get(0).getParent() instanceof ViewGroup) {
                container = (ViewGroup) anchors.get(0).getParent();
                index = container.indexOfChild(anchors.get(0)) + (after != null ? 1 : 0);
            }
        }
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        int oldIndex = oldParent.indexOfChild(view);
        oldParent.removeView(view);
        if (container == oldParent && index > oldIndex) {
            index--;
        }
        if (opt.has("width") || opt.has("height") || opt.has("weight") || opt.has("gravity")
                || opt.has("margin") || lp == null
                || (container != oldParent && container.getClass() != oldParent.getClass())) {
            ViewGroup.LayoutParams fresh = layoutParamsFor(activity, container, opt);
            if (lp != null && !opt.has("width")) {
                fresh.width = lp.width;
            }
            if (lp != null && !opt.has("height")) {
                fresh.height = lp.height;
            }
            lp = fresh;
        }
        container.addView(view, clampIndex(container, index), lp);
        return 1;
    }

    // ---------------------------------------------------------------- 原生 View 事件

    private static int installListeners(Activity activity, String key, JSONObject opt) {
        uninstallListeners(key);
        sListeners.put(key, opt);
        JSONObject query = opt.optJSONObject("query");
        String event = opt.optString("event", "click");
        JSONObject opts = opt.optJSONObject("opts");
        if (opts == null) {
            opts = new JSONObject();
        }
        boolean consume = opts.optBoolean("consume", !"touch".equals(event));
        boolean reportMove = opts.optBoolean("move", false);
        List<View> hits = LemurXUiHost.findViews(query, opts.optInt("max", 20));
        List<Object[]> installed = new ArrayList<>();
        for (View view : hits) {
            Object watcher = null;
            switch (event) {
                case "click":
                    if (consume) {
                        view.setOnClickListener(v -> dispatchNative(key, v, "click", null));
                    } else {
                        // 只观察不接管：用 touch 监听看 UP，返回 false 不吞事件
                        view.setOnTouchListener((v, ev) -> {
                            if (ev.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                                dispatchNative(key, v, "click", null);
                            }
                            return false;
                        });
                    }
                    break;
                case "longclick":
                    view.setOnLongClickListener(v -> {
                        dispatchNative(key, v, "longclick", null);
                        return consume;
                    });
                    break;
                case "touch":
                    view.setOnTouchListener((v, ev) -> {
                        int action = ev.getActionMasked();
                        if (action == android.view.MotionEvent.ACTION_MOVE && !reportMove) {
                            return consume;
                        }
                        JSONObject extra = new JSONObject();
                        try {
                            extra.put("touch", action == android.view.MotionEvent.ACTION_DOWN ? "down"
                                    : action == android.view.MotionEvent.ACTION_UP ? "up"
                                    : action == android.view.MotionEvent.ACTION_MOVE ? "move"
                                    : action == android.view.MotionEvent.ACTION_CANCEL ? "cancel"
                                    : String.valueOf(action));
                            extra.put("x", ev.getX());
                            extra.put("y", ev.getY());
                            extra.put("rawX", ev.getRawX());
                            extra.put("rawY", ev.getRawY());
                        } catch (Exception ignored) {
                            // 字段可选
                        }
                        dispatchNative(key, v, "touch", extra);
                        return consume;
                    });
                    break;
                case "focus":
                    view.setOnFocusChangeListener((v, hasFocus) -> {
                        JSONObject extra = new JSONObject();
                        try {
                            extra.put("focus", hasFocus);
                        } catch (Exception ignored) {
                            // 字段可选
                        }
                        dispatchNative(key, v, "focus", extra);
                    });
                    break;
                case "text":
                    if (view instanceof TextView) {
                        TextWatcher tw = new TextWatcher() {
                            @Override
                            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                            @Override
                            public void onTextChanged(CharSequence s, int a, int b, int c) {}

                            @Override
                            public void afterTextChanged(Editable s) {
                                dispatchNative(key, view, "text", null);
                            }
                        };
                        ((TextView) view).addTextChangedListener(tw);
                        watcher = tw;
                    }
                    break;
                default:
                    continue;
            }
            installed.add(new Object[] {new WeakReference<>(view), event, watcher});
        }
        sListenerViews.put(key, installed);
        return installed.size();
    }

    private static boolean uninstallListeners(String key) {
        boolean existed = sListeners.remove(key) != null;
        List<Object[]> installed = sListenerViews.remove(key);
        if (installed == null) {
            return existed;
        }
        for (Object[] item : installed) {
            @SuppressWarnings("unchecked")
            View view = ((WeakReference<View>) item[0]).get();
            if (view == null) {
                continue;
            }
            String event = (String) item[1];
            switch (event) {
                case "click":
                    view.setOnClickListener(null);
                    view.setOnTouchListener(null);
                    break;
                case "longclick":
                    view.setOnLongClickListener(null);
                    break;
                case "touch":
                    view.setOnTouchListener(null);
                    break;
                case "focus":
                    view.setOnFocusChangeListener(null);
                    break;
                case "text":
                    if (item[2] instanceof TextWatcher && view instanceof TextView) {
                        ((TextView) view).removeTextChangedListener((TextWatcher) item[2]);
                    }
                    break;
                default:
                    break;
            }
        }
        return true;
    }

    private static void dispatchNative(String key, View view, String action, JSONObject extra) {
        try {
            JSONObject ev = LemurXUiHost.describe(view, false);
            ev.put("key", key);
            ev.put("action", action);
            if (extra != null) {
                Iterator<String> keys = extra.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    ev.put(k, extra.opt(k));
                }
            }
            LemurXBridge.notifyUiClick(key, ev.toString());
        } catch (Exception e) {
            LemurXBridge.notifyUiClick(key);
        }
    }

    /** Activity attach（含重建）时把皮肤、挂载树、样式手术全部重放。 */
    static void reattach(Activity activity) {
        if (activity == null || activity.isDestroyed()) {
            return;
        }
        ThreadUtils.assertOnUiThread();
        loadTheme();
        Activity previous = sActivity.get();
        sActivity = new WeakReference<>(activity);
        if (previous != activity) {
            // 旧 Activity 的 View 全部失效
            for (Mount m : sMounts.values()) {
                m.view = null;
                m.popup = null;
                m.replaced = null;
                m.replacedParent = null;
                m.replacedLp = null;
            }
            // 摘下的 View 属于旧 Activity，清掉引用，保留 query 以便重放
            for (List<Detached> list : sDetached.values()) {
                list.clear();
            }
            sListenerViews.clear();
            sBottomBarCaptured = false;
            sBottomBarOriginalBg = null;
        }
        ensureStyleListener();
        Window window = activity.getWindow();
        View decor = window == null ? null : window.getDecorView();
        if (decor == null) {
            return;
        }
        decor.post(() -> replayAll(activity));
        // 工具栏/底栏 inflate 得晚，再补两刀
        decor.postDelayed(() -> replayAll(activity), 1500);
        decor.postDelayed(() -> replayAll(activity), 4000);
    }

    /** 给 ui.dump / ui.find 用：挂在 PopupWindow 里的树不在 decor 下，要单独遍历。 */
    static List<View> popupRoots() {
        List<View> roots = new ArrayList<>();
        for (Mount m : sMounts.values()) {
            if (m.popup != null && m.view != null) {
                roots.add(m.view);
            }
        }
        return roots;
    }

    private static void replayAll(Activity activity) {
        if (activity.isDestroyed() || activity.isFinishing()) {
            return;
        }
        try {
            if (sTheme.length() > 0) {
                applyTheme(activity, sTheme);
            }
            remountAll(activity);
            for (JSONObject record : sStyles.values()) {
                JSONObject query = record.optJSONObject("query");
                JSONObject style = record.optJSONObject("style");
                if (query != null && style != null) {
                    applyStyleQuery(activity, query, style);
                }
            }
            // detach / move / on 也按 query 重放
            for (Map.Entry<String, List<Detached>> item : new ArrayList<>(sDetached.entrySet())) {
                boolean alive = false;
                for (Detached d : item.getValue()) {
                    if (d.view.getContext() == activity) {
                        alive = true;
                        break;
                    }
                }
                if (!alive) {
                    try {
                        detachQuery(activity, new JSONObject(item.getKey()));
                    } catch (Exception ignored) {
                        // key 一定是合法 json
                    }
                }
            }
            for (JSONObject move : sMoves.values()) {
                moveQuery(activity, move);
            }
            for (Map.Entry<String, JSONObject> item : new ArrayList<>(sListeners.entrySet())) {
                List<Object[]> installed = sListenerViews.get(item.getKey());
                boolean alive = false;
                if (installed != null) {
                    for (Object[] rec : installed) {
                        @SuppressWarnings("unchecked")
                        View v = ((WeakReference<View>) rec[0]).get();
                        if (v != null && v.getContext() == activity && v.isAttachedToWindow()) {
                            alive = true;
                            break;
                        }
                    }
                }
                if (!alive) {
                    installListeners(activity, item.getKey(), item.getValue());
                }
            }
        } catch (Exception e) {
            LemurLogUtils.i(TAG, "skin replay failed", e.getMessage());
        }
    }

    private static void remountAll(Activity activity) {
        for (Mount m : sMounts.values()) {
            boolean attached =
                    m.view != null
                            && (m.popup != null ? m.popup.isShowing() : m.view.getParent() != null);
            if (!attached) {
                mountOnUi(activity, m);
            }
        }
    }

    private static void scheduleRemount(Activity activity, long delayMs) {
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor != null) {
            decor.postDelayed(() -> remountAll(activity), delayMs);
        }
    }

    private static void ensureStyleListener() {
        if (sStyleListenerAdded) {
            return;
        }
        sStyleListenerAdded = true;
        // 底栏切换样式时会 removeAllViews，我们挂在里面的树得重新塞回去
        BottomToolbarStyleManger.addOnStyleChangeListener(
                style -> {
                    Activity activity = sActivity.get();
                    if (activity == null) {
                        return;
                    }
                    View decor =
                            activity.getWindow() == null
                                    ? null
                                    : activity.getWindow().getDecorView();
                    if (decor != null) {
                        decor.post(() -> remountAll(activity));
                    }
                });
    }

    // ---------------------------------------------------------------- 主题

    private static SharedPreferences prefs() {
        return ContextUtils.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void loadTheme() {
        if (sThemeLoaded) {
            return;
        }
        sThemeLoaded = true;
        try {
            String json = prefs().getString(KEY_THEME, "");
            sTheme = TextUtils.isEmpty(json) ? new JSONObject() : new JSONObject(json);
        } catch (Exception e) {
            sTheme = new JSONObject();
        }
    }

    private static void saveTheme() {
        prefs().edit().putString(KEY_THEME, sTheme.toString()).apply();
    }

    /**
     * 支持的键：toolbar / statusBar / navBar / bottomBar（颜色），iconTint（图标色），
     * dark（bool），bottomStyle（0/1/2），hideButtons（数组），urlBarHidden / bottomBarHidden /
     * menuButton / pullRefresh（bool）。
     */
    private static boolean applyTheme(Activity activity, JSONObject theme) {
        boolean any = false;
        ChromeTabbedActivity tabbed =
                activity instanceof ChromeTabbedActivity ? (ChromeTabbedActivity) activity : null;
        if (theme.has("dark")) {
            boolean dark = theme.optBoolean("dark", false);
            if (dark != LemurThemeUtils.isDarkMode()) {
                LemurXChromeHost.setDarkMode(dark);
            }
            any = true;
        }
        if (theme.has("toolbar")) {
            int color = LemurXUiHost.parseColor(theme.optString("toolbar"), Color.WHITE);
            any |= LemurXChromeHost.setToolbarColor(color);
        }
        if (theme.has("statusBar")) {
            any |= LemurXChromeHost.setStatusBarColor(
                    LemurXUiHost.parseColor(theme.optString("statusBar"), Color.WHITE));
        }
        if (theme.has("navBar") && activity.getWindow() != null) {
            activity.getWindow()
                    .setNavigationBarColor(
                            LemurXUiHost.parseColor(theme.optString("navBar"), Color.WHITE));
            any = true;
        }
        if (theme.has("bottomBar")) {
            View bottom = findByName(activity, "bottom_toolbar_browsing");
            if (bottom != null) {
                captureBottomBar(bottom);
                bottom.setBackgroundColor(
                        LemurXUiHost.parseColor(theme.optString("bottomBar"), Color.WHITE));
                any = true;
            }
        }
        if (theme.has("iconTint")) {
            ColorStateList tint =
                    ColorStateList.valueOf(
                            LemurXUiHost.parseColor(theme.optString("iconTint"), Color.GRAY));
            any |= tintContainer(activity, "toolbar_buttons_left", tint);
            any |= tintContainer(activity, "toolbar_buttons", tint);
            any |= tintContainer(activity, "bottom_toolbar_browsing", tint);
        }
        if (theme.has("bottomStyle") || theme.has("barLayout")) {
            Object layout = theme.has("barLayout") ? theme.opt("barLayout") : theme.opt("bottomStyle");
            if (layout instanceof String) {
                LemurXChromeHost.setBarLayout((String) layout);
            } else {
                int n = theme.optInt("bottomStyle", 1);
                // 旧 0/1/2 映射到现行 5 槽：1=home 2=tabs 3=search 4=tools 5=menu 6=back 7=forward
                String mapped = n == 0 ? "6-2-1-4-5" : n == 2 ? "1-2-3-4-5" : "6-2-3-4-5";
                LemurXChromeHost.setBarLayout(mapped);
                BottomToolbarStyleManger.onStyleChange(n);
            }
            any = true;
        }
        if (tabbed != null) {
            JSONArray hidden = theme.optJSONArray("hideButtons");
            if (hidden != null) {
                for (int i = 0; i < hidden.length(); i++) {
                    LemurXChromeHost.hideButton(hidden.optString(i), true);
                }
                any = true;
            }
            if (theme.has("urlBarHidden")) {
                any |= LemurXChromeHost.hideUrlBar(theme.optBoolean("urlBarHidden"));
            }
            if (theme.has("bottomBarHidden")) {
                any |= LemurXChromeHost.hideBottomToolbar(theme.optBoolean("bottomBarHidden"));
            }
            if (theme.has("menuButton")) {
                any |= LemurXChromeHost.setMenuButtonVisible(theme.optBoolean("menuButton"));
            }
            if (theme.has("pullRefresh")) {
                any |= LemurXChromeHost.setPullRefresh(theme.optBoolean("pullRefresh"));
            }
        }
        return any || theme.length() == 0;
    }

    private static void restoreTheme(Activity activity, JSONObject old) {
        ChromeTabbedActivity tabbed =
                activity instanceof ChromeTabbedActivity ? (ChromeTabbedActivity) activity : null;
        if (old.has("toolbar")) {
            // 交还给 ToolbarManager 按站点主题色刷新
            LemurXChromeHost.resetToolbarColor(
                    LemurThemeUtils.isDarkMode() ? 0xFF202124 : Color.WHITE);
        }
        if (old.has("statusBar")) {
            LemurXChromeHost.setStatusBarColor(
                    LemurThemeUtils.isDarkMode() ? 0xFF202124 : Color.WHITE);
        }
        if (old.has("navBar") && activity.getWindow() != null) {
            activity.getWindow()
                    .setNavigationBarColor(
                            LemurThemeUtils.isDarkMode() ? 0xFF202124 : Color.WHITE);
        }
        if (old.has("bottomBar")) {
            View bottom = findByName(activity, "bottom_toolbar_browsing");
            if (bottom != null && sBottomBarCaptured) {
                bottom.setBackground(sBottomBarOriginalBg);
            }
        }
        if (old.has("iconTint")) {
            ColorStateList tint = defaultIconTint(activity);
            if (tint != null) {
                tintContainer(activity, "toolbar_buttons_left", tint);
                tintContainer(activity, "toolbar_buttons", tint);
                tintContainer(activity, "bottom_toolbar_browsing", tint);
            }
        }
        if (old.has("bottomStyle") || old.has("barLayout")) {
            LemurXChromeHost.setBarLayout("1-2-3-4-5");
            BottomToolbarStyleManger.onStyleChange(1);
        }
        if (tabbed != null) {
            JSONArray hidden = old.optJSONArray("hideButtons");
            if (hidden != null) {
                for (int i = 0; i < hidden.length(); i++) {
                    LemurXChromeHost.hideButton(hidden.optString(i), false);
                }
            }
            if (old.has("urlBarHidden")) {
                LemurXChromeHost.hideUrlBar(false);
            }
            if (old.has("bottomBarHidden")) {
                LemurXChromeHost.hideBottomToolbar(false);
            }
            if (old.has("menuButton")) {
                LemurXChromeHost.setMenuButtonVisible(true);
            }
            if (old.has("pullRefresh")) {
                LemurXChromeHost.setPullRefresh(true);
            }
        }
    }

    private static void captureBottomBar(View bottom) {
        if (!sBottomBarCaptured) {
            sBottomBarCaptured = true;
            sBottomBarOriginalBg = bottom.getBackground();
        }
    }

    private static ColorStateList defaultIconTint(Activity activity) {
        int id =
                activity.getResources()
                        .getIdentifier(
                                "default_icon_color_tint_list", "color",
                                activity.getPackageName());
        if (id == 0) {
            return null;
        }
        try {
            return activity.getResources().getColorStateList(id, activity.getTheme());
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean tintContainer(Activity activity, String name, ColorStateList tint) {
        View root = findByName(activity, name);
        if (root == null) {
            return false;
        }
        tintTree(root, tint);
        return true;
    }

    private static void tintTree(View view, ColorStateList tint) {
        if (view instanceof ImageView) {
            ((ImageView) view).setImageTintList(tint);
        } else if (view instanceof TextView) {
            ((TextView) view).setTextColor(tint);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                tintTree(group.getChildAt(i), tint);
            }
        }
    }

    // ---------------------------------------------------------------- 挂载

    private static ViewGroup slotContainer(Activity activity, String slot) {
        View view;
        switch (slot) {
            case "toolbar.start":
                view = findByName(activity, "toolbar_buttons_left");
                break;
            case "toolbar.end":
                view = findByName(activity, "toolbar_buttons");
                break;
            case "bottom.bar":
            case "bottom.start":
                view = findByName(activity, "bottom_toolbar_browsing");
                break;
            default:
                if (slot.startsWith("view:")) {
                    view = findByName(activity, slot.substring("view:".length()));
                } else {
                    view = null;
                }
                break;
        }
        return view instanceof ViewGroup ? (ViewGroup) view : null;
    }

    private static boolean mountOnUi(Activity activity, Mount mount) {
        try {
            mount.ids.clear();
            View view = build(activity, mount.tree, mount);
            if (view == null) {
                return false;
            }
            view.setTag(SLOT_TAG_PREFIX + mount.slot);
            if (!TextUtils.isEmpty(mount.tree.optString("id", ""))) {
                view.setTag(mount.tree.optString("id"));
            }
            if (mount.mode != null) {
                return mountSurgery(activity, mount, view);
            }
            if (mount.slot.startsWith("page.") || "float".equals(mount.slot)) {
                return mountPopup(activity, mount, view);
            }
            ViewGroup container = slotContainer(activity, mount.slot);
            if (container == null) {
                return false;
            }
            // 同一个 slot 之前挂过但 view 引用已丢的，按 tag 清掉
            for (int i = container.getChildCount() - 1; i >= 0; i--) {
                View child = container.getChildAt(i);
                if ((SLOT_TAG_PREFIX + mount.slot).equals(child.getTag())
                        || (child.getTag() instanceof String
                                && child.getTag().equals(mount.tree.optString("id", "\u0000")))) {
                    container.removeViewAt(i);
                }
            }
            ViewGroup.LayoutParams lp = layoutParamsFor(activity, container, mount.tree);
            if (container instanceof LinearLayout
                    && ("toolbar.start".equals(mount.slot)
                            || "toolbar.end".equals(mount.slot)
                            || "bottom.bar".equals(mount.slot)
                            || "bottom.start".equals(mount.slot))) {
                // 外壳里的按钮条都是等权分配，默认跟着走
                LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
                if (!mount.tree.has("width")) {
                    llp.width = 0;
                    llp.weight = (float) mount.tree.optDouble("weight", 1);
                }
                if (!mount.tree.has("height")) {
                    llp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                }
            }
            int index = mount.tree.optInt("index", -1);
            if ("toolbar.start".equals(mount.slot) || "bottom.start".equals(mount.slot)) {
                index = mount.tree.has("index") ? index : 0;
            }
            if (index < 0 || index > container.getChildCount()) {
                container.addView(view, lp);
            } else {
                container.addView(view, index, lp);
            }
            mount.view = view;
            mount.popup = null;
            return true;
        } catch (Exception e) {
            LemurLogUtils.i(TAG, "mount failed", mount.slot, e.getMessage());
            return false;
        }
    }

    private static boolean mountPopup(Activity activity, Mount mount, View view) {
        Window window = activity.getWindow();
        View decor = window == null ? null : window.getDecorView();
        if (decor == null || decor.getWindowToken() == null) {
            return false;
        }
        boolean editable = containsType(mount.tree, "edit");
        boolean fullWidth = "page.top".equals(mount.slot) || "page.bottom".equals(mount.slot);
        int width =
                mount.tree.has("width")
                        ? sizeOf(activity, mount.tree.opt("width"), ViewGroup.LayoutParams.WRAP_CONTENT)
                        : (fullWidth
                                ? ViewGroup.LayoutParams.MATCH_PARENT
                                : ViewGroup.LayoutParams.WRAP_CONTENT);
        int height =
                mount.tree.has("height")
                        ? sizeOf(activity, mount.tree.opt("height"), ViewGroup.LayoutParams.WRAP_CONTENT)
                        : ViewGroup.LayoutParams.WRAP_CONTENT;
        PopupWindow popup = new PopupWindow(view, width, height, editable);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setTouchable(true);
        popup.setFocusable(editable);
        popup.setOutsideTouchable(false);
        popup.setClippingEnabled(false);
        popup.setInputMethodMode(
                editable ? PopupWindow.INPUT_METHOD_NEEDED : PopupWindow.INPUT_METHOD_NOT_NEEDED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            popup.setTouchModal(false);
        }
        int gravity;
        int x = dp(activity, mount.tree.optInt("x", 0));
        int y = dp(activity, mount.tree.optInt("y", 0));
        switch (mount.slot) {
            case "page.top": {
                gravity = Gravity.TOP | Gravity.START;
                View toolbar = findByName(activity, "toolbar");
                int[] loc = new int[2];
                if (toolbar != null && toolbar.getHeight() > 0) {
                    toolbar.getLocationInWindow(loc);
                    y += loc[1] + toolbar.getHeight();
                } else {
                    y += dp(activity, 80);
                }
                break;
            }
            case "page.bottom": {
                gravity = Gravity.BOTTOM | Gravity.START;
                View bottom = findByName(activity, "bottom_toolbar_browsing");
                if (bottom != null && bottom.getVisibility() == View.VISIBLE) {
                    y += bottom.getHeight() > 0 ? bottom.getHeight() : dp(activity, 56);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && decor.getRootWindowInsets() != null) {
                    y += decor.getRootWindowInsets().getSystemWindowInsetBottom();
                }
                break;
            }
            case "page.center":
                gravity = Gravity.CENTER;
                break;
            default:
                gravity = LemurXUiHost.parseGravity(mount.tree.optString("gravity", "end|bottom"));
                if (!mount.tree.has("x")) {
                    x = dp(activity, 16);
                }
                if (!mount.tree.has("y")) {
                    y = dp(activity, 96);
                }
                break;
        }
        try {
            popup.showAtLocation(decor, gravity, x, y);
        } catch (Exception e) {
            LemurLogUtils.i(TAG, "popup show failed", mount.slot, e.getMessage());
            return false;
        }
        mount.view = view;
        mount.popup = popup;
        return true;
    }

    private static void unmountView(Mount mount) {
        if (mount.popup != null) {
            try {
                mount.popup.dismiss();
            } catch (Exception ignored) {
                // 窗口可能已经拆掉
            }
        } else if (mount.view != null && mount.view.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) mount.view.getParent();
            int index = parent.indexOfChild(mount.view);
            parent.removeView(mount.view);
            // replace 模式：把被换下来的原生 View 放回原位（仅限同一个 Activity 还活着）
            if (mount.replaced != null && mount.replaced.getParent() == null
                    && mount.replaced.getContext() == parent.getContext()) {
                try {
                    parent.addView(mount.replaced, clampIndex(parent, index >= 0
                            ? index : mount.replacedIndex), mount.replacedLp);
                } catch (Exception e) {
                    LemurLogUtils.i(TAG, "restore replaced failed", e.getMessage());
                }
            }
        }
        mount.replaced = null;
        mount.replacedParent = null;
        mount.replacedLp = null;
        mount.popup = null;
        mount.view = null;
    }

    private static boolean containsType(JSONObject node, String type) {
        if (node == null) {
            return false;
        }
        if (type.equals(node.optString("type", ""))) {
            return true;
        }
        JSONArray children = node.optJSONArray("children");
        if (children != null) {
            for (int i = 0; i < children.length(); i++) {
                if (containsType(children.optJSONObject(i), type)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 控件树

    private static View build(Activity activity, JSONObject node, Mount mount) throws Exception {
        if (node == null) {
            return null;
        }
        String type = node.optString("type", node.has("children") ? "column" : "text");
        View view;
        switch (type) {
            case "row":
            case "column": {
                LinearLayout layout = new LinearLayout(activity);
                layout.setOrientation(
                        "row".equals(type) ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
                layout.setGravity(
                        node.has("align")
                                ? LemurXUiHost.parseGravity(node.optString("align"))
                                : ("row".equals(type) ? Gravity.CENTER_VERTICAL : Gravity.START));
                addChildren(activity, layout, node, mount);
                view = layout;
                break;
            }
            case "stack": {
                FrameLayout layout = new FrameLayout(activity);
                addChildren(activity, layout, node, mount);
                view = layout;
                break;
            }
            case "scroll":
            case "hscroll": {
                ViewGroup scroll =
                        "scroll".equals(type)
                                ? new ScrollView(activity)
                                : new HorizontalScrollView(activity);
                LinearLayout inner = new LinearLayout(activity);
                inner.setOrientation(
                        "scroll".equals(type) ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                addChildren(activity, inner, node, mount);
                scroll.addView(
                        inner,
                        new ViewGroup.LayoutParams(
                                "scroll".equals(type)
                                        ? ViewGroup.LayoutParams.MATCH_PARENT
                                        : ViewGroup.LayoutParams.WRAP_CONTENT,
                                "scroll".equals(type)
                                        ? ViewGroup.LayoutParams.WRAP_CONTENT
                                        : ViewGroup.LayoutParams.MATCH_PARENT));
                view = scroll;
                break;
            }
            case "text": {
                TextView text = new TextView(activity);
                applyText(activity, text, node, 14, textColorDefault());
                view = text;
                break;
            }
            case "button": {
                TextView button = new TextView(activity);
                applyText(activity, button, node, 14, Color.WHITE);
                button.setGravity(Gravity.CENTER);
                if (!node.has("background")) {
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(
                            LemurXUiHost.parseColor(
                                    sTheme.optString("accent", "#FF3F51B5"), 0xFF3F51B5));
                    bg.setCornerRadius(dp(activity, node.optInt("radius", 8)));
                    button.setBackground(bg);
                }
                if (!node.has("padding")) {
                    int h = dp(activity, 14);
                    int v = dp(activity, 8);
                    button.setPadding(h, v, h, v);
                }
                button.setClickable(true);
                view = button;
                break;
            }
            case "icon":
            case "image": {
                ImageView image = new ImageView(activity);
                Drawable drawable = resolveIcon(activity, node.optString("icon", node.optString("src", "")));
                if (drawable == null && node.has("text")) {
                    // 没图就退化成文字图标（emoji 很好用）
                    TextView glyph = new TextView(activity);
                    applyText(activity, glyph, node, 18, textColorDefault());
                    glyph.setGravity(Gravity.CENTER);
                    view = glyph;
                    break;
                }
                image.setImageDrawable(drawable);
                if (node.has("tint")) {
                    image.setImageTintList(
                            ColorStateList.valueOf(
                                    LemurXUiHost.parseColor(node.optString("tint"), Color.GRAY)));
                } else if ("icon".equals(type) && sTheme.has("iconTint")) {
                    image.setImageTintList(
                            ColorStateList.valueOf(
                                    LemurXUiHost.parseColor(
                                            sTheme.optString("iconTint"), Color.GRAY)));
                }
                image.setScaleType(scaleTypeOf(node.optString("scale", "icon".equals(type) ? "center" : "fit")));
                if ("icon".equals(type) && !node.has("padding")) {
                    int p = dp(activity, 12);
                    image.setPadding(p, p, p, p);
                }
                if (node.has("desc")) {
                    image.setContentDescription(node.optString("desc"));
                }
                view = image;
                break;
            }
            case "edit": {
                EditText edit = new EditText(activity);
                applyText(activity, edit, node, 14, textColorDefault());
                edit.setHint(node.optString("hint", ""));
                if (node.has("hintColor")) {
                    edit.setHintTextColor(
                            LemurXUiHost.parseColor(node.optString("hintColor"), Color.GRAY));
                }
                edit.setText(node.optString("value", node.optString("text", "")));
                edit.setSingleLine(node.optBoolean("single", true));
                edit.setInputType(inputTypeOf(node.optString("inputType", "text")));
                if (node.has("maxLength")) {
                    edit.setFilters(
                            new android.text.InputFilter[] {
                                new android.text.InputFilter.LengthFilter(node.optInt("maxLength"))
                            });
                }
                final String id = node.optString("id", "");
                if (node.optBoolean("onChange", false) && !TextUtils.isEmpty(id)) {
                    edit.addTextChangedListener(
                            new TextWatcher() {
                                @Override
                                public void beforeTextChanged(
                                        CharSequence s, int start, int count, int after) {}

                                @Override
                                public void onTextChanged(
                                        CharSequence s, int start, int before, int count) {}

                                @Override
                                public void afterTextChanged(Editable s) {
                                    dispatch(id + ":change", id, "change", mount.slot,
                                            "text", s == null ? "" : s.toString());
                                }
                            });
                    edit.setOnEditorActionListener(
                            (v, actionId, event) -> {
                                if (actionId == EditorInfo.IME_ACTION_DONE
                                        || actionId == EditorInfo.IME_ACTION_GO
                                        || actionId == EditorInfo.IME_ACTION_SEARCH
                                        || actionId == EditorInfo.IME_ACTION_SEND) {
                                    dispatch(id + ":change", id, "submit", mount.slot,
                                            "text", v.getText().toString());
                                    return true;
                                }
                                return false;
                            });
                    edit.setImeOptions(EditorInfo.IME_ACTION_DONE);
                }
                view = edit;
                break;
            }
            case "switch": {
                Switch toggle = new Switch(activity);
                applyText(activity, toggle, node, 14, textColorDefault());
                toggle.setChecked(node.optBoolean("checked", false));
                final String id = node.optString("id", "");
                if (node.optBoolean("onChange", false) && !TextUtils.isEmpty(id)) {
                    toggle.setOnCheckedChangeListener(
                            (v, checked) ->
                                    dispatch(id + ":change", id, "change", mount.slot,
                                            "checked", checked));
                }
                view = toggle;
                break;
            }
            case "progress": {
                ProgressBar bar =
                        new ProgressBar(
                                activity, null, android.R.attr.progressBarStyleHorizontal);
                bar.setMax(node.optInt("max", 100));
                bar.setProgress(node.optInt("value", 0));
                bar.setIndeterminate(node.optBoolean("indeterminate", false));
                if (node.has("color")) {
                    bar.setProgressTintList(
                            ColorStateList.valueOf(
                                    LemurXUiHost.parseColor(node.optString("color"), Color.BLUE)));
                }
                view = bar;
                break;
            }
            case "divider": {
                View line = new View(activity);
                line.setBackgroundColor(
                        LemurXUiHost.parseColor(node.optString("color", "#33000000"), 0x33000000));
                view = line;
                break;
            }
            case "spacer":
            default: {
                view = new View(activity);
                break;
            }
        }
        applyCommon(activity, view, node, mount);
        return view;
    }

    private static void addChildren(Activity activity, ViewGroup parent, JSONObject node, Mount mount)
            throws Exception {
        JSONArray children = node.optJSONArray("children");
        if (children == null) {
            return;
        }
        for (int i = 0; i < children.length(); i++) {
            JSONObject childNode = children.optJSONObject(i);
            if (childNode == null) {
                // 允许直接写字符串当文本节点
                String text = children.optString(i, "");
                childNode = new JSONObject();
                childNode.put("type", "text");
                childNode.put("text", text);
            }
            View child = build(activity, childNode, mount);
            if (child != null) {
                parent.addView(child, layoutParamsFor(activity, parent, childNode));
            }
        }
    }

    private static void applyCommon(Activity activity, View view, JSONObject node, Mount mount) {
        String id = node.optString("id", "");
        if (!TextUtils.isEmpty(id)) {
            view.setTag(id);
            if (mount != null) {
                mount.ids.add(id);
            }
        }
        applyStyle(activity, view, node);
        final String slot = mount == null ? "" : mount.slot;
        if (node.optBoolean("onClick", false) && !TextUtils.isEmpty(id)) {
            view.setClickable(true);
            view.setOnClickListener(v -> dispatch(id, id, "click", slot, null, null));
            addRipple(activity, view);
        }
        if (node.optBoolean("onLongClick", false) && !TextUtils.isEmpty(id)) {
            view.setLongClickable(true);
            view.setOnLongClickListener(
                    v -> {
                        dispatch(id + ":long", id, "longclick", slot, null, null);
                        return true;
                    });
            addRipple(activity, view);
        }
    }

    /**
     * 通用样式：width/height/padding/margin/background/radius/visible/enabled/alpha/elevation/
     * rotation/desc/tooltip/minWidth/minHeight，文本类再叠 text/color/size/bold/...，
     * 图片类再叠 icon/tint/scale。既给 render 用也给 ui.style 用。
     */
    private static void applyStyle(Activity activity, View view, JSONObject node) {
        if (node.has("padding") || node.has("paddingH") || node.has("paddingV")) {
            int[] p = insets(activity, node, "padding", view.getPaddingLeft(), view.getPaddingTop(),
                    view.getPaddingRight(), view.getPaddingBottom());
            view.setPadding(p[0], p[1], p[2], p[3]);
        }
        if (node.has("background") || (node.has("radius") && !(view instanceof TextView
                && "button".equals(node.optString("type"))))) {
            Drawable bg = backgroundOf(activity, node);
            if (bg != null) {
                view.setBackground(bg);
            }
        }
        if (node.has("visible")) {
            view.setVisibility(node.optBoolean("visible", true) ? View.VISIBLE : View.GONE);
        }
        if (node.has("enabled")) {
            view.setEnabled(node.optBoolean("enabled", true));
        }
        if (node.has("alpha")) {
            view.setAlpha((float) node.optDouble("alpha", 1));
        }
        if (node.has("elevation")) {
            view.setElevation(dp(activity, node.optInt("elevation", 0)));
        }
        if (node.has("rotation")) {
            view.setRotation((float) node.optDouble("rotation", 0));
        }
        if (node.has("desc")) {
            view.setContentDescription(node.optString("desc"));
        }
        if (node.has("tooltip") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.setTooltipText(node.optString("tooltip"));
        }
        if (node.has("minWidth")) {
            view.setMinimumWidth(dp(activity, node.optInt("minWidth")));
        }
        if (node.has("minHeight")) {
            view.setMinimumHeight(dp(activity, node.optInt("minHeight")));
        }
        if (view instanceof TextView && !(view instanceof Switch && !node.has("text"))) {
            // render 时 applyText 已经做过；ui.style 走到这里补一遍文本属性
            if (node.has("text") && !(view instanceof EditText)) {
                ((TextView) view).setText(node.optString("text"));
            } else if (node.has("value") && view instanceof EditText) {
                ((EditText) view).setText(node.optString("value"));
            }
            applyTextStyle(activity, (TextView) view, node);
        }
        if (view instanceof ImageView) {
            ImageView image = (ImageView) view;
            if (node.has("icon") || node.has("src")) {
                Drawable drawable =
                        resolveIcon(activity, node.optString("icon", node.optString("src", "")));
                if (drawable != null) {
                    image.setImageDrawable(drawable);
                }
            }
            if (node.has("tint")) {
                String tint = node.optString("tint", "");
                image.setImageTintList(
                        TextUtils.isEmpty(tint) || "none".equals(tint)
                                ? null
                                : ColorStateList.valueOf(
                                        LemurXUiHost.parseColor(tint, Color.GRAY)));
            }
            if (node.has("scale")) {
                image.setScaleType(scaleTypeOf(node.optString("scale")));
            }
        }
        if (view instanceof ProgressBar && !(view instanceof android.widget.AbsSeekBar)) {
            ProgressBar bar = (ProgressBar) view;
            if (node.has("value")) {
                bar.setProgress(node.optInt("value"));
            }
            if (node.has("max")) {
                bar.setMax(node.optInt("max"));
            }
        }
        if (view instanceof Switch && node.has("checked")) {
            ((Switch) view).setChecked(node.optBoolean("checked"));
        }
        // 尺寸 / 外边距 / 权重只有已经挂在父容器上（或 ui.style 场景）时才能改
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            boolean changed = false;
            if (node.has("width")) {
                lp.width = sizeOf(activity, node.opt("width"), lp.width);
                changed = true;
            }
            if (node.has("height")) {
                lp.height = sizeOf(activity, node.opt("height"), lp.height);
                changed = true;
            }
            if (lp instanceof ViewGroup.MarginLayoutParams
                    && (node.has("margin") || node.has("marginH") || node.has("marginV"))) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                int[] m = insets(activity, node, "margin", mlp.leftMargin, mlp.topMargin,
                        mlp.rightMargin, mlp.bottomMargin);
                mlp.setMargins(m[0], m[1], m[2], m[3]);
                changed = true;
            }
            if (lp instanceof LinearLayout.LayoutParams && node.has("weight")) {
                ((LinearLayout.LayoutParams) lp).weight = (float) node.optDouble("weight", 0);
                changed = true;
            }
            if (node.has("gravity")) {
                int gravity = LemurXUiHost.parseGravity(node.optString("gravity"));
                if (lp instanceof LinearLayout.LayoutParams) {
                    ((LinearLayout.LayoutParams) lp).gravity = gravity;
                    changed = true;
                } else if (lp instanceof FrameLayout.LayoutParams) {
                    ((FrameLayout.LayoutParams) lp).gravity = gravity;
                    changed = true;
                }
            }
            if (changed) {
                view.setLayoutParams(lp);
            }
        }
    }

    private static void applyText(Activity activity, TextView text, JSONObject node,
            int defaultSize, int defaultColor) {
        text.setText(node.optString("text", ""));
        text.setTextColor(LemurXUiHost.parseColor(node.optString("color", ""), defaultColor));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) node.optDouble("size", defaultSize));
        applyTextStyle(activity, text, node);
    }

    private static void applyTextStyle(Activity activity, TextView text, JSONObject node) {
        if (node.has("color")) {
            text.setTextColor(LemurXUiHost.parseColor(node.optString("color"), text.getCurrentTextColor()));
        }
        if (node.has("size")) {
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) node.optDouble("size", 14));
        }
        boolean bold = node.optBoolean("bold", false);
        boolean italic = node.optBoolean("italic", false);
        if (node.has("bold") || node.has("italic") || node.has("font")) {
            Typeface base = Typeface.DEFAULT;
            String font = node.optString("font", "");
            if ("monospace".equals(font)) {
                base = Typeface.MONOSPACE;
            } else if ("serif".equals(font)) {
                base = Typeface.SERIF;
            } else if ("sans".equals(font) || "sans-serif".equals(font)) {
                base = Typeface.SANS_SERIF;
            } else if (font.startsWith("file:")) {
                try {
                    base = Typeface.createFromFile(userFile(font.substring("file:".length())));
                } catch (Exception ignored) {
                    // 字体文件坏了就用默认
                }
            }
            int style = (bold ? Typeface.BOLD : 0) | (italic ? Typeface.ITALIC : 0);
            text.setTypeface(base, style);
        }
        if (node.has("maxLines")) {
            text.setMaxLines(node.optInt("maxLines", 1));
        }
        if (node.optBoolean("ellipsize", false)) {
            text.setEllipsize(TextUtils.TruncateAt.END);
        }
        if (node.optBoolean("singleLine", false)) {
            text.setSingleLine(true);
        }
        if (node.has("align")) {
            text.setGravity(LemurXUiHost.parseGravity(node.optString("align")));
        }
        if (node.has("underline")) {
            if (node.optBoolean("underline")) {
                text.setPaintFlags(text.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            } else {
                text.setPaintFlags(text.getPaintFlags() & ~Paint.UNDERLINE_TEXT_FLAG);
            }
        }
        if (node.has("strike")) {
            if (node.optBoolean("strike")) {
                text.setPaintFlags(text.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            } else {
                text.setPaintFlags(text.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            }
        }
        if (node.has("lineSpacing")) {
            text.setLineSpacing(0, (float) node.optDouble("lineSpacing", 1));
        }
        if (node.has("hint") && text instanceof EditText) {
            text.setHint(node.optString("hint"));
        }
    }

    private static ViewGroup.LayoutParams layoutParamsFor(Activity activity, ViewGroup parent,
            JSONObject node) {
        String type = node.optString("type", "");
        boolean isLinear = parent instanceof LinearLayout;
        boolean horizontal =
                isLinear && ((LinearLayout) parent).getOrientation() == LinearLayout.HORIZONTAL;
        int defaultW = ViewGroup.LayoutParams.WRAP_CONTENT;
        int defaultH = ViewGroup.LayoutParams.WRAP_CONTENT;
        if ("divider".equals(type)) {
            defaultW = horizontal ? dp(activity, 1) : ViewGroup.LayoutParams.MATCH_PARENT;
            defaultH = horizontal ? ViewGroup.LayoutParams.MATCH_PARENT : dp(activity, 1);
        } else if ("icon".equals(type)) {
            defaultW = dp(activity, 48);
            defaultH = dp(activity, 48);
        } else if ("progress".equals(type) || "edit".equals(type)) {
            defaultW = ViewGroup.LayoutParams.MATCH_PARENT;
        } else if ("column".equals(type) || "scroll".equals(type)) {
            defaultW = horizontal ? ViewGroup.LayoutParams.WRAP_CONTENT
                    : ViewGroup.LayoutParams.MATCH_PARENT;
        } else if ("row".equals(type) || "hscroll".equals(type)) {
            defaultW = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        int width = sizeOf(activity, node.opt("width"), defaultW);
        int height = sizeOf(activity, node.opt("height"), defaultH);
        ViewGroup.MarginLayoutParams lp;
        if (isLinear) {
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(width, height);
            double weight = node.optDouble("weight", "spacer".equals(type) && !node.has("width")
                    && !node.has("height") ? 1 : 0);
            if (weight > 0) {
                llp.weight = (float) weight;
                if (horizontal && !node.has("width")) {
                    llp.width = 0;
                } else if (!horizontal && !node.has("height")) {
                    llp.height = 0;
                }
            }
            if (node.has("gravity")) {
                llp.gravity = LemurXUiHost.parseGravity(node.optString("gravity"));
            }
            lp = llp;
        } else if (parent instanceof FrameLayout) {
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(width, height);
            if (node.has("gravity")) {
                flp.gravity = LemurXUiHost.parseGravity(node.optString("gravity"));
            }
            lp = flp;
        } else {
            lp = new ViewGroup.MarginLayoutParams(width, height);
        }
        if (node.has("margin") || node.has("marginH") || node.has("marginV")) {
            int[] m = insets(activity, node, "margin", 0, 0, 0, 0);
            lp.setMargins(m[0], m[1], m[2], m[3]);
        }
        return lp;
    }

    // ---------------------------------------------------------------- ui.style

    private static int applyStyleQuery(Activity activity, JSONObject query, JSONObject style) {
        List<View> hits = LemurXUiHost.findViews(query, query.optInt("max", 50));
        for (View view : hits) {
            try {
                applyStyle(activity, view, style);
                if (style.has("text") && view instanceof TextView && !(view instanceof EditText)) {
                    ((TextView) view).setText(style.optString("text"));
                }
            } catch (Exception e) {
                LemurLogUtils.i(TAG, "style failed", e.getMessage());
            }
        }
        return hits.size();
    }

    // ---------------------------------------------------------------- 回调

    private static void dispatch(String key, String id, String action, String slot,
            String extraKey, Object extraValue) {
        try {
            JSONObject ev = new JSONObject();
            ev.put("id", id);
            ev.put("action", action);
            ev.put("slot", slot);
            if (extraKey != null) {
                ev.put(extraKey, extraValue);
            }
            LemurXBridge.notifyUiClick(key, ev.toString());
        } catch (Exception e) {
            LemurXBridge.notifyUiClick(key);
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static void addRipple(Activity activity, View view) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || view.getForeground() != null) {
            return;
        }
        try {
            TypedValue value = new TypedValue();
            activity.getTheme()
                    .resolveAttribute(android.R.attr.selectableItemBackground, value, true);
            if (value.resourceId != 0) {
                view.setForeground(activity.getDrawable(value.resourceId));
            }
        } catch (Exception ignored) {
            // 没有 ripple 也不影响点击
        }
    }

    private static Drawable backgroundOf(Activity activity, JSONObject node) {
        Object raw = node.opt("background");
        int radius = dp(activity, node.optInt("radius", 0));
        if (raw instanceof JSONObject) {
            JSONObject spec = (JSONObject) raw;
            GradientDrawable bg = new GradientDrawable();
            JSONArray gradient = spec.optJSONArray("gradient");
            if (gradient != null && gradient.length() >= 2) {
                int[] colors = new int[gradient.length()];
                for (int i = 0; i < gradient.length(); i++) {
                    colors[i] = LemurXUiHost.parseColor(gradient.optString(i), Color.TRANSPARENT);
                }
                bg.setColors(colors);
                bg.setOrientation(
                        "vertical".equals(spec.optString("orientation", "horizontal"))
                                ? GradientDrawable.Orientation.TOP_BOTTOM
                                : GradientDrawable.Orientation.LEFT_RIGHT);
            } else {
                bg.setColor(LemurXUiHost.parseColor(spec.optString("color", "#00000000"), 0));
            }
            bg.setCornerRadius(dp(activity, spec.optInt("radius", node.optInt("radius", 0))));
            if (spec.has("stroke")) {
                bg.setStroke(
                        dp(activity, spec.optInt("stroke", 1)),
                        LemurXUiHost.parseColor(spec.optString("strokeColor", "#33000000"), 0x33000000));
            }
            if (spec.optBoolean("ripple", false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return new RippleDrawable(ColorStateList.valueOf(0x33000000), bg, null);
            }
            return bg;
        }
        String value = raw == null ? "" : String.valueOf(raw);
        if (value.startsWith("drawable:")) {
            return resolveIcon(activity, value.substring("drawable:".length()));
        }
        if (value.startsWith("file:") || value.startsWith("data:")) {
            return resolveIcon(activity, value);
        }
        if (TextUtils.isEmpty(value) && radius == 0) {
            return null;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(LemurXUiHost.parseColor(value, Color.TRANSPARENT));
        bg.setCornerRadius(radius);
        return bg;
    }

    /**
     * icon 取值：apk 里的 drawable 名（如 "ic_search"）、"android:ic_menu_search"、
     * "file:relative/path.png"（相对 files/lua/）、"data:image/png;base64,..."。
     */
    private static Drawable resolveIcon(Activity activity, String icon) {
        if (TextUtils.isEmpty(icon)) {
            return null;
        }
        try {
            if (icon.startsWith("data:")) {
                int comma = icon.indexOf(',');
                byte[] bytes = Base64.decode(icon.substring(comma + 1), Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                return bitmap == null ? null : new BitmapDrawable(activity.getResources(), bitmap);
            }
            if (icon.startsWith("file:")) {
                File file = userFile(icon.substring("file:".length()));
                Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                return bitmap == null ? null : new BitmapDrawable(activity.getResources(), bitmap);
            }
            if (icon.startsWith("android:")) {
                int id =
                        android.content.res.Resources.getSystem()
                                .getIdentifier(icon.substring("android:".length()), "drawable", "android");
                return id == 0 ? null : activity.getDrawable(id);
            }
            String name = icon.startsWith("drawable:") ? icon.substring("drawable:".length()) : icon;
            int id = activity.getResources().getIdentifier(name, "drawable", activity.getPackageName());
            if (id == 0) {
                id = activity.getResources().getIdentifier(name, "mipmap", activity.getPackageName());
            }
            return id == 0 ? null : activity.getDrawable(id);
        } catch (Exception e) {
            LemurLogUtils.i(TAG, "icon failed", icon, e.getMessage());
            return null;
        }
    }

    private static File userFile(String relative) {
        File root = new File(ContextUtils.getApplicationContext().getFilesDir(), "lua");
        return new File(root, relative);
    }

    private static ImageView.ScaleType scaleTypeOf(String scale) {
        switch (scale) {
            case "crop":
                return ImageView.ScaleType.CENTER_CROP;
            case "center":
                return ImageView.ScaleType.CENTER_INSIDE;
            case "fill":
                return ImageView.ScaleType.FIT_XY;
            case "fit":
            default:
                return ImageView.ScaleType.FIT_CENTER;
        }
    }

    private static int inputTypeOf(String type) {
        switch (type) {
            case "number":
                return InputType.TYPE_CLASS_NUMBER;
            case "url":
                return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;
            case "password":
                return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
            case "multiline":
                return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
            case "text":
            default:
                return InputType.TYPE_CLASS_TEXT;
        }
    }

    /** "match" / "wrap" / 数字(dp) / "0" */
    private static int sizeOf(Activity activity, Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number) {
            return dp(activity, ((Number) value).intValue());
        }
        String s = String.valueOf(value);
        if ("match".equals(s) || "match_parent".equals(s) || "fill".equals(s)) {
            return ViewGroup.LayoutParams.MATCH_PARENT;
        }
        if ("wrap".equals(s) || "wrap_content".equals(s)) {
            return ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        try {
            return dp(activity, Integer.parseInt(s.replace("dp", "").trim()));
        } catch (Exception e) {
            return fallback;
        }
    }

    /** padding/margin：数字、[l,t,r,b]、{left,top,right,bottom} 或 xxxH/xxxV */
    private static int[] insets(Activity activity, JSONObject node, String key,
            int l, int t, int r, int b) {
        int[] out = {l, t, r, b};
        Object raw = node.opt(key);
        if (raw instanceof Number) {
            int v = dp(activity, ((Number) raw).intValue());
            out = new int[] {v, v, v, v};
        } else if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            if (arr.length() == 2) {
                int h = dp(activity, arr.optInt(0));
                int v = dp(activity, arr.optInt(1));
                out = new int[] {h, v, h, v};
            } else if (arr.length() >= 4) {
                out = new int[] {
                    dp(activity, arr.optInt(0)), dp(activity, arr.optInt(1)),
                    dp(activity, arr.optInt(2)), dp(activity, arr.optInt(3))
                };
            }
        } else if (raw instanceof JSONObject) {
            JSONObject obj = (JSONObject) raw;
            out = new int[] {
                obj.has("left") ? dp(activity, obj.optInt("left")) : l,
                obj.has("top") ? dp(activity, obj.optInt("top")) : t,
                obj.has("right") ? dp(activity, obj.optInt("right")) : r,
                obj.has("bottom") ? dp(activity, obj.optInt("bottom")) : b
            };
        }
        if (node.has(key + "H")) {
            int h = dp(activity, node.optInt(key + "H"));
            out[0] = h;
            out[2] = h;
        }
        if (node.has(key + "V")) {
            int v = dp(activity, node.optInt(key + "V"));
            out[1] = v;
            out[3] = v;
        }
        return out;
    }

    private static int textColorDefault() {
        return LemurThemeUtils.isDarkMode() ? 0xFFE8EAED : 0xFF202124;
    }

    private static View findByName(Activity activity, String name) {
        if (activity == null || TextUtils.isEmpty(name)) {
            return null;
        }
        int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
        View view = id == 0 ? null : activity.findViewById(id);
        return view != null ? view : LemurXUiHost.findByIdName(name);
    }

    private static int dp(Activity activity, int value) {
        return LemurXUiHost.dp(activity, value);
    }

    private static String error(String message) {
        return errorObj(message).toString();
    }

    private static JSONObject errorObj(String message) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", false);
            out.put("error", message == null ? "error" : message);
        } catch (Exception ignored) {
            // 不会失败
        }
        return out;
    }
}
