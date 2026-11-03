// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.HapticFeedbackConstants;
import android.view.View;

import org.chromium.base.ContextUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;

/**
 * 轻触反馈：底栏、发送、建议、前进后退。用系统 haptic 通道。
 * CLOCK_TICK 最轻（导航格），KEYBOARD_TAP 稍实一点（发送 / 确认）。
 * 对话里的情绪反应走震动马达，让人感觉得到。
 */
@NullMarked
public final class LemurXHaptics {
    private LemurXHaptics() {}

    /** 底栏格、建议、前进后退：一下轻点。 */
    public static void tick(@Nullable View view) {
        if (view == null) return;
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    /** 发送、停止、打开设置：稍实一点。 */
    public static void click(@Nullable View view) {
        if (view == null) return;
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    /** 气泡旁的反应出现时：手机短震一下。马达不可用时退回稍重的触感。 */
    public static void pulse(@Nullable View view) {
        Context context = view != null ? view.getContext() : ContextUtils.getApplicationContext();
        try {
            Vibrator vibrator = vibrator(context);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE));
                return;
            }
        } catch (Exception e) {
            // 没有马达就走下面的触感
        }
        if (view == null) return;
        int feedback = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? HapticFeedbackConstants.CONFIRM
                : HapticFeedbackConstants.LONG_PRESS;
        view.performHapticFeedback(feedback);
    }

    private static @Nullable Vibrator vibrator(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager =
                    (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return manager == null ? null : manager.getDefaultVibrator();
        }
        return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }
}
