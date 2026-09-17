// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import org.chromium.base.ApplicationStatus;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * 原生覆盖层。每个控件一块 WRAP_CONTENT 的 PopupWindow，避免全屏窗口把浏览器点死。
 * 点击通过 {@link LemurXBridge} 回到 Lua 线程。
 */
class LemurXUiHost {
    private static final String TAG = "LemurX";
    private static final Map<String, View> sOverlays = new HashMap<>();
    private static final Map<String, String> sOverlayJson = new LinkedHashMap<>();
    private static final Map<String, PopupWindow> sPopups = new HashMap<>();
    private static int sNextId = 1;

    /**
     * 在 UI 线程同步跑一个 Callable，失败返回 null。Chromium 154 去掉了
     * ThreadUtils.runOnUiThreadBlockingNoException，runOnUiThreadBlocking(Callable) 会把
     * 任务里的异常包成 RuntimeException 抛出，这里兜住以维持旧的“出错返回 null”语义。
     */
    private static <T> T uiBlocking(Callable<T> task) {
        try {
            return ThreadUtils.runOnUiThreadBlocking(task);
        } catch (RuntimeException e) {
            Log.i(TAG, "ui thread task failed: %s", e.toString());
            return null;
        }
    }

    static String show(String optionsJson) {
        return uiBlocking(
                () -> {
                    Activity activity = hostActivity();
                    if (activity == null) {
                        return "";
                    }
                    JSONObject options;
                    try {
                        options = TextUtils.isEmpty(optionsJson)
                                ? new JSONObject()
                                : new JSONObject(optionsJson);
                    } catch (Exception e) {
                        Log.i(TAG, "ui.show parse: %s", e.getMessage());
                        return "";
                    }
                    String id = options.optString("id", "");
                    if (TextUtils.isEmpty(id)) {
                        id = "overlay_" + (sNextId++);
                    }
                    sOverlayJson.put(id, options.toString());
                    return addView(activity, id, options);
                });
    }

    static boolean remove(String id) {
        Boolean removed =
                uiBlocking(
                        () -> {
                            sOverlayJson.remove(id);
                            return removeView(id);
                        });
        return removed != null && removed;
    }

    static void clear() {
        ThreadUtils.runOnUiThread(
                () -> {
                    sOverlayJson.clear();
                    dismissAllPopups();
                });
    }

    /** 用户关掉 Lua / 重载脚本时（UI 线程）：所有覆盖层立刻拆掉，且 reattach() 无物可重放。 */
    static void resetAll() {
        ThreadUtils.assertOnUiThread();
        sOverlayJson.clear();
        dismissAllPopups();
    }

    static String op(String action, String json) {
        String act = action == null ? "" : action.toLowerCase(Locale.US);
        if ("dump".equals(act)) {
            return dump(json);
        }
        if ("find".equals(act)) {
            return find(json);
        }
        if ("click".equals(act) || "longclick".equals(act) || "long_click".equals(act)) {
            return click(json, act.contains("long"));
        }
        if ("settext".equals(act) || "set_text".equals(act)) {
            return setText(json);
        }
        if ("gettext".equals(act) || "get_text".equals(act)) {
            return getText(json);
        }
        if ("visible".equals(act) || "setvisible".equals(act)) {
            return setVisible(json);
        }
        if ("enabled".equals(act) || "setenabled".equals(act)) {
            return setEnabled(json);
        }
        if ("dialog".equals(act) || "prompt".equals(act) || "alert".equals(act)) {
            return dialog(json);
        }
        if ("render".equals(act)
                || "unmount".equals(act)
                || "style".equals(act)
                || "slots".equals(act)
                || "replace".equals(act)
                || "insert".equals(act)
                || "detach".equals(act)
                || "restore".equals(act)
                || "move".equals(act)
                || "children".equals(act)
                || "on".equals(act)
                || "off".equals(act)
                || "shell".equals(act)
                || act.startsWith("theme_")) {
            return LemurXSkinHost.op(act, json);
        }
        JSONObject err = new JSONObject();
        try {
            err.put("ok", false);
            err.put("error", "unknown ui op " + action);
        } catch (Exception ignored) {
            // 保持已写入字段
        }
        return err.toString();
    }

    static void reattach(Activity activity) {
        ThreadUtils.assertOnUiThread();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        Map<String, String> json = new LinkedHashMap<>(sOverlayJson);
        dismissAllPopups();
        if (json.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> item : json.entrySet()) {
            try {
                addView(activity, item.getKey(), new JSONObject(item.getValue()));
            } catch (Exception e) {
                Log.i(TAG, "reattach overlay %s: %s", item.getKey(), e.getMessage());
            }
        }
    }

    private static String addView(Activity activity, String id, JSONObject options) {
        removeView(id);
        View view = createView(activity, options);
        view.setTag(id);
        view.setOnClickListener(v -> LemurXBridge.notifyUiClick(id));
        boolean editable = view instanceof EditText;
        int width = options.has("width")
                ? dp(activity, options.optInt("width"))
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        int height = options.has("height")
                ? dp(activity, options.optInt("height"))
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        PopupWindow popup = new PopupWindow(view, width, height, editable);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setTouchable(true);
        popup.setFocusable(editable);
        popup.setOutsideTouchable(false);
        popup.setClippingEnabled(false);
        popup.setInputMethodMode(
                editable
                        ? PopupWindow.INPUT_METHOD_NEEDED
                        : PopupWindow.INPUT_METHOD_NOT_NEEDED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            popup.setTouchModal(false);
        }
        int gravity = parseGravity(options.optString("gravity", "end|bottom"));
        int x = dp(activity, options.optInt("x", 16));
        int y = dp(activity, options.optInt("y", 80));
        View token = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        Runnable show =
                () -> {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    View decor =
                            activity.getWindow() == null
                                    ? null
                                    : activity.getWindow().getDecorView();
                    if (decor == null || !decor.isAttachedToWindow()) {
                        return;
                    }
                    try {
                        if (popup.isShowing()) {
                            popup.update(x, y, -1, -1);
                        } else {
                            popup.showAtLocation(decor, gravity, x, y);
                        }
                    } catch (Exception e) {
                        Log.i(TAG, "popup show failed %s: %s", id, e.getMessage());
                    }
                };
        if (token != null && token.isAttachedToWindow()) {
            show.run();
        } else if (token != null) {
            token.post(show);
        }
        sPopups.put(id, popup);
        sOverlays.put(id, view);
        return id;
    }

    private static boolean removeView(String id) {
        sOverlays.remove(id);
        PopupWindow popup = sPopups.remove(id);
        if (popup == null) {
            return false;
        }
        try {
            popup.dismiss();
        } catch (Exception ignored) {
            // 窗口可能已经拆掉
        }
        return true;
    }

    private static void dismissAllPopups() {
        for (PopupWindow popup : sPopups.values()) {
            try {
                popup.dismiss();
            } catch (Exception ignored) {
                // 窗口可能已经拆掉
            }
        }
        sPopups.clear();
        sOverlays.clear();
    }

    static Activity hostActivity() {
        Activity focused = ApplicationStatus.getLastTrackedFocusedActivity();
        if (focused instanceof ChromeTabbedActivity) {
            return focused;
        }
        ChromeTabbedActivity tabbed = LemurXBridge.currentActivity();
        return tabbed != null ? tabbed : focused;
    }

    private static View createView(Activity activity, JSONObject options) {
        String type = options.optString("type", "button");
        String text = options.optString("text", "");
        int textColor = parseColor(options.optString("color", "#FFFFFFFF"), Color.WHITE);
        int bgColor = parseColor(options.optString("background", "#E64CAF50"), 0xE64CAF50);
        int radius = dp(activity, options.optInt("radius", 24));
        int paddingH = dp(activity, options.optInt("paddingH", 16));
        int paddingV = dp(activity, options.optInt("paddingV", 10));

        GradientDrawable background = new GradientDrawable();
        background.setColor(bgColor);
        background.setCornerRadius(radius);

        TextView view;
        if ("text".equals(type)) {
            view = new TextView(activity);
        } else if ("edit".equals(type) || "input".equals(type)) {
            EditText edit = new EditText(activity);
            edit.setHint(options.optString("hint", ""));
            edit.setSingleLine(true);
            view = edit;
        } else {
            Button button = new Button(activity);
            button.setAllCaps(false);
            button.setStateListAnimator(null);
            view = button;
        }
        view.setText(text);
        view.setTextColor(textColor);
        view.setBackground(background);
        view.setPadding(paddingH, paddingV, paddingH, paddingV);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) options.optDouble("textSize", 14));
        view.setElevation(dp(activity, 8));
        view.setClickable(true);
        return view;
    }

    private static String dump(String json) {
        String result =
                uiBlocking(
                        () -> {
                            JSONObject out = new JSONObject();
                            try {
                                JSONObject opt = parseJson(json);
                                int maxDepth = opt.optInt("maxDepth", 12);
                                int maxNodes = opt.optInt("maxNodes", 800);
                                boolean gone = opt.optBoolean("gone", true);
                                int[] count = {0};
                                JSONArray roots = new JSONArray();
                                List<View> windowRoots = allWindowRoots();
                                if (windowRoots.isEmpty()) {
                                    out.put("ok", false);
                                    out.put("error", "no activity");
                                    return out.toString();
                                }
                                for (View root : windowRoots) {
                                    if (count[0] >= maxNodes) {
                                        break;
                                    }
                                    JSONObject dumped =
                                            dumpView(root, 0, maxDepth, maxNodes, gone, count);
                                    dumped.put("window", true);
                                    roots.put(dumped);
                                }
                                out.put("ok", true);
                                out.put("nodes", count[0]);
                                out.put("windows", windowRoots.size());
                                out.put("roots", roots);
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
        return result == null ? "{\"ok\":false}" : result;
    }

    private static String find(String json) {
        String result =
                uiBlocking(
                        () -> {
                            JSONObject out = new JSONObject();
                            try {
                                JSONObject query = parseJson(json);
                                int max = query.optInt("max", 20);
                                List<View> hits = findViews(query, max);
                                JSONArray nodes = new JSONArray();
                                for (View view : hits) {
                                    nodes.put(describe(view, false));
                                }
                                out.put("ok", !hits.isEmpty());
                                out.put("count", hits.size());
                                out.put("nodes", nodes);
                                if (!hits.isEmpty()) {
                                    out.put("node", describe(hits.get(0), false));
                                }
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
        return result == null ? "{\"ok\":false}" : result;
    }

    private static String click(String json, boolean longClick) {
        String result =
                uiBlocking(
                        () -> {
                            JSONObject out = new JSONObject();
                            try {
                                JSONObject query = parseJson(json);
                                List<View> hits = findViews(query, 1);
                                if (hits.isEmpty()) {
                                    out.put("ok", false);
                                    out.put("error", "view not found");
                                    return out.toString();
                                }
                                View view = hits.get(0);
                                boolean clicked = clickView(view, longClick);
                                out.put("ok", clicked);
                                out.put("node", describe(view, false));
                                out.put("long", longClick);
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
        return result == null ? "{\"ok\":false}" : result;
    }

    private static String setText(String json) {
        String result =
                uiBlocking(
                        () -> {
                            JSONObject out = new JSONObject();
                            try {
                                JSONObject query = parseJson(json);
                                String value = query.optString("value", query.optString("text", ""));
                                query.remove("value");
                                List<View> hits = findViews(query, 1);
                                if (hits.isEmpty()) {
                                    out.put("ok", false);
                                    out.put("error", "view not found");
                                    return out.toString();
                                }
                                View view = hits.get(0);
                                if (view instanceof TextView) {
                                    ((TextView) view).setText(value);
                                    view.requestFocus();
                                    out.put("ok", true);
                                    out.put("node", describe(view, false));
                                } else {
                                    out.put("ok", false);
                                    out.put("error", "not a text view");
                                }
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
        return result == null ? "{\"ok\":false}" : result;
    }

    private static String getText(String json) {
        String result =
                uiBlocking(
                        () -> {
                            JSONObject out = new JSONObject();
                            try {
                                List<View> hits = findViews(parseJson(json), 1);
                                if (hits.isEmpty()) {
                                    out.put("ok", false);
                                    out.put("error", "view not found");
                                    return out.toString();
                                }
                                View view = hits.get(0);
                                out.put("ok", true);
                                out.put("text", viewText(view));
                                out.put("node", describe(view, false));
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
        return result == null ? "{\"ok\":false}" : result;
    }

    private static String setVisible(String json) {
        return mutateView(
                json,
                (view, query) -> {
                    boolean show = query.optBoolean("visible", query.optBoolean("value", true));
                    view.setVisibility(show ? View.VISIBLE : View.GONE);
                    return true;
                });
    }

    private static String setEnabled(String json) {
        return mutateView(
                json,
                (view, query) -> {
                    view.setEnabled(query.optBoolean("enabled", query.optBoolean("value", true)));
                    return true;
                });
    }

    private interface ViewMutator {
        boolean apply(View view, JSONObject query) throws Exception;
    }

    private static String mutateView(String json, ViewMutator mutator) {
        String result =
                uiBlocking(
                        () -> {
                            JSONObject out = new JSONObject();
                            try {
                                JSONObject query = parseJson(json);
                                List<View> hits = findViews(query, 1);
                                if (hits.isEmpty()) {
                                    out.put("ok", false);
                                    out.put("error", "view not found");
                                    return out.toString();
                                }
                                View view = hits.get(0);
                                out.put("ok", mutator.apply(view, query));
                                out.put("node", describe(view, false));
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
        return result == null ? "{\"ok\":false}" : result;
    }

    private static String dialog(String json) {
        Activity activity = hostActivity();
        JSONObject opt = parseJson(json);
        String id = opt.optString("id", "");
        if (TextUtils.isEmpty(id)) {
            id = "dialog_" + (sNextId++);
        }
        final String dialogId = id;
        ThreadUtils.postOnUiThread(
                () -> {
                    Activity host = hostActivity();
                    if (host == null) {
                        return;
                    }
                    try {
                        AlertDialog.Builder builder = new AlertDialog.Builder(host);
                        String title = opt.optString("title", "");
                        String message = opt.optString("message", opt.optString("text", ""));
                        if (!TextUtils.isEmpty(title)) {
                            builder.setTitle(title);
                        }
                        boolean prompt = opt.optBoolean("prompt", opt.has("hint"));
                        EditText input = null;
                        if (prompt) {
                            LinearLayout box = new LinearLayout(host);
                            int pad = dp(host, 20);
                            box.setPadding(pad, dp(host, 8), pad, 0);
                            box.setOrientation(LinearLayout.VERTICAL);
                            input = new EditText(host);
                            input.setHint(opt.optString("hint", ""));
                            input.setText(opt.optString("value", ""));
                            input.setSingleLine(true);
                            box.addView(
                                    input,
                                    new LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT));
                            builder.setView(box);
                        } else if (!TextUtils.isEmpty(message)) {
                            builder.setMessage(message);
                        }
                        String ok = opt.optString("ok", prompt ? "确定" : "好");
                        String cancel = opt.optString("cancel", prompt ? "取消" : "");
                        EditText captured = input;
                        builder.setPositiveButton(
                                TextUtils.isEmpty(ok) ? "好" : ok,
                                (d, w) -> {
                                    JSONObject ev = new JSONObject();
                                    try {
                                        ev.put("id", dialogId);
                                        ev.put("action", "ok");
                                        if (captured != null) {
                                            ev.put("text", String.valueOf(captured.getText()));
                                        }
                                    } catch (Exception ignored) {
                                        // 保持已写入字段
                                    }
                                    LemurXBridge.notifyUiClick(
                                            "dialog:" + dialogId + ":ok", ev.toString());
                                });
                        if (!TextUtils.isEmpty(cancel)) {
                            builder.setNegativeButton(
                                    cancel,
                                    (d, w) -> {
                                        JSONObject ev = new JSONObject();
                                        try {
                                            ev.put("id", dialogId);
                                            ev.put("action", "cancel");
                                        } catch (Exception ignored) {
                                            // 保持已写入字段
                                        }
                                        LemurXBridge.notifyUiClick(
                                                "dialog:" + dialogId + ":cancel", ev.toString());
                                    });
                        }
                        builder.setOnCancelListener(
                                d -> {
                                    JSONObject ev = new JSONObject();
                                    try {
                                        ev.put("id", dialogId);
                                        ev.put("action", "cancel");
                                    } catch (Exception ignored) {
                                        // 保持已写入字段
                                    }
                                    LemurXBridge.notifyUiClick(
                                            "dialog:" + dialogId + ":cancel", ev.toString());
                                });
                        builder.show();
                    } catch (Exception e) {
                        Log.i(TAG, "ui.dialog: %s", e.getMessage());
                    }
                });
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
            out.put("id", dialogId);
        } catch (Exception ignored) {
            // 保持已写入字段
        }
        return out.toString();
    }

    private static JSONObject dumpView(
            View view, int depth, int maxDepth, int maxNodes, boolean gone, int[] count)
            throws Exception {
        JSONObject node = describe(view, false);
        count[0]++;
        if (!(view instanceof ViewGroup) || depth >= maxDepth || count[0] >= maxNodes) {
            return node;
        }
        JSONArray children = new JSONArray();
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (count[0] >= maxNodes) {
                break;
            }
            View child = group.getChildAt(i);
            if (child == null) {
                continue;
            }
            if (!gone && child.getVisibility() != View.VISIBLE) {
                continue;
            }
            children.put(dumpView(child, depth + 1, maxDepth, maxNodes, gone, count));
        }
        if (children.length() > 0) {
            node.put("children", children);
        }
        return node;
    }

    static List<View> findViews(JSONObject query, int max) {
        List<View> out = new ArrayList<>();
        if (query == null) {
            return out;
        }
        for (View root : allWindowRoots()) {
            collect(root, query, out, max);
            if (out.size() >= max) {
                break;
            }
        }
        return out;
    }

    /**
     * 所有窗口根：Activity、对话框、PopupWindow、系统浮层。
     * 走 WindowManagerGlobal，工具抽屉 / 系统 Dialog 才进 dump/find。
     */
    static List<View> allWindowRoots() {
        List<View> roots = new ArrayList<>();
        Set<View> seen = new HashSet<>();
        try {
            Class<?> cls = Class.forName("android.view.WindowManagerGlobal");
            Object inst = cls.getMethod("getInstance").invoke(null);
            Object list = null;
            try {
                list = cls.getMethod("getRootViews").invoke(inst);
            } catch (NoSuchMethodException ignored) {
                java.lang.reflect.Field field = cls.getDeclaredField("mViews");
                field.setAccessible(true);
                list = field.get(inst);
            }
            if (list instanceof List) {
                for (Object item : (List<?>) list) {
                    if (item instanceof View && seen.add((View) item)) {
                        roots.add((View) item);
                    }
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "window roots: %s", e.getMessage());
        }
        Activity activity = hostActivity();
        if (activity != null && activity.getWindow() != null) {
            View decor = activity.getWindow().getDecorView();
            if (decor != null && seen.add(decor)) {
                roots.add(0, decor);
            }
        }
        for (View overlay : sOverlays.values()) {
            if (overlay != null && seen.add(overlay)) {
                roots.add(overlay);
            }
        }
        for (View root : LemurXSkinHost.popupRoots()) {
            if (root != null && seen.add(root)) {
                roots.add(root);
            }
        }
        return roots;
    }

    static View findByIdName(String name) {
        if (TextUtils.isEmpty(name)) {
            return null;
        }
        Activity activity = hostActivity();
        if (activity != null) {
            int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
            if (id != 0) {
                View view = activity.findViewById(id);
                if (view != null) {
                    return view;
                }
            }
        }
        JSONObject query = new JSONObject();
        try {
            query.put("id", name);
        } catch (Exception ignored) {
            return null;
        }
        List<View> hits = findViews(query, 1);
        return hits.isEmpty() ? null : hits.get(0);
    }

    private static void collect(View view, JSONObject query, List<View> out, int max) {
        if (view == null || out.size() >= max) {
            return;
        }
        if (match(view, query)) {
            out.add(view);
            if (out.size() >= max) {
                return;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), query, out, max);
                if (out.size() >= max) {
                    return;
                }
            }
        }
    }

    private static boolean match(View view, JSONObject query) {
        if (query == null) {
            return false;
        }
        String id = query.optString("id", "");
        String text = query.optString("text", query.optString("contains", ""));
        String desc = query.optString("desc", query.optString("contentDescription", ""));
        String cls = query.optString("class", query.optString("type", ""));
        boolean clickable = query.optBoolean("clickable", false);
        boolean requireClickable = query.has("clickable");
        if (!TextUtils.isEmpty(id)) {
            String name = viewIdName(view);
            String rid = resourceEntryName(view);
            if (!id.equals(name)
                    && !name.contains(id)
                    && !id.equals(rid)
                    && !rid.contains(id)
                    && !String.valueOf(view.getId()).equals(id)) {
                return false;
            }
        }
        if (!TextUtils.isEmpty(text)) {
            String hay = (viewText(view) + " " + descOf(view)).toLowerCase(Locale.US);
            if (!hay.contains(text.toLowerCase(Locale.US))) {
                return false;
            }
        }
        if (!TextUtils.isEmpty(desc)) {
            if (!descOf(view).toLowerCase(Locale.US).contains(desc.toLowerCase(Locale.US))) {
                return false;
            }
        }
        if (!TextUtils.isEmpty(cls)) {
            if (!view.getClass().getName().toLowerCase(Locale.US).contains(cls.toLowerCase(Locale.US))
                    && !view.getClass()
                            .getSimpleName()
                            .toLowerCase(Locale.US)
                            .contains(cls.toLowerCase(Locale.US))) {
                return false;
            }
        }
        if (requireClickable && view.isClickable() != clickable) {
            return false;
        }
        return !TextUtils.isEmpty(id)
                || !TextUtils.isEmpty(text)
                || !TextUtils.isEmpty(desc)
                || !TextUtils.isEmpty(cls)
                || requireClickable;
    }

    static JSONObject describe(View view, boolean withChildren) throws Exception {
        JSONObject node = new JSONObject();
        node.put("class", view.getClass().getSimpleName());
        node.put("id", viewIdName(view));
        String rid = resourceEntryName(view);
        if (!TextUtils.isEmpty(rid) && !rid.equals(node.optString("id"))) {
            node.put("rid", rid);
        }
        node.put("text", viewText(view));
        node.put("desc", descOf(view));
        node.put("clickable", view.isClickable());
        node.put("enabled", view.isEnabled());
        node.put("focused", view.isFocused());
        int vis = view.getVisibility();
        node.put("visible", vis == View.VISIBLE);
        node.put(
                "visibility",
                vis == View.VISIBLE ? "visible" : vis == View.INVISIBLE ? "invisible" : "gone");
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        node.put("x", loc[0]);
        node.put("y", loc[1]);
        node.put("w", view.getWidth());
        node.put("h", view.getHeight());
        if (view.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view.getParent();
            node.put("index", parent.indexOfChild(view));
            node.put("parent", viewIdName(parent));
        }
        if (view instanceof ViewGroup) {
            node.put("childCount", ((ViewGroup) view).getChildCount());
        }
        return node;
    }

    static String resourceEntryName(View view) {
        int id = view.getId();
        if (id == View.NO_ID) {
            return "";
        }
        try {
            return view.getResources().getResourceEntryName(id);
        } catch (Exception e) {
            return "";
        }
    }

    private static String viewIdName(View view) {
        // lemurx.ui.render 生成的控件用 tag 记 Lua 侧 id，优先返回它
        Object tag = view.getTag();
        if (tag instanceof String && !TextUtils.isEmpty((String) tag)) {
            return (String) tag;
        }
        int id = view.getId();
        if (id == View.NO_ID) {
            return "";
        }
        return resourceEntryName(view);
    }

    private static String viewText(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            return text == null ? "" : text.toString();
        }
        return "";
    }

    private static String descOf(View view) {
        CharSequence desc = view.getContentDescription();
        return desc == null ? "" : desc.toString();
    }

    private static boolean clickView(View view, boolean longClick) {
        if (longClick) {
            if (view.performLongClick()) {
                return true;
            }
        } else if (view.performClick()) {
            return true;
        }
        View current = view;
        while (current != null) {
            if (current.isClickable()) {
                if (longClick && current.performLongClick()) {
                    return true;
                }
                if (!longClick && current.performClick()) {
                    return true;
                }
            }
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return tapCenter(view, longClick);
    }

    private static boolean tapCenter(View view, boolean longClick) {
        if (view.getWidth() <= 0 || view.getHeight() <= 0) {
            return false;
        }
        float x = view.getWidth() / 2f;
        float y = view.getHeight() / 2f;
        long down = SystemClock.uptimeMillis();
        MotionEvent press =
                MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        boolean ok = view.dispatchTouchEvent(press);
        press.recycle();
        long hold = longClick ? 700 : 40;
        long upTime = down + hold;
        MotionEvent release =
                MotionEvent.obtain(down, upTime, MotionEvent.ACTION_UP, x, y, 0);
        ok = view.dispatchTouchEvent(release) || ok;
        release.recycle();
        return ok;
    }

    static JSONObject parseJson(String json) {
        if (TextUtils.isEmpty(json)) {
            return new JSONObject();
        }
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static int parseGravity(String gravity) {
        if (TextUtils.isEmpty(gravity)) {
            return Gravity.END | Gravity.BOTTOM;
        }
        int result = Gravity.NO_GRAVITY;
        String[] parts = gravity.split("\\|");
        for (String part : parts) {
            switch (part.trim()) {
                case "start":
                    result |= Gravity.START;
                    break;
                case "end":
                    result |= Gravity.END;
                    break;
                case "top":
                    result |= Gravity.TOP;
                    break;
                case "bottom":
                    result |= Gravity.BOTTOM;
                    break;
                case "center":
                    result |= Gravity.CENTER;
                    break;
                case "center_horizontal":
                    result |= Gravity.CENTER_HORIZONTAL;
                    break;
                case "center_vertical":
                    result |= Gravity.CENTER_VERTICAL;
                    break;
                default:
                    break;
            }
        }
        return result == Gravity.NO_GRAVITY ? Gravity.END | Gravity.BOTTOM : result;
    }

    static int parseColor(String value, int fallback) {
        if (TextUtils.isEmpty(value)) {
            return fallback;
        }
        try {
            return Color.parseColor(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    static int dp(Activity activity, int value) {
        return Math.round(
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        value,
                        activity.getResources().getDisplayMetrics()));
    }

}
