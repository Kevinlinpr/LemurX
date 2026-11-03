// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.chromium.base.ThreadUtils;
import org.chromium.chrome.browser.lemurx.LemurXConsoleShell;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;

/**
 * 冷启动时先盖住浏览器自己的工具栏。这一层就是空的 Agent 对话：底下一行输入和导航，
 * 真正的对话画出来之后再撤掉。
 */
@NullMarked
public final class LemurXBootCover {
    private static @Nullable View sCover;
    private static int sTries;

    private LemurXBootCover() {}

    /** 布局还没挂上时，窗口底色先换成对话的底色，避免闪出浏览器的灰底。 */
    public static void prepare(Activity activity) {
        ThreadUtils.assertOnUiThread();
        try {
            activity.getWindow().setBackgroundDrawable(new ColorDrawable(MusePage.bg(activity)));
        } catch (Exception e) {
            // 窗口还没建好就等 show()
        }
    }

    /** 内容区已经有了：盖上一张对话的骨架，挡住地址栏。 */
    public static void show(Activity activity) {
        ThreadUtils.assertOnUiThread();
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (sCover != null && sCover.getParent() != null) {
            sCover.bringToFront();
            return;
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;

        FrameLayout cover = new FrameLayout(activity);
        cover.setBackgroundColor(MusePage.bg(activity));
        cover.setClickable(true);

        LinearLayout dock = new LinearLayout(activity);
        dock.setOrientation(LinearLayout.VERTICAL);
        int side = dp(activity, 16);
        dock.setPadding(side, 0, side, dp(activity, 18));

        View composer = new View(activity);
        composer.setBackground(MusePage.rounded(MusePage.surface(activity), 26, activity));
        LinearLayout.LayoutParams composerLp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 52));
        composerLp.bottomMargin = dp(activity, 12);
        dock.addView(composer, composerLp);

        View nav = new View(activity);
        nav.setBackground(MusePage.rounded(MusePage.surface(activity), 28, activity));
        dock.addView(
                nav,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 62)));

        cover.addView(
                dock,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM));
        cover.setOnApplyWindowInsetsListener(
                (v, insets) -> {
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
                    dock.setPadding(side, 0, side, dp(activity, 18) + bottom);
                    return insets;
                });

        content.addView(
                cover,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sCover = cover;
        cover.bringToFront();
        cover.requestApplyInsets();
    }

    public static @Nullable View view() {
        return sCover != null && sCover.getParent() != null ? sCover : null;
    }

    /** 对话页挂上之后再揭开。揭太早会露出浏览器那一层。 */
    public static void releaseWhenReady(Activity activity) {
        ThreadUtils.assertOnUiThread();
        sTries = 0;
        View cover = sCover;
        if (cover == null) return;
        cover.post(new Runnable() {
            @Override
            public void run() {
                if (sCover == null) return;
                if (LemurXConsoleShell.startupCoverReady(chatReady(activity)) || sTries++ > 80) {
                    dismiss();
                    return;
                }
                View again = sCover;
                if (again != null) again.postDelayed(this, 50);
            }
        });
    }

    private static boolean chatReady(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        return content != null && hasChat(content);
    }

    private static boolean hasChat(View view) {
        if (view instanceof MuseChatView && view.isAttachedToWindow()) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasChat(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static void dismiss() {
        View cover = sCover;
        sCover = null;
        if (cover != null && cover.getParent() instanceof ViewGroup) {
            ((ViewGroup) cover.getParent()).removeView(cover);
        }
    }

    private static int dp(Activity activity, int value) {
        return MusePage.dp(activity, value);
    }
}
