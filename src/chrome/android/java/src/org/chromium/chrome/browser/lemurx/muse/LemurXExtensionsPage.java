// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.lemurx.LemurXConsoleShell;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 插件管理页：搜索、Chrome 网上应用店、开发者模式、已安装列表的开关 / 固定 / 移除 / 详情。
 *
 * <p>盖在当前窗口上，确认框也是 Activity 对话框，不会被外壳挡住。未打包的扩展仍从
 * chrome://extensions 加载，开发者模式打开之后那一页才有入口。
 */
@NullMarked
public final class LemurXExtensionsPage implements LemurXExtensions.Listener {
    private static @Nullable LemurXExtensionsPage sOpen;

    private final Activity mActivity;
    private final Dialog mDialog;
    private final Profile mProfile;
    private final long mToken;
    private final List<Item> mItems = new ArrayList<>();
    private final LinearLayout mRows;
    private final TextView mInstalled;
    private final TextView mEmpty;
    private final View mLoadUnpacked;
    private final Switch mDeveloperSwitch;
    private String mQuery = "";
    private boolean mRendering;
    private boolean mDeveloperMode;

    private LemurXExtensionsPage(Activity activity, Profile profile) {
        mActivity = activity;
        mProfile = profile;
        mToken = LemurXExtensions.bind(this);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MusePage.bg(activity));
        int pad = MusePage.dp(activity, 16);
        root.setPadding(pad, MusePage.dp(activity, 12), pad, pad);

        root.addView(titleBar());

        EditText search = new EditText(activity);
        search.setHint(R.string.lemurx_ext_search);
        search.setSingleLine(true);
        search.setTextColor(MusePage.text(activity));
        search.setHintTextColor(MusePage.muted(activity));
        search.setBackgroundColor(MusePage.surface(activity));
        search.setPadding(pad, MusePage.dp(activity, 10), pad, MusePage.dp(activity, 10));
        search.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(Editable s) {
                        mQuery = s == null ? "" : s.toString().trim();
                        renderRows();
                    }
                });
        root.addView(search, matchWrap());

        ScrollView scroll = new ScrollView(activity);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(storeRow());

        mDeveloperSwitch = new Switch(activity);
        body.addView(developerRow());

        mLoadUnpacked = textRow(
                activity.getString(R.string.lemurx_ext_load_unpacked),
                v -> {
                    LemurXConsoleShell.openPage("chrome://extensions");
                    dismiss();
                });
        mLoadUnpacked.setVisibility(View.GONE);
        body.addView(mLoadUnpacked);

        mInstalled = label(activity.getString(R.string.lemurx_ext_installed, 0), true);
        body.addView(mInstalled);
        mEmpty = label(activity.getString(R.string.lemurx_ext_empty), false);
        body.addView(mEmpty);
        mRows = new LinearLayout(activity);
        mRows.setOrientation(LinearLayout.VERTICAL);
        body.addView(mRows);
        scroll.addView(body, matchWrap());
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mDialog = new Dialog(activity);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        mDialog.setContentView(root);
        mDialog.setCancelable(true);
        mDialog.setOnDismissListener(d -> {
            if (sOpen == this) sOpen = null;
            LemurXExtensions.unbind(mToken);
        });
        Window window = mDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(MusePage.bg(activity)));
            window.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    /** 打开管理页。已经打开时只刷新列表。 */
    public static void show() {
        Activity activity = LemurXConsoleShell.activity();
        if (activity == null || activity.isFinishing()) return;
        if (sOpen != null) {
            sOpen.refresh();
            return;
        }
        Profile profile;
        try {
            profile = ProfileManager.getLastUsedRegularProfile();
        } catch (RuntimeException e) {
            return;
        }
        LemurXExtensionsPage page = new LemurXExtensionsPage(activity, profile);
        sOpen = page;
        page.mDialog.show();
        page.refresh();
    }

    private void refresh() {
        LemurXExtensions.refresh(mProfile, mToken);
    }

    private void dismiss() {
        mDialog.dismiss();
    }

    @Override
    public void begin(boolean developerMode) {
        mItems.clear();
        mDeveloperMode = developerMode;
        mRendering = true;
        mDeveloperSwitch.setChecked(developerMode);
        mRendering = false;
        mLoadUnpacked.setVisibility(developerMode ? View.VISIBLE : View.GONE);
    }

    @Override
    public void add(
            String id,
            String name,
            String description,
            String version,
            boolean enabled,
            boolean webStore,
            boolean canPin,
            boolean pinned) {
        Item item = new Item();
        item.id = id;
        item.name = name;
        item.description = description;
        item.version = version;
        item.enabled = enabled;
        item.webStore = webStore;
        item.canPin = canPin;
        item.pinned = pinned;
        mItems.add(item);
    }

    @Override
    public void end() {
        renderRows();
    }

    @Override
    public void icon(String id, Bitmap bitmap) {
        for (Item item : mItems) {
            if (item.id.equals(id)) {
                item.icon = bitmap;
                if (item.iconView != null) item.iconView.setImageBitmap(bitmap);
                return;
            }
        }
    }

    private void renderRows() {
        mRows.removeAllViews();
        String query = mQuery.toLowerCase(Locale.getDefault());
        int shown = 0;
        for (Item item : mItems) {
            if (!query.isEmpty() && !matches(item, query)) continue;
            shown++;
            mRows.addView(extensionRow(item));
        }
        boolean noneInstalled = mItems.isEmpty();
        boolean noHits = !noneInstalled && shown == 0;
        mEmpty.setText(noHits
                ? R.string.lemurx_ext_search_empty
                : R.string.lemurx_ext_empty);
        mEmpty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
        mInstalled.setText(mActivity.getString(R.string.lemurx_ext_installed, shown));
        mInstalled.setVisibility(shown == 0 ? View.GONE : View.VISIBLE);
    }

    private static boolean matches(Item item, String query) {
        return contains(item.name, query)
                || contains(item.description, query)
                || contains(item.id, query);
    }

    private static boolean contains(@Nullable String value, String query) {
        return value != null && value.toLowerCase(Locale.getDefault()).contains(query);
    }

    private View titleBar() {
        LinearLayout bar = new LinearLayout(mActivity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(mActivity);
        title.setText(R.string.lemurx_muse_extensions);
        title.setTextColor(MusePage.text(mActivity));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = new TextView(mActivity);
        close.setText(R.string.lemurx_ext_close);
        close.setTextColor(MusePage.link(mActivity));
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        close.setPadding(MusePage.dp(mActivity, 8), MusePage.dp(mActivity, 8), 0, MusePage.dp(mActivity, 8));
        close.setOnClickListener(v -> dismiss());
        bar.addView(close);
        return bar;
    }

    private View storeRow() {
        return textRow(
                mActivity.getString(R.string.lemurx_ext_store),
                mActivity.getString(R.string.lemurx_ext_store_hint),
                v -> {
                    LemurXConsoleShell.openPage("https://chromewebstore.google.com/");
                    dismiss();
                });
    }

    private View developerRow() {
        LinearLayout row = rowShell();
        LinearLayout text = new LinearLayout(mActivity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(label(mActivity.getString(R.string.lemurx_ext_developer), true));
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mDeveloperSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (mRendering || checked == mDeveloperMode) return;
            LemurXExtensions.setDeveloperMode(mProfile, checked);
            mDeveloperMode = checked;
            mLoadUnpacked.setVisibility(checked ? View.VISIBLE : View.GONE);
        });
        row.addView(mDeveloperSwitch);
        return row;
    }

    private View extensionRow(Item item) {
        LinearLayout row = rowShell();
        FrameLayout iconSlot = new FrameLayout(mActivity);
        int size = MusePage.dp(mActivity, 40);
        ImageView icon = new ImageView(mActivity);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable fallback = new GradientDrawable();
        fallback.setCornerRadius(MusePage.dp(mActivity, 8));
        fallback.setColor(MusePage.surface(mActivity));
        icon.setBackground(fallback);
        if (item.icon != null) icon.setImageBitmap(item.icon);
        item.iconView = icon;
        iconSlot.addView(icon, new FrameLayout.LayoutParams(size, size));
        row.addView(iconSlot, new LinearLayout.LayoutParams(size, size));

        LinearLayout text = new LinearLayout(mActivity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(MusePage.dp(mActivity, 12), 0, MusePage.dp(mActivity, 8), 0);
        TextView name = label(TextUtils.isEmpty(item.name) ? item.id : item.name, true);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(name);
        TextView status = label(statusLine(item), false);
        text.addView(status);
        if (item.canPin) {
            TextView pin = label(
                    mActivity.getString(item.pinned
                            ? R.string.lemurx_ext_unpin
                            : R.string.lemurx_ext_pin),
                    false);
            pin.setTextColor(MusePage.link(mActivity));
            pin.setOnClickListener(v -> {
                if (!LemurXExtensions.setPinned(mProfile, item.id, !item.pinned)) return;
                item.pinned = !item.pinned;
                pin.setText(item.pinned ? R.string.lemurx_ext_unpin : R.string.lemurx_ext_pin);
            });
            text.addView(pin);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView remove = label(mActivity.getString(R.string.lemurx_ext_remove), false);
        remove.setTextColor(MusePage.error(mActivity));
        remove.setOnClickListener(v -> confirmRemove(item));
        row.addView(remove);

        Switch enable = new Switch(mActivity);
        mRendering = true;
        enable.setChecked(item.enabled);
        mRendering = false;
        enable.setOnCheckedChangeListener((CompoundButton button, boolean checked) -> {
            if (mRendering || checked == item.enabled) return;
            if (!LemurXExtensions.setEnabled(mProfile, item.id, checked)) {
                mRendering = true;
                enable.setChecked(item.enabled);
                mRendering = false;
                return;
            }
            item.enabled = checked;
            status.setText(statusLine(item));
        });
        row.addView(enable);
        row.setOnClickListener(v -> showDetails(item));
        return row;
    }

    private String statusLine(Item item) {
        String state = mActivity.getString(item.enabled
                ? R.string.lemurx_ext_enabled
                : R.string.lemurx_ext_disabled);
        StringBuilder line = new StringBuilder(state);
        if (!TextUtils.isEmpty(item.description)) {
            line.append(" · ").append(item.description);
        }
        if (mDeveloperMode && !TextUtils.isEmpty(item.version)) {
            line.append(" · ").append(mActivity.getString(R.string.lemurx_ext_version, item.version));
        }
        return line.toString();
    }

    private void confirmRemove(Item item) {
        String name = TextUtils.isEmpty(item.name) ? item.id : item.name;
        LemurXExtensions.card(
                mActivity,
                mActivity.getString(R.string.lemurx_ext_remove_title, name),
                mActivity.getString(R.string.lemurx_ext_remove_message),
                mActivity.getString(R.string.lemurx_ext_cancel),
                mActivity.getString(R.string.lemurx_ext_remove),
                () -> {},
                () -> {
                    if (!LemurXExtensions.uninstall(mProfile, item.id)) {
                        Toast.makeText(
                                        mActivity,
                                        R.string.lemurx_ext_remove_failed,
                                        Toast.LENGTH_SHORT)
                                .show();
                    }
                    refresh();
                });
    }

    private void showDetails(Item item) {
        String name = TextUtils.isEmpty(item.name) ? item.id : item.name;
        StringBuilder body = new StringBuilder();
        if (!TextUtils.isEmpty(item.description)) body.append(item.description).append("\n\n");
        if (!TextUtils.isEmpty(item.version)) {
            body.append(mActivity.getString(R.string.lemurx_ext_version, item.version)).append('\n');
        }
        body.append(item.id);
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity)
                .setTitle(name)
                .setMessage(body.toString())
                .setNegativeButton(R.string.lemurx_ext_close, null);
        if (item.webStore) {
            builder.setPositiveButton(R.string.lemurx_ext_view_store, (d, w) -> {
                LemurXConsoleShell.openPage(
                        "https://chromewebstore.google.com/detail/" + item.id);
                dismiss();
            });
        }
        builder.show();
    }

    private View textRow(String title, View.OnClickListener click) {
        return textRow(title, null, click);
    }

    private View textRow(String title, @Nullable String subtitle, View.OnClickListener click) {
        LinearLayout row = rowShell();
        LinearLayout text = new LinearLayout(mActivity);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = label(title, true);
        text.addView(name);
        if (!TextUtils.isEmpty(subtitle)) text.addView(label(subtitle, false));
        row.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(click);
        return row;
    }

    private LinearLayout rowShell() {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = MusePage.dp(mActivity, 12);
        row.setPadding(0, pad, 0, pad);
        return row;
    }

    private TextView label(String text, boolean primary) {
        TextView view = new TextView(mActivity);
        view.setText(text);
        view.setTextColor(primary ? MusePage.text(mActivity) : MusePage.muted(mActivity));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, primary ? 16 : 13);
        return view;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static final class Item {
        String id = "";
        String name = "";
        String description = "";
        String version = "";
        boolean enabled;
        boolean webStore;
        boolean canPin;
        boolean pinned;
        @Nullable Bitmap icon;
        @Nullable ImageView iconView;
    }
}
