// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户对 Lua 的最终裁决权：总开关 + 逐脚本开关 + 启动崩溃保护 + 原生管理界面。
 *
 * <p>设计原则（这决定了 LemurX 能不能既是"最可定制"又"和官方 Chromium 一样稳"）：
 *
 * <ul>
 *   <li>状态存在独立的 SharedPreferences 文件 {@value #PREF_FILE} 里。Lua 能碰到的只有
 *       {@code lemurx.storage.*}（写 lemurx_storage）和 {@code lemurx.prefs.*}（写 Chrome
 *       PrefService），都到不了这里——脚本永远不能把自己重新打开或者把别的脚本关掉。
 *   <li>总开关关闭时：引擎不启动、任何宿主不挂接、返回键/菜单/页面观察者一个都不装，
 *       ContentBrowserClient 上的钩子全部走"无规则"分支——就是官方 Chromium。唯一残留是
 *       三点菜单里一条「Lua 脚本」入口，用来再打开。
 *   <li>开关变化立刻生效：关 → 清掉所有脚本写进原生层的状态 + 停引擎 + 重建 Activity
 *       （官方外壳从零 inflate，比逐项撤销每一刀手术可靠）；开 / 改脚本清单 → 同样先清
 *       后重启，脚本从头跑一遍。
 *   <li>启动保护：脚本刚跑起来 {@value #BOOT_GRACE_MS} ms 内进程若死掉，计一次；连续
 *       {@value #BOOT_CRASH_LIMIT} 次就自动关掉总开关并提示，保证用户永远能进到浏览器里
 *       把肇事脚本关掉，而不是被自己写的脚本锁在门外。
 *   <li>管理界面是纯原生 Android 控件，不依赖 Lua，Lua 隐藏不了它的菜单项、拦不住它的点击。
 * </ul>
 */
public final class LemurXScripts {
    private static final String TAG = "LemurX";

    /** 独立偏好文件；见类注释——Lua 没有任何 API 能写它。 */
    static final String PREF_FILE = "lemurx_settings";
    private static final String KEY_ENABLED = "lua_enabled";
    private static final String KEY_DISABLED = "disabled_scripts";
    private static final String KEY_BOOT_PENDING = "boot_pending";
    private static final String KEY_AUTO_DISABLED = "auto_disabled_reason";

    /** 脚本跑起来后活过这么久才算"启动成功"。 */
    static final long BOOT_GRACE_MS = 20_000;
    /** 连续几次没活过 BOOT_GRACE_MS 就自动停用。 */
    static final int BOOT_CRASH_LIMIT = 2;

    /** 内置教程脚本在 files/lua 里的文件名（LemurXBridge 种子化）。 */
    static final String TUTORIAL_FILE = "00_tutorial.lua";

    /** 三点菜单里「Lua 脚本」管理入口的动作名；LemurXChromeHost 保证 Lua 无法隐藏/拦截它。 */
    static final String MANAGER_ACTION = "lua_scripts";

    private LemurXScripts() {}

    // ---------------------------------------------------------------- 状态

    private static SharedPreferences prefs() {
        return ContextUtils.getApplicationContext()
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    /** 总开关。默认开：Lua 是这个浏览器存在的理由；但用户说关就是关。 */
    public static boolean isEnabled() {
        try {
            return prefs().getBoolean(KEY_ENABLED, true);
        } catch (Exception e) {
            return true;
        }
    }

    /** 只写状态，不做运行时切换；切换用 {@link #apply}。 */
    static void setEnabledPref(boolean enabled) {
        prefs().edit().putBoolean(KEY_ENABLED, enabled).remove(KEY_AUTO_DISABLED).apply();
    }

    /**
     * 单个脚本是否启用。name 是相对 files/lua 的路径：{@code "00_tutorial.lua"}、
     * {@code "ugc/foo.lua"}。默认启用。
     */
    static boolean isScriptEnabled(String name) {
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        return !disabledSet().contains(name);
    }

    static void setScriptEnabledPref(String name, boolean enabled) {
        Set<String> disabled = new HashSet<>(disabledSet());
        if (enabled) {
            disabled.remove(name);
        } else {
            disabled.add(name);
        }
        prefs().edit().putStringSet(KEY_DISABLED, disabled).apply();
    }

    private static Set<String> disabledSet() {
        try {
            Set<String> set = prefs().getStringSet(KEY_DISABLED, null);
            return set == null ? new HashSet<>() : set;
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    /** 上次因启动崩溃被自动停用的说明；null 表示没有。 */
    static @Nullable String autoDisabledReason() {
        return prefs().getString(KEY_AUTO_DISABLED, null);
    }

    // ---------------------------------------------------------------- 启动保护

    /**
     * LemurXBridge.start() 在跑任何脚本之前调用。返回 false 表示上两次启动都没活过
     * 宽限期，本次已自动关掉总开关，调用方不要再跑脚本。
     */
    static boolean beginBoot() {
        SharedPreferences p = prefs();
        int pending = p.getInt(KEY_BOOT_PENDING, 0);
        if (pending >= BOOT_CRASH_LIMIT) {
            String reason =
                    "Lua 脚本连续 " + pending + " 次在启动后 "
                            + (BOOT_GRACE_MS / 1000) + " 秒内导致浏览器退出，已自动停用。"
                            + "在三点菜单「Lua 脚本」里排查后可重新开启。";
            p.edit()
                    .putBoolean(KEY_ENABLED, false)
                    .putInt(KEY_BOOT_PENDING, 0)
                    .putString(KEY_AUTO_DISABLED, reason)
                    .apply();
            Log.w(TAG, "scripts auto-disabled: %s", reason);
            return false;
        }
        // 先记账再跑脚本；活过宽限期后 markBootHealthy 清零
        p.edit().putInt(KEY_BOOT_PENDING, pending + 1).apply();
        return true;
    }

    static void markBootHealthy() {
        prefs().edit().putInt(KEY_BOOT_PENDING, 0).apply();
    }

    // ---------------------------------------------------------------- 脚本清单

    /** 一条可开关的脚本。 */
    static final class ScriptInfo {
        /** 相对 files/lua 的路径，也是开关的 key。 */
        final String name;
        final File file;
        final boolean builtin;
        final boolean ugc;
        boolean enabled;

        ScriptInfo(String name, File file, boolean builtin, boolean ugc) {
            this.name = name;
            this.file = file;
            this.builtin = builtin;
            this.ugc = ugc;
            this.enabled = isScriptEnabled(name);
        }
    }

    static File scriptsDir() {
        return new File(ContextUtils.getApplicationContext().getFilesDir(), "lua");
    }

    /** 按加载顺序列出 files/lua/*.lua 与 files/lua/ugc/*.lua。 */
    static List<ScriptInfo> listScripts() {
        List<ScriptInfo> out = new ArrayList<>();
        File dir = scriptsDir();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".lua"));
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                out.add(new ScriptInfo(f.getName(), f, TUTORIAL_FILE.equals(f.getName()), false));
            }
        }
        File[] ugc = new File(dir, "ugc").listFiles((d, n) -> n.endsWith(".lua"));
        if (ugc != null) {
            Arrays.sort(ugc);
            for (File f : ugc) {
                out.add(new ScriptInfo("ugc/" + f.getName(), f, false, true));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 运行时切换

    /**
     * 把用户在界面上的选择落地（UI 线程）。任何一项变化都走同一条路：
     * 清原生状态 → 停引擎 → （开着的话）重新 start → 重建 Activity 让外壳回到官方样子。
     */
    static void apply(
            @Nullable Activity activity,
            boolean enabled,
            List<ScriptInfo> scripts) {
        ThreadUtils.assertOnUiThread();
        boolean wasEnabled = isEnabled();
        boolean scriptsChanged = false;
        for (ScriptInfo s : scripts) {
            if (isScriptEnabled(s.name) != s.enabled) {
                setScriptEnabledPref(s.name, s.enabled);
                scriptsChanged = true;
            }
        }
        setEnabledPref(enabled);
        if (!enabled) {
            if (wasEnabled || LemurXBridge.isStarted()) {
                LemurXBridge.shutdown(activity, /* restart= */ false);
                LemurXBridge.showToast("Lua 已停用，浏览器回到官方 Chromium 行为");
            }
            return;
        }
        if (!wasEnabled || scriptsChanged) {
            LemurXBridge.shutdown(activity, /* restart= */ true);
            LemurXBridge.showToast(wasEnabled ? "脚本已重载" : "Lua 已启用");
        }
    }

    // ---------------------------------------------------------------- 管理界面

    /** 三点菜单「Lua 脚本」点进来的原生对话框。纯 Android 控件，不经过 Lua。 */
    static void showManager(@Nullable Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        ThreadUtils.assertOnUiThread();
        try {
            final boolean enabledNow = isEnabled();
            final List<ScriptInfo> scripts = listScripts();

            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(activity, 20);
            root.setPadding(pad, dp(activity, 8), pad, 0);

            Switch master = new Switch(activity);
            master.setText("启用 Lua 脚本");
            master.setTextSize(16);
            master.setChecked(enabledNow);
            master.setPadding(0, dp(activity, 8), 0, dp(activity, 8));
            root.addView(master);

            TextView hint = new TextView(activity);
            hint.setTextSize(12);
            hint.setText(
                    "关闭后引擎不启动、任何脚本不加载，浏览器就是官方 Chromium；"
                            + "这里的设置脚本自己改不了。改动会立即生效并重建界面。");
            hint.setPadding(0, 0, 0, dp(activity, 12));
            root.addView(hint);

            String auto = autoDisabledReason();
            if (auto != null) {
                TextView warn = new TextView(activity);
                warn.setTextSize(12);
                warn.setTypeface(null, Typeface.BOLD);
                warn.setText(auto);
                warn.setPadding(0, 0, 0, dp(activity, 12));
                root.addView(warn);
            }

            TextView listTitle = new TextView(activity);
            listTitle.setText("脚本（按加载顺序）");
            listTitle.setTypeface(null, Typeface.BOLD);
            listTitle.setPadding(0, 0, 0, dp(activity, 4));
            root.addView(listTitle);

            final List<CheckBox> boxes = new ArrayList<>();
            if (scripts.isEmpty()) {
                TextView empty = new TextView(activity);
                empty.setTextSize(13);
                empty.setText("（没有脚本）");
                root.addView(empty);
            }
            for (ScriptInfo s : scripts) {
                CheckBox box = new CheckBox(activity);
                String label = s.name;
                if (s.builtin) {
                    label += "  · 内置教程";
                } else if (s.ugc) {
                    label += "  · 受限沙箱";
                } else {
                    label += "  · 本地，全权限";
                }
                box.setText(label);
                box.setTextSize(14);
                box.setChecked(s.enabled);
                box.setEnabled(enabledNow);
                boxes.add(box);
                root.addView(box);
            }
            master.setOnCheckedChangeListener(
                    (v, checked) -> {
                        for (CheckBox b : boxes) {
                            b.setEnabled(checked);
                        }
                    });

            TextView path = new TextView(activity);
            path.setTextSize(11);
            path.setPadding(0, dp(activity, 12), 0, dp(activity, 8));
            path.setText(
                    "本地脚本目录：" + scriptsDir().getAbsolutePath()
                            + "\n受限脚本目录：" + new File(scriptsDir(), "ugc").getAbsolutePath()
                            + "\n本地脚本以浏览器进程的全部权限运行；受限脚本无 io/os、无特权 API。");
            root.addView(path);

            ScrollView scroll = new ScrollView(activity);
            scroll.addView(
                    root,
                    new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));

            new AlertDialog.Builder(activity)
                    .setTitle("Lua 脚本")
                    .setView(scroll)
                    .setNegativeButton("取消", null)
                    .setPositiveButton(
                            "应用",
                            (d, w) -> {
                                for (int i = 0; i < scripts.size() && i < boxes.size(); i++) {
                                    scripts.get(i).enabled = boxes.get(i).isChecked();
                                }
                                apply(activity, master.isChecked(), scripts);
                            })
                    .show();
        } catch (Exception e) {
            Log.i(TAG, "scripts manager: %s", e.getMessage());
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** 给 ui.dump 等调试用：当前策略的一行摘要。 */
    static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("enabled=").append(isEnabled());
        Set<String> disabled = disabledSet();
        if (!disabled.isEmpty()) {
            sb.append(" disabled=").append(disabled);
        }
        return sb.toString();
    }
}
