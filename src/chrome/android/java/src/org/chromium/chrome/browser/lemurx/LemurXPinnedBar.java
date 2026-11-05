// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewStub;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.chromium.chrome.R;

import org.chromium.base.Log;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileManager;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.toolbar.AppThemeColorProvider;
import org.chromium.chrome.browser.toolbar.extensions.ExtensionsToolbarCoordinator;
import org.chromium.chrome.browser.ui.browser_window.ChromeAndroidTask;
import org.chromium.chrome.browser.ui.browser_window.ChromeAndroidTaskTrackerFactory;
import org.chromium.ui.base.WindowAndroid;

/**
 * 看网页时，底栏药丸上面的一排已固定插件。
 *
 * <p>图标、拼图按钮、点按弹出的插件页、长按菜单，都用桌面工具栏那一套：点图标跑插件动作，
 * 有弹层就锚在图标上方，再点一次收起；拼图按钮打开插件菜单。没有固定插件时仍留着拼图按钮。
 */
@NullMarked
final class LemurXPinnedBar {
    private static final String TAG = "LemurXPinned";

    private static @Nullable FrameLayout sHost;
    private static @Nullable ExtensionsToolbarCoordinator sCoordinator;

    private LemurXPinnedBar() {}

    /** 插在底栏药丸上面。导航栏收起时这一排一起收起。 */
    static void attach(Activity activity, LinearLayout nav) {
        if (sHost != null && sHost.getParent() == nav) return;
        destroy();
        FrameLayout host = new FrameLayout(activity);
        host.setVisibility(View.GONE);
        float density = activity.getResources().getDisplayMetrics().density;
        int gap = Math.round(8f * density);
        host.setPadding(0, gap, 0, gap);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        nav.addView(host, 0, lp);
        sHost = host;
    }

    /** 只有正在看网页、底栏本身也在的时候才显示。 */
    static void sync(ChromeTabbedActivity activity, boolean show) {
        FrameLayout host = sHost;
        if (host == null) return;
        if (!show) {
            host.setVisibility(View.GONE);
            return;
        }
        if (sCoordinator == null && !ensure(activity, host)) return;
        host.setVisibility(View.VISIBLE);
        layoutIcons(host);
    }

    private static boolean ensure(ChromeTabbedActivity activity, FrameLayout host) {
        if (host.getChildCount() > 0) return sCoordinator != null;
        WindowAndroid window = activity.getWindowAndroid();
        if (window == null) return false;
        ChromeAndroidTask task =
                ChromeAndroidTaskTrackerFactory.getInstance().get(activity.getTaskId());
        var dialogManager = activity.getModalDialogManager();
        if (task == null || dialogManager == null) return false;
        Profile profile;
        try {
            Tab tab = activity.getActivityTab();
            profile = tab != null ? tab.getProfile() : ProfileManager.getLastUsedRegularProfile();
        } catch (RuntimeException e) {
            return false;
        }
        if (profile == null) return false;
        ViewStub stub = new ViewStub(activity);
        stub.setLayoutParams(
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER_HORIZONTAL));
        host.addView(stub);
        try {
            sCoordinator =
                    ExtensionsToolbarCoordinator.maybeCreate(
                            activity,
                            stub,
                            window,
                            task,
                            profile,
                            activity.getActivityTabProvider().asObservable(),
                            activity.getCurrentTabCreator(),
                            new AppThemeColorProvider(activity, null, null),
                            host,
                            /* contextMenuPopulatorFactory= */ null,
                            /* selectionDropdownMenuDelegate= */ null,
                            activity.getTabModelSelector(),
                            dialogManager,
                            /* onFeatureRemoved= */ null,
                            /* isWebApp= */ false);
        } catch (RuntimeException e) {
            Log.i(TAG, "pinned bar", e);
            host.removeAllViews();
            sCoordinator = null;
            return false;
        }
        if (sCoordinator == null) {
            host.removeAllViews();
            return false;
        }
        host.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (right - left != oldRight - oldLeft) layoutIcons(host);
                });
        return true;
    }

    private static void layoutIcons(FrameLayout host) {
        ExtensionsToolbarCoordinator coordinator = sCoordinator;
        if (coordinator == null || host.getWidth() <= 0) return;
        int width = host.getWidth() - host.getPaddingLeft() - host.getPaddingRight();
        if (width <= 0) return;
        coordinator.getActionListWidthConsumer().updateVisibility(width);
        coordinator.getMenuButtonWidthConsumer().updateVisibility(width);
        coordinator.getRequestAccessButtonWidthConsumer().updateVisibility(width);
        coordinator.getPoppedOutActionWidthConsumer().updateVisibility(width);
        styleStrip(host);
    }

    /** 整排用底部导航那颗药丸：同一张底、同一圈圆角和阴影。图标之间靠槽位留白。 */
    private static void styleStrip(FrameLayout host) {
        if (host.getChildCount() == 0) return;
        View strip = host.getChildAt(0);
        float density = host.getResources().getDisplayMetrics().density;
        int pad = host.getResources().getDimensionPixelSize(R.dimen.lemurx_console_pill_padding);
        int cell = Math.round(47f * density);
        ViewGroup.LayoutParams stripParams = strip.getLayoutParams();
        if (stripParams != null && stripParams.height != cell + pad * 2) {
            stripParams.height = cell + pad * 2;
            strip.setLayoutParams(stripParams);
        }
        strip.setBackgroundResource(R.drawable.lemurx_console_pill_bg);
        strip.setPadding(pad, pad, pad, pad);
        strip.setElevation(host.getResources().getDimensionPixelSize(R.dimen.lemurx_console_pill_elevation));
        strip.setClipToOutline(true);
        strip.setOutlineProvider(
                new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
                    }
                });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            int shadow = host.getResources().getColor(R.color.lemurx_console_shadow, host.getContext().getTheme());
            int soft = Color.argb(0x12, Color.red(shadow), Color.green(shadow), Color.blue(shadow));
            strip.setOutlineAmbientShadowColor(soft);
            strip.setOutlineSpotShadowColor(soft);
        }
        ImageView puzzle = strip.findViewById(R.id.extensions_menu_button);
        if (puzzle == null) return;
        int width = Math.round(48f * density);
        int height = Math.round(47f * density);
        ViewGroup.LayoutParams params = puzzle.getLayoutParams();
        if (params != null && (params.width != width || params.height != height)) {
            params.width = width;
            params.height = height;
            puzzle.setLayoutParams(params);
        }
        int inset = Math.round(12f * density);
        puzzle.setPadding(inset, inset, inset, inset);
        puzzle.setScaleType(ImageView.ScaleType.FIT_CENTER);
        puzzle.setImageTintList(null);
        puzzle.setImageResource(R.drawable.lemurx_console_ic_extensions);
        boolean night =
                (host.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES;
        int active = night ? 0xFF2A363E : 0xFFF0F1F2;
        StateListDrawable states = new StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setCornerRadius(28f * density);
        pressed.setColor(active);
        GradientDrawable idle = new GradientDrawable();
        idle.setCornerRadius(28f * density);
        idle.setColor(0);
        states.addState(new int[] {android.R.attr.state_pressed}, pressed);
        states.addState(new int[] {}, idle);
        puzzle.setBackground(states);
    }

    private static void destroy() {
        if (sCoordinator != null) {
            sCoordinator.destroy();
            sCoordinator = null;
        }
        if (sHost != null) {
            if (sHost.getParent() instanceof ViewGroup) {
                ((ViewGroup) sHost.getParent()).removeView(sHost);
            }
            sHost = null;
        }
    }
}
