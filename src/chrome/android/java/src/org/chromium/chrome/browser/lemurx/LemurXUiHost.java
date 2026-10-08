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
        // Agent 底座：屏幕坐标系的点按 / 滑动、整窗截图、网页视口映射。
        if ("tap".equals(act) || "longpress".equals(act) || "long_press".equals(act)) {
            return tapScreen(json, act.contains("long"));
        }
        if ("swipe".equals(act)) {
            return swipeScreen(json);
        }
        if ("screenshot".equals(act)) {
            return LemurXMoatHost.windowScreenshot(json);
        }
        if ("viewport".equals(act)) {
            return LemurXMoatHost.viewport(json);
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
                        JSONArray items = opt.optJSONArray("items");
                        if (items != null && items.length() > 0) {
                            // 单选列表：点某项回 dialog:<id>:select {index, text}
                            final String[] labels = new String[items.length()];
                            for (int i = 0; i < items.length(); i++) {
                                labels[i] = items.optString(i, "");
                            }
                            builder.setItems(
                                    labels,
                                    (d, which) -> {
                                        JSONObject ev = new JSONObject();
                                        try {
                                            ev.put("id", dialogId);
                                            ev.put("action", "select");
                                            ev.put("index", which);
                                            ev.put("text", labels[which]);
                                        } catch (Exception ignored) {
                                            // 保持已写入字段
                                        }
                                        LemurXBridge.notifyUiClick(
                                                "dialog:" + dialogId + ":select", ev.toString());
                                    });
                        } else if (prompt) {
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
                        String ok = opt.optString("ok", items != null ? "" : (prompt ? "确定" : "好"));
                        String cancel = opt.optString("cancel", prompt ? "取消" : "");
                        EditText captured = input;
                        if (!TextUtils.isEmpty(ok) || items == null) builder.setPositiveButton(
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
        // ref 精确命中：dump/find 给出的句柄，跳过整棵树的模糊匹配。
        if (query.has("ref")) {
            View exact = viewForRef(query.optInt("ref", -1));
            if (exact != null) {
                out.add(exact);
            }
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

    // ------------------------------------------------------------ 节点 ref

    /**
     * 稳定的节点句柄：dump/find 返回的每个节点都带一个整数 {@code ref}，之后
     * {@code lemurx.ui.click({ref=N})} / {@code lemurx.agent.act{ref="n12"}} 可以直接指回同一个
     * View，而不用再拿 id/text 去猜（Agent 一轮观察多个同名按钮时必须这样）。
     * 弱引用，View 回收后 ref 自动失效；同一个 View 多次 dump 拿到同一个 ref。
     */
    private static final java.util.WeakHashMap<View, Integer> sRefByView =
            new java.util.WeakHashMap<>();

    private static final Map<Integer, java.lang.ref.WeakReference<View>> sViewByRef =
            new HashMap<>();
    private static int sNextRef = 1;

    static int refFor(View view) {
        Integer ref = sRefByView.get(view);
        if (ref != null) {
            return ref;
        }
        ref = sNextRef++;
        sRefByView.put(view, ref);
        sViewByRef.put(ref, new java.lang.ref.WeakReference<>(view));
        if (sViewByRef.size() > 4096) {
            // 定期把已经回收的条目清掉，避免无限增长。
            java.util.Iterator<Map.Entry<Integer, java.lang.ref.WeakReference<View>>> it =
                    sViewByRef.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue().get() == null) {
                    it.remove();
                }
            }
        }
        return ref;
    }

    static View viewForRef(int ref) {
        java.lang.ref.WeakReference<View> weak = sViewByRef.get(ref);
        View view = weak == null ? null : weak.get();
        if (view == null || !view.isAttachedToWindow()) {
            return null;
        }
        return view;
    }

    static JSONObject describe(View view, boolean withChildren) throws Exception {
        JSONObject node = new JSONObject();
        node.put("ref", refFor(view));
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

    // ------------------------------------------------------------ 屏幕坐标输入

    /**
     * 在屏幕坐标 (x, y) 上点一下。找到覆盖该点的最上层窗口根（Dialog / PopupWindow /
     * Activity），换算成该窗口的局部坐标后走 dispatchTouchEvent —— 和 dump/find 报出的
     * x/y/w/h 是同一个坐标系（{@link View#getLocationOnScreen}），Agent 拿到节点就能点。
     * 网页内部的点用 lemurx.input.tap（相对网页 View）或 agent.act 自动换算。
     */
    private static String tapScreen(String json, boolean longPress) {
        String result =
                uiBlocking(
                        () -> {
                            JSONObject out = new JSONObject();
                            JSONObject opt = parseJson(json);
                            float[] xy = screenPoint(opt, "x", "y");
                            View root = rootAt(xy[0], xy[1]);
                            if (root == null) {
                                out.put("ok", false);
                                out.put("error", "no window at point");
                                return out.toString();
                            }
                            int[] loc = new int[2];
                            root.getLocationOnScreen(loc);
                            float lx = xy[0] - loc[0];
                            float ly = xy[1] - loc[1];
                            long hold = longPress ? Math.max(600, opt.optInt("hold", 700)) : 48;
                            long down = SystemClock.uptimeMillis();
                            MotionEvent press =
                                    MotionEvent.obtain(
                                            down, down, MotionEvent.ACTION_DOWN, lx, ly, 0);
                            boolean ok = root.dispatchTouchEvent(press);
                            press.recycle();
                            if (longPress) {
                                // 长按需要真实经过时间，否则 GestureDetector 不会判成 long press；
                                // UP 延后投递，不阻塞 UI 线程。
                                final View target = root;
                                root.postDelayed(
                                        () -> {
                                            MotionEvent release =
                                                    MotionEvent.obtain(
                                                            down,
                                                            SystemClock.uptimeMillis(),
                                                            MotionEvent.ACTION_UP,
                                                            lx,
                                                            ly,
                                                            0);
                                            target.dispatchTouchEvent(release);
                                            release.recycle();
                                        },
                                        hold);
                            } else {
                                MotionEvent release =
                                        MotionEvent.obtain(
                                                down,
                                                down + hold,
                                                MotionEvent.ACTION_UP,
                                                lx,
                                                ly,
                                                0);
                                ok = root.dispatchTouchEvent(release) || ok;
                                release.recycle();
                            }
                            out.put("ok", ok);
                            out.put("x", xy[0]);
                            out.put("y", xy[1]);
                            out.put("window", viewIdName(root));
                            return out.toString();
                        });
        return result == null ? "{\"ok\":false}" : result;
    }

    private static String swipeScreen(String json) {
        String result =
                uiBlocking(
                        () -> {
                            JSONObject out = new JSONObject();
                            JSONObject opt = parseJson(json);
                            float[] a = screenPoint(opt, "x1", "y1");
                            float[] b = screenPoint(opt, "x2", "y2");
                            int duration = Math.max(80, opt.optInt("duration", 320));
                            View root = rootAt(a[0], a[1]);
                            if (root == null) {
                                out.put("ok", false);
                                out.put("error", "no window at point");
                                return out.toString();
                            }
                            int[] loc = new int[2];
                            root.getLocationOnScreen(loc);
                            float x1 = a[0] - loc[0];
                            float y1 = a[1] - loc[1];
                            float x2 = b[0] - loc[0];
                            float y2 = b[1] - loc[1];
                            long down = SystemClock.uptimeMillis();
                            MotionEvent press =
                                    MotionEvent.obtain(
                                            down, down, MotionEvent.ACTION_DOWN, x1, y1, 0);
                            boolean ok = root.dispatchTouchEvent(press);
                            press.recycle();
                            int steps = Math.max(6, duration / 16);
                            for (int i = 1; i <= steps; i++) {
                                float t = i / (float) steps;
                                MotionEvent move =
                                        MotionEvent.obtain(
                                                down,
                                                down + (long) (duration * t),
                                                MotionEvent.ACTION_MOVE,
                                                x1 + (x2 - x1) * t,
                                                y1 + (y2 - y1) * t,
                                                0);
                                ok = root.dispatchTouchEvent(move) || ok;
                                move.recycle();
                            }
                            MotionEvent release =
                                    MotionEvent.obtain(
                                            down,
                                            down + duration,
                                            MotionEvent.ACTION_UP,
                                            x2,
                                            y2,
                                            0);
                            ok = root.dispatchTouchEvent(release) || ok;
                            release.recycle();
                            out.put("ok", ok);
                            return out.toString();
                        });
        return result == null ? "{\"ok\":false}" : result;
    }

    /** 屏幕坐标；unit="dp" 时按屏幕密度换算成像素。 */
    private static float[] screenPoint(JSONObject opt, String xKey, String yKey) {
        float x = (float) opt.optDouble(xKey, 0);
        float y = (float) opt.optDouble(yKey, 0);
        if ("dp".equalsIgnoreCase(opt.optString("unit", "px"))) {
            Activity activity = hostActivity();
            if (activity != null) {
                float density = activity.getResources().getDisplayMetrics().density;
                x *= density;
                y *= density;
            }
        }
        return new float[] {x, y};
    }

    /** 覆盖屏幕点 (x, y) 的最上层窗口根。WindowManagerGlobal 的列表按加入顺序，后加的在上面。 */
    private static View rootAt(float x, float y) {
        List<View> roots = allWindowRoots();
        View hit = null;
        for (View root : roots) {
            if (root == null || !root.isAttachedToWindow() || root.getVisibility() != View.VISIBLE) {
                continue;
            }
            int[] loc = new int[2];
            root.getLocationOnScreen(loc);
            if (x >= loc[0]
                    && y >= loc[1]
                    && x < loc[0] + root.getWidth()
                    && y < loc[1] + root.getHeight()) {
                hit = root; // 继续遍历，取最后一个（最上层）命中的
            }
        }
        if (hit == null) {
            Activity activity = hostActivity();
            if (activity != null && activity.getWindow() != null) {
                hit = activity.getWindow().getDecorView();
            }
        }
        return hit;
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
