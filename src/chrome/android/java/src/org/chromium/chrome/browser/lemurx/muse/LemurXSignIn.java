// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.lemurx.LemurXConsoleShell;
import org.chromium.ui.base.WindowAndroid;

/**
 * 登录底栏：Lemur 浏览器的 "Dialog/Google Login"（OverseasLoginHubFragment +
 * dialog_overseas_login_hub.xml）——圆角卡片、拖拽把手、应用图标、标题、说明、"使用 Google
 * 继续"、"暂不"、法律说明。代码构建，不用 XML。设置页和对话里拦截游客操作时都用这一张。
 */
@NullMarked
public final class LemurXSignIn {
    /** 把 Google 账号选择页的 Intent 交出去启动。 */
    public interface Launch {
        void start(Intent intent);
    }

    /** 给人看的失败说明。 */
    public interface ErrorSink {
        void onError(String message);
    }

    private static @Nullable Dialog sDialog;
    private static @Nullable View sGoogleButton;
    private static @Nullable Launch sLaunch;
    private static @Nullable ErrorSink sErrors;
    private static boolean sBusy;

    private LemurXSignIn() {}

    /**
     * 从浏览器窗口弹出登录。Google 账号选择页走当前窗口的 {@link WindowAndroid}，回来后换票并重新登记手机。
     */
    public static void showFromBrowser(Activity activity) {
        ThreadUtils.assertOnUiThread();
        show(
                activity,
                intent -> {
                    WindowAndroid window = LemurXConsoleShell.windowAndroid();
                    boolean shown =
                            window != null
                                    && window.showIntent(
                                            intent,
                                            (resultCode, data) ->
                                                    ThreadUtils.runOnUiThread(
                                                            () -> onBrowserResult(activity, data)),
                                            null);
                    if (!shown) fail(activity.getString(R.string.lemurx_login_google_failed));
                },
                message -> Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
    }

    public static void show(Activity activity, Launch launch, ErrorSink errors) {
        ThreadUtils.assertOnUiThread();
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Dialog existing = sDialog;
        if (existing != null && existing.isShowing()) return;
        sLaunch = launch;
        sErrors = errors;
        sBusy = false;

        int pad = MusePage.dp(activity, 20);
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(MusePage.rounded(MusePage.sheet(activity), 24, activity));
        card.setPadding(pad, MusePage.dp(activity, 10), pad, pad);

        View handle = new View(activity);
        handle.setBackground(MusePage.rounded(MusePage.handle(activity), 2, activity));
        card.addView(
                handle,
                new LinearLayout.LayoutParams(MusePage.dp(activity, 36), MusePage.dp(activity, 4)));

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.mipmap.app_icon);
        icon.setClipToOutline(true);
        icon.setOutlineProvider(
                new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setOval(0, 0, view.getWidth(), view.getHeight());
                    }
                });
        LinearLayout.LayoutParams iconLp =
                new LinearLayout.LayoutParams(MusePage.dp(activity, 56), MusePage.dp(activity, 56));
        iconLp.topMargin = MusePage.dp(activity, 22);
        card.addView(icon, iconLp);

        TextView title = new TextView(activity);
        title.setText(R.string.lemurx_login_title);
        title.setTextColor(MusePage.text(activity));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = wrap();
        titleLp.topMargin = MusePage.dp(activity, 14);
        card.addView(title, titleLp);

        TextView description = new TextView(activity);
        description.setText(R.string.lemurx_login_description);
        description.setTextColor(MusePage.night(activity) ? MusePage.muted(activity) : 0x99000000);
        description.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        description.setGravity(Gravity.CENTER);
        description.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams descLp = wrap();
        descLp.topMargin = MusePage.dp(activity, 6);
        card.addView(description, descLp);

        // "使用 Google 继续"：白底、细边、胶囊
        LinearLayout google = new LinearLayout(activity);
        google.setOrientation(LinearLayout.HORIZONTAL);
        google.setGravity(Gravity.CENTER);
        GradientDrawable googleBg = MusePage.rounded(Color.WHITE, 29, activity);
        googleBg.setStroke(
                MusePage.dp(activity, 1),
                MusePage.night(activity) ? MusePage.line(activity) : 0xFFEBEBEB);
        google.setBackground(googleBg);
        google.setClickable(true);
        google.setFocusable(true);
        ImageView googleIcon = new ImageView(activity);
        googleIcon.setImageResource(R.drawable.lemurx_login_ic_google);
        google.addView(
                googleIcon,
                new LinearLayout.LayoutParams(MusePage.dp(activity, 20), MusePage.dp(activity, 20)));
        TextView googleLabel = new TextView(activity);
        googleLabel.setText(R.string.lemurx_login_google);
        googleLabel.setTextColor(Color.BLACK);
        googleLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        googleLabel.setTypeface(googleLabel.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams labelLp = wrap();
        labelLp.setMarginStart(MusePage.dp(activity, 10));
        google.addView(googleLabel, labelLp);
        LinearLayout.LayoutParams googleLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, MusePage.dp(activity, 52));
        googleLp.topMargin = MusePage.dp(activity, 26);
        card.addView(google, googleLp);
        google.setOnClickListener(v -> startGoogle(activity));
        sGoogleButton = google;

        TextView notNow = new TextView(activity);
        notNow.setText(R.string.lemurx_login_not_now);
        notNow.setTextColor(MusePage.night(activity) ? MusePage.muted(activity) : 0x99000000);
        notNow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        notNow.setGravity(Gravity.CENTER);
        notNow.setPadding(0, MusePage.dp(activity, 14), 0, MusePage.dp(activity, 14));
        notNow.setClickable(true);
        notNow.setFocusable(true);
        card.addView(
                notNow,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView legal = new TextView(activity);
        legal.setText(R.string.lemurx_login_legal);
        legal.setTextColor(MusePage.night(activity) ? 0x669AA6B0 : 0x4D000000);
        legal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        legal.setGravity(Gravity.CENTER);
        card.addView(legal, wrap());

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(card);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setDimAmount(0.5f);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.y = MusePage.dp(activity, 16);
            window.setAttributes(lp);
            // 两侧留 16dp，像 Lemur 的 setMargin(16)
            int margin = MusePage.dp(activity, 16);
            window.getDecorView().setPadding(margin, 0, margin, 0);
        }
        notNow.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(
                d -> {
                    if (sDialog == dialog) {
                        sDialog = null;
                        sGoogleButton = null;
                        sLaunch = null;
                        sErrors = null;
                        sBusy = false;
                    }
                });
        sDialog = dialog;
        dialog.show();
    }

    public static void dismiss() {
        ThreadUtils.assertOnUiThread();
        Dialog dialog = sDialog;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    public static void setBusy(boolean busy) {
        ThreadUtils.assertOnUiThread();
        sBusy = busy;
        View button = sGoogleButton;
        if (button == null) return;
        button.setEnabled(!busy);
        button.setAlpha(busy ? 0.5f : 1f);
    }

    /** 登录没走完：按钮恢复，并把说明交给当前这张底栏的回调。 */
    public static void fail(String message) {
        ThreadUtils.assertOnUiThread();
        setBusy(false);
        ErrorSink errors = sErrors;
        if (errors != null) errors.onError(message);
    }

    private static void startGoogle(Activity activity) {
        if (sBusy) return;
        Launch launch = sLaunch;
        if (launch == null) return;
        setBusy(true);
        LemurXGoogleSignIn.start(
                activity,
                intent -> {
                    try {
                        launch.start(intent);
                    } catch (RuntimeException e) {
                        fail(activity.getString(R.string.lemurx_login_google_failed));
                    }
                },
                LemurXSignIn::fail);
    }

    private static void onBrowserResult(Activity activity, @Nullable Intent data) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            setBusy(false);
            return;
        }
        LemurXGoogleSignIn.handleResult(
                activity,
                data,
                new LemurXGoogleSignIn.Callback() {
                    @Override
                    public void onAccount(LemurXGoogleSignIn.Account account) {
                        LemurXAccount.signInWithGoogle(
                                account,
                                error -> {
                                    setBusy(false);
                                    if (error != null) {
                                        fail(error);
                                        return;
                                    }
                                    dismiss();
                                    MuseApi.rebind();
                                });
                    }

                    @Override
                    public void onError(int statusCode, String message) {
                        setBusy(false);
                        // 用户自己取消的不用提示
                        if (!LemurXGoogleSignIn.isCancelled(statusCode)) fail(message);
                    }
                });
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
