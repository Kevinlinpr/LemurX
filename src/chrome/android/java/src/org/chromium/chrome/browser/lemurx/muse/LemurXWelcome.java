// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.chromium.base.ContextUtils;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.lemurx.LemurXConsoleShell;
import org.chromium.chrome.browser.lemurx.LemurXHaptics;

/**
 * 第一次打开时的封面。浅色纸面，中间是品牌和一句它能做什么，底下登录或先以游客进去。
 * 选过一次就不再挡。
 */
@NullMarked
public final class LemurXWelcome {
    private static final String PREF_FILE = "lemurx_console";
    /** 封面换成浅色之后换一把钥匙，已经看过黑底的人还能再见到这一版。 */
    private static final String KEY_DONE = "welcome_paper";
    private static final int COVER = 0xFFF7F5F2;
    private static final int INK = 0xFF132631;
    private static final int MUTED = 0xFF6B7680;
    private static final int ON_INK = 0xFFF7F5F2;
    private static final Object NAV = new Object();

    private static @Nullable View sCover;

    private LemurXWelcome() {}

    /** 还没选过、也没登录时盖上。窗口重挂时如果封面还在，不要再铺一张。 */
    public static void maybeShow(Activity activity) {
        ThreadUtils.assertOnUiThread();
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (done()) return;
        if (sCover != null && sCover.getParent() != null) {
            sCover.bringToFront();
            return;
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;

        FrameLayout cover = new FrameLayout(activity);
        cover.setBackgroundColor(COVER);
        cover.setClickable(true);
        cover.setOnApplyWindowInsetsListener(
                (v, insets) -> {
                    int top = LemurXConsoleShell.statusInsetPx();
                    int bottom = 0;
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            bottom = insets.getInsets(android.view.WindowInsets.Type.systemBars()).bottom;
                        } else {
                            bottom = insets.getSystemWindowInsetBottom();
                        }
                    } catch (Exception e) {
                        bottom = 0;
                    }
                    v.setPadding(0, top, 0, bottom);
                    return insets;
                });

        LinearLayout brand = new LinearLayout(activity);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setGravity(Gravity.CENTER_HORIZONTAL);
        brand.setPadding(dp(activity, 36), 0, dp(activity, 36), dp(activity, 72));

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.mipmap.app_icon);
        icon.setClipToOutline(true);
        icon.setOutlineProvider(
                new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(
                                0, 0, view.getWidth(), view.getHeight(), view.getWidth() * 0.26f);
                    }
                });
        brand.addView(icon, new LinearLayout.LayoutParams(dp(activity, 84), dp(activity, 84)));

        TextView title = new TextView(activity);
        title.setText(R.string.lemurx_muse_brand);
        title.setTextColor(INK);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = wrap();
        titleLp.topMargin = dp(activity, 22);
        brand.addView(title, titleLp);

        TextView tagline = new TextView(activity);
        tagline.setText(R.string.lemurx_welcome_tagline);
        tagline.setTextColor(MUTED);
        tagline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tagline.setGravity(Gravity.CENTER);
        tagline.setLineSpacing(dp(activity, 3), 1f);
        LinearLayout.LayoutParams tagLp = wrap();
        tagLp.topMargin = dp(activity, 10);
        brand.addView(tagline, tagLp);

        cover.addView(
                brand,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = dp(activity, 24);
        actions.setPadding(side, 0, side, dp(activity, 28));

        TextView signIn = new TextView(activity);
        signIn.setText(R.string.lemurx_welcome_sign_in);
        signIn.setTextColor(ON_INK);
        signIn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        signIn.setTypeface(Typeface.DEFAULT_BOLD);
        signIn.setGravity(Gravity.CENTER);
        signIn.setBackground(MusePage.rounded(INK, 26, activity));
        signIn.setClickable(true);
        signIn.setFocusable(true);
        signIn.setOnClickListener(
                v -> {
                    LemurXHaptics.click(v);
                    if (LemurXAccount.isSignedIn()) {
                        enter();
                        return;
                    }
                    LemurXSignIn.showFromBrowser(activity);
                });
        actions.addView(
                signIn,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 52)));

        TextView guest = new TextView(activity);
        guest.setText(R.string.lemurx_welcome_guest);
        guest.setTextColor(INK);
        guest.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        guest.setGravity(Gravity.CENTER);
        guest.setPadding(0, dp(activity, 16), 0, dp(activity, 8));
        guest.setClickable(true);
        guest.setFocusable(true);
        guest.setOnClickListener(
                v -> {
                    LemurXHaptics.tick(v);
                    enter();
                });
        actions.addView(
                guest,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cover.addView(
                actions,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM));

        content.addView(
                cover,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sCover = cover;
        cover.bringToFront();
        cover.requestApplyInsets();
        LemurXConsoleShell.setNavSuppressed(NAV, true);
    }

    /** 登录完成。封面收掉，下次不再出现。 */
    public static void onSignedIn() {
        ThreadUtils.assertOnUiThread();
        enter();
    }

    public static boolean isShowing() {
        return sCover != null && sCover.getParent() != null;
    }

    /** 封面还在时，返回键先留在这一页。 */
    public static boolean consumeBack() {
        return isShowing();
    }

    /** 窗口拆掉时把这一层摘下来。没选过的话，下次打开还会再来。 */
    public static void detach() {
        View cover = sCover;
        sCover = null;
        if (cover != null && cover.getParent() instanceof ViewGroup) {
            ((ViewGroup) cover.getParent()).removeView(cover);
        }
        LemurXConsoleShell.setNavSuppressed(NAV, false);
    }

    private static void enter() {
        markDone();
        dismiss();
    }

    private static void dismiss() {
        detach();
    }

    private static boolean done() {
        try {
            return prefs().getBoolean(KEY_DONE, false);
        } catch (Exception e) {
            return false;
        }
    }

    private static void markDone() {
        try {
            prefs().edit().putBoolean(KEY_DONE, true).apply();
        } catch (Exception e) {
            // 记不住就下次再问一次
        }
    }

    private static SharedPreferences prefs() {
        return ContextUtils.getApplicationContext()
                .getSharedPreferences(PREF_FILE, android.content.Context.MODE_PRIVATE);
    }

    private static int dp(Activity activity, int value) {
        return MusePage.dp(activity, value);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
