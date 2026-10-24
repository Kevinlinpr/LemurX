// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.chromium.base.Log;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.password_manager.ManagePasswordsReferrer;
import org.chromium.chrome.browser.password_manager.PasswordManagerLauncher;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.settings.SettingsNavigationFactory;
import org.chromium.components.browser_ui.settings.SettingsNavigation.SettingsFragment;
import org.chromium.ui.modaldialog.ModalDialogManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 个人数据：用户自己的资料库。全局的，不属于某个标签页，所以不在底部导航栏里，而是 ☰ 设置里的
 * 一页（{@link LemurXDataFragment} 把它包成 SettingsActivity 里的 Fragment）。
 *
 * <ul>
 *   <li>「浏览器保管」：密码、支付方式、地址。密码和 Chrome 同一套加密存储，Aero 可以在页面上
 *       填入已保存的登录，密码不离开这台手机。支付方式和地址只在设置里看，智能体读不到。
 *   <li>「给 LemurX 的资料」：服务端的 memories（{@code /api/agent/memories}），每次对话都会
 *       提供给智能体，用来放偏好、常用信息、工作背景这类方便它办事的内容。可增删改。
 * </ul>
 */
@NullMarked
class MuseDataView extends FrameLayout {
    private static final String TAG = "LemurXMuse";

    /** 宿主提供打开密码管理需要的 Profile 和对话框管理器。 */
    interface Host {
        @Nullable Profile profile();

        @Nullable ModalDialogManager dialogs();
    }

    private final Host mHost;
    private final LinearLayout mMemories;
    private final TextView mError;
    private @Nullable JSONArray mItems;
    private boolean mLoading;

    MuseDataView(Context context, Host host) {
        super(context);
        mHost = host;
        int pad = MusePage.pagePadding(context);

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, dp(4), pad, pad);

        // ---- 浏览器保管的数据
        column.addView(heading(R.string.lemurx_muse_data_browser, R.string.lemurx_muse_data_browser_hint));
        LinearLayout group = group();
        group.addView(row(R.drawable.lemurx_muse_ic_key, R.string.lemurx_muse_data_passwords,
                R.string.lemurx_muse_data_passwords_hint, v -> openPasswords()));
        group.addView(divider());
        group.addView(row(R.drawable.lemurx_muse_ic_card, R.string.lemurx_muse_data_payments,
                R.string.lemurx_muse_data_payments_hint,
                v -> openSettings(SettingsFragment.PAYMENT_METHODS)));
        group.addView(divider());
        group.addView(row(R.drawable.lemurx_muse_ic_pin, R.string.lemurx_muse_data_addresses,
                R.string.lemurx_muse_data_addresses_hint,
                v -> openSettings(SettingsFragment.AUTOFILL_PROFILES)));
        column.addView(group, groupLp());

        // ---- 给智能体的资料
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View title = heading(R.string.lemurx_muse_data_agent, R.string.lemurx_muse_data_agent_hint);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = new TextView(context);
        add.setText(R.string.lemurx_muse_data_add);
        add.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        add.setTypeface(Typeface.DEFAULT_BOLD);
        add.setTextColor(MusePage.onAccent(context));
        add.setBackground(MusePage.rounded(MusePage.accent(getContext()), 16, context));
        add.setPadding(dp(14), dp(7), dp(14), dp(7));
        add.setOnClickListener(v -> edit(null));
        head.addView(add);
        column.addView(head);

        mError = new TextView(context);
        mError.setTextColor(MusePage.error(context));
        mError.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mError.setPadding(0, 0, 0, dp(8));
        mError.setVisibility(View.GONE);
        column.addView(mError);

        mMemories = new LinearLayout(context);
        mMemories.setOrientation(LinearLayout.VERTICAL);
        column.addView(mMemories, groupLp());

        scroll.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        renderMemories();
    }

    private int dp(int value) {
        return MusePage.dp(getContext(), value);
    }

    // ---------------------------------------------------------------- 小部件

    private View heading(int title, int hint) {
        Context context = getContext();
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(14), dp(4), dp(8));
        TextView t = new TextView(context);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(MusePage.text(getContext()));
        box.addView(t);
        TextView h = new TextView(context);
        h.setText(hint);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        h.setTextColor(MusePage.muted(getContext()));
        h.setLineSpacing(0, 1.25f);
        h.setPadding(0, dp(2), 0, 0);
        box.addView(h);
        return box;
    }

    private LinearLayout group() {
        LinearLayout group = new LinearLayout(getContext());
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(MusePage.rounded(MusePage.surface(getContext()), 18, getContext()));
        return group;
    }

    private LinearLayout.LayoutParams groupLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        return lp;
    }

    private View divider() {
        View line = new View(getContext());
        line.setBackgroundColor(MusePage.line(getContext()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.setMarginStart(dp(56));
        line.setLayoutParams(lp);
        return line;
    }

    private View row(int icon, int title, int hint, View.OnClickListener onClick) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackgroundResource(R.drawable.lemurx_console_cell_bg);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(onClick);
        ImageView image = new ImageView(context);
        image.setImageResource(icon);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(24), dp(24));
        ilp.setMarginEnd(dp(16));
        row.addView(image, ilp);
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(context);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(MusePage.text(getContext()));
        texts.addView(t);
        TextView h = new TextView(context);
        h.setText(hint);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        h.setTextColor(MusePage.muted(getContext()));
        texts.addView(h);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView chevron = new ImageView(context);
        chevron.setImageResource(R.drawable.lemurx_muse_ic_chevron);
        chevron.setColorFilter(MusePage.muted(context), PorterDuff.Mode.SRC_IN);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(20), dp(20)));
        return row;
    }

    // ---------------------------------------------------------------- 浏览器入口

    private void openPasswords() {
        try {
            Profile profile = mHost.profile();
            ModalDialogManager dialogs = mHost.dialogs();
            if (profile == null || dialogs == null) {
                openSettings(SettingsFragment.AUTOFILL_AND_PASSWORDS);
                return;
            }
            PasswordManagerLauncher.showPasswordSettings(
                    getContext(),
                    profile,
                    ManagePasswordsReferrer.CHROME_SETTINGS,
                    dialogs,
                    /* managePasskeys= */ false);
        } catch (Exception e) {
            Log.i(TAG, "passwords: %s", e.getMessage());
            openSettings(SettingsFragment.AUTOFILL_AND_PASSWORDS);
        }
    }

    private void openSettings(@SettingsFragment int fragment) {
        try {
            SettingsNavigationFactory.createSettingsNavigation().startSettings(getContext(), fragment);
        } catch (Exception e) {
            Log.i(TAG, "settings %d: %s", fragment, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 资料（memories）

    void refreshNow() {
        if (mLoading) return;
        mLoading = true;
        MuseApi.get()
                .get(
                        "/api/agent",
                        (value, error) -> {
                            mLoading = false;
                            if (error != null) {
                                mError.setText(error);
                                mError.setVisibility(View.VISIBLE);
                                return;
                            }
                            mError.setVisibility(View.GONE);
                            mItems = value instanceof JSONObject
                                    ? ((JSONObject) value).optJSONArray("memories")
                                    : null;
                            renderMemories();
                        });
    }

    private void renderMemories() {
        Context context = getContext();
        mMemories.removeAllViews();
        JSONArray items = mItems;
        if (items == null || items.length() == 0) {
            TextView empty = new TextView(context);
            empty.setText(items == null ? R.string.lemurx_muse_data_loading : R.string.lemurx_muse_data_empty);
            empty.setTextColor(MusePage.muted(getContext()));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            empty.setPadding(dp(4), dp(8), dp(4), dp(8));
            mMemories.addView(empty);
            return;
        }
        LinearLayout group = group();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            if (i > 0) group.addView(divider());
            group.addView(memoryRow(item));
        }
        mMemories.addView(group);
    }

    private View memoryRow(JSONObject item) {
        Context context = getContext();
        String id = item.optString("id", "");
        String text = item.optString("text", "");
        String source = item.optString("source", "");
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackgroundResource(R.drawable.lemurx_console_cell_bg);
        row.setClickable(true);
        row.setOnClickListener(v -> edit(item));
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.lemurx_muse_ic_sparkles);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(20), dp(20));
        ilp.setMarginEnd(dp(12));
        head.addView(icon, ilp);
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(MusePage.text(getContext()));
        t.setLineSpacing(0, 1.25f);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(head);
        if (!source.isEmpty() && !"You".equals(source)) {
            TextView s = new TextView(context);
            s.setText(context.getString(R.string.lemurx_muse_data_source, source));
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            s.setTextColor(MusePage.muted(getContext()));
            s.setPadding(0, dp(2), 0, 0);
            row.addView(s);
        }
        row.setOnLongClickListener(
                v -> {
                    confirmForget(id);
                    return true;
                });
        return row;
    }

    /** 新增（item 为 null）或编辑一条资料。 */
    private void edit(@Nullable JSONObject item) {
        Context context = getContext();
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(3);
        input.setHint(R.string.lemurx_muse_data_add_hint);
        if (item != null) input.setText(item.optString("text", ""));
        FrameLayout wrap = new FrameLayout(context);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog.Builder builder =
                new AlertDialog.Builder(context)
                        .setTitle(item == null ? R.string.lemurx_muse_data_add : R.string.lemurx_muse_data_edit)
                        .setView(wrap)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(
                                R.string.lemurx_muse_data_save,
                                (dialog, which) -> {
                                    String text = input.getText().toString().trim();
                                    if (text.isEmpty()) return;
                                    save(item == null ? null : item.optString("id", ""), text);
                                });
        if (item != null) {
            builder.setNeutralButton(
                    R.string.lemurx_muse_data_forget,
                    (dialog, which) -> confirmForget(item.optString("id", "")));
        }
        builder.show();
    }

    private void save(@Nullable String id, String text) {
        JSONObject body = new JSONObject();
        try {
            body.put("text", text);
            body.put("source", "You");
        } catch (JSONException e) {
            return;
        }
        String path = id == null || id.isEmpty() ? "/api/agent/memories" : "/api/agent/memories/" + id;
        MuseApi.get().send("POST", path, body, (value, error) -> afterChange(error));
    }

    private void confirmForget(String id) {
        if (id.isEmpty()) return;
        new AlertDialog.Builder(getContext())
                .setMessage(R.string.lemurx_muse_data_forget_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        R.string.lemurx_muse_data_forget,
                        (dialog, which) ->
                                MuseApi.get()
                                        .send("POST", "/api/agent/memories/" + id + "/forget", new JSONObject(),
                                                (value, error) -> afterChange(error)))
                .show();
    }

    private void afterChange(@Nullable String error) {
        if (error != null) {
            mError.setText(error);
            mError.setVisibility(View.VISIBLE);
        }
        refreshNow();
    }

    void destroy() {}
}
