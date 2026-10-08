// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code lemurx.system.*} 的实现：通过 {@link LemurXAccessibilityService} 观察和操作整机 UI。
 *
 * <p>能力对照 adb：
 *
 * <pre>
 *   uiautomator dump      -> tree / find / windows / foreground
 *   input tap / swipe     -> tap / swipe / longPress（dispatchGesture）
 *   input keyevent BACK   -> global("back" | "home" | "recents" | "notifications" ...)
 *   screencap             -> screenshot（API 30+）
 *   am start              -> launch
 *   node.click()          -> click / setText / scroll / focus（AccessibilityNodeInfo.performAction）
 * </pre>
 *
 * <p>所有方法都是 JSON 进 JSON 出，由 {@link #call(String, String)} 统一分派；C++ 侧只有一个
 * JNI 桩，Lua 侧 {@code lemurx.system.<name>(table)} 一一对应。全部只对主（本地特权）脚本开放，
 * 特权检查在 C++ 的 RequirePrivilege 里做。
 *
 * <p>节点句柄：tree/find 返回的每个节点带整数 {@code ref}，之后 click/setText/scroll 传
 * {@code {ref=N}} 即可精确指回；每次 tree/find 都会重建句柄表，旧 ref 失效（节点本身也早就变了）。
 *
 * <p>线程：Lua 线程调进来；AccessibilityNodeInfo 是跨进程句柄，任何线程都能用。手势和截图
 * 需要等系统回调，用 latch 最多等 3 秒。事件回调来自主线程，转成 {@code system.*} 事件投给
 * Lua（默认关闭，脚本 {@code lemurx.system.on} 时才打开，避免刷屏）。
 */
final class LemurXSystemHost {
    private static final String TAG = "LemurXSystem";
    private static final long GESTURE_TIMEOUT_MS = 3000;
    private static final long CONTENT_EVENT_MIN_INTERVAL_MS = 250;
    private static final int DEFAULT_MAX_NODES = 1500;
    private static final int DEFAULT_MAX_DEPTH = 40;

    private static volatile LemurXAccessibilityService sService;
    private static final SparseArray<AccessibilityNodeInfo> sNodeByRef = new SparseArray<>();
    private static int sNextRef = 1;
    private static final AtomicBoolean sEventsEnabled = new AtomicBoolean(false);
    private static volatile long sLastContentEventMs;

    private LemurXSystemHost() {}

    // ------------------------------------------------------------------ 生命周期

    static void onServiceConnected(LemurXAccessibilityService service) {
        sService = service;
        dispatch("connected", new JSONObject());
    }

    static void onServiceDisconnected(LemurXAccessibilityService service) {
        if (sService == service) {
            sService = null;
            synchronized (sNodeByRef) {
                sNodeByRef.clear();
            }
            dispatch("disconnected", new JSONObject());
        }
    }

    /** Lua 关掉 / 重载时：句柄表清空、事件转发关掉。服务本身是用户的系统设置，不动。 */
    static void resetAll() {
        sEventsEnabled.set(false);
        synchronized (sNodeByRef) {
            sNodeByRef.clear();
        }
    }

    static void onAccessibilityEvent(AccessibilityEvent event) {
        if (!sEventsEnabled.get()) {
            return;
        }
        String name;
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                name = "window";
                break;
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                long now = SystemClock.uptimeMillis();
                if (now - sLastContentEventMs < CONTENT_EVENT_MIN_INTERVAL_MS) {
                    return;
                }
                sLastContentEventMs = now;
                name = "content";
                break;
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
                name = "click";
                break;
            case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                name = "focus";
                break;
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                name = "text";
                break;
            case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED:
                name = "notification";
                break;
            default:
                return;
        }
        JSONObject json = new JSONObject();
        try {
            json.put("type", name);
            json.put("package", String.valueOf(event.getPackageName()));
            json.put("class", String.valueOf(event.getClassName()));
            List<CharSequence> texts = event.getText();
            if (texts != null && !texts.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (CharSequence t : texts) {
                    if (t != null) arr.put(t.toString());
                }
                json.put("text", arr);
            }
            if (event.getContentDescription() != null) {
                json.put("desc", event.getContentDescription().toString());
            }
            json.put("time", event.getEventTime());
        } catch (Exception ignored) {
            // 保持已写入字段
        }
        dispatch(name, json);
    }

    private static void dispatch(String name, JSONObject json) {
        try {
            LemurXBridge.dispatchTabEventNative("system." + name, json.toString());
        } catch (Throwable t) {
            Log.i(TAG, "dispatch %s: %s", name, t.toString());
        }
    }

    // ------------------------------------------------------------------ 分派

    static String call(String method, String json) {
        String m = method == null ? "" : method.toLowerCase(Locale.US);
        JSONObject opt = parse(json);
        try {
            switch (m) {
                case "status":
                    return status().toString();
                case "opensettings":
                case "requestenable":
                    return openSettings().toString();
                case "events":
                    sEventsEnabled.set(opt.optBoolean("enable", true));
                    return ok().put("enabled", sEventsEnabled.get()).toString();
                case "foreground":
                    return requireService(opt) == null ? notConnected() : foreground().toString();
                case "windows":
                    return requireService(opt) == null ? notConnected() : windows().toString();
                case "tree":
                    return requireService(opt) == null ? notConnected() : tree(opt).toString();
                case "find":
                    return requireService(opt) == null ? notConnected() : find(opt).toString();
                case "click":
                case "longclick":
                    return requireService(opt) == null
                            ? notConnected()
                            : clickNode(opt, m.startsWith("long") || opt.optBoolean("long"))
                                    .toString();
                case "settext":
                    return requireService(opt) == null ? notConnected() : setText(opt).toString();
                case "focus":
                    return requireService(opt) == null
                            ? notConnected()
                            : nodeAction(opt, AccessibilityNodeInfo.ACTION_FOCUS).toString();
                case "scroll":
                    return requireService(opt) == null ? notConnected() : scroll(opt).toString();
                case "tap":
                case "longpress":
                    return requireService(opt) == null
                            ? notConnected()
                            : tap(opt, m.equals("longpress") || opt.optBoolean("long")).toString();
                case "swipe":
                    return requireService(opt) == null ? notConnected() : swipe(opt).toString();
                case "global":
                    return requireService(opt) == null ? notConnected() : global(opt).toString();
                case "screenshot":
                    return requireService(opt) == null
                            ? notConnected()
                            : screenshot(opt).toString();
                case "launch":
                    return launch(opt).toString();
                default:
                    return fail("unknown system op " + method).toString();
            }
        } catch (Throwable t) {
            Log.i(TAG, "%s failed: %s", m, t.toString());
            return fail(String.valueOf(t.getMessage())).toString();
        }
    }

    private static AccessibilityService requireService(JSONObject unused) {
        return sService;
    }

    private static String notConnected() {
        return fail("accessibility service not enabled; call lemurx.system.openSettings()")
                .toString();
    }

    // ------------------------------------------------------------------ 状态 / 启用

    private static JSONObject status() throws Exception {
        Context ctx = ContextUtils.getApplicationContext();
        JSONObject out = ok();
        out.put("connected", sService != null);
        out.put("enabledInSettings", isEnabledInSettings(ctx));
        out.put("component", component(ctx).flattenToString());
        out.put("canScreenshot", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
        out.put("events", sEventsEnabled.get());
        out.put("sdk", Build.VERSION.SDK_INT);
        return out;
    }

    private static ComponentName component(Context ctx) {
        return new ComponentName(ctx, LemurXAccessibilityService.class);
    }

    private static boolean isEnabledInSettings(Context ctx) {
        try {
            String enabled =
                    Settings.Secure.getString(
                            ctx.getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(enabled)) {
                return false;
            }
            String flat = component(ctx).flattenToString();
            String shortFlat = component(ctx).flattenToShortString();
            for (String item : enabled.split(":")) {
                if (item.equalsIgnoreCase(flat) || item.equalsIgnoreCase(shortFlat)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 没有权限读设置就当没开
        }
        return false;
    }

    /** 打开系统的无障碍设置页；用户自己决定开不开。 */
    private static JSONObject openSettings() throws Exception {
        Context ctx = ContextUtils.getApplicationContext();
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ThreadUtils.runOnUiThread(
                () -> {
                    try {
                        ctx.startActivity(intent);
                    } catch (Throwable t) {
                        Log.i(TAG, "openSettings: %s", t.toString());
                    }
                });
        JSONObject out = ok();
        out.put("component", component(ctx).flattenToString());
        out.put("alreadyEnabled", isEnabledInSettings(ctx));
        return out;
    }

    // ------------------------------------------------------------------ 观察

    private static JSONObject foreground() throws Exception {
        AccessibilityService service = sService;
        JSONObject out = ok();
        AccessibilityNodeInfo root = service == null ? null : service.getRootInActiveWindow();
        if (root != null) {
            out.put("package", String.valueOf(root.getPackageName()));
            out.put("class", String.valueOf(root.getClassName()));
            out.put("windowId", root.getWindowId());
            out.put("self", isSelf(root.getPackageName()));
            AccessibilityWindowInfo window = root.getWindow();
            if (window != null && window.getTitle() != null) {
                out.put("title", window.getTitle().toString());
            }
        } else {
            out.put("package", "");
        }
        return out;
    }

    private static boolean isSelf(CharSequence pkg) {
        return pkg != null
                && pkg.toString().equals(ContextUtils.getApplicationContext().getPackageName());
    }

    private static JSONObject windows() throws Exception {
        AccessibilityService service = sService;
        JSONObject out = ok();
        JSONArray arr = new JSONArray();
        List<AccessibilityWindowInfo> windows = service == null ? null : service.getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo w : windows) {
                if (w == null) continue;
                JSONObject j = new JSONObject();
                j.put("id", w.getId());
                j.put("type", windowType(w.getType()));
                j.put("layer", w.getLayer());
                j.put("active", w.isActive());
                j.put("focused", w.isFocused());
                if (w.getTitle() != null) j.put("title", w.getTitle().toString());
                Rect b = new Rect();
                w.getBoundsInScreen(b);
                putBounds(j, b);
                AccessibilityNodeInfo root = w.getRoot();
                if (root != null) {
                    j.put("package", String.valueOf(root.getPackageName()));
                }
                arr.put(j);
            }
        }
        out.put("windows", arr);
        out.put("count", arr.length());
        return out;
    }

    private static String windowType(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION:
                return "application";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD:
                return "ime";
            case AccessibilityWindowInfo.TYPE_SYSTEM:
                return "system";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY:
                return "a11y_overlay";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER:
                return "divider";
            default:
                return "other";
        }
    }

    /** 整机 UI 树。默认只看活动窗口；{@code all=true} 遍历所有窗口（含输入法、系统栏）。 */
    private static JSONObject tree(JSONObject opt) throws Exception {
        AccessibilityService service = sService;
        int maxDepth = opt.optInt("maxDepth", DEFAULT_MAX_DEPTH);
        int maxNodes = opt.optInt("maxNodes", DEFAULT_MAX_NODES);
        boolean visibleOnly = opt.optBoolean("visibleOnly", true);
        boolean interactiveOnly = opt.optBoolean("interactiveOnly", false);
        boolean flat = opt.optBoolean("flat", false);
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        if (opt.optBoolean("all", false)) {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    AccessibilityNodeInfo r = w == null ? null : w.getRoot();
                    if (r != null) roots.add(r);
                }
            }
        }
        if (roots.isEmpty()) {
            AccessibilityNodeInfo r = service.getRootInActiveWindow();
            if (r != null) roots.add(r);
        }
        resetRefs();
        int[] count = {0};
        JSONArray out = new JSONArray();
        JSONArray flatList = flat ? new JSONArray() : null;
        for (AccessibilityNodeInfo root : roots) {
            if (count[0] >= maxNodes) break;
            JSONObject dumped =
                    dumpNode(root, 0, maxDepth, maxNodes, visibleOnly, interactiveOnly, count,
                            flatList);
            if (dumped != null) out.put(dumped);
        }
        JSONObject result = ok();
        result.put("nodes", count[0]);
        result.put("windows", roots.size());
        if (flat) {
            result.put("list", flatList);
        } else {
            result.put("roots", out);
        }
        AccessibilityNodeInfo first = roots.isEmpty() ? null : roots.get(0);
        if (first != null) {
            result.put("package", String.valueOf(first.getPackageName()));
            result.put("self", isSelf(first.getPackageName()));
        }
        return result;
    }

    private static JSONObject dumpNode(
            AccessibilityNodeInfo node,
            int depth,
            int maxDepth,
            int maxNodes,
            boolean visibleOnly,
            boolean interactiveOnly,
            int[] count,
            JSONArray flatList)
            throws Exception {
        if (node == null || count[0] >= maxNodes) {
            return null;
        }
        if (visibleOnly && !node.isVisibleToUser()) {
            return null;
        }
        boolean interactive = isInteractive(node);
        boolean include = !interactiveOnly || interactive || hasText(node);
        JSONObject json = include ? describe(node) : null;
        if (include) {
            count[0]++;
            if (flatList != null) {
                json.put("depth", depth);
                flatList.put(json);
            }
        }
        if (depth < maxDepth) {
            JSONArray children = new JSONArray();
            int n = node.getChildCount();
            for (int i = 0; i < n && count[0] < maxNodes; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                JSONObject c =
                        dumpNode(child, depth + 1, maxDepth, maxNodes, visibleOnly,
                                interactiveOnly, count, flatList);
                // flat 模式下所有节点都已进 flatList，这里不再挂树。
                if (c != null && flatList == null) {
                    children.put(c);
                }
            }
            if (flatList == null && children.length() > 0) {
                if (json == null) {
                    // 被 interactiveOnly 过滤掉的容器：返回一个只带 children 的透明节点，
                    // 子节点提升到本层，树保持连通。
                    json = new JSONObject();
                    json.put("passthrough", true);
                }
                json.put("children", children);
            }
        }
        return json;
    }

    private static boolean isInteractive(AccessibilityNodeInfo n) {
        return n.isClickable()
                || n.isLongClickable()
                || n.isEditable()
                || n.isCheckable()
                || n.isScrollable()
                || n.isFocusable() && !TextUtils.isEmpty(n.getText());
    }

    private static boolean hasText(AccessibilityNodeInfo n) {
        return !TextUtils.isEmpty(n.getText()) || !TextUtils.isEmpty(n.getContentDescription());
    }

    private static JSONObject describe(AccessibilityNodeInfo n) throws Exception {
        JSONObject j = new JSONObject();
        j.put("ref", refFor(n));
        j.put("class", shortClass(n.getClassName()));
        j.put("package", String.valueOf(n.getPackageName()));
        String id = n.getViewIdResourceName();
        j.put("id", id == null ? "" : id.substring(id.indexOf('/') + 1));
        if (id != null) j.put("rid", id);
        j.put("text", n.getText() == null ? "" : n.getText().toString());
        j.put("desc", n.getContentDescription() == null ? "" : n.getContentDescription().toString());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && n.getHintText() != null) {
            j.put("hint", n.getHintText().toString());
        }
        j.put("clickable", n.isClickable());
        j.put("longClickable", n.isLongClickable());
        j.put("editable", n.isEditable());
        j.put("checkable", n.isCheckable());
        if (n.isCheckable()) j.put("checked", n.isChecked());
        j.put("scrollable", n.isScrollable());
        j.put("focusable", n.isFocusable());
        j.put("focused", n.isFocused());
        j.put("selected", n.isSelected());
        j.put("enabled", n.isEnabled());
        j.put("visible", n.isVisibleToUser());
        if (n.isPassword()) j.put("password", true);
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        putBounds(j, b);
        j.put("childCount", n.getChildCount());
        return j;
    }

    private static String shortClass(CharSequence cls) {
        if (cls == null) return "";
        String s = cls.toString();
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    private static void putBounds(JSONObject j, Rect b) throws Exception {
        j.put("x", b.left);
        j.put("y", b.top);
        j.put("w", Math.max(0, b.width()));
        j.put("h", Math.max(0, b.height()));
    }

    private static void resetRefs() {
        synchronized (sNodeByRef) {
            sNodeByRef.clear();
        }
    }

    private static int refFor(AccessibilityNodeInfo node) {
        synchronized (sNodeByRef) {
            int ref = sNextRef++;
            sNodeByRef.put(ref, node);
            return ref;
        }
    }

    private static AccessibilityNodeInfo nodeForRef(int ref) {
        AccessibilityNodeInfo node;
        synchronized (sNodeByRef) {
            node = sNodeByRef.get(ref);
        }
        if (node == null) return null;
        try {
            if (!node.refresh()) {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
        return node;
    }

    /** 查找：{ref} 精确；否则 text / id / desc / class / clickable / editable 模糊（包含，忽略大小写）。 */
    private static JSONObject find(JSONObject q) throws Exception {
        AccessibilityService service = sService;
        int max = q.optInt("max", 20);
        List<AccessibilityNodeInfo> hits = findNodes(service, q, max);
        JSONObject out = ok();
        out.put("ok", !hits.isEmpty());
        out.put("count", hits.size());
        JSONArray arr = new JSONArray();
        for (AccessibilityNodeInfo n : hits) {
            arr.put(describe(n));
        }
        out.put("nodes", arr);
        if (!hits.isEmpty()) out.put("node", arr.getJSONObject(0));
        return out;
    }

    private static List<AccessibilityNodeInfo> findNodes(
            AccessibilityService service, JSONObject q, int max) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        if (q.has("ref")) {
            AccessibilityNodeInfo n = nodeForRef(q.optInt("ref", -1));
            if (n != null) out.add(n);
            return out;
        }
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        if (q.optBoolean("all", false)) {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    AccessibilityNodeInfo r = w == null ? null : w.getRoot();
                    if (r != null) roots.add(r);
                }
            }
        }
        if (roots.isEmpty()) {
            AccessibilityNodeInfo r = service.getRootInActiveWindow();
            if (r != null) roots.add(r);
        }
        // 快路径：系统自带的按 id / 文本查找（精确匹配）。
        String rid = q.optString("rid", "");
        if (!TextUtils.isEmpty(rid) && rid.contains("/")) {
            for (AccessibilityNodeInfo root : roots) {
                List<AccessibilityNodeInfo> byId = root.findAccessibilityNodeInfosByViewId(rid);
                if (byId != null) out.addAll(byId);
                if (out.size() >= max) break;
            }
            return out.size() > max ? out.subList(0, max) : out;
        }
        for (AccessibilityNodeInfo root : roots) {
            collect(root, q, out, max, 0);
            if (out.size() >= max) break;
        }
        return out;
    }

    private static void collect(
            AccessibilityNodeInfo node, JSONObject q, List<AccessibilityNodeInfo> out, int max,
            int depth) {
        if (node == null || out.size() >= max || depth > DEFAULT_MAX_DEPTH) return;
        if (matches(node, q)) {
            out.add(node);
            if (out.size() >= max) return;
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            collect(node.getChild(i), q, out, max, depth + 1);
            if (out.size() >= max) return;
        }
    }

    private static boolean matches(AccessibilityNodeInfo n, JSONObject q) {
        boolean exact = q.optBoolean("exact", false);
        String text = q.optString("text", "");
        String id = q.optString("id", "");
        String desc = q.optString("desc", "");
        String cls = q.optString("class", "");
        String pkg = q.optString("package", "");
        boolean any = false;
        if (!TextUtils.isEmpty(text)) {
            any = true;
            String hay = (str(n.getText()) + " " + str(n.getContentDescription()));
            if (!contains(hay, text, exact) && !contains(str(n.getText()), text, exact)) {
                return false;
            }
        }
        if (!TextUtils.isEmpty(desc)) {
            any = true;
            if (!contains(str(n.getContentDescription()), desc, exact)) return false;
        }
        if (!TextUtils.isEmpty(id)) {
            any = true;
            String rid = n.getViewIdResourceName();
            String shortId = rid == null ? "" : rid.substring(rid.indexOf('/') + 1);
            if (!contains(shortId, id, exact) && !contains(rid == null ? "" : rid, id, exact)) {
                return false;
            }
        }
        if (!TextUtils.isEmpty(cls)) {
            any = true;
            if (!contains(str(n.getClassName()), cls, false)) return false;
        }
        if (!TextUtils.isEmpty(pkg)) {
            any = true;
            if (!contains(str(n.getPackageName()), pkg, false)) return false;
        }
        if (q.has("clickable")) {
            any = true;
            if (n.isClickable() != q.optBoolean("clickable")) return false;
        }
        if (q.has("editable")) {
            any = true;
            if (n.isEditable() != q.optBoolean("editable")) return false;
        }
        if (q.has("scrollable")) {
            any = true;
            if (n.isScrollable() != q.optBoolean("scrollable")) return false;
        }
        if (q.optBoolean("visibleOnly", true) && !n.isVisibleToUser()) {
            return false;
        }
        return any;
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    private static boolean contains(String hay, String needle, boolean exact) {
        if (hay == null) return false;
        if (exact) return hay.trim().equalsIgnoreCase(needle.trim());
        return hay.toLowerCase(Locale.US).contains(needle.toLowerCase(Locale.US));
    }

    // ------------------------------------------------------------------ 节点动作

    private static AccessibilityNodeInfo firstMatch(JSONObject q) {
        List<AccessibilityNodeInfo> hits = findNodes(sService, q, 1);
        return hits.isEmpty() ? null : hits.get(0);
    }

    private static JSONObject clickNode(JSONObject q, boolean longClick) throws Exception {
        AccessibilityNodeInfo node = firstMatch(q);
        if (node == null) return fail("node not found");
        int action =
                longClick
                        ? AccessibilityNodeInfo.ACTION_LONG_CLICK
                        : AccessibilityNodeInfo.ACTION_CLICK;
        // 先试节点自己，再沿父链找可点的，最后退回手势点中心。
        AccessibilityNodeInfo cur = node;
        for (int hop = 0; cur != null && hop < 8; hop++) {
            boolean capable = longClick ? cur.isLongClickable() : cur.isClickable();
            if (capable && cur.performAction(action)) {
                JSONObject out = ok();
                out.put("via", hop == 0 ? "action" : "parent");
                out.put("node", describe(node));
                return out;
            }
            cur = cur.getParent();
        }
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        if (b.isEmpty()) return fail("node has no bounds");
        boolean ok = gestureTap(b.centerX(), b.centerY(), longClick ? 700 : 60);
        JSONObject out = ok ? ok() : fail("gesture failed");
        out.put("via", "gesture");
        out.put("node", describe(node));
        return out;
    }

    private static JSONObject nodeAction(JSONObject q, int action) throws Exception {
        AccessibilityNodeInfo node = firstMatch(q);
        if (node == null) return fail("node not found");
        boolean ok = node.performAction(action);
        JSONObject out = ok ? ok() : fail("action rejected");
        out.put("node", describe(node));
        return out;
    }

    private static JSONObject setText(JSONObject q) throws Exception {
        AccessibilityNodeInfo node = firstMatch(q);
        if (node == null) {
            // 没指定目标：用当前输入焦点。
            AccessibilityNodeInfo root = sService.getRootInActiveWindow();
            node = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        }
        if (node == null) return fail("no editable node");
        String text = q.optString("text", q.optString("value", ""));
        if (q.optBoolean("append", false) && node.getText() != null) {
            text = node.getText().toString() + text;
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (!node.isFocused()) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!ok) {
            // 退路：剪贴板 + 粘贴。
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager)
                                ContextUtils.getApplicationContext()
                                        .getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("lemurx", text));
                ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            } catch (Throwable t) {
                Log.i(TAG, "paste fallback: %s", t.toString());
            }
        }
        JSONObject out = ok ? ok() : fail("set text rejected");
        out.put("node", describe(node));
        return out;
    }

    private static JSONObject scroll(JSONObject q) throws Exception {
        String dir = q.optString("dir", q.optString("direction", "forward")).toLowerCase(Locale.US);
        AccessibilityNodeInfo node = firstMatch(q);
        if (node == null) {
            // 没指定就找第一个可滚动的。
            JSONObject any = new JSONObject();
            any.put("scrollable", true);
            node = firstMatch(any);
        }
        if (node == null) return fail("no scrollable node");
        int action;
        switch (dir) {
            case "backward":
            case "up":
            case "left":
                action = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                break;
            default:
                action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
        }
        boolean ok = node.performAction(action);
        JSONObject out = ok ? ok() : fail("scroll rejected");
        out.put("node", describe(node));
        return out;
    }

    // ------------------------------------------------------------------ 手势

    private static JSONObject tap(JSONObject q, boolean longPress) throws Exception {
        float[] p = point(q, "x", "y");
        long hold = longPress ? Math.max(500, q.optLong("hold", 700)) : q.optLong("hold", 60);
        boolean ok = gestureTap(p[0], p[1], hold);
        JSONObject out = ok ? ok() : fail("gesture failed");
        out.put("x", p[0]);
        out.put("y", p[1]);
        return out;
    }

    private static JSONObject swipe(JSONObject q) throws Exception {
        float[] a = point(q, "x1", "y1");
        float[] b = point(q, "x2", "y2");
        long duration = Math.max(50, q.optLong("duration", 300));
        Path path = new Path();
        path.moveTo(a[0], a[1]);
        path.lineTo(b[0], b[1]);
        boolean ok = dispatchGesture(path, duration);
        return ok ? ok() : fail("gesture failed");
    }

    private static float[] point(JSONObject q, String xKey, String yKey) {
        float x = (float) q.optDouble(xKey, 0);
        float y = (float) q.optDouble(yKey, 0);
        if ("dp".equalsIgnoreCase(q.optString("unit", "px"))) {
            DisplayMetrics metrics =
                    ContextUtils.getApplicationContext().getResources().getDisplayMetrics();
            x *= metrics.density;
            y *= metrics.density;
        }
        return new float[] {Math.max(0, x), Math.max(0, y)};
    }

    private static boolean gestureTap(float x, float y, long holdMs) {
        Path path = new Path();
        path.moveTo(x, y);
        return dispatchGesture(path, Math.max(1, holdMs));
    }

    private static boolean dispatchGesture(Path path, long durationMs) {
        AccessibilityService service = sService;
        if (service == null) return false;
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, durationMs);
        GestureDescription gesture =
                new GestureDescription.Builder().addStroke(stroke).build();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(false);
        boolean accepted =
                service.dispatchGesture(
                        gesture,
                        new AccessibilityService.GestureResultCallback() {
                            @Override
                            public void onCompleted(GestureDescription g) {
                                result.set(true);
                                latch.countDown();
                            }

                            @Override
                            public void onCancelled(GestureDescription g) {
                                result.set(false);
                                latch.countDown();
                            }
                        },
                        null);
        if (!accepted) return false;
        try {
            if (ThreadUtils.runningOnUiThread()) {
                // 主线程上不能等回调；已接受就当成功。
                return true;
            }
            latch.await(durationMs + GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            return false;
        }
        return result.get();
    }

    // ------------------------------------------------------------------ 全局动作

    private static JSONObject global(JSONObject q) throws Exception {
        String name = q.optString("action", q.optString("name", "")).toLowerCase(Locale.US);
        int action;
        switch (name) {
            case "back":
                action = AccessibilityService.GLOBAL_ACTION_BACK;
                break;
            case "home":
                action = AccessibilityService.GLOBAL_ACTION_HOME;
                break;
            case "recents":
            case "recent":
                action = AccessibilityService.GLOBAL_ACTION_RECENTS;
                break;
            case "notifications":
                action = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
                break;
            case "quicksettings":
            case "quick_settings":
                action = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS;
                break;
            case "power":
            case "powerdialog":
                action = AccessibilityService.GLOBAL_ACTION_POWER_DIALOG;
                break;
            case "lock":
            case "lockscreen":
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return fail("needs API 28");
                action = AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN;
                break;
            case "screenshot":
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return fail("needs API 28");
                action = AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT;
                break;
            case "dismissnotifications":
            case "dismiss_notification_shade":
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return fail("needs API 31");
                action = AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE;
                break;
            default:
                return fail("unknown global action " + name);
        }
        boolean ok = sService.performGlobalAction(action);
        JSONObject out = ok ? ok() : fail("global action rejected");
        out.put("action", name);
        return out;
    }

    // ------------------------------------------------------------------ 截图

    @SuppressLint("NewApi")
    private static JSONObject screenshot(JSONObject q) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return fail("system screenshot needs API 30");
        }
        AccessibilityService service = sService;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bitmap> captured = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ContextUtils.getApplicationContext().getMainExecutor(),
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        try {
                            HardwareBuffer buffer = result.getHardwareBuffer();
                            Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                            if (hw != null) {
                                captured.set(hw.copy(Bitmap.Config.ARGB_8888, false));
                                hw.recycle();
                            }
                            buffer.close();
                        } catch (Throwable t) {
                            error.set(t.toString());
                        }
                        latch.countDown();
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        error.set("takeScreenshot error " + errorCode);
                        latch.countDown();
                    }
                });
        if (ThreadUtils.runningOnUiThread()) {
            return fail("screenshot must not be called on the UI thread");
        }
        latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        Bitmap bitmap = captured.get();
        if (bitmap == null) {
            return fail(error.get() == null ? "screenshot timeout" : error.get());
        }
        int fullW = bitmap.getWidth();
        int fullH = bitmap.getHeight();
        double scale = q.optDouble("scale", 1.0);
        if (scale > 0 && scale < 1.0) {
            Bitmap scaled =
                    Bitmap.createScaledBitmap(
                            bitmap,
                            Math.max(1, (int) Math.round(fullW * scale)),
                            Math.max(1, (int) Math.round(fullH * scale)),
                            true);
            bitmap.recycle();
            bitmap = scaled;
        } else {
            scale = 1.0;
        }
        int quality = Math.min(100, Math.max(20, q.optInt("quality", 70)));
        boolean privileged = q.optBoolean("privileged", true);
        File dir =
                new File(
                        new File(
                                ContextUtils.getApplicationContext().getFilesDir(),
                                privileged ? "lua" : "lua/ugc"),
                        "captures");
        if (!dir.exists() && !dir.mkdirs()) {
            return fail("cannot create captures");
        }
        String name = "system_" + System.currentTimeMillis() + ".jpg";
        File out = new File(dir, name);
        try (FileOutputStream stream = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream);
        }
        JSONObject result = ok();
        result.put("path", "captures/" + name);
        result.put("abs", out.getAbsolutePath());
        result.put("width", bitmap.getWidth());
        result.put("height", bitmap.getHeight());
        result.put("scale", scale);
        result.put("screenWidth", fullW);
        result.put("screenHeight", fullH);
        if (q.optBoolean("base64", false)) {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, Math.min(quality, 60), encoded);
            result.put("data", Base64.encodeToString(encoded.toByteArray(), Base64.NO_WRAP));
            result.put("base64", true);
        }
        bitmap.recycle();
        return result;
    }

    // ------------------------------------------------------------------ 启动其他 App

    /**
     * {package[, activity]} 启动 App；{url} 用系统默认处理器打开；{action, data, extras} 任意 Intent。
     * 不需要无障碍服务，普通 startActivity。
     */
    private static JSONObject launch(JSONObject q) throws Exception {
        Context ctx = ContextUtils.getApplicationContext();
        Intent intent = null;
        String pkg = q.optString("package", "");
        String activity = q.optString("activity", "");
        String url = q.optString("url", "");
        String action = q.optString("action", "");
        if (!TextUtils.isEmpty(pkg) && !TextUtils.isEmpty(activity)) {
            intent = new Intent(Intent.ACTION_MAIN);
            intent.setComponent(
                    new ComponentName(
                            pkg, activity.startsWith(".") ? pkg + activity : activity));
        } else if (!TextUtils.isEmpty(pkg)) {
            intent = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) return fail("package not found: " + pkg);
        } else if (!TextUtils.isEmpty(url)) {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        } else if (!TextUtils.isEmpty(action)) {
            intent = new Intent(action);
            String data = q.optString("data", "");
            if (!TextUtils.isEmpty(data)) intent.setData(Uri.parse(data));
        }
        if (intent == null) return fail("need package, url or action");
        JSONObject extras = q.optJSONObject("extras");
        if (extras != null) {
            java.util.Iterator<String> keys = extras.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object v = extras.opt(k);
                if (v instanceof Boolean) intent.putExtra(k, (Boolean) v);
                else if (v instanceof Integer) intent.putExtra(k, (Integer) v);
                else if (v instanceof Number) intent.putExtra(k, ((Number) v).doubleValue());
                else if (v != null) intent.putExtra(k, String.valueOf(v));
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Intent toStart = intent;
        AtomicReference<String> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        ThreadUtils.runOnUiThread(
                () -> {
                    try {
                        ctx.startActivity(toStart);
                    } catch (Throwable t) {
                        err.set(t.toString());
                    }
                    latch.countDown();
                });
        if (!ThreadUtils.runningOnUiThread()) {
            latch.await(2, TimeUnit.SECONDS);
        }
        if (err.get() != null) return fail(err.get());
        JSONObject out = ok();
        out.put("intent", intent.toUri(0));
        return out;
    }

    // ------------------------------------------------------------------ 工具

    private static JSONObject parse(String json) {
        if (TextUtils.isEmpty(json)) return new JSONObject();
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONObject ok() {
        JSONObject j = new JSONObject();
        try {
            j.put("ok", true);
        } catch (Exception ignored) {
            // 不会发生
        }
        return j;
    }

    private static JSONObject fail(String error) {
        JSONObject j = new JSONObject();
        try {
            j.put("ok", false);
            j.put("error", error);
        } catch (Exception ignored) {
            // 不会发生
        }
        return j;
    }
}
