// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.jni_zero.CalledByNative;
import org.jni_zero.JniType;
import org.jni_zero.NativeMethods;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.ui.base.WindowAndroid;
import org.chromium.ui.util.ColorUtils;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * 插件列表和卸载确认的原生桥。
 *
 * <p>列表、开关、固定、移除、开发者模式都在 {@code lemurx_extensions.cc}。卸载确认用
 * Activity 对话框，不走会被 Aero 窗口盖住的标签模态框。
 */
@NullMarked
public final class LemurXExtensions {
    /** 一次列表刷新的接收方。token 对不上的迟到图标直接丢掉。 */
    public interface Listener {
        void begin(boolean developerMode);

        void add(
                String id,
                String name,
                String description,
                String version,
                boolean enabled,
                boolean webStore,
                boolean canPin,
                boolean pinned);

        void end();

        void icon(String id, Bitmap bitmap);
    }

    private static final Map<Long, Listener> sListeners = new HashMap<>();
    private static long sNextToken;

    private static long sUninstallPtr;
    private static @Nullable Dialog sUninstallDialog;

    private LemurXExtensions() {}

    static long bind(Listener listener) {
        long token = ++sNextToken;
        sListeners.put(token, listener);
        return token;
    }

    static void unbind(long token) {
        sListeners.remove(token);
    }

    static void refresh(Profile profile, long token) {
        LemurXExtensionsJni.get().refresh(profile, token);
    }

    static boolean setEnabled(Profile profile, String id, boolean enabled) {
        return LemurXExtensionsJni.get().setEnabled(profile, id, enabled);
    }

    static boolean uninstall(Profile profile, String id) {
        return LemurXExtensionsJni.get().uninstall(profile, id);
    }

    static boolean setPinned(Profile profile, String id, boolean pinned) {
        return LemurXExtensionsJni.get().setPinned(profile, id, pinned);
    }

    static void setDeveloperMode(Profile profile, boolean enabled) {
        LemurXExtensionsJni.get().setDeveloperMode(profile, enabled);
    }

    /** 装过至少一个非组件插件时，底栏才在对话和搜索之间留出插件钮。 */
    public static boolean hasInstalled(Profile profile) {
        return LemurXExtensionsJni.get().hasInstalled(profile);
    }

    @CalledByNative
    private static void begin(long token, boolean developerMode) {
        Listener listener = sListeners.get(token);
        if (listener != null) listener.begin(developerMode);
    }

    @CalledByNative
    private static void add(
            long token,
            @JniType("std::string") String id,
            @JniType("std::string") String name,
            @JniType("std::string") String description,
            @JniType("std::string") String version,
            boolean enabled,
            boolean webStore,
            boolean canPin,
            boolean pinned) {
        Listener listener = sListeners.get(token);
        if (listener != null) {
            listener.add(id, name, description, version, enabled, webStore, canPin, pinned);
        }
    }

    @CalledByNative
    private static void end(long token) {
        Listener listener = sListeners.get(token);
        if (listener != null) listener.end();
    }

    @CalledByNative
    private static void icon(
            long token, @JniType("std::string") String id, @JniType("SkBitmap") Bitmap bitmap) {
        Listener listener = sListeners.get(token);
        if (listener != null) listener.icon(id, bitmap);
    }

    @CalledByNative
    private static void showUninstall(
            @Nullable WindowAndroid window,
            @JniType("std::string") String title,
            @JniType("std::string") String message,
            @JniType("std::string") String ok,
            @JniType("std::string") String cancel,
            long prompt) {
        Activity activity = activityFrom(window);
        if (activity == null || activity.isFinishing()) {
            LemurXExtensionsJni.get().onUninstallChoice(prompt, false);
            return;
        }
        dismissUninstall();
        sUninstallPtr = prompt;
        sUninstallDialog =
                card(
                        activity,
                        title,
                        message,
                        cancel,
                        ok,
                        () -> chooseUninstall(false),
                        () -> chooseUninstall(true));
    }

    /**
     * 和桌面卸载确认同一张卡片：圆角、右侧取消文字、蓝色实心确认。点外面或返回算取消。
     */
    static Dialog card(
            Activity activity,
            String title,
            String message,
            String cancel,
            String ok,
            Runnable onCancel,
            Runnable onOk) {
        float density = activity.getResources().getDisplayMetrics().density;
        boolean night = ColorUtils.inNightMode(activity);
        int background = night ? 0xFF292A2D : 0xFFFFFFFF;
        int textColor = night ? 0xFFE8EAED : 0xFF202124;
        int bodyColor = night ? 0xFFBDC1C6 : 0xFF3C4043;
        int accent = night ? 0xFF8AB4F8 : 0xFF1A73E8;
        int filledText = night ? 0xFF202124 : 0xFFFFFFFF;

        GradientDrawable shape = new GradientDrawable();
        shape.setColor(background);
        shape.setCornerRadius(12f * density);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(shape);
        int pad = Math.round(24f * density);
        root.setPadding(pad, pad, pad, Math.round(16f * density));

        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(textColor);
        titleView.setTextSize(16f);
        root.addView(titleView);
        if (message != null && !message.isEmpty()) {
            TextView body = new TextView(activity);
            body.setText(message);
            body.setTextColor(bodyColor);
            body.setTextSize(14f);
            body.setPadding(0, Math.round(12f * density), 0, 0);
            root.addView(body);
        }

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams buttonsLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsLp.topMargin = Math.round(20f * density);
        buttons.setLayoutParams(buttonsLp);

        Dialog dialog = new Dialog(activity);
        buttons.addView(pill(activity, cancel, accent, 0, density, v -> {
            dialog.setOnCancelListener(null);
            dialog.dismiss();
            onCancel.run();
        }));
        TextView accept = pill(activity, ok, filledText, accent, density, v -> {
            dialog.setOnCancelListener(null);
            dialog.dismiss();
            onOk.run();
        });
        LinearLayout.LayoutParams acceptLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        acceptLp.setMarginStart(Math.round(8f * density));
        accept.setLayoutParams(acceptLp);
        buttons.addView(accept);
        root.addView(buttons);

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnCancelListener(d -> onCancel.run());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            int width =
                    Math.min(
                            Math.round(448f * density),
                            activity.getResources().getDisplayMetrics().widthPixels
                                    - Math.round(32f * density));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        return dialog;
    }

    private static TextView pill(
            Activity activity,
            String label,
            int text,
            int fill,
            float density,
            View.OnClickListener click) {
        TextView view = new TextView(activity);
        view.setText(label);
        view.setTextSize(14f);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(text);
        int hPad = Math.round(16f * density);
        int vPad = Math.round(10f * density);
        view.setPadding(hPad, vPad, hPad, vPad);
        if (fill != 0) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(fill);
            bg.setCornerRadius(20f * density);
            view.setBackground(bg);
        }
        view.setOnClickListener(click);
        return view;
    }

    @CalledByNative
    private static void dismissUninstall() {
        sUninstallPtr = 0;
        Dialog dialog = sUninstallDialog;
        sUninstallDialog = null;
        if (dialog != null) {
            dialog.setOnCancelListener(null);
            dialog.dismiss();
        }
    }

    private static void chooseUninstall(boolean accepted) {
        long ptr = sUninstallPtr;
        if (ptr == 0) return;
        sUninstallPtr = 0;
        sUninstallDialog = null;
        LemurXExtensionsJni.get().onUninstallChoice(ptr, accepted);
    }

    private static @Nullable Activity activityFrom(@Nullable WindowAndroid window) {
        if (window != null) {
            WeakReference<Activity> ref = window.getActivity();
            Activity activity = ref == null ? null : ref.get();
            if (activity != null) return activity;
        }
        Activity foreground = org.chromium.chrome.browser.lemurx.LemurXConsoleShell.activity();
        return foreground;
    }

    @NativeMethods
    interface Natives {
        void refresh(@JniType("Profile*") Profile profile, long token);

        boolean setEnabled(
                @JniType("Profile*") Profile profile,
                @JniType("std::string") String id,
                boolean enabled);

        boolean uninstall(@JniType("Profile*") Profile profile, @JniType("std::string") String id);

        boolean setPinned(
                @JniType("Profile*") Profile profile,
                @JniType("std::string") String id,
                boolean pinned);

        void setDeveloperMode(@JniType("Profile*") Profile profile, boolean enabled);

        boolean hasInstalled(@JniType("Profile*") Profile profile);

        void onUninstallChoice(long prompt, boolean accepted);
    }
}
