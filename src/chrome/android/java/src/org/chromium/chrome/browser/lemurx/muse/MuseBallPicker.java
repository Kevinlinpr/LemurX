// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.lemurx.LemurXHaptics;

/**
 * 32 种 Agent 表情：上面一颗预览球，下面按生命周期 / 情绪 / 工作状态分组点选。
 */
@NullMarked
final class MuseBallPicker {
    interface Listener {
        void onPick(String emotionId);
    }

    private MuseBallPicker() {}

    static void show(Context context, String currentId, Listener listener) {
        Activity activity = activityOf(context);
        if (activity == null || activity.isFinishing()) return;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(MusePage.rounded(MusePage.bg(activity), 22, activity));
        int pad = MusePage.dp(activity, 16);
        root.setPadding(pad, pad, pad, pad);

        MuseBallView preview = new MuseBallView(activity, 96);
        preview.setEmotion(currentId);
        LinearLayout.LayoutParams previewLp =
                new LinearLayout.LayoutParams(MusePage.dp(activity, 96), MusePage.dp(activity, 96));
        previewLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(preview, previewLp);

        TextView title = new TextView(activity);
        title.setText(R.string.lemurx_agent_ball_pick);
        title.setTextColor(MusePage.text(activity));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = MusePage.dp(activity, 8);
        titleLp.bottomMargin = MusePage.dp(activity, 10);
        root.addView(title, titleLp);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        String lastGroup = "";
        LinearLayout row = null;
        int inRow = 0;
        for (MuseBallEmotion emotion : MuseBallEmotion.ALL) {
            if (!emotion.group.equals(lastGroup)) {
                lastGroup = emotion.group;
                inRow = 0;
                row = null;
                TextView heading = new TextView(activity);
                heading.setText(MuseBallEmotion.groupLabel(activity, emotion.group));
                heading.setTextColor(MusePage.muted(activity));
                heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                heading.setTypeface(Typeface.DEFAULT_BOLD);
                LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                headLp.topMargin = MusePage.dp(activity, 10);
                headLp.bottomMargin = MusePage.dp(activity, 6);
                list.addView(heading, headLp);
            }
            if (row == null || inRow == 3) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                list.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                inRow = 0;
            }
            row.addView(chip(activity, emotion, currentId, preview, listener),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            inRow++;
        }
        scroll.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        dialog.setCancelable(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x66000000));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.78f);
            window.setAttributes(lp);
        }
        dialog.show();
    }

    private static TextView chip(Activity activity, MuseBallEmotion emotion, String currentId,
            MuseBallView preview, Listener listener) {
        TextView chip = new TextView(activity);
        chip.setText(emotion.label(activity));
        chip.setGravity(Gravity.CENTER);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        boolean on = emotion.id.equals(currentId);
        chip.setTextColor(on ? MusePage.onAccent(activity) : MusePage.text(activity));
        chip.setBackground(MusePage.rounded(
                on ? MusePage.accent(activity) : MusePage.surface(activity), 12, activity));
        int v = MusePage.dp(activity, 10);
        int h = MusePage.dp(activity, 6);
        chip.setPadding(h, v, h, v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(MusePage.dp(activity, 3), MusePage.dp(activity, 3), MusePage.dp(activity, 3),
                MusePage.dp(activity, 3));
        chip.setLayoutParams(lp);
        chip.setOnClickListener(
                v1 -> {
                    LemurXHaptics.tick(v1);
                    preview.setEmotion(emotion.id);
                    listener.onPick(emotion.id);
                });
        return chip;
    }

    private static @Nullable Activity activityOf(Context context) {
        Context walk = context;
        while (walk instanceof ContextWrapper) {
            if (walk instanceof Activity) return (Activity) walk;
            walk = ((ContextWrapper) walk).getBaseContext();
        }
        return null;
    }
}
