// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx;

import android.view.HapticFeedbackConstants;
import android.view.View;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;

/**
 * 轻触反馈：底栏、发送、建议、前进后退。用系统 haptic 通道，不申请 VIBRATE。
 * CLOCK_TICK 最轻（导航格），KEYBOARD_TAP 稍实一点（发送 / 确认）。
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
}
