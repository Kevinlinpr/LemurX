// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.Html;
import android.text.InputType;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.browser.app.ChromeActivity;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * luakit 控件树（widgets/*.c）的 Android 宿主。
 *
 * <p>每个 Lua widget 对应一个保留的 Android View（按整数 id 索引），Lua 内核经
 * {@code __luakit.widget(op, id, args)} 同步调进来（内部切 UI 线程），控件事件用
 * {@code LemurXBridge.dispatchLuakitWidgetNative(id, json)} 回投到 Lua 线程；
 * key-press 需要“Lua 是否吃掉这个键”的同步答复，走 {@link #awaitLuaVerdict}：
 * 投给 Lua 后 UI 线程在带超时的循环里等答复，同时代跑 Lua 处理器发来的控件操作。
 *
 * <p>类型映射：
 * <ul>
 *   <li>window → 盖在 Activity 内容区上的全屏 FrameLayout；显示时隐藏原生工具栏/底栏</li>
 *   <li>hbox/vbox → LinearLayout；paned → 两子项 LinearLayout；overlay/stack/eventbox → FrameLayout</li>
 *   <li>notebook → FrameLayout，一次只显示当前页；页是 webview 时是透明占位，
 *       原生 Tab 内容（CompositorViewHolder）被挪到占位的矩形里</li>
 *   <li>label → TextView（Pango 标记 → HTML）；entry → EditText；image → ImageView；
 *       spinner → ProgressBar；scrolled → ScrollView/HorizontalScrollView；drawing_area → View</li>
 * </ul>
 * luakit 的像素按 dp 处理，字号按 sp。
 */
public class LemurXWidgetHost {
    private static final String TAG = "LemurX";

    /** 一个控件的宿主侧状态。 */
    private static class W {
        int id;
        String type;
        View view;
        int tabId = -1;            // webview 占位
        int paned = 0;             // paned：已 pack 的槽位掩码
        String title;              // notebook 页标题
        List<Integer> pages;       // notebook 页（子控件 id，按顺序）
        int current = -1;          // notebook 当前页索引（0 基）
        boolean wantsKeyPress;     // Lua 挂了 key-press 处理器
        boolean wantsButton;       // Lua 挂了 button-* 处理器
        boolean wantsScroll;
        boolean wantsEnter;
        boolean suppressChanged;   // entry 程序性改文本时不发 changed
        int lastW = -1;
        int lastH = -1;
        String bg;
        String fg;
        String font;
        int minW = -1;
        int minH = -1;
        int alignH = -1;
        int alignV = -1;
        boolean windowShown;
    }

    private static final Map<Integer, W> sWidgets = new HashMap<>();
    private static final Map<View, W> sByView = new HashMap<>();
    private static int sNextId = 1;
    private static FrameLayout sWindowLayer;          // 所有 window 的父容器（盖在 android.R.id.content 上）
    private static boolean sShellHidden;
    private static final int[] sContentInsets = new int[4];

    // ------------------------------------------------------------ 入口

    // ---- UI↔Lua 同步事件（key-press 判定）----
    // UI 线程等 Lua 答复期间，Lua 处理器几乎一定会来改控件（换模式、改标签文字），
    // 而那些操作又要 UI 线程执行。所以等待不是干等：等待循环替 Lua 代跑排进来的控件操作。
    private static final Object sSyncLock = new Object();
    private static final java.util.ArrayDeque<java.util.concurrent.FutureTask<String>> sPendingOps =
            new java.util.ArrayDeque<>();
    private static boolean sUiWaiting;
    private static int sSyncToken;
    private static String sSyncResult;
    private static final long SYNC_TIMEOUT_MS = 400;

    /** Lua 线程调用：op ∈ create destroy set get call；返回 JSON。 */
    static String op(String op, int id, String json) {
        java.util.concurrent.Callable<String> task = () -> runOp(op, id, json);
        if (ThreadUtils.runningOnUiThread()) {
            try {
                return task.call();
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"" + e + "\"}";
            }
        }
        java.util.concurrent.FutureTask<String> future = null;
        synchronized (sSyncLock) {
            if (sUiWaiting) {
                future = new java.util.concurrent.FutureTask<>(task);
                sPendingOps.add(future);
                sSyncLock.notifyAll();
            }
        }
        if (future != null) {
            // 进了队列就一定会被等待循环跑掉（循环退出前会清空队列）
            try {
                return future.get();
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"" + e + "\"}";
            }
        }
        // Chromium 154 没有 runOnUiThreadBlockingNoException 了；runOnUiThreadBlocking(Callable)
        // 会把任务里的异常包成 RuntimeException 抛回来（runOp 自己已兜住，这里只防 UI 线程投递失败）
        String result;
        try {
            result = ThreadUtils.runOnUiThreadBlocking(task);
        } catch (RuntimeException e) {
            result = null;
        }
        return result == null ? "{\"ok\":false,\"error\":\"ui thread\"}" : result;
    }

    /** Lua 线程回来的答复。 */
    static void onSyncResult(int token, String verdict) {
        synchronized (sSyncLock) {
            if (token == sSyncToken) {
                sSyncResult = verdict;
            }
            sSyncLock.notifyAll();
        }
    }

    /** UI 线程：把事件投给 Lua 并等答复，期间代跑 Lua 排进来的控件操作。 */
    private static String awaitLuaVerdict(int id, String json) {
        ThreadUtils.assertOnUiThread();
        int token;
        synchronized (sSyncLock) {
            if (sUiWaiting) {
                return "";   // 重入（不该发生）：不吃键
            }
            sUiWaiting = true;
            token = ++sSyncToken;
            sSyncResult = null;
        }
        if (!LemurXBridge.postLuakitWidgetSync(id, json, token)) {
            synchronized (sSyncLock) {
                sUiWaiting = false;
            }
            return "";
        }
        long deadline = android.os.SystemClock.uptimeMillis() + SYNC_TIMEOUT_MS;
        String verdict;
        synchronized (sSyncLock) {
            while (true) {
                drainPendingOpsLocked();
                if (sSyncResult != null) {
                    break;
                }
                long remaining = deadline - android.os.SystemClock.uptimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    sSyncLock.wait(remaining);
                } catch (InterruptedException e) {
                    break;
                }
            }
            drainPendingOpsLocked();
            verdict = sSyncResult == null ? "" : sSyncResult;
            sSyncResult = null;
            sUiWaiting = false;
        }
        return verdict;
    }

    private static void drainPendingOpsLocked() {
        java.util.concurrent.FutureTask<String> t;
        while ((t = sPendingOps.poll()) != null) {
            t.run();
        }
    }

    private static String runOp(String op, int id, String json) {
        {
            try {
                JSONObject args = TextUtils.isEmpty(json) ? new JSONObject() : new JSONObject(json);
                Object out = dispatch(op, id, args);
                JSONObject wrap = new JSONObject();
                wrap.put("ok", true);
                if (out != null) {
                    wrap.put("value", out);
                } else if ("get".equals(op)) {
                    // get 的“没有值”要在 Lua 侧读成 nil 而不是 true
                    wrap.put("value", JSONObject.NULL);
                }
                return wrap.toString();
            } catch (Throwable e) {
                Log.i(TAG, "luakit widget op failed: %s %s %s", op, String.valueOf(id), e.toString());
                JSONObject err = new JSONObject();
                try {
                    err.put("ok", false);
                    err.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
                } catch (Exception ignored) {
                }
                return err.toString();
            }
        }
    }

    private static Object dispatch(String op, int id, JSONObject args) throws Exception {
        switch (op) {
            case "create":
                return create(args);
            case "destroy":
                destroy(id);
                return null;
            case "set": {
                W w = need(id);
                Iterator<String> keys = args.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    set(w, k, args.opt(k));
                }
                return null;
            }
            case "get":
                return get(need(id), args.optString("key", ""));
            case "call":
                return call(need(id), args.optString("method", ""), args.optJSONArray("args"));
            default:
                throw new IllegalArgumentException("unknown widget op " + op);
        }
    }

    private static W need(int id) {
        W w = sWidgets.get(id);
        if (w == null) {
            throw new IllegalStateException("widget " + id + " is gone");
        }
        return w;
    }

    private static Activity activity() {
        ChromeTabbedActivity a = LemurXBridge.currentActivity();
        if (a != null && !a.isDestroyed() && !a.isFinishing()) {
            return a;
        }
        return LemurXUiHost.hostActivity();
    }

    private static Context ctx() {
        Activity a = activity();
        return a != null ? a : ContextUtils.getApplicationContext();
    }

    // ------------------------------------------------------------ 建 / 销

    private static int create(JSONObject args) {
        String type = args.optString("type", "");
        Context c = ctx();
        W w = new W();
        w.id = sNextId++;
        w.type = type;
        switch (type) {
            case "window": {
                FrameLayout root = new FrameLayout(c) {
                    @Override
                    public boolean dispatchKeyEvent(KeyEvent event) {
                        if (handleKey(this, event)) {
                            return true;
                        }
                        return super.dispatchKeyEvent(event);
                    }

                    @Override
                    public boolean onTouchEvent(MotionEvent ev) {
                        // 没被子控件吃掉的触摸（透明的 webview 占位区）放给下面的原生浏览器
                        return false;
                    }
                };
                root.setFocusableInTouchMode(true);
                root.setVisibility(View.GONE);
                w.view = root;
                break;
            }
            case "hbox":
            case "vbox": {
                LinearLayout ll = new LinearLayout(c);
                ll.setOrientation("hbox".equals(type) ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
                w.view = ll;
                break;
            }
            case "hpaned":
            case "vpaned": {
                LinearLayout ll = new LinearLayout(c);
                ll.setOrientation("hpaned".equals(type) ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
                w.view = ll;
                break;
            }
            case "notebook": {
                FrameLayout fl = new FrameLayout(c) {
                    @Override
                    public boolean onTouchEvent(MotionEvent ev) {
                        return false;
                    }
                };
                w.pages = new ArrayList<>();
                w.view = fl;
                break;
            }
            case "overlay":
            case "stack":
            case "eventbox": {
                FrameLayout fl = new FrameLayout(c);
                w.view = fl;
                if ("eventbox".equals(type)) {
                    installTouch(w, fl);
                }
                break;
            }
            case "scrolled": {
                // 默认竖向滚动；scrollbars 属性可切成横向
                ScrollView sv = new ScrollView(c);
                sv.setFillViewport(true);
                w.view = sv;
                break;
            }
            case "label": {
                TextView tv = new TextView(c);
                tv.setTextColor(Color.BLACK);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setGravity(Gravity.CENTER_VERTICAL);
                w.view = tv;
                installTouch(w, tv);
                break;
            }
            case "entry": {
                EditText et = new EditText(c);
                et.setSingleLine(true);
                et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                et.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
                et.setBackground(null);
                et.setPadding(dp(4), 0, dp(4), 0);
                final W fw = w;
                et.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int st, int cnt, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int st, int before, int cnt) {}

                    @Override
                    public void afterTextChanged(Editable s) {
                        if (!fw.suppressChanged) {
                            emit(fw, "changed", null);
                            emitProp(fw, "text");
                        }
                        emitProp(fw, "position");
                    }
                });
                et.setOnEditorActionListener((v, actionId, event) -> {
                    if (actionId == EditorInfo.IME_ACTION_GO
                            || actionId == EditorInfo.IME_ACTION_DONE
                            || actionId == EditorInfo.IME_ACTION_SEND
                            || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                        emit(fw, "activate", null);
                        return true;
                    }
                    return false;
                });
                et.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) {
                        return false;
                    }
                    return keyPress(fw, event);
                });
                et.setOnFocusChangeListener((v, has) -> {
                    emit(fw, has ? "focus" : "unfocus", null);
                    emitProp(fw, "focused");
                });
                w.view = et;
                break;
            }
            case "image": {
                ImageView iv = new ImageView(c);
                iv.setAdjustViewBounds(true);
                w.view = iv;
                break;
            }
            case "spinner": {
                ProgressBar pb = new ProgressBar(c);
                pb.setIndeterminate(true);
                pb.setVisibility(View.INVISIBLE);
                w.view = pb;
                break;
            }
            case "drawing_area": {
                w.view = new View(c);
                installTouch(w, w.view);
                break;
            }
            case "webview": {
                // 透明占位：原生 Tab 内容会被挪到它的矩形里
                View v = new View(c) {
                    @Override
                    public boolean onTouchEvent(MotionEvent ev) {
                        return false;
                    }
                };
                w.tabId = args.optInt("tab", -1);
                w.view = v;
                break;
            }
            default:
                throw new IllegalArgumentException("unknown widget type " + type);
        }
        w.view.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sWidgets.put(w.id, w);
        sByView.put(w.view, w);
        final W fw = w;
        w.view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int nw = r - l;
            int nh = b - t;
            if (nw != fw.lastW || nh != fw.lastH) {
                fw.lastW = nw;
                fw.lastH = nh;
                JSONObject ev = new JSONObject();
                try {
                    ev.put("width", px2dp(nw));
                    ev.put("height", px2dp(nh));
                } catch (Exception ignored) {
                }
                emit(fw, "resize", ev);
            }
            if ("webview".equals(fw.type) || "notebook".equals(fw.type)) {
                syncContentRect();
            }
        });
        if (!"window".equals(type) && !"webview".equals(type) && !"entry".equals(type)) {
            w.view.setOnFocusChangeListener((v, has) -> {
                emit(fw, has ? "focus" : "unfocus", null);
                emitProp(fw, "focused");
            });
        }
        return w.id;
    }

    private static void destroy(int id) {
        W w = sWidgets.remove(id);
        if (w == null) {
            return;
        }
        sByView.remove(w.view);
        if ("window".equals(w.type)) {
            hideWindow(w);
        }
        ViewParent parent = w.view.getParent();
        if (parent instanceof ViewGroup) {
            W pw = sByView.get(parent);
            ((ViewGroup) parent).removeView(w.view);
            if (pw != null) {
                onChildRemoved(pw, w);
            }
        }
        emit(w, "destroy", null);
        syncContentRect();
    }

    // ------------------------------------------------------------ 属性

    private static void set(W w, String key, Object value) throws Exception {
        View v = w.view;
        switch (key) {
            case "visible": {
                boolean vis = truthy(value);
                if ("window".equals(w.type)) {
                    if (vis) {
                        showWindow(w);
                    } else {
                        hideWindow(w);
                    }
                } else if ("spinner".equals(w.type)) {
                    v.setVisibility(vis ? View.VISIBLE : View.GONE);
                } else {
                    v.setVisibility(vis ? View.VISIBLE : View.GONE);
                }
                syncContentRect();
                return;
            }
            case "margin":
                setMargins(v, dp(num(value)), dp(num(value)), dp(num(value)), dp(num(value)));
                return;
            case "margin_left":
                setMargin(v, 0, dp(num(value)));
                return;
            case "margin_top":
                setMargin(v, 1, dp(num(value)));
                return;
            case "margin_right":
                setMargin(v, 2, dp(num(value)));
                return;
            case "margin_bottom":
                setMargin(v, 3, dp(num(value)));
                return;
            case "can_focus":
                v.setFocusable(truthy(value));
                v.setFocusableInTouchMode(truthy(value));
                return;
            case "tooltip":
                if (Build.VERSION.SDK_INT >= 26) {
                    v.setTooltipText(value == null ? null : String.valueOf(value));
                }
                return;
            case "min_size": {
                JSONObject o = value instanceof JSONObject ? (JSONObject) value : new JSONObject();
                if (o.has("w")) {
                    w.minW = dp(o.optDouble("w", 0));
                    v.setMinimumWidth(w.minW);
                    if (v instanceof TextView) ((TextView) v).setMinWidth(w.minW);
                }
                if (o.has("h")) {
                    w.minH = dp(o.optDouble("h", 0));
                    v.setMinimumHeight(w.minH);
                    if (v instanceof TextView) ((TextView) v).setMinHeight(w.minH);
                }
                v.requestLayout();
                return;
            }
            case "align": {
                JSONObject o = value instanceof JSONObject ? (JSONObject) value : new JSONObject();
                if (o.has("h")) w.alignH = alignOf(o.opt("h"));
                if (o.has("v")) w.alignV = alignOf(o.opt("v"));
                if (o.has("x") && v instanceof TextView) {
                    // lousy.widget.tab: label.align = { x = 0 }（GtkMisc xalign）
                    double x = o.optDouble("x", 0);
                    TextView tv = (TextView) v;
                    int g = tv.getGravity() & Gravity.VERTICAL_GRAVITY_MASK;
                    tv.setGravity(g | (x < 0.34 ? Gravity.START : x > 0.66 ? Gravity.END : Gravity.CENTER_HORIZONTAL));
                }
                applyAlign(w);
                return;
            }
            case "css":
                applyCss(w, String.valueOf(value));
                return;
            case "bg": {
                w.bg = value == null ? null : String.valueOf(value);
                Integer color = color(w.bg);
                v.setBackground(color == null ? null : new ColorDrawable(color));
                return;
            }
            case "fg": {
                w.fg = value == null ? null : String.valueOf(value);
                Integer color = color(w.fg);
                if (v instanceof TextView && color != null) {
                    ((TextView) v).setTextColor(color);
                }
                return;
            }
            case "font": {
                w.font = value == null ? null : String.valueOf(value);
                if (v instanceof TextView) {
                    applyFont((TextView) v, w.font);
                }
                return;
            }
            case "text": {
                String s = value == null ? "" : String.valueOf(value);
                if (v instanceof EditText) {
                    EditText et = (EditText) v;
                    w.suppressChanged = true;
                    et.setText(s);
                    et.setSelection(et.getText().length());
                    w.suppressChanged = false;
                } else if (v instanceof TextView) {
                    ((TextView) v).setText(pangoToSpanned(s));
                }
                return;
            }
            case "textwidth":
                if (v instanceof TextView) {
                    ((TextView) v).setMinEms((int) num(value));
                }
                return;
            case "selectable":
                if (v instanceof TextView) {
                    ((TextView) v).setTextIsSelectable(truthy(value));
                }
                return;
            case "padding": {
                JSONObject o = value instanceof JSONObject ? (JSONObject) value : new JSONObject();
                v.setPadding(dp(o.optDouble("x", 0)), dp(o.optDouble("y", 0)),
                        dp(o.optDouble("x", 0)), dp(o.optDouble("y", 0)));
                return;
            }
            case "position": {
                if (v instanceof EditText) {
                    EditText et = (EditText) v;
                    int pos = (int) num(value);
                    int len = et.getText().length();
                    if (pos < 0 || pos > len) pos = len;
                    et.setSelection(pos);
                } else if ("hpaned".equals(w.type) || "vpaned".equals(w.type)) {
                    setPanedPosition(w, dp(num(value)));
                }
                return;
            }
            case "show_frame":
                if (v instanceof EditText) {
                    v.setBackground(truthy(value) ? new EditText(ctx()).getBackground() : null);
                }
                return;
            case "homogeneous":
                if (v instanceof LinearLayout) {
                    // GTK homogeneous：所有子项等分。用权重实现
                    LinearLayout ll = (LinearLayout) v;
                    for (int i = 0; i < ll.getChildCount(); i++) {
                        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) ll.getChildAt(i).getLayoutParams();
                        if (truthy(value)) {
                            lp.weight = 1;
                            if (ll.getOrientation() == LinearLayout.HORIZONTAL) lp.width = 0; else lp.height = 0;
                        }
                    }
                    ll.requestLayout();
                }
                return;
            case "spacing":
                if (v instanceof LinearLayout && Build.VERSION.SDK_INT >= 11) {
                    LinearLayout ll = (LinearLayout) v;
                    int sp = dp(num(value));
                    ll.setShowDividers(sp > 0 ? LinearLayout.SHOW_DIVIDER_MIDDLE : LinearLayout.SHOW_DIVIDER_NONE);
                    ll.setDividerDrawable(sp > 0 ? new SpacerDrawable(sp) : null);
                }
                return;
            case "show_tabs":
            case "show_border":
                // Android 没有 GtkNotebook 自带的页签；luakit 自己用 tablist 画，这里无事可做
                return;
            case "scrollbars": {
                JSONObject o = value instanceof JSONObject ? (JSONObject) value : new JSONObject();
                String h = o.optString("h", "auto");
                String vv = o.optString("v", "auto");
                setScrolledOrientation(w, !"never".equals(h) && "never".equals(vv));
                w.view.setHorizontalScrollBarEnabled(!"never".equals(h) && !"external".equals(h));
                w.view.setVerticalScrollBarEnabled(!"never".equals(vv) && !"external".equals(vv));
                return;
            }
            case "scroll": {
                JSONObject o = value instanceof JSONObject ? (JSONObject) value : new JSONObject();
                int x = o.has("x") ? dp(o.optDouble("x", 0)) : w.view.getScrollX();
                int y = o.has("y") ? dp(o.optDouble("y", 0)) : w.view.getScrollY();
                w.view.scrollTo(x, y);
                return;
            }
            case "visible_child": {
                if ("stack".equals(w.type)) {
                    int cid = (int) num(value);
                    W child = sWidgets.get(cid);
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        View cv = g.getChildAt(i);
                        cv.setVisibility(child != null && cv == child.view ? View.VISIBLE : View.GONE);
                    }
                }
                return;
            }
            case "title":
                if ("window".equals(w.type)) {
                    Activity a = activity();
                    if (a != null) a.setTitle(value == null ? "" : String.valueOf(value));
                }
                return;
            case "decorated":
            case "urgency_hint":
            case "icon":
            case "screen":
            case "maximized":
                return;
            case "fullscreen":
                if ("window".equals(w.type)) {
                    LemurXChromeHost.setFullscreen(truthy(value));
                }
                return;
            case "wants_key_press":
                w.wantsKeyPress = truthy(value);
                if (w.wantsKeyPress && !(v instanceof EditText)) {
                    v.setFocusable(true);
                    v.setFocusableInTouchMode(true);
                }
                return;
            case "wants_button":
                w.wantsButton = truthy(value);
                if (w.wantsButton) v.setClickable(true);
                return;
            case "wants_scroll":
                w.wantsScroll = truthy(value);
                if (w.wantsScroll) v.setClickable(true);
                return;
            case "wants_enter":
                w.wantsEnter = truthy(value);
                if (w.wantsEnter) v.setClickable(true);
                return;
            case "child": {
                // bin 容器：eventbox / scrolled / window / overlay 的主子项
                ViewGroup g = (ViewGroup) v;
                for (int i = g.getChildCount() - 1; i >= 0; i--) {
                    View cv = g.getChildAt(i);
                    W cw = sByView.get(cv);
                    if (cw == null || !"overlay".equals(w.type) || Boolean.TRUE.equals(cv.getTag(TAG_MAIN))) {
                        g.removeViewAt(i);
                        if (cw != null) onChildRemoved(w, cw);
                    }
                }
                if (value != null && !(value instanceof Boolean)) {
                    W child = need((int) num(value));
                    detach(child);
                    ViewGroup.LayoutParams lp;
                    if (g instanceof FrameLayout) {
                        lp = new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                    } else {
                        lp = new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                    }
                    child.view.setTag(TAG_MAIN, Boolean.TRUE);
                    g.addView(child.view, 0, lp);
                    onChildAdded(w, child);
                }
                return;
            }
            default:
                // 未知属性静默忽略（luakit 对 GTK 没有的属性也是如此）
        }
    }

    private static final int TAG_MAIN = 0x7f0f0001;

    private static Object get(W w, String key) throws Exception {
        View v = w.view;
        switch (key) {
            case "visible":
                return "window".equals(w.type) ? w.windowShown : v.getVisibility() == View.VISIBLE;
            case "focused":
                return v.hasFocus() || ("window".equals(w.type) && v.hasWindowFocus());
            case "width":
                return px2dp(v.getWidth());
            case "height":
                return px2dp(v.getHeight());
            case "min_size": {
                JSONObject o = new JSONObject();
                o.put("w", px2dp(Math.max(0, w.minW)));
                o.put("h", px2dp(Math.max(0, w.minH)));
                return o;
            }
            case "align": {
                JSONObject o = new JSONObject();
                o.put("h", w.alignH < 0 ? 0 : w.alignH);
                o.put("v", w.alignV < 0 ? 0 : w.alignV);
                return o;
            }
            case "tooltip":
                return Build.VERSION.SDK_INT >= 26 && v.getTooltipText() != null ? v.getTooltipText().toString() : null;
            case "parent": {
                ViewParent p = v.getParent();
                W pw = p instanceof View ? sByView.get(p) : null;
                return pw == null ? null : pw.id;
            }
            case "children": {
                JSONArray arr = new JSONArray();
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        W cw = sByView.get(g.getChildAt(i));
                        if (cw != null) arr.put(cw.id);
                    }
                }
                return arr;
            }
            case "child": {
                if (v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0) {
                    W cw = sByView.get(((ViewGroup) v).getChildAt(0));
                    return cw == null ? null : cw.id;
                }
                return null;
            }
            case "text":
                if (v instanceof TextView) {
                    return ((TextView) v).getText().toString();
                }
                return null;
            case "position":
                if (v instanceof EditText) {
                    return ((EditText) v).getSelectionStart();
                }
                if ("hpaned".equals(w.type) || "vpaned".equals(w.type)) {
                    ViewGroup g = (ViewGroup) v;
                    if (g.getChildCount() > 0) {
                        View first = g.getChildAt(0);
                        return px2dp("hpaned".equals(w.type) ? first.getWidth() : first.getHeight());
                    }
                    return 0;
                }
                return null;
            case "fg":
                return w.fg;
            case "bg":
                return w.bg;
            case "font":
                return w.font;
            case "show_frame":
                return v.getBackground() != null;
            case "selectable":
                return v instanceof TextView && ((TextView) v).isTextSelectable();
            case "textwidth":
                return v instanceof TextView ? ((TextView) v).getMinEms() : 0;
            case "padding": {
                JSONObject o = new JSONObject();
                o.put("x", px2dp(v.getPaddingLeft()));
                o.put("y", px2dp(v.getPaddingTop()));
                return o;
            }
            case "homogeneous":
                return false;
            case "spacing":
                return 0;
            case "show_tabs":
            case "show_border":
                return false;
            case "scroll": {
                JSONObject o = new JSONObject();
                o.put("x", px2dp(v.getScrollX()));
                o.put("y", px2dp(v.getScrollY()));
                View inner = v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0
                        ? ((ViewGroup) v).getChildAt(0) : null;
                o.put("xmax", px2dp(Math.max(0, (inner == null ? 0 : inner.getWidth()) - v.getWidth())));
                o.put("ymax", px2dp(Math.max(0, (inner == null ? 0 : inner.getHeight()) - v.getHeight())));
                o.put("xpage_size", px2dp(v.getWidth()));
                o.put("ypage_size", px2dp(v.getHeight()));
                return o;
            }
            case "scrollbars": {
                JSONObject o = new JSONObject();
                o.put("h", v.isHorizontalScrollBarEnabled() ? "auto" : "never");
                o.put("v", v.isVerticalScrollBarEnabled() ? "auto" : "never");
                return o;
            }
            case "visible_child": {
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        View cv = g.getChildAt(i);
                        if (cv.getVisibility() == View.VISIBLE) {
                            W cw = sByView.get(cv);
                            return cw == null ? null : cw.id;
                        }
                    }
                }
                return null;
            }
            case "started":
                return v.getVisibility() == View.VISIBLE;
            case "title": {
                Activity a = activity();
                return a == null || a.getTitle() == null ? "" : a.getTitle().toString();
            }
            case "decorated":
            case "urgency_hint":
            case "maximized":
                return false;
            case "fullscreen":
                return false;
            case "id":
                return w.id;
            case "root_win_xid":
                return 0;
            case "screen": {
                JSONObject o = new JSONObject();
                Activity a = activity();
                if (a != null) {
                    android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
                    o.put("width", px2dp(dm.widthPixels));
                    o.put("height", px2dp(dm.heightPixels));
                }
                return o;
            }
            case "top":
            case "left":
                return panedChild(w, 0);
            case "bottom":
            case "right":
                return panedChild(w, 1);
            default:
                return null;
        }
    }

    // ------------------------------------------------------------ 方法

    private static Object call(W w, String method, JSONArray a) throws Exception {
        if (a == null) a = new JSONArray();
        View v = w.view;
        switch (method) {
            case "show":
                set(w, "visible", true);
                return null;
            case "hide":
                set(w, "visible", false);
                return null;
            case "focus": {
                if (v instanceof EditText) {
                    v.requestFocus();
                    InputMethodManager imm = (InputMethodManager) ctx().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
                } else if ("window".equals(w.type)) {
                    v.requestFocus();
                } else {
                    v.setFocusable(true);
                    v.setFocusableInTouchMode(true);
                    v.requestFocus();
                }
                return null;
            }
            case "unfocus": {
                v.clearFocus();
                if (v instanceof EditText) {
                    InputMethodManager imm = (InputMethodManager) ctx().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                }
                return null;
            }
            case "pack":
                return pack(w, need(a.getInt(0)), a.optJSONObject(1));
            case "remove": {
                W child = need(a.getInt(0));
                if (child.view.getParent() == v) {
                    ((ViewGroup) v).removeView(child.view);
                    onChildRemoved(w, child);
                }
                return null;
            }
            case "replace": {
                // luakit widget:replace(new)：用另一个控件替换自己在父容器中的位置
                W repl = need(a.getInt(0));
                ViewParent p = v.getParent();
                if (p instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) p;
                    int idx = g.indexOfChild(v);
                    ViewGroup.LayoutParams lp = v.getLayoutParams();
                    W pw = sByView.get(g);
                    g.removeViewAt(idx);
                    if (pw != null) onChildRemoved(pw, w);
                    detach(repl);
                    g.addView(repl.view, idx, lp);
                    if (pw != null) onChildAdded(pw, repl);
                }
                return null;
            }
            case "reorder": {
                W child = need(a.getInt(0));
                int idx = a.getInt(1);
                if ("notebook".equals(w.type)) {
                    // luakit notebook:reorder 用 1 基下标（-1 = 末尾）；box:reorder 是 GTK 的 0 基
                    idx = idx < 1 ? -1 : idx - 1;
                }
                if (child.view.getParent() == v) {
                    ViewGroup g = (ViewGroup) v;
                    ViewGroup.LayoutParams lp = child.view.getLayoutParams();
                    g.removeView(child.view);
                    if (idx < 0 || idx > g.getChildCount()) idx = g.getChildCount();
                    g.addView(child.view, idx, lp);
                    if ("notebook".equals(w.type)) {
                        w.pages.remove((Integer) child.id);
                        w.pages.add(Math.min(idx, w.pages.size()), child.id);
                        JSONObject ev = new JSONObject();
                        ev.put("child", child.id);
                        ev.put("index", w.pages.indexOf(child.id));
                        emit(w, "page-reordered", ev);
                        showNotebookPage(w, w.current);
                    }
                }
                return null;
            }
            // ---- notebook
            case "insert": {
                int idx = a.length() > 1 ? a.getInt(0) : -1;
                W child = need(a.getInt(a.length() > 1 ? 1 : 0));
                return notebookInsert(w, idx, child);
            }
            case "count":
                return w.pages == null ? 0 : w.pages.size();
            case "current":
                return w.pages == null ? 0 : w.current + 1;
            case "atindex": {
                int idx = a.getInt(0);
                if (w.pages == null || idx < 1 || idx > w.pages.size()) return null;
                return w.pages.get(idx - 1);
            }
            case "indexof": {
                if (w.pages == null) return -1;
                int i = w.pages.indexOf(a.getInt(0));
                return i < 0 ? -1 : i + 1;
            }
            case "switch": {
                int idx = a.getInt(0);
                if (w.pages == null || w.pages.isEmpty()) return null;
                if (idx < 1 || idx > w.pages.size()) idx = w.pages.size();
                switchNotebook(w, idx - 1, true);
                return idx;
            }
            case "set_title": {
                W child = need(a.getInt(0));
                child.title = a.optString(1, "");
                return null;
            }
            case "get_title": {
                W child = need(a.getInt(0));
                return child.title == null ? "" : child.title;
            }
            // ---- paned
            case "pack1":
            case "pack2": {
                W child = need(a.getInt(0));
                JSONObject opts = a.optJSONObject(1);
                panedPack(w, child, "pack1".equals(method) ? 0 : 1, opts);
                return null;
            }
            // ---- entry
            case "insert_text": {
                if (v instanceof EditText) {
                    EditText et = (EditText) v;
                    int pos = a.length() > 1 ? a.getInt(0) : et.getSelectionStart();
                    String text = a.getString(a.length() > 1 ? 1 : 0);
                    if (pos < 0 || pos > et.getText().length()) pos = et.getText().length();
                    et.getText().insert(pos, text);
                }
                return null;
            }
            case "select_region": {
                if (v instanceof EditText) {
                    EditText et = (EditText) v;
                    int len = et.getText().length();
                    int s = a.getInt(0);
                    int e = a.length() > 1 ? a.getInt(1) : -1;
                    if (s < 0) s = len;
                    if (e < 0 || e > len) e = len;
                    et.setSelection(Math.min(s, len), e);
                }
                return null;
            }
            // ---- image
            case "filename": {
                if (v instanceof ImageView) {
                    String path = a.getString(0);
                    Bitmap bmp = BitmapFactory.decodeFile(path);
                    ((ImageView) v).setImageBitmap(bmp);
                    if (a.length() > 1 && bmp != null) {
                        int size = dp(a.optDouble(1, bmp.getWidth()));
                        setSize(v, size, size);
                    }
                }
                return null;
            }
            case "icon": {
                if (v instanceof ImageView) {
                    String name = a.getString(0);
                    int size = dp(a.optDouble(1, 16));
                    setIcon((ImageView) v, name, size);
                }
                return null;
            }
            case "scale": {
                if (v instanceof ImageView) {
                    int wpx = dp(a.optDouble(0, 16));
                    int hpx = dp(a.optDouble(1, a.optDouble(0, 16)));
                    setSize(v, wpx, hpx);
                    ((ImageView) v).setScaleType(ImageView.ScaleType.FIT_CENTER);
                }
                return null;
            }
            case "set_favicon_for_uri": {
                if (v instanceof ImageView) {
                    Bitmap bmp = LemurXBridge.faviconForUri(a.getString(0));
                    ((ImageView) v).setImageBitmap(bmp);
                    return bmp != null;
                }
                return false;
            }
            // ---- spinner
            case "start":
                v.setVisibility(View.VISIBLE);
                emitProp(w, "started");
                return null;
            case "stop":
                v.setVisibility(View.INVISIBLE);
                emitProp(w, "started");
                return null;
            // ---- drawing_area
            case "invalidate":
                v.invalidate();
                return null;
            // ---- window
            case "set_default_size":
                return null;
            case "set_dark_mode":
                return null;
            case "send_key": {
                // send_key(key, mods)：合成一次 key-press 给该控件
                JSONObject ev = new JSONObject();
                ev.put("key", a.getString(0));
                ev.put("mods", a.optJSONArray(1) == null ? new JSONArray() : a.optJSONArray(1));
                ev.put("synthetic", true);
                emit(w, "key-press", ev);
                return null;
            }
            case "query_tooltip":
                return get(w, "tooltip");
            case "css_reset":
                return null;
            case "rect": {
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                JSONObject o = new JSONObject();
                o.put("x", px2dp(loc[0]));
                o.put("y", px2dp(loc[1]));
                o.put("w", px2dp(v.getWidth()));
                o.put("h", px2dp(v.getHeight()));
                return o;
            }
            default:
                throw new IllegalArgumentException("widget " + w.type + " has no method " + method);
        }
    }

    // ------------------------------------------------------------ 容器操作

    private static void detach(W child) {
        ViewParent p = child.view.getParent();
        if (p instanceof ViewGroup) {
            W pw = sByView.get(p);
            ((ViewGroup) p).removeView(child.view);
            if (pw != null) {
                onChildRemoved(pw, child);
            }
        }
    }

    private static int pack(W w, W child, JSONObject opts) throws Exception {
        if (opts == null) opts = new JSONObject();
        detach(child);
        View v = w.view;
        if (v instanceof LinearLayout && ("hbox".equals(w.type) || "vbox".equals(w.type))) {
            LinearLayout ll = (LinearLayout) v;
            boolean expand = opts.optBoolean("expand", false);
            boolean fill = opts.optBoolean("fill", false);
            int padding = dp(opts.optDouble("padding", 0));
            boolean horizontal = ll.getOrientation() == LinearLayout.HORIZONTAL;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    horizontal ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT,
                    horizontal ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT);
            if (expand) {
                lp.weight = 1;
                if (horizontal) lp.width = 0; else lp.height = 0;
                if (!fill) {
                    // expand 不 fill：子项居中，多余空间留白。用 gravity 近似
                    lp.gravity = Gravity.CENTER;
                }
            }
            if (horizontal) {
                lp.leftMargin = padding;
                lp.rightMargin = padding;
            } else {
                lp.topMargin = padding;
                lp.bottomMargin = padding;
            }
            ll.addView(child.view, lp);
            onChildAdded(w, child);
            return ll.indexOfChild(child.view);
        }
        if ("overlay".equals(w.type)) {
            FrameLayout fl = (FrameLayout) v;
            int gh = gravityOf(opts.opt("halign"), true);
            int gv = gravityOf(opts.opt("valign"), false);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    gh == Gravity.FILL_HORIZONTAL ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                    gv == Gravity.FILL_VERTICAL ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                    (gh == Gravity.FILL_HORIZONTAL ? Gravity.START : gh) | (gv == Gravity.FILL_VERTICAL ? Gravity.TOP : gv));
            fl.addView(child.view, lp);
            onChildAdded(w, child);
            return fl.indexOfChild(child.view);
        }
        if ("stack".equals(w.type)) {
            FrameLayout fl = (FrameLayout) v;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            child.view.setVisibility(fl.getChildCount() == 0 ? View.VISIBLE : View.GONE);
            fl.addView(child.view, lp);
            onChildAdded(w, child);
            return fl.indexOfChild(child.view);
        }
        if ("notebook".equals(w.type)) {
            return notebookInsert(w, -1, child);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            g.addView(child.view, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            onChildAdded(w, child);
            return g.indexOfChild(child.view);
        }
        throw new IllegalStateException(w.type + " is not a container");
    }

    private static void onChildAdded(W parent, W child) {
        applyAlign(child);
        JSONObject ev = new JSONObject();
        try {
            ev.put("child", child.id);
        } catch (Exception ignored) {
        }
        emit(parent, "add", ev);
        JSONObject ps = new JSONObject();
        try {
            ps.put("parent", parent.id);
        } catch (Exception ignored) {
        }
        emit(child, "parent-set", ps);
        emitProp(child, "parent");
        syncContentRect();
    }

    private static void onChildRemoved(W parent, W child) {
        if ("notebook".equals(parent.type) && parent.pages != null) {
            int idx = parent.pages.indexOf(child.id);
            if (idx >= 0) {
                parent.pages.remove(idx);
                JSONObject ev = new JSONObject();
                try {
                    ev.put("child", child.id);
                } catch (Exception ignored) {
                }
                emit(parent, "page-removed", ev);
                if (parent.pages.isEmpty()) {
                    parent.current = -1;
                } else if (parent.current >= parent.pages.size()) {
                    switchNotebook(parent, parent.pages.size() - 1, true);
                } else if (idx <= parent.current) {
                    switchNotebook(parent, Math.max(0, parent.current - (idx < parent.current ? 1 : 0)), idx == parent.current);
                }
            }
        }
        JSONObject ev = new JSONObject();
        try {
            ev.put("child", child.id);
        } catch (Exception ignored) {
        }
        emit(parent, "remove", ev);
        emit(child, "parent-set", new JSONObject());
        emitProp(child, "parent");
        syncContentRect();
    }

    private static int notebookInsert(W nb, int idx, W child) throws Exception {
        detach(child);
        FrameLayout fl = (FrameLayout) nb.view;
        if (idx < 1 || idx > nb.pages.size() + 1) {
            idx = nb.pages.size() + 1;
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        child.view.setVisibility(View.GONE);
        fl.addView(child.view, idx - 1, lp);
        nb.pages.add(idx - 1, child.id);
        // 页对应的 webview 占位：默认可见性由当前页决定
        JSONObject ev = new JSONObject();
        ev.put("child", child.id);
        ev.put("index", idx);
        emit(nb, "page-added", ev);
        JSONObject ps = new JSONObject();
        ps.put("parent", nb.id);
        emit(child, "parent-set", ps);
        if (nb.current < 0) {
            switchNotebook(nb, 0, true);
        } else if (idx - 1 <= nb.current) {
            nb.current++;
            showNotebookPage(nb, nb.current);
        }
        syncContentRect();
        return idx;
    }

    private static void showNotebookPage(W nb, int index) {
        FrameLayout fl = (FrameLayout) nb.view;
        for (int i = 0; i < fl.getChildCount(); i++) {
            fl.getChildAt(i).setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
    }

    private static void switchNotebook(W nb, int index, boolean emitSignal) {
        if (nb.pages == null || nb.pages.isEmpty()) {
            nb.current = -1;
            return;
        }
        if (index < 0) index = 0;
        if (index >= nb.pages.size()) index = nb.pages.size() - 1;
        boolean changed = nb.current != index;
        nb.current = index;
        showNotebookPage(nb, index);
        W page = sWidgets.get(nb.pages.get(index));
        if (page != null && "webview".equals(page.type) && page.tabId >= 0) {
            LemurXBridge.selectTabForLuakit(page.tabId);
        }
        if (emitSignal && page != null) {
            JSONObject ev = new JSONObject();
            try {
                ev.put("child", page.id);
                ev.put("index", index + 1);
            } catch (Exception ignored) {
            }
            emit(nb, "switch-page", ev);
        }
        syncContentRect();
    }

    /** 原生侧切了 Tab（用户手势/其它入口）：同步所有包含该 tab 的 notebook。 */
    static void onNativeTabSelected(int tabId) {
        ThreadUtils.runOnUiThread(() -> {
            for (W nb : new ArrayList<>(sWidgets.values())) {
                if (!"notebook".equals(nb.type) || nb.pages == null) continue;
                for (int i = 0; i < nb.pages.size(); i++) {
                    W page = sWidgets.get(nb.pages.get(i));
                    if (page != null && page.tabId == tabId && nb.current != i) {
                        nb.current = i;
                        showNotebookPage(nb, i);
                        JSONObject ev = new JSONObject();
                        try {
                            ev.put("child", page.id);
                            ev.put("index", i + 1);
                        } catch (Exception ignored) {
                        }
                        emit(nb, "switch-page", ev);
                        syncContentRect();
                    }
                }
            }
        });
    }

    private static void panedPack(W w, W child, int slot, JSONObject opts) {
        detach(child);
        LinearLayout ll = (LinearLayout) w.view;
        boolean horizontal = ll.getOrientation() == LinearLayout.HORIZONTAL;
        boolean resize = opts == null || opts.optBoolean("resize", slot == 1);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                horizontal ? (resize ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT) : ViewGroup.LayoutParams.MATCH_PARENT,
                horizontal ? ViewGroup.LayoutParams.MATCH_PARENT : (resize ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT));
        if (resize) lp.weight = 1;
        // 槽位 0 在前
        int index = slot == 0 ? 0 : ll.getChildCount();
        if (slot == 0 && (w.paned & 1) != 0) {
            View old = ll.getChildAt(0);
            W ow = sByView.get(old);
            ll.removeViewAt(0);
            if (ow != null) onChildRemoved(w, ow);
        } else if (slot == 1 && (w.paned & 2) != 0) {
            View old = ll.getChildAt(ll.getChildCount() - 1);
            W ow = sByView.get(old);
            ll.removeView(old);
            if (ow != null) onChildRemoved(w, ow);
            index = ll.getChildCount();
        }
        child.view.setTag(TAG_MAIN, slot);
        ll.addView(child.view, Math.min(index, ll.getChildCount()), lp);
        w.paned |= (slot == 0 ? 1 : 2);
        onChildAdded(w, child);
    }

    private static Object panedChild(W w, int slot) {
        if (!(w.view instanceof LinearLayout)) return null;
        LinearLayout ll = (LinearLayout) w.view;
        for (int i = 0; i < ll.getChildCount(); i++) {
            View cv = ll.getChildAt(i);
            Object tag = cv.getTag(TAG_MAIN);
            if (tag instanceof Integer && (Integer) tag == slot) {
                W cw = sByView.get(cv);
                return cw == null ? null : cw.id;
            }
        }
        return null;
    }

    private static void setPanedPosition(W w, int px) {
        LinearLayout ll = (LinearLayout) w.view;
        if (ll.getChildCount() == 0) return;
        View first = ll.getChildAt(0);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) first.getLayoutParams();
        lp.weight = 0;
        if (ll.getOrientation() == LinearLayout.HORIZONTAL) lp.width = px; else lp.height = px;
        first.setLayoutParams(lp);
        if (ll.getChildCount() > 1) {
            LinearLayout.LayoutParams lp2 = (LinearLayout.LayoutParams) ll.getChildAt(1).getLayoutParams();
            lp2.weight = 1;
            if (ll.getOrientation() == LinearLayout.HORIZONTAL) lp2.width = 0; else lp2.height = 0;
            ll.getChildAt(1).setLayoutParams(lp2);
        }
        emitProp(w, "position");
    }

    private static void setScrolledOrientation(W w, boolean horizontal) {
        boolean isH = w.view instanceof HorizontalScrollView;
        if (isH == horizontal) return;
        ViewGroup old = (ViewGroup) w.view;
        View child = old.getChildCount() > 0 ? old.getChildAt(0) : null;
        if (child != null) old.removeView(child);
        ViewGroup fresh;
        if (horizontal) {
            HorizontalScrollView h = new HorizontalScrollView(ctx());
            h.setFillViewport(true);
            fresh = h;
        } else {
            ScrollView s = new ScrollView(ctx());
            s.setFillViewport(true);
            fresh = s;
        }
        fresh.setLayoutParams(old.getLayoutParams());
        ViewParent p = old.getParent();
        if (p instanceof ViewGroup) {
            ViewGroup pg = (ViewGroup) p;
            int idx = pg.indexOfChild(old);
            pg.removeViewAt(idx);
            pg.addView(fresh, idx, old.getLayoutParams());
        }
        if (child != null) {
            fresh.addView(child, new ViewGroup.LayoutParams(
                    horizontal ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT,
                    horizontal ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        sByView.remove(old);
        w.view = fresh;
        sByView.put(fresh, w);
    }

    // ------------------------------------------------------------ window

    private static FrameLayout windowLayer(Activity a) {
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null) return null;
        if (sWindowLayer != null && sWindowLayer.getParent() == content) {
            return sWindowLayer;
        }
        if (sWindowLayer != null && sWindowLayer.getParent() instanceof ViewGroup) {
            ((ViewGroup) sWindowLayer.getParent()).removeView(sWindowLayer);
        }
        if (sWindowLayer == null) {
            sWindowLayer = new FrameLayout(a) {
                @Override
                public boolean onTouchEvent(MotionEvent ev) {
                    return false;
                }
            };
        }
        content.addView(sWindowLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return sWindowLayer;
    }

    private static void showWindow(W w) {
        Activity a = activity();
        if (a == null) return;
        FrameLayout layer = windowLayer(a);
        if (layer == null) return;
        if (w.view.getParent() != layer) {
            if (w.view.getParent() instanceof ViewGroup) {
                ((ViewGroup) w.view.getParent()).removeView(w.view);
            }
            layer.addView(w.view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        w.view.setVisibility(View.VISIBLE);
        w.windowShown = true;
        if (!sShellHidden) {
            sShellHidden = true;
            LemurXChromeHost.setControls("hidden");
            LemurXChromeHost.hideBottomToolbar(true);
        }
        w.view.requestFocus();
        emitProp(w, "visible");
        syncContentRect();
    }

    private static void hideWindow(W w) {
        w.view.setVisibility(View.GONE);
        if (w.view.getParent() instanceof ViewGroup) {
            ((ViewGroup) w.view.getParent()).removeView(w.view);
        }
        w.windowShown = false;
        boolean anyShown = false;
        for (W o : sWidgets.values()) {
            if ("window".equals(o.type) && o.windowShown) anyShown = true;
        }
        if (!anyShown && sShellHidden) {
            sShellHidden = false;
            LemurXChromeHost.setControls("both");
            LemurXChromeHost.hideBottomToolbar(false);
            applyContentInsets(0, 0, 0, 0);
        }
        emitProp(w, "visible");
    }

    /** Activity 重建后把显示中的 window 重新挂上。 */
    static void reattach(Activity a) {
        ThreadUtils.assertOnUiThread();
        for (W w : new ArrayList<>(sWidgets.values())) {
            if ("window".equals(w.type) && w.windowShown) {
                showWindow(w);
            }
        }
    }

    // ------------------------------------------------------------ 原生内容矩形

    /**
     * 找到当前可见的 webview 占位，把 CompositorViewHolder 的外边距设成占位矩形与
     * 坐标系容器的差，让原生 Tab 内容正好落在 luakit 的 notebook 区域里。
     */
    private static void syncContentRect() {
        Activity a = activity();
        if (a == null || !sShellHidden) return;
        View placeholder = null;
        for (W w : sWidgets.values()) {
            if ("webview".equals(w.type) && w.view.isShown() && w.view.getWidth() > 0) {
                placeholder = w.view;
                break;
            }
        }
        View holder = compositorViewHolder(a);
        if (holder == null) return;
        ViewParent hp = holder.getParent();
        if (!(hp instanceof View)) return;
        if (placeholder == null) {
            applyContentInsets(0, 0, 0, 0);
            return;
        }
        int[] pl = new int[2];
        int[] cl = new int[2];
        placeholder.getLocationInWindow(pl);
        ((View) hp).getLocationInWindow(cl);
        int parentW = ((View) hp).getWidth();
        int parentH = ((View) hp).getHeight();
        int left = pl[0] - cl[0];
        int top = pl[1] - cl[1];
        int right = parentW - (left + placeholder.getWidth());
        int bottom = parentH - (top + placeholder.getHeight());
        applyContentInsets(Math.max(0, left), Math.max(0, top), Math.max(0, right), Math.max(0, bottom));
    }

    /**
     * 原生 Tab 内容所在的 CompositorViewHolder。Chromium 154 的 ChromeActivity 公开的是
     * getCompositorViewHolderSupplier()（ForTesting 版不用于产品代码）；供应器还没就绪时
     * 退回按资源名找 R.id.compositor_view_holder（compositor_view_holder.xml 里仍叫这个名字）。
     */
    private static View compositorViewHolder(Activity a) {
        View holder = null;
        if (a instanceof ChromeActivity) {
            holder = ((ChromeActivity) a).getCompositorViewHolderSupplier().get();
        }
        if (holder == null) {
            int id = a.getResources().getIdentifier("compositor_view_holder", "id", a.getPackageName());
            holder = id == 0 ? null : a.findViewById(id);
        }
        return holder;
    }

    private static void applyContentInsets(int left, int top, int right, int bottom) {
        Activity a = activity();
        if (a == null) return;
        View holder = compositorViewHolder(a);
        if (holder == null) return;
        if (sContentInsets[0] == left && sContentInsets[1] == top && sContentInsets[2] == right && sContentInsets[3] == bottom) {
            return;
        }
        sContentInsets[0] = left;
        sContentInsets[1] = top;
        sContentInsets[2] = right;
        sContentInsets[3] = bottom;
        ViewGroup.LayoutParams lp = holder.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            mlp.leftMargin = left;
            mlp.topMargin = top;
            mlp.rightMargin = right;
            mlp.bottomMargin = bottom;
            holder.setLayoutParams(mlp);
            holder.requestLayout();
        }
    }

    // ------------------------------------------------------------ 事件

    private static void emit(W w, String signal, JSONObject fields) {
        if (w == null) return;
        JSONObject ev = fields == null ? new JSONObject() : fields;
        try {
            ev.put("ev", signal);
        } catch (Exception ignored) {
        }
        LemurXBridge.dispatchLuakitWidgetNative(w.id, ev.toString());
    }

    private static void emitProp(W w, String name) {
        JSONObject ev = new JSONObject();
        try {
            ev.put("name", name);
        } catch (Exception ignored) {
        }
        emit(w, "property", ev);
    }

    private static boolean handleKey(View windowRoot, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        W w = sByView.get(windowRoot);
        if (w == null) return false;
        return keyPress(w, event);
    }

    /** 同步问 Lua：key-press 是否被吃掉。 */
    private static boolean keyPress(W w, KeyEvent event) {
        if (!w.wantsKeyPress) {
            return false;
        }
        String key = keyName(event);
        if (key == null) return false;
        JSONObject ev = new JSONObject();
        try {
            ev.put("ev", "key-press");
            ev.put("key", key);
            ev.put("mods", modifiers(event.getMetaState()));
            ev.put("synthetic", false);
        } catch (Exception ignored) {
        }
        String verdict = awaitLuaVerdict(w.id, ev.toString());
        return "true".equals(verdict);
    }

    private static JSONArray modifiers(int meta) {
        JSONArray arr = new JSONArray();
        if ((meta & KeyEvent.META_SHIFT_ON) != 0) arr.put("Shift");
        if ((meta & KeyEvent.META_CAPS_LOCK_ON) != 0) arr.put("Lock");
        if ((meta & KeyEvent.META_CTRL_ON) != 0) arr.put("Control");
        if ((meta & KeyEvent.META_ALT_ON) != 0) arr.put("Mod1");
        if ((meta & KeyEvent.META_NUM_LOCK_ON) != 0) arr.put("Mod2");
        if ((meta & KeyEvent.META_META_ON) != 0) arr.put("Mod4");
        return arr;
    }

    /** Android 键码 → GDK 键名（luakit 绑定用的名字）。 */
    private static String keyName(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return "Return";
            case KeyEvent.KEYCODE_ESCAPE:
                return "Escape";
            case KeyEvent.KEYCODE_DEL:
                return "BackSpace";
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return "Delete";
            case KeyEvent.KEYCODE_TAB:
                return (event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0 ? "ISO_Left_Tab" : "Tab";
            case KeyEvent.KEYCODE_DPAD_UP:
                return "Up";
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return "Down";
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return "Left";
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return "Right";
            case KeyEvent.KEYCODE_MOVE_HOME:
                return "Home";
            case KeyEvent.KEYCODE_MOVE_END:
                return "End";
            case KeyEvent.KEYCODE_PAGE_UP:
                return "Page_Up";
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return "Page_Down";
            case KeyEvent.KEYCODE_INSERT:
                return "Insert";
            case KeyEvent.KEYCODE_SPACE:
                return "space";
            case KeyEvent.KEYCODE_BACK:
                return "Escape";
            case KeyEvent.KEYCODE_MENU:
                return "Menu";
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
            case KeyEvent.KEYCODE_CAPS_LOCK:
                return null;
            default:
                break;
        }
        int kc = event.getKeyCode();
        if (kc >= KeyEvent.KEYCODE_F1 && kc <= KeyEvent.KEYCODE_F12) {
            return "F" + (kc - KeyEvent.KEYCODE_F1 + 1);
        }
        int uc = event.getUnicodeChar(event.getMetaState() & ~KeyEvent.META_CTRL_MASK & ~KeyEvent.META_ALT_MASK);
        if (uc > 0 && !Character.isISOControl(uc)) {
            return new String(Character.toChars(uc));
        }
        return KeyEvent.keyCodeToString(kc).replace("KEYCODE_", "");
    }

    private static void installTouch(W w, View v) {
        final float[] down = new float[2];
        final long[] lastUp = new long[1];
        v.setOnTouchListener((view, ev) -> {
            if (!w.wantsButton && !w.wantsEnter && !w.wantsScroll) {
                return false;
            }
            JSONArray mods = new JSONArray();
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    down[0] = ev.getX();
                    down[1] = ev.getY();
                    if (w.wantsEnter) emitWithMods(w, "mouse-enter", mods);
                    if (w.wantsButton) {
                        long now = System.currentTimeMillis();
                        boolean dbl = now - lastUp[0] < 300;
                        JSONObject e = new JSONObject();
                        try {
                            e.put("mods", mods);
                            e.put("button", 1);
                        } catch (Exception ignored) {
                        }
                        emit(w, dbl ? "button-double-click" : "button-press", e);
                    }
                    return w.wantsButton;
                }
                case MotionEvent.ACTION_UP: {
                    lastUp[0] = System.currentTimeMillis();
                    if (w.wantsButton) {
                        JSONObject e = new JSONObject();
                        try {
                            e.put("mods", mods);
                            e.put("button", 1);
                        } catch (Exception ignored) {
                        }
                        emit(w, "button-release", e);
                    }
                    if (w.wantsEnter) emitWithMods(w, "mouse-leave", mods);
                    return w.wantsButton;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (w.wantsScroll) {
                        float dx = down[0] - ev.getX();
                        float dy = down[1] - ev.getY();
                        if (Math.abs(dx) > 24 || Math.abs(dy) > 24) {
                            down[0] = ev.getX();
                            down[1] = ev.getY();
                            JSONObject e = new JSONObject();
                            try {
                                e.put("mods", mods);
                                e.put("dx", Math.signum(dx));
                                e.put("dy", Math.signum(dy));
                            } catch (Exception ignored) {
                            }
                            emit(w, "scroll", e);
                        }
                    }
                    return w.wantsButton;
                }
                default:
                    return false;
            }
        });
        if (w.wantsButton || w.wantsEnter || w.wantsScroll) {
            v.setClickable(true);
        }
    }

    private static void emitWithMods(W w, String signal, JSONArray mods) {
        JSONObject e = new JSONObject();
        try {
            e.put("mods", mods);
        } catch (Exception ignored) {
        }
        emit(w, signal, e);
    }

    // ------------------------------------------------------------ 样式辅助

    private static void applyAlign(W w) {
        View v = w.view;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) return;
        if (lp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams l = (LinearLayout.LayoutParams) lp;
            int g = 0;
            if (w.alignH >= 0) {
                switch (w.alignH) {
                    case 0: l.width = ViewGroup.LayoutParams.MATCH_PARENT; break;
                    case 1: g |= Gravity.START; break;
                    case 2: g |= Gravity.END; break;
                    default: g |= Gravity.CENTER_HORIZONTAL; break;
                }
            }
            if (w.alignV >= 0) {
                switch (w.alignV) {
                    case 0: l.height = ViewGroup.LayoutParams.MATCH_PARENT; break;
                    case 1: g |= Gravity.TOP; break;
                    case 2: g |= Gravity.BOTTOM; break;
                    default: g |= Gravity.CENTER_VERTICAL; break;
                }
            }
            if (g != 0) l.gravity = g;
            v.setLayoutParams(l);
            if (v instanceof TextView && w.alignV >= 0) {
                TextView tv = (TextView) v;
                int hg = tv.getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK;
                tv.setGravity(hg | (w.alignV == 1 ? Gravity.TOP : w.alignV == 2 ? Gravity.BOTTOM : Gravity.CENTER_VERTICAL));
            }
        } else if (lp instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams f = (FrameLayout.LayoutParams) lp;
            int g = f.gravity == -1 ? 0 : f.gravity;
            if (w.alignH >= 0) {
                g &= ~Gravity.HORIZONTAL_GRAVITY_MASK;
                switch (w.alignH) {
                    case 0: f.width = ViewGroup.LayoutParams.MATCH_PARENT; g |= Gravity.START; break;
                    case 1: g |= Gravity.START; break;
                    case 2: g |= Gravity.END; break;
                    default: g |= Gravity.CENTER_HORIZONTAL; break;
                }
            }
            if (w.alignV >= 0) {
                g &= ~Gravity.VERTICAL_GRAVITY_MASK;
                switch (w.alignV) {
                    case 0: f.height = ViewGroup.LayoutParams.MATCH_PARENT; g |= Gravity.TOP; break;
                    case 1: g |= Gravity.TOP; break;
                    case 2: g |= Gravity.BOTTOM; break;
                    default: g |= Gravity.CENTER_VERTICAL; break;
                }
            }
            f.gravity = g;
            v.setLayoutParams(f);
        }
    }

    /** GTK align 值：fill=0 start=1 end=2 center=3 baseline=4；也接受名字。 */
    private static int alignOf(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        String s = String.valueOf(v);
        switch (s) {
            case "fill": return 0;
            case "start": return 1;
            case "end": return 2;
            case "center": return 3;
            case "baseline": return 4;
            default:
                try {
                    return Integer.parseInt(s);
                } catch (Exception e) {
                    return 0;
                }
        }
    }

    private static int gravityOf(Object v, boolean horizontal) {
        int a = v == null ? 0 : alignOf(v);
        switch (a) {
            case 1: return horizontal ? Gravity.START : Gravity.TOP;
            case 2: return horizontal ? Gravity.END : Gravity.BOTTOM;
            case 3:
            case 4: return horizontal ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL;
            default: return horizontal ? Gravity.FILL_HORIZONTAL : Gravity.FILL_VERTICAL;
        }
    }

    /**
     * 极简 CSS：luakit 的 widget.css 多用于 GTK 主题细节；这里认
     * background-color / color / font-size / border / padding / min-height。
     */
    private static void applyCss(W w, String css) {
        if (css == null) return;
        for (String decl : css.split(";")) {
            int colon = decl.indexOf(':');
            if (colon < 0) continue;
            String prop = decl.substring(0, colon).trim().toLowerCase(Locale.US);
            String val = decl.substring(colon + 1).trim();
            try {
                switch (prop) {
                    case "background-color":
                    case "background":
                        set(w, "bg", val);
                        break;
                    case "color":
                        set(w, "fg", val);
                        break;
                    case "font-size":
                        if (w.view instanceof TextView) {
                            ((TextView) w.view).setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) parseLen(val));
                        }
                        break;
                    case "padding": {
                        int p = dp(parseLen(val));
                        w.view.setPadding(p, p, p, p);
                        break;
                    }
                    case "min-height":
                        w.view.setMinimumHeight(dp(parseLen(val)));
                        break;
                    case "min-width":
                        w.view.setMinimumWidth(dp(parseLen(val)));
                        break;
                    case "border":
                        if (val.startsWith("0")) {
                            if (w.view instanceof EditText) w.view.setBackground(null);
                        }
                        break;
                    case "opacity":
                        w.view.setAlpha((float) Double.parseDouble(val));
                        break;
                    default:
                        break;
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static double parseLen(String s) {
        String digits = s.replaceAll("[^0-9.]", "");
        return digits.isEmpty() ? 0 : Double.parseDouble(digits);
    }

    /** Pango 字体描述 "monospace bold 10" / "Sans 12" → Typeface + sp。 */
    private static void applyFont(TextView tv, String font) {
        if (TextUtils.isEmpty(font)) return;
        String[] parts = font.trim().split("\\s+");
        float size = -1;
        int style = Typeface.NORMAL;
        List<String> family = new ArrayList<>();
        for (String p : parts) {
            String lp = p.toLowerCase(Locale.US).replace(",", "");
            if (lp.matches("[0-9]+(\\.[0-9]+)?(px|pt)?")) {
                size = (float) parseLen(lp);
            } else if (lp.equals("bold") || lp.equals("semibold") || lp.equals("heavy")) {
                style |= Typeface.BOLD;
            } else if (lp.equals("italic") || lp.equals("oblique")) {
                style |= Typeface.ITALIC;
            } else if (lp.equals("normal") || lp.equals("regular") || lp.equals("light") || lp.equals("medium")) {
                // 忽略
            } else {
                family.add(lp);
            }
        }
        Typeface tf = Typeface.DEFAULT;
        for (String f : family) {
            if (f.contains("mono") || f.contains("courier") || f.contains("terminus") || f.contains("fixed")) {
                tf = Typeface.MONOSPACE;
                break;
            }
            if (f.contains("serif") && !f.contains("sans")) {
                tf = Typeface.SERIF;
                break;
            }
            if (f.contains("sans")) {
                tf = Typeface.SANS_SERIF;
                break;
            }
        }
        tv.setTypeface(Typeface.create(tf, style));
        if (size > 0) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        }
    }

    /**
     * Pango 标记 → Android HTML。支持 span 的 foreground/color background/bgcolor
     * weight style underline strikethrough font/font_family size；b i u s tt big small。
     */
    static CharSequence pangoToSpanned(String markup) {
        if (markup == null) return "";
        if (markup.indexOf('<') < 0 && markup.indexOf('&') < 0) {
            return markup;
        }
        StringBuilder html = new StringBuilder();
        List<String> closers = new ArrayList<>();
        int i = 0;
        int n = markup.length();
        while (i < n) {
            char c = markup.charAt(i);
            if (c == '<') {
                int end = markup.indexOf('>', i);
                if (end < 0) {
                    html.append("&lt;");
                    i++;
                    continue;
                }
                String tag = markup.substring(i + 1, end).trim();
                i = end + 1;
                if (tag.startsWith("/")) {
                    String name = tag.substring(1).trim();
                    if (name.equals("span")) {
                        if (!closers.isEmpty()) html.append(closers.remove(closers.size() - 1));
                    } else {
                        html.append("</").append(name).append(">");
                    }
                    continue;
                }
                boolean selfClose = tag.endsWith("/");
                if (selfClose) tag = tag.substring(0, tag.length() - 1).trim();
                String name = tag.split("\\s+")[0];
                if (name.equals("span")) {
                    StringBuilder open = new StringBuilder();
                    StringBuilder close = new StringBuilder();
                    Map<String, String> attrs = parseAttrs(tag.substring(name.length()));
                    String fg = attrs.containsKey("foreground") ? attrs.get("foreground")
                            : attrs.containsKey("color") ? attrs.get("color") : attrs.get("fgcolor");
                    String bg = attrs.containsKey("background") ? attrs.get("background") : attrs.get("bgcolor");
                    String face = attrs.containsKey("font_family") ? attrs.get("font_family")
                            : attrs.containsKey("face") ? attrs.get("face") : attrs.get("font");
                    List<String> styles = new ArrayList<>();
                    if (fg != null) styles.add("color:" + cssColor(fg));
                    if (bg != null) styles.add("background-color:" + cssColor(bg));
                    if (face != null) {
                        String lf = face.toLowerCase(Locale.US);
                        styles.add("font-family:" + (lf.contains("mono") ? "monospace" : lf.contains("serif") && !lf.contains("sans") ? "serif" : "sans-serif"));
                    }
                    if (!styles.isEmpty()) {
                        open.append("<span style=\"").append(TextUtils.join(";", styles)).append("\">");
                        close.insert(0, "</span>");
                    }
                    String weight = attrs.get("weight");
                    if (weight != null && (weight.equals("bold") || weight.equals("heavy") || weight.equals("ultrabold")
                            || (weight.matches("[0-9]+") && Integer.parseInt(weight) >= 600))) {
                        open.append("<b>");
                        close.insert(0, "</b>");
                    }
                    String style = attrs.get("style");
                    if ("italic".equals(style) || "oblique".equals(style)) {
                        open.append("<i>");
                        close.insert(0, "</i>");
                    }
                    String underline = attrs.get("underline");
                    if (underline != null && !underline.equals("none")) {
                        open.append("<u>");
                        close.insert(0, "</u>");
                    }
                    if ("true".equals(attrs.get("strikethrough"))) {
                        open.append("<s>");
                        close.insert(0, "</s>");
                    }
                    String size = attrs.get("size");
                    if (size != null) {
                        if (size.contains("large") || size.equals("larger")) {
                            open.append("<big>");
                            close.insert(0, "</big>");
                        } else if (size.contains("small") || size.equals("smaller")) {
                            open.append("<small>");
                            close.insert(0, "</small>");
                        }
                    }
                    html.append(open);
                    closers.add(close.toString());
                } else if (name.equals("tt")) {
                    html.append("<tt>");
                } else if (name.equals("b") || name.equals("i") || name.equals("u") || name.equals("s")
                        || name.equals("big") || name.equals("small") || name.equals("sub") || name.equals("sup")) {
                    html.append("<").append(name).append(">");
                } else if (name.equals("markup")) {
                    // 根标签忽略
                } else {
                    html.append("<").append(tag).append(">");
                }
                continue;
            }
            if (c == '\n') {
                html.append("<br>");
                i++;
                continue;
            }
            html.append(c);
            i++;
        }
        while (!closers.isEmpty()) html.append(closers.remove(closers.size() - 1));
        Spanned sp;
        if (Build.VERSION.SDK_INT >= 24) {
            sp = Html.fromHtml(html.toString(), Html.FROM_HTML_MODE_COMPACT);
        } else {
            sp = Html.fromHtml(html.toString());
        }
        // Html.fromHtml 会在末尾留换行
        int len = sp.length();
        while (len > 0 && sp.charAt(len - 1) == '\n') len--;
        return len == sp.length() ? sp : sp.subSequence(0, len);
    }

    private static Map<String, String> parseAttrs(String s) {
        Map<String, String> out = new HashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([a-zA-Z_]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s]+))").matcher(s);
        while (m.find()) {
            String v = m.group(3) != null ? m.group(3) : m.group(4) != null ? m.group(4) : m.group(5);
            out.put(m.group(1).toLowerCase(Locale.US), v);
        }
        return out;
    }

    private static String cssColor(String c) {
        Integer col = color(c);
        if (col == null) return c;
        return String.format(Locale.US, "#%06X", col & 0xFFFFFF);
    }

    /** GTK/CSS 颜色 → ARGB。支持 #rgb #rrggbb #rrggbbaa rgb() rgba() 和常见名字。 */
    static Integer color(String s) {
        if (s == null) return null;
        String c = s.trim().toLowerCase(Locale.US);
        if (c.isEmpty() || c.equals("none") || c.equals("transparent")) return Color.TRANSPARENT;
        try {
            if (c.startsWith("#")) {
                String hex = c.substring(1);
                if (hex.length() == 3) {
                    hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
                } else if (hex.length() == 4) {
                    hex = "" + hex.charAt(3) + hex.charAt(3) + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
                } else if (hex.length() == 8) {
                    hex = hex.substring(6) + hex.substring(0, 6);   // rrggbbaa → aarrggbb
                }
                return Color.parseColor("#" + hex);
            }
            if (c.startsWith("rgb")) {
                String[] p = c.substring(c.indexOf('(') + 1, c.indexOf(')')).split(",");
                int r = (int) Double.parseDouble(p[0].trim());
                int g = (int) Double.parseDouble(p[1].trim());
                int b = (int) Double.parseDouble(p[2].trim());
                int a = p.length > 3 ? (int) (Double.parseDouble(p[3].trim()) * 255) : 255;
                return Color.argb(a, r, g, b);
            }
            Integer named = NAMED_COLORS.get(c);
            if (named != null) return named;
            return Color.parseColor(c);
        } catch (Exception e) {
            return null;
        }
    }

    private static final Map<String, Integer> NAMED_COLORS = new HashMap<>();
    static {
        String[][] t = {
            {"white", "#ffffff"}, {"black", "#000000"}, {"red", "#ff0000"}, {"green", "#008000"},
            {"blue", "#0000ff"}, {"yellow", "#ffff00"}, {"orange", "#ffa500"}, {"gray", "#808080"},
            {"grey", "#808080"}, {"darkgray", "#a9a9a9"}, {"darkgrey", "#a9a9a9"}, {"lightgray", "#d3d3d3"},
            {"lightgrey", "#d3d3d3"}, {"darkred", "#8b0000"}, {"darkgreen", "#006400"}, {"darkblue", "#00008b"},
            {"lightblue", "#add8e6"}, {"lightgreen", "#90ee90"}, {"pink", "#ffc0cb"}, {"purple", "#800080"},
            {"cyan", "#00ffff"}, {"magenta", "#ff00ff"}, {"brown", "#a52a2a"}, {"gold", "#ffd700"},
            {"silver", "#c0c0c0"}, {"navy", "#000080"}, {"teal", "#008080"}, {"olive", "#808000"},
            {"maroon", "#800000"}, {"lime", "#00ff00"}, {"aqua", "#00ffff"}, {"fuchsia", "#ff00ff"},
            {"crimson", "#dc143c"}, {"tomato", "#ff6347"}, {"salmon", "#fa8072"}, {"khaki", "#f0e68c"},
            {"dimgray", "#696969"}, {"dimgrey", "#696969"}, {"slategray", "#708090"}, {"steelblue", "#4682b4"},
            {"royalblue", "#4169e1"}, {"dodgerblue", "#1e90ff"}, {"skyblue", "#87ceeb"}, {"seagreen", "#2e8b57"},
            {"forestgreen", "#228b22"}, {"limegreen", "#32cd32"}, {"chartreuse", "#7fff00"}, {"indigo", "#4b0082"},
            {"violet", "#ee82ee"}, {"orchid", "#da70d6"}, {"plum", "#dda0dd"}, {"tan", "#d2b48c"},
            {"beige", "#f5f5dc"}, {"ivory", "#fffff0"}, {"snow", "#fffafa"}, {"wheat", "#f5deb3"},
        };
        for (String[] row : t) {
            NAMED_COLORS.put(row[0], Color.parseColor(row[1]));
        }
    }

    private static void setIcon(ImageView iv, String name, int size) {
        Activity a = activity();
        Bitmap bmp = null;
        // 1. luakit resources/icons/<name>.png（随 apk 解包到 filesDir/luakit/resources）
        File root = new File(ContextUtils.getApplicationContext().getFilesDir(), "luakit/resources/icons");
        String[] candidates = {name + ".png", name + "-symbolic.png", name.replace("-symbolic", "") + ".png"};
        for (String c : candidates) {
            File f = new File(root, c);
            if (f.exists()) {
                bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (bmp != null) break;
            }
        }
        if (bmp != null) {
            iv.setImageBitmap(bmp);
        } else if (a != null) {
            int res = a.getResources().getIdentifier(name.replace('-', '_'), "drawable", a.getPackageName());
            if (res == 0) {
                res = a.getResources().getIdentifier("ic_" + name.replace('-', '_'), "drawable", a.getPackageName());
            }
            if (res != 0) {
                iv.setImageResource(res);
            } else {
                iv.setImageDrawable(null);
            }
        }
        setSize(iv, size, size);
    }

    private static void setSize(View v, int w, int h) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) lp = new ViewGroup.LayoutParams(w, h);
        lp.width = w;
        lp.height = h;
        v.setLayoutParams(lp);
    }

    private static void setMargins(View v, int l, int t, int r, int b) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) {
            lp = new ViewGroup.MarginLayoutParams(lp == null ? new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) : lp);
        }
        ((ViewGroup.MarginLayoutParams) lp).setMargins(l, t, r, b);
        v.setLayoutParams(lp);
    }

    private static void setMargin(View v, int side, int px) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) {
            lp = new ViewGroup.MarginLayoutParams(lp == null ? new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) : lp);
        }
        ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
        switch (side) {
            case 0: m.leftMargin = px; break;
            case 1: m.topMargin = px; break;
            case 2: m.rightMargin = px; break;
            default: m.bottomMargin = px; break;
        }
        v.setLayoutParams(m);
    }

    /** LinearLayout 间距用的透明分隔 drawable。 */
    private static class SpacerDrawable extends ColorDrawable {
        private final int mSize;

        SpacerDrawable(int size) {
            super(Color.TRANSPARENT);
            mSize = size;
        }

        @Override
        public int getIntrinsicWidth() {
            return mSize;
        }

        @Override
        public int getIntrinsicHeight() {
            return mSize;
        }
    }

    private static boolean truthy(Object v) {
        if (v == null || v == JSONObject.NULL) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        String s = String.valueOf(v);
        return !(s.isEmpty() || s.equals("false") || s.equals("0"));
    }

    private static double num(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v == null || v == JSONObject.NULL) return 0;
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }

    private static int dp(double v) {
        float density = ctx().getResources().getDisplayMetrics().density;
        return (int) Math.round(v * density);
    }

    private static int px2dp(int px) {
        float density = ctx().getResources().getDisplayMetrics().density;
        return Math.round(px / density);
    }

    /** 暴露给 UiHost.reattach 之类的调用方：当前是否有 luakit window 在显示。 */
    static boolean anyWindowShown() {
        for (W w : sWidgets.values()) {
            if ("window".equals(w.type) && w.windowShown) return true;
        }
        return false;
    }

    /** 调试：所有控件概览。 */
    static String dump() {
        JSONArray arr = new JSONArray();
        for (W w : sWidgets.values()) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", w.id);
                o.put("type", w.type);
                o.put("visible", w.view.getVisibility() == View.VISIBLE);
                Rect r = new Rect();
                w.view.getGlobalVisibleRect(r);
                o.put("rect", r.flattenToString());
            } catch (Exception ignored) {
            }
            arr.put(o);
        }
        return arr.toString();
    }
}
