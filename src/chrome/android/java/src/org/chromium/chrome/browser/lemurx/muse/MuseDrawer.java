// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.back_press.BackPressManager;
import org.chromium.chrome.browser.lemurx.LemurXConsoleShell;
import org.chromium.chrome.browser.lemurx.LemurXHaptics;
import org.chromium.chrome.browser.settings.SettingsNavigationFactory;
import org.chromium.components.browser_ui.settings.SettingsNavigation.SettingsFragment;

import java.util.ArrayList;

/**
 * 底部导航三条线拉出的设置。整个界面往右让开，抽屉停在让出来的左侧，不再盖在页面上。
 * 颜色和字号跟控制台同一套。点某一行要进 Chromium 自己的页面时，那一页才另开。
 * 从这里点进同一窗口的二级页再返回，抽屉回到点进去之前的打开状态，包括滚到哪里。
 * 用量和系统设置是另一扇窗口，抽屉留在底下，返回时还是离开前的样子。
 */
@NullMarked
final class MuseDrawer {
    private static @Nullable MuseDrawer sOpen;

    private final FrameLayout mHost;
    private final View mScrim;
    private final LinearLayout mPanel;
    private final LinearLayout mColumn;
    private TextView mAccountName;
    private TextView mAccountHint;
    private ImageView mAccountIcon;
    private boolean mOpen;
    private boolean mClosing;
    /** 收起动画结束才真正摘掉。中途被重新打开时清掉，避免结束回调把刚打开的抽屉拆掉。 */
    private boolean mDismissOnEnd;
    private ScrollView mScroll;
    /** 离开去盖层之前，抽屉滚到了这里。回来时贴回去。 */
    private int mReturnScroll;
    private boolean mApplyScroll;
    /** 从二级页回来时直接停在打开的位置，不再从左边滑入。 */
    private boolean mSnapOpen;
    private static boolean sReturnCover;
    private static @Nullable MuseDrawer sReturnDrawer;
    private @Nullable Runnable mArchiveAction;
    private @Nullable Runnable mComputerAction;
    private final ArrayList<View> mPushed = new ArrayList<>();
    private final ArrayList<Float> mPushedFrom = new ArrayList<>();

    MuseDrawer(FrameLayout host) {
        mHost = host;
        android.content.Context context = host.getContext();
        mScrim = new View(context);
        mScrim.setBackgroundColor(0x66000000);
        mScrim.setAlpha(0f);
        mScrim.setOnClickListener(v -> close());

        mPanel = new LinearLayout(context);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setBackgroundColor(MusePage.bg(context));
        mPanel.setElevation(MusePage.dp(context, 8));
        mPanel.setClickable(true);

        mScroll = new ScrollView(context);
        ScrollView scroll = mScroll;
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        mColumn = column;
        applyTopInset();
        mPanel.setOnApplyWindowInsetsListener((v, insets) -> {
            applyTopInset();
            return insets;
        });

        LinearLayout account = accountRow(context);
        column.addView(account);

        column.addView(label(context, R.string.lemurx_drawer_general));
        column.addView(row(context, R.drawable.lemurx_muse_ic_user,
                R.string.lemurx_archive, R.string.lemurx_archive_hint,
                v -> {
                    leaveForCover();
                    if (mArchiveAction != null) mArchiveAction.run();
                }));
        column.addView(row(context, R.drawable.lemurx_muse_ic_server,
                R.string.lemurx_muse_computer, R.string.lemurx_muse_computer_hint,
                v -> {
                    if (!stayForSignIn()) return;
                    leaveForCover();
                    if (mComputerAction != null) mComputerAction.run();
                }));
        column.addView(row(context, R.drawable.lemurx_console_ic_community,
                R.string.lemurx_muse_community, R.string.lemurx_drawer_community_hint,
                v -> {
                    if (!stayForSignIn()) return;
                    leaveForCover();
                    LemurXConsoleShell.goCommunity();
                }));
        // 用量和系统设置盖住当前窗口。抽屉不收，返回时还停在原来的位置。
        column.addView(row(context, R.drawable.lemurx_muse_ic_card,
                R.string.lemurx_billing_heading, 0,
                v -> SettingsNavigationFactory.createSettingsNavigation()
                        .startSettings(context, LemurXBillingFragment.class)));
        column.addView(row(context, R.drawable.lemurx_muse_ic_settings,
                R.string.lemurx_muse_settings_all, 0,
                v -> openChrome(SettingsFragment.MAIN)));

        scroll.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mPanel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 内容从状态栏下面开始。面板背景仍然铺满，时钟不会压住第一行。 */
    private void applyTopInset() {
        android.content.Context context = mHost.getContext();
        int top = LemurXConsoleShell.statusInsetPx() + MusePage.dp(context, 12);
        mColumn.setPadding(MusePage.dp(context, 8), top, MusePage.dp(context, 8),
                MusePage.dp(context, 24));
    }

    /** 档案库。先记下抽屉现在的样子再收起，返回时按这个样子打开。 */
    void setArchiveAction(Runnable action) {
        mArchiveAction = action;
    }

    /** 用户自己的电脑。同样先记下抽屉，返回时再打开。 */
    void setComputerAction(Runnable action) {
        mComputerAction = action;
    }

    /**
     * 侧边栏里点进同一窗口的二级页。先记住滚到哪里，再收起。
     * 用户按返回离开那一页时，侧边栏回到现在这个样子。
     */
    private void leaveForCover() {
        mReturnScroll = mScroll.getScrollY();
        sReturnDrawer = this;
        sReturnCover = true;
        close();
    }

    /** 电脑和社区要账号。没登录就停在打开的侧边栏上，只拉起登录。 */
    private boolean stayForSignIn() {
        if (LemurXAccount.isSignedIn()) return true;
        Activity activity = LemurXConsoleShell.activity();
        if (activity != null) LemurXSignIn.showFromBrowser(activity);
        return false;
    }

    /** 二级页被返回关掉。只有从侧边栏进去的那次才把抽屉直接打回打开的位置。 */
    static void restoreAfterCover() {
        if (!sReturnCover || sReturnDrawer == null) return;
        MuseDrawer drawer = sReturnDrawer;
        sReturnCover = false;
        sReturnDrawer = null;
        drawer.mApplyScroll = true;
        drawer.mSnapOpen = true;
        drawer.open(true);
    }

    /** 底栏换页、去登录网页这类往前走的操作：不要在回来时再把侧边栏打开。 */
    static void dropReturn() {
        sReturnCover = false;
        if (sReturnDrawer != null) {
            sReturnDrawer.mApplyScroll = false;
            sReturnDrawer.mSnapOpen = false;
        }
        sReturnDrawer = null;
    }

    /** 去付款页时侧边栏不该还停在打开。立刻拿掉，也不再等返回。 */
    static void abandon() {
        dropReturn();
        MuseDrawer drawer = sOpen;
        if (drawer != null) drawer.dismiss();
    }

    /** 有没有一扇抽屉正开着（含正在滑回去）。返回键要先收它。 */
    static boolean isOpen() {
        MuseDrawer drawer = sOpen;
        return drawer != null && (drawer.mOpen || drawer.mClosing);
    }

    /** 收起抽屉。没有开着的抽屉时返回 false。 */
    static boolean closeIfOpen() {
        MuseDrawer drawer = sOpen;
        if (drawer == null || (!drawer.mOpen && !drawer.mClosing)) return false;
        drawer.close();
        return true;
    }

    void open() {
        open(false);
    }

    /**
     * 从二级页返回时 {@code restoring} 为真：侧边栏直接停在离开前的位置，不再从左边滑入。
     * 自己点开侧边栏时清掉还没回来的那次记录，避免稍后又被自动打开。
     */
    private void open(boolean restoring) {
        mDismissOnEnd = false;
        if (mClosing) {
            mPanel.animate().cancel();
            mClosing = false;
        }
        if (!restoring && sReturnDrawer == this) {
            sReturnCover = false;
            sReturnDrawer = null;
            mApplyScroll = false;
            mSnapOpen = false;
        }
        if (mOpen) return;
        FrameLayout stage = stage();
        int stageW = stage.getWidth();
        int parentW = mHost.getWidth();
        if (stageW <= 0 && parentW <= 0) {
            View waiter = stageW <= 0 ? stage : mHost;
            waiter.post(() -> open(restoring));
            return;
        }
        applyTopInset();
        MuseDrawer previous = sOpen;
        if (previous != null && previous != this) previous.dismiss();
        sOpen = this;
        if (stageW <= 0) stageW = parentW;
        int width = Math.min(Math.round(stageW * 0.86f), MusePage.dp(mHost.getContext(), 360));
        width = Math.max(width, Math.min(stageW, MusePage.dp(mHost.getContext(), 280)));
        if (mScrim.getParent() != stage) {
            if (mScrim.getParent() instanceof ViewGroup) {
                ((ViewGroup) mScrim.getParent()).removeView(mScrim);
            }
            if (mPanel.getParent() instanceof ViewGroup) {
                ((ViewGroup) mPanel.getParent()).removeView(mPanel);
            }
            mScrim.setBackgroundColor(0x14132631);
            stage.addView(mScrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
            mPanel.setElevation(MusePage.dp(mHost.getContext(), 48));
            stage.addView(mPanel, lp);
        } else {
            ViewGroup.LayoutParams lp = mPanel.getLayoutParams();
            lp.width = width;
            mPanel.setLayoutParams(lp);
            stage.bringChildToFront(mScrim);
            stage.bringChildToFront(mPanel);
        }
        refreshAccount();
        mScrim.setAlpha(1f);
        mScrim.setVisibility(View.VISIBLE);
        mPanel.setVisibility(View.VISIBLE);
        mOpen = true;
        boolean snap = mSnapOpen;
        mSnapOpen = false;
        mPanel.animate().cancel();
        if (snap) {
            mPanel.setTranslationX(0f);
            snapContent(stage, width);
        } else {
            mPanel.setTranslationX(-width);
            mPanel.animate().translationX(0f).setDuration(320)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
            pushContent(stage, width);
        }
        if (mApplyScroll) {
            int y = mReturnScroll;
            mApplyScroll = false;
            mScroll.scrollTo(0, y);
            mScroll.post(() -> mScroll.scrollTo(0, y));
        }
        BackPressManager.lemurXConsoleStateChanged();
        if (LemurXAccount.isSignedIn()) {
            LemurXAccount.refreshProfile(error -> refreshAccount());
        }
    }

    void close() {
        if (!mOpen || mClosing) return;
        mClosing = true;
        mOpen = false;
        mDismissOnEnd = true;
        float width = mPanel.getWidth() > 0 ? mPanel.getWidth() : mPanel.getTranslationX();
        if (width <= 0) width = mPanel.getLayoutParams().width;
        mPanel.animate().translationX(-Math.abs(width)).setDuration(240)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (mDismissOnEnd) dismiss();
                }).start();
        pullContent();
        BackPressManager.lemurXConsoleStateChanged();
    }

    /** 页面销毁时直接拿掉，不再做动画。 */
    void dismiss() {
        mDismissOnEnd = false;
        mPanel.animate().cancel();
        mScrim.animate().cancel();
        mOpen = false;
        mClosing = false;
        if (mScrim.getParent() instanceof ViewGroup) {
            ((ViewGroup) mScrim.getParent()).removeView(mScrim);
        }
        if (mPanel.getParent() instanceof ViewGroup) {
            ((ViewGroup) mPanel.getParent()).removeView(mPanel);
        }
        if (sOpen == this) sOpen = null;
        for (int i = 0; i < mPushed.size(); i++) {
            mPushed.get(i).animate().cancel();
            mPushed.get(i).setTranslationX(mPushedFrom.get(i));
        }
        mPushed.clear();
        mPushedFrom.clear();
        BackPressManager.lemurXConsoleStateChanged();
    }

    /** 活动窗口的内容根。整页和底部导航都在这里，一起往右推。 */
    private FrameLayout stage() {
        Activity activity = LemurXConsoleShell.activity();
        if (activity != null) {
            android.view.View content = activity.findViewById(android.R.id.content);
            if (content instanceof FrameLayout) return (FrameLayout) content;
        }
        return mHost;
    }

    /** 返回二级页时内容已经让开，不再做滑入。收起动画进行到一半时，按离开前的起点摆回去。 */
    private void snapContent(FrameLayout stage, float dx) {
        if (!mPushed.isEmpty()) {
            for (int i = 0; i < mPushed.size(); i++) {
                View child = mPushed.get(i);
                child.animate().cancel();
                child.setTranslationX(mPushedFrom.get(i) + dx);
            }
            return;
        }
        mPushed.clear();
        mPushedFrom.clear();
        for (int i = 0; i < stage.getChildCount(); i++) {
            View child = stage.getChildAt(i);
            if (child == mPanel) continue;
            float from = child.getTranslationX();
            mPushed.add(child);
            mPushedFrom.add(from);
            child.animate().cancel();
            child.setTranslationX(from + dx);
        }
    }

    private void pushContent(FrameLayout stage, float dx) {
        mPushed.clear();
        mPushedFrom.clear();
        android.view.animation.Interpolator ease = new DecelerateInterpolator(1.6f);
        for (int i = 0; i < stage.getChildCount(); i++) {
            View child = stage.getChildAt(i);
            if (child == mPanel) continue;
            float from = child.getTranslationX();
            mPushed.add(child);
            mPushedFrom.add(from);
            child.animate().translationX(from + dx).setDuration(320).setInterpolator(ease).start();
        }
    }

    private void pullContent() {
        android.view.animation.Interpolator ease = new DecelerateInterpolator();
        for (int i = 0; i < mPushed.size(); i++) {
            mPushed.get(i).animate().translationX(mPushedFrom.get(i)).setDuration(240)
                    .setInterpolator(ease).start();
        }
    }

    // ---------------------------------------------------------------- 账号

    private void refreshAccount() {
        android.content.Context context = mHost.getContext();
        LemurXAccount.Profile profile = LemurXAccount.profile();
        if (profile != null) {
            mAccountName.setText(profile.displayName());
            mAccountHint.setText(TextUtils.isEmpty(profile.email)
                    ? context.getString(R.string.lemurx_muse_settings_signed_in)
                    : profile.email);
            mAccountHint.setVisibility(View.VISIBLE);
            mAccountIcon.setColorFilter(null);
            int size = MusePage.dp(context, 40);
            LemurXAccount.loadAvatar(size, drawable -> {
                if (drawable != null && LemurXAccount.isSignedIn()) {
                    mAccountIcon.setImageDrawable(drawable);
                }
            });
        } else {
            mAccountName.setText(R.string.lemurx_muse_settings_sign_in);
            mAccountHint.setText(R.string.lemurx_muse_settings_sign_in_hint);
            mAccountHint.setVisibility(View.VISIBLE);
            mAccountIcon.setImageResource(R.drawable.lemurx_muse_ic_user);
            mAccountIcon.setColorFilter(MusePage.text(context), PorterDuff.Mode.SRC_IN);
        }
    }

    private void onAccountClick() {
        LemurXHaptics.tick(mAccountName);
        if (LemurXAccount.isSignedIn()) {
            showProfile();
            return;
        }
        Activity activity = LemurXConsoleShell.activity();
        if (activity == null) return;
        close();
        LemurXSignIn.showFromBrowser(activity);
    }

    private void showProfile() {
        LemurXAccount.Profile profile = LemurXAccount.profile();
        if (profile == null) return;
        android.content.Context context = mHost.getContext();
        StringBuilder text = new StringBuilder();
        if (!TextUtils.isEmpty(profile.nickname)) {
            text.append(context.getString(R.string.lemurx_muse_settings_nickname))
                    .append(": ").append(profile.nickname).append('\n');
        }
        if (!TextUtils.isEmpty(profile.email)) {
            text.append(context.getString(R.string.lemurx_muse_settings_email))
                    .append(": ").append(profile.email).append('\n');
        }
        if (!TextUtils.isEmpty(profile.userNo)) {
            text.append(context.getString(R.string.lemurx_muse_settings_user_no))
                    .append(": ").append(profile.userNo).append('\n');
        }
        new AlertDialog.Builder(context)
                .setTitle(profile.displayName())
                .setMessage(text.toString().trim())
                .setNeutralButton(R.string.lemurx_muse_settings_sign_out, (d, w) -> confirmSignOut())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void confirmSignOut() {
        new AlertDialog.Builder(mHost.getContext())
                .setTitle(R.string.lemurx_muse_settings_sign_out)
                .setMessage(R.string.lemurx_muse_settings_sign_out_hint)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.lemurx_muse_settings_sign_out, (d, w) -> signOut())
                .show();
    }

    private void signOut() {
        Activity activity = LemurXConsoleShell.activity();
        if (activity != null) LemurXGoogleSignIn.signOut(activity);
        LemurXAccount.signOut(error -> {
            MuseApi.rebind();
            LemurXConsoleShell.onSignedOut();
            refreshAccount();
        });
    }

    // ---------------------------------------------------------------- 往下走的页面

    private void openChrome(@SettingsFragment int fragment) {
        try {
            SettingsNavigationFactory.createSettingsNavigation()
                    .startSettings(mHost.getContext(), fragment);
        } catch (RuntimeException ignored) {
            // 同上
        }
    }

    // ---------------------------------------------------------------- 小部件

    private LinearLayout accountRow(android.content.Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(MusePage.dp(context, 12), MusePage.dp(context, 16),
                MusePage.dp(context, 12), MusePage.dp(context, 16));
        row.setBackgroundResource(R.drawable.lemurx_console_cell_bg);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> onAccountClick());
        mAccountIcon = new ImageView(context);
        mAccountIcon.setImageResource(R.drawable.lemurx_muse_ic_user);
        row.addView(mAccountIcon, new LinearLayout.LayoutParams(
                MusePage.dp(context, 40), MusePage.dp(context, 40)));
        mAccountName = new TextView(context);
        mAccountHint = new TextView(context);
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        mAccountName.setText(R.string.lemurx_muse_settings_sign_in);
        mAccountName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mAccountName.setTextColor(MusePage.text(context));
        mAccountName.setTypeface(Typeface.DEFAULT_BOLD);
        mAccountHint.setText(R.string.lemurx_muse_settings_sign_in_hint);
        mAccountHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mAccountHint.setTextColor(MusePage.muted(context));
        texts.addView(mAccountName);
        texts.addView(mAccountHint);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(MusePage.dp(context, 12));
        row.addView(texts, tlp);
        return row;
    }

    private View row(android.content.Context context, int icon, int title, int hint,
            View.@Nullable OnClickListener onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(MusePage.dp(context, 12), MusePage.dp(context, 14),
                MusePage.dp(context, 8), MusePage.dp(context, 14));
        if (onClick != null) {
            row.setBackgroundResource(R.drawable.lemurx_console_cell_bg);
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                LemurXHaptics.tick(v);
                onClick.onClick(v);
            });
        }
        ImageView image = new ImageView(context);
        image.setImageResource(icon);
        image.setColorFilter(MusePage.text(context), PorterDuff.Mode.SRC_IN);
        row.addView(image, new LinearLayout.LayoutParams(
                MusePage.dp(context, 22), MusePage.dp(context, 22)));
        String hintText = hint == 0 ? "" : context.getString(hint);
        LinearLayout texts = texts(context, context.getString(title), hintText);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(MusePage.dp(context, 14));
        row.addView(texts, tlp);
        if (onClick != null) {
            ImageView chevron = new ImageView(context);
            chevron.setImageResource(R.drawable.lemurx_muse_ic_chevron);
            row.addView(chevron, new LinearLayout.LayoutParams(
                    MusePage.dp(context, 18), MusePage.dp(context, 18)));
        }
        return row;
    }

    private LinearLayout texts(android.content.Context context, String title, String hint) {
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(context);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(MusePage.text(context));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(t);
        TextView h = new TextView(context);
        h.setText(hint);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        h.setTextColor(MusePage.muted(context));
        h.setMaxLines(2);
        h.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (hint.isEmpty()) h.setVisibility(View.GONE);
        texts.addView(h);
        return texts;
    }

    private View label(android.content.Context context, int title) {
        TextView label = new TextView(context);
        label.setText(title);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(MusePage.muted(context));
        label.setPadding(MusePage.dp(context, 12), MusePage.dp(context, 18),
                MusePage.dp(context, 12), MusePage.dp(context, 4));
        return label;
    }
}
