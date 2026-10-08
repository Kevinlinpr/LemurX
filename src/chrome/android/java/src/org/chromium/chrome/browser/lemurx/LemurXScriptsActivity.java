// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.chromium.base.ApplicationStatus;
import org.chromium.base.Log;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.browser.lemurx.LemurXScripts.ScriptInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 「Lua 脚本」管理界面：列表 → 详情 → 源码。纯原生控件、代码建 View、不经过 Lua，
 * 脚本藏不掉也拦不住它。
 *
 * <ul>
 *   <li>列表：总开关；官方 / 本地 / 受限三组，每行 图标·名称·说明·开关。
 *   <li>详情：元数据（版本、替代的 Chrome 扩展、文件路径）、查看源码（入口文件和同名子目录里的全部
 *       Lua）、复制为本地脚本（官方）、删除（本地/受限）。脚本在文件头写了 {@code @page} 才出现设置页入口。
 *   <li>源码：官方只读；本地/受限的入口文件可编辑保存。
 *   <li>开关/编辑/删除的改动积攒到「应用并重载」一次生效：停引擎 → 重跑脚本 → 重建浏览器界面。
 * </ul>
 */
public class LemurXScriptsActivity extends Activity {
    private static final String TAG = "LemurX";

    private FrameLayout mRoot;
    private final List<Runnable> mBackStack = new ArrayList<>();
    private List<ScriptInfo> mScripts = new ArrayList<>();
    private boolean mMaster;
    private boolean mDirty;
    private int mColorFg;
    private int mColorMuted;
    private int mColorCard;
    private int mColorBg;
    private int mColorAccent = 0xFF0A84FF;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Lua 脚本");
        resolveColors();
        mRoot = new FrameLayout(this);
        mRoot.setBackgroundColor(mColorBg);
        setContentView(mRoot);
        reload();
        if (!openFromIntent(getIntent())) {
            showList();
        }
    }

    /** {@code lemurx.intent.startActivity{action="lemurx.scripts", extras={lemurx_script, lemurx_view}}} */
    private boolean openFromIntent(@Nullable Intent intent) {
        if (intent == null) {
            return false;
        }
        String key = intent.getStringExtra("lemurx_script");
        if (TextUtils.isEmpty(key)) {
            return false;
        }
        for (ScriptInfo s : mScripts) {
            if (key.equals(s.name) || key.equals(s.file.getName())) {
                if ("source".equals(intent.getStringExtra("lemurx_view"))) {
                    showSource(s);
                } else {
                    showDetail(s);
                }
                return true;
            }
        }
        return false;
    }

    private void resolveColors() {
        mColorFg = themeColor(android.R.attr.textColorPrimary, 0xFF1D1D1F);
        mColorMuted = themeColor(android.R.attr.textColorSecondary, 0xFF6E6E73);
        mColorBg = themeColor(android.R.attr.colorBackground, 0xFFFAFAFA);
        boolean dark = luminance(mColorBg) < 0.5;
        mColorCard = dark ? 0xFF1C1C1E : Color.WHITE;
        if (dark && mColorBg == 0xFFFAFAFA) mColorBg = Color.BLACK;
    }

    private static double luminance(int c) {
        return (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0;
    }

    private int themeColor(int attr, int fallback) {
        try {
            TypedValue tv = new TypedValue();
            if (getTheme().resolveAttribute(attr, tv, true)) {
                if (tv.resourceId != 0) {
                    return getResources().getColor(tv.resourceId, getTheme());
                }
                if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                        && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return tv.data;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private void reload() {
        mScripts = LemurXScripts.listScripts();
        mMaster = LemurXScripts.isEnabled();
    }

    // ---------------------------------------------------------------- 导航

    private void show(View v, @Nullable Runnable back) {
        mRoot.removeAllViews();
        mRoot.addView(
                v,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (back != null) {
            mBackStack.add(back);
        }
    }

    @Override
    public void onBackPressed() {
        if (!mBackStack.isEmpty()) {
            Runnable r = mBackStack.remove(mBackStack.size() - 1);
            r.run();
            return;
        }
        if (mDirty) {
            new AlertDialog.Builder(this)
                    .setTitle("有未应用的改动")
                    .setMessage("开关或源码改了但还没重载。现在应用吗？")
                    .setPositiveButton("应用并重载", (d, w) -> applyAndFinish())
                    .setNegativeButton("放弃", (d, w) -> finish())
                    .setNeutralButton("继续编辑", null)
                    .show();
            return;
        }
        super.onBackPressed();
    }

    private void applyAndFinish() {
        try {
            Activity chrome = chromeActivity();
            LemurXScripts.apply(chrome, mMaster, mScripts);
        } catch (Exception e) {
            Log.i(TAG, "apply scripts: %s", e.getMessage());
        }
        mDirty = false;
        finish();
    }

    private static @Nullable Activity chromeActivity() {
        try {
            for (Activity a : ApplicationStatus.getRunningActivities()) {
                if (a instanceof ChromeTabbedActivity && !a.isFinishing() && !a.isDestroyed()) {
                    return a;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void openInBrowser(String url) {
        finish();
        try {
            LemurXBridge.openTab(url, null);
        } catch (Exception e) {
            Log.i(TAG, "open %s: %s", url, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 列表

    private void showList() {
        mBackStack.clear();
        LinearLayout col = column();
        col.setPadding(dp(12), dp(8), dp(12), dp(24));

        // 头
        LinearLayout head = row();
        TextView title = text("Lua 脚本", 22, mColorFg, true);
        title.setLayoutParams(weight1());
        head.addView(title);
        Switch master = new Switch(this);
        master.setChecked(mMaster);
        master.setOnCheckedChangeListener(
                (v, checked) -> {
                    mMaster = checked;
                    mDirty = true;
                    updateApplyBar();
                });
        head.addView(master);
        col.addView(head);
        col.addView(
                text(
                        "关掉总开关：引擎不启动、任何脚本不加载，浏览器就是官方 Chromium；这里的设置脚本自己改不了。",
                        12,
                        mColorMuted,
                        false));

        String auto = LemurXScripts.autoDisabledReason();
        if (auto != null) {
            TextView warn = text(auto, 13, 0xFFFF3B30, true);
            warn.setPadding(0, dp(8), 0, dp(4));
            col.addView(warn);
        }

        addGroup(col, "官方脚本 · Chrome 热门扩展的 Lua 实现", s -> s.official);
        addGroup(col, "本地脚本 · 全权限", s -> !s.official && !s.ugc);
        addGroup(col, "受限脚本 · 沙箱", s -> s.ugc);

        // 动作
        LinearLayout actions = row();
        actions.setPadding(0, dp(12), 0, 0);
        Button create = button("新建本地脚本", false);
        create.setOnClickListener(v -> promptNewScript());
        actions.addView(create);
        Button all = button("在浏览器里看官方脚本目录", false);
        all.setOnClickListener(v -> openInBrowser("lemurx://scripts/"));
        actions.addView(all);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(actions);
        col.addView(hs);

        TextView path = text(
                "官方：" + LemurXScripts.officialDir().getAbsolutePath()
                        + "\n本地：" + LemurXScripts.scriptsDir().getAbsolutePath()
                        + "\n受限：" + new File(LemurXScripts.scriptsDir(), "ugc").getAbsolutePath()
                        + "\n官方脚本随版本覆盖，改它请先「复制为本地脚本」。",
                11, mColorMuted, false);
        path.setPadding(0, dp(16), 0, 0);
        col.addView(path);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);

        LinearLayout page = column();
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        page.addView(applyBar());
        show(page, null);
        updateApplyBar();
    }

    private interface Pred {
        boolean test(ScriptInfo s);
    }

    private void addGroup(LinearLayout col, String label, Pred pred) {
        List<ScriptInfo> items = new ArrayList<>();
        for (ScriptInfo s : mScripts) {
            if (pred.test(s)) items.add(s);
        }
        TextView sec = text(label, 12, mColorMuted, false);
        sec.setPadding(dp(4), dp(18), dp(4), dp(4));
        col.addView(sec);
        LinearLayout card = card();
        if (items.isEmpty()) {
            TextView empty = text("（没有）", 13, mColorMuted, false);
            empty.setPadding(dp(16), dp(14), dp(16), dp(14));
            card.addView(empty);
        }
        for (int i = 0; i < items.size(); i++) {
            ScriptInfo s = items.get(i);
            card.addView(scriptRow(s));
            if (i < items.size() - 1) card.addView(divider());
        }
        col.addView(card);
    }

    private View scriptRow(ScriptInfo s) {
        LinearLayout r = row();
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.setMinimumHeight(dp(56));
        r.setClickable(true);
        r.setBackground(ripple());
        TextView icon = text(TextUtils.isEmpty(s.icon) ? (s.official ? "📜" : s.ugc ? "🔒" : "📄") : s.icon, 24, mColorFg, false);
        icon.setWidth(dp(40));
        icon.setGravity(Gravity.CENTER);
        r.addView(icon);
        LinearLayout mid = column();
        mid.setLayoutParams(weight1());
        mid.addView(text(s.displayTitle(), 16, mColorFg, false));
        String sub = !TextUtils.isEmpty(s.description) ? s.description : (s.kindLabel() + " · " + s.file.getName());
        if (s.official && !TextUtils.isEmpty(s.replaces)) sub = "替代 " + s.replaces + " · " + sub;
        TextView d = text(sub, 12, mColorMuted, false);
        d.setMaxLines(2);
        d.setEllipsize(TextUtils.TruncateAt.END);
        mid.addView(d);
        r.addView(mid);
        Switch sw = new Switch(this);
        sw.setChecked(s.enabled);
        sw.setEnabled(mMaster);
        sw.setOnCheckedChangeListener(
                (v, checked) -> {
                    s.enabled = checked;
                    mDirty = true;
                    updateApplyBar();
                });
        r.addView(sw);
        r.setOnClickListener(v -> showDetail(s));
        return r;
    }

    private LinearLayout mApplyBar;
    private Button mApplyButton;

    private View applyBar() {
        mApplyBar = row();
        mApplyBar.setPadding(dp(12), dp(8), dp(12), dp(12));
        mApplyBar.setBackgroundColor(mColorCard);
        mApplyButton = button("应用并重载", true);
        mApplyButton.setLayoutParams(weight1());
        mApplyButton.setOnClickListener(v -> applyAndFinish());
        mApplyBar.addView(mApplyButton);
        return mApplyBar;
    }

    private void updateApplyBar() {
        if (mApplyBar == null) return;
        mApplyBar.setVisibility(mDirty ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- 详情

    private void showDetail(ScriptInfo s) {
        LinearLayout col = column();
        col.setPadding(dp(12), dp(8), dp(12), dp(24));

        LinearLayout head = row();
        Button back = button("‹ 脚本", false);
        back.setOnClickListener(v -> onBackPressed());
        head.addView(back);
        col.addView(head);

        LinearLayout card = card();
        LinearLayout top = row();
        top.setPadding(dp(16), dp(16), dp(16), dp(8));
        TextView icon = text(TextUtils.isEmpty(s.icon) ? "📜" : s.icon, 34, mColorFg, false);
        icon.setPadding(0, 0, dp(12), 0);
        top.addView(icon);
        LinearLayout mid = column();
        mid.setLayoutParams(weight1());
        mid.addView(text(s.displayTitle(), 20, mColorFg, true));
        StringBuilder meta = new StringBuilder(s.kindLabel());
        if (!TextUtils.isEmpty(s.version)) meta.append(" · v").append(s.version);
        if (!TextUtils.isEmpty(s.category)) meta.append(" · ").append(s.category);
        mid.addView(text(meta.toString(), 12, mColorMuted, false));
        top.addView(mid);
        Switch sw = new Switch(this);
        sw.setChecked(s.enabled);
        sw.setEnabled(mMaster);
        sw.setOnCheckedChangeListener(
                (v, checked) -> {
                    s.enabled = checked;
                    mDirty = true;
                });
        top.addView(sw);
        card.addView(top);
        if (!TextUtils.isEmpty(s.description)) {
            TextView d = text(s.description, 14, mColorFg, false);
            d.setPadding(dp(16), 0, dp(16), dp(12));
            card.addView(d);
        }
        if (!TextUtils.isEmpty(s.replaces)) {
            TextView rp = text("对应 Chrome 扩展：" + s.replaces, 13, mColorMuted, false);
            rp.setPadding(dp(16), 0, dp(16), dp(12));
            card.addView(rp);
        }
        TextView file = text("文件：" + s.file.getAbsolutePath() + "\n开关键：" + s.name, 11, mColorMuted, false);
        file.setPadding(dp(16), 0, dp(16), dp(14));
        card.addView(file);
        col.addView(card);

        // 动作
        LinearLayout acts = card();
        String page = s.page;
        if (!TextUtils.isEmpty(page)) {
            final String url = page;
            acts.addView(actionRow("⚙️", "打开设置页", url, v -> openInBrowser(url)));
            acts.addView(divider());
        }
        int nFiles = LemurXScripts.sourceFiles(s).size();
        acts.addView(actionRow("📖", s.official ? "查看源码" : "查看 / 编辑源码",
                (nFiles > 1 ? nFiles + " 个 Lua 文件 · " : "")
                        + (s.official ? "官方脚本只读，可复制为本地脚本后修改" : "保存后需要「应用并重载」"),
                v -> showSource(s)));
        if (s.official) {
            acts.addView(divider());
            acts.addView(actionRow("📋", "复制为本地脚本", "复制到 files/lua 并停用官方那份，改坏了删掉即可恢复", v -> {
                String name = LemurXScripts.forkOfficial(s);
                if (name == null) {
                    toast("复制失败");
                    return;
                }
                mDirty = true;
                reload();
                toast("已复制为 " + name + "，官方脚本已停用");
                showList();
            }));
        }
        if (!s.official && !s.builtin) {
            acts.addView(divider());
            acts.addView(actionRow("🗑", "删除脚本", s.file.getName(), v -> confirmDelete(s)));
        }
        col.addView(acts);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        show(scroll, this::showList);
    }

    private View actionRow(String icon, String title, String sub, View.OnClickListener onClick) {
        LinearLayout r = row();
        r.setPadding(dp(12), dp(12), dp(12), dp(12));
        r.setClickable(true);
        r.setBackground(ripple());
        TextView ic = text(icon, 20, mColorFg, false);
        ic.setWidth(dp(40));
        ic.setGravity(Gravity.CENTER);
        r.addView(ic);
        LinearLayout mid = column();
        mid.setLayoutParams(weight1());
        mid.addView(text(title, 16, mColorAccent, false));
        if (!TextUtils.isEmpty(sub)) {
            TextView d = text(sub, 12, mColorMuted, false);
            d.setMaxLines(2);
            d.setEllipsize(TextUtils.TruncateAt.END);
            mid.addView(d);
        }
        r.addView(mid);
        r.setOnClickListener(onClick);
        return r;
    }

    private void confirmDelete(ScriptInfo s) {
        new AlertDialog.Builder(this)
                .setTitle("删除 " + s.file.getName() + "？")
                .setMessage("文件会从设备上删除，不可恢复。")
                .setPositiveButton(
                        "删除",
                        (d, w) -> {
                            if (s.file.delete()) {
                                LemurXScripts.setScriptEnabledPref(s.name, true);
                                mDirty = true;
                                reload();
                                toast("已删除");
                                showList();
                            } else {
                                toast("删除失败");
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- 源码

    private void showSource(ScriptInfo s) {
        List<File> files = LemurXScripts.sourceFiles(s);
        final File[] current = new File[] {s.file};

        LinearLayout page = column();
        LinearLayout head = row();
        head.setPadding(dp(8), dp(4), dp(8), dp(4));
        head.setBackgroundColor(mColorCard);
        Button back = button("‹ 返回", false);
        back.setOnClickListener(v -> onBackPressed());
        head.addView(back);
        TextView name = text("", 14, mColorFg, true);
        name.setLayoutParams(weight1());
        name.setPadding(dp(8), 0, dp(8), 0);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        head.addView(name);
        page.addView(head);

        EditText editor = new EditText(this);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(12);
        editor.setTextColor(mColorFg);
        editor.setBackgroundColor(mColorBg);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setHorizontallyScrolling(true);
        editor.setPadding(dp(12), dp(8), dp(12), dp(8));
        editor.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setFillViewport(true);
        hsv.addView(
                editor,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.addView(hsv);

        Button save = button("保存", true);
        Runnable load =
                () -> {
                    File file = current[0];
                    boolean editable = !s.official && file.equals(s.file);
                    String src = LemurXScripts.readSource(file);
                    if (src == null) src = "-- 读不到 " + file.getAbsolutePath();
                    editor.setText(src);
                    editor.setFocusable(editable);
                    editor.setFocusableInTouchMode(editable);
                    editor.setCursorVisible(editable);
                    name.setText(
                            LemurXScripts.sourceLabel(s, file) + (editable ? "" : "  · 只读"));
                    save.setVisibility(editable ? View.VISIBLE : View.GONE);
                    sv.scrollTo(0, 0);
                };

        if (files.size() > 1) {
            HorizontalScrollView chipsScroll = new HorizontalScrollView(this);
            chipsScroll.setBackgroundColor(mColorCard);
            LinearLayout chips = row();
            chips.setPadding(dp(8), dp(4), dp(8), dp(8));
            for (File f : files) {
                Button chip = button(LemurXScripts.sourceLabel(s, f), f.equals(s.file));
                chip.setTextSize(12);
                chip.setOnClickListener(
                        v -> {
                            current[0] = f;
                            load.run();
                        });
                chips.addView(chip);
            }
            chipsScroll.addView(chips);
            page.addView(chipsScroll);
        }
        page.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bar = row();
        bar.setPadding(dp(12), dp(8), dp(12), dp(12));
        bar.setBackgroundColor(mColorCard);
        Button copy = button("复制全文", false);
        copy.setOnClickListener(
                v -> {
                    try {
                        ClipboardManager cm =
                                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(
                                ClipData.newPlainText(
                                        current[0].getName(), editor.getText().toString()));
                        toast("已复制");
                    } catch (Exception e) {
                        toast("复制失败");
                    }
                });
        bar.addView(copy);
        View spacer = new View(this);
        spacer.setLayoutParams(weight1());
        bar.addView(spacer);
        save.setOnClickListener(
                v -> {
                    if (LemurXScripts.writeSource(current[0], editor.getText().toString())) {
                        mDirty = true;
                        if (current[0].equals(s.file)) LemurXScripts.readHeader(s);
                        toast("已保存，回到列表「应用并重载」生效");
                    } else {
                        toast("保存失败");
                    }
                });
        bar.addView(save);
        page.addView(bar);
        load.run();
        show(page, () -> showDetail(s));
    }

    private void promptNewScript() {
        EditText input = new EditText(this);
        input.setHint("文件名，如 my_tweak");
        input.setSingleLine();
        input.setPadding(dp(20), dp(12), dp(20), dp(12));
        new AlertDialog.Builder(this)
                .setTitle("新建本地脚本")
                .setMessage("创建到 files/lua/，以浏览器进程全部权限运行。")
                .setView(input)
                .setPositiveButton(
                        "创建",
                        (d, w) -> {
                            String n = input.getText().toString().trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
                            if (n.isEmpty()) n = "new_script";
                            if (!n.endsWith(".lua")) n += ".lua";
                            File f = new File(LemurXScripts.scriptsDir(), n);
                            if (f.exists()) {
                                toast("已存在同名脚本");
                                return;
                            }
                            String tpl =
                                    "-- @name " + n.replace(".lua", "") + "\n"
                                            + "-- @description 我的脚本\n"
                                            + "-- @version 0.1\n"
                                            + "-- @icon ✨\n"
                                            + "--\n"
                                            + "-- 本地脚本，浏览器进程全部权限。可以 require(\"lx\") 复用官方脚本框架：\n"
                                            + "--   local lx = require(\"lx\")\n"
                                            + "--   lx.on_navigation(function(view, uri, ev) end)\n"
                                            + "-- 接口清单见 lemurx.help()，教程见三点菜单「Lua 教程」。\n\n"
                                            + "lemurx.toast(\"" + n + " 已加载\")\n";
                            if (LemurXScripts.writeSource(f, tpl)) {
                                mDirty = true;
                                reload();
                                for (ScriptInfo s : mScripts) {
                                    if (s.file.equals(f)) {
                                        showList();
                                        showSource(s);
                                        return;
                                    }
                                }
                                showList();
                            } else {
                                toast("创建失败");
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- 控件工具

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout card() {
        LinearLayout l = column();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(mColorCard);
        bg.setCornerRadius(dp(14));
        l.setBackground(bg);
        return l;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(luminance(mColorBg) < 0.5 ? 0xFF2C2C2E : 0xFFE5E5EA);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(dp(56), 0, 0, 0);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        if (primary) {
            bg.setColor(mColorAccent);
            b.setTextColor(Color.WHITE);
        } else {
            bg.setColor(luminance(mColorBg) < 0.5 ? 0xFF2C2C2E : 0xFFE5E5EA);
            b.setTextColor(mColorFg);
        }
        b.setBackground(bg);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(8), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private android.graphics.drawable.Drawable ripple() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId != 0 ? getResources().getDrawable(tv.resourceId, getTheme()) : null;
    }

    private LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
