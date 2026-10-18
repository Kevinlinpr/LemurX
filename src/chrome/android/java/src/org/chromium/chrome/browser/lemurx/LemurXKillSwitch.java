// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;

import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.lemurx.agent.LemurXDeviceBridge;
import org.chromium.chrome.browser.lemurx.muse.MuseApi;
import org.json.JSONObject;

/**
 * 测试包远程关停。服务端设 {@code AERO_TEST_DISABLED=1} 后，健康检查和所有
 * {@code /api}、{@code /lemurx} 接口都会拒绝；这里挡住界面并停掉设备桥，
 * 散发出去的 APK 就连不上智能体。
 */
@NullMarked
public final class LemurXKillSwitch {
    private static final String TAG = "LemurXKill";
    private static final long RECHECK_MS = 45_000;

    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static final Runnable sRecheck = LemurXKillSwitch::probe;

    private static @Nullable Activity sActivity;
    private static boolean sDisabled;
    private static boolean sShown;

    private LemurXKillSwitch() {}

    public static boolean isDisabled() {
        return sDisabled;
    }

    /** 窗口挂上时开始探活，之后隔一阵再问一次。 */
    public static void attach(Activity activity) {
        ThreadUtils.assertOnUiThread();
        sActivity = activity;
        sHandler.removeCallbacks(sRecheck);
        probe();
    }

    public static void detach(Activity activity) {
        if (sActivity != activity) return;
        sHandler.removeCallbacks(sRecheck);
        sActivity = null;
    }

    /** 轮询 / 会话拿到 410 TEST_DISABLED 时由网络层调入。任意线程。 */
    public static void activate() {
        if (!ThreadUtils.runningOnUiThread()) {
            ThreadUtils.postOnUiThread(LemurXKillSwitch::activate);
            return;
        }
        if (sDisabled) {
            show();
            return;
        }
        sDisabled = true;
        LemurXDeviceBridge.haltFromKill();
        show();
    }

    static void probe() {
        MuseApi.get()
                .probeHealth(
                        (json, error) -> {
                            if (json != null && isDisabledPayload(json)) {
                                activate();
                            } else if (json != null && json.optBoolean("ok", false)) {
                                sDisabled = false;
                                sShown = false;
                            }
                            sHandler.postDelayed(sRecheck, RECHECK_MS);
                        });
    }

    public static boolean isDisabledPayload(@Nullable JSONObject json) {
        if (json == null) return false;
        if (json.optBoolean("disabled", false)) return true;
        String code = json.optString("code", "");
        if ("TEST_DISABLED".equals(code)) return true;
        String error = json.optString("error", "");
        return "TEST_DISABLED".equals(error);
    }

    public static boolean isDisabledStatus(int status, @Nullable String body) {
        if (status == 410) return true;
        if (body == null || body.isEmpty()) return false;
        try {
            return isDisabledPayload(new JSONObject(body));
        } catch (Exception e) {
            return false;
        }
    }

    private static void show() {
        Activity activity = sActivity;
        if (sShown || activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        sShown = true;
        Log.i(TAG, "test build disabled by server");
        try {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.lemurx_muse_test_disabled_title)
                    .setMessage(R.string.lemurx_muse_test_disabled_message)
                    .setCancelable(false)
                    .setPositiveButton(
                            R.string.lemurx_muse_test_disabled_ok,
                            (d, w) -> {
                                if (!activity.isFinishing()) activity.finish();
                            })
                    .show();
        } catch (Exception e) {
            Log.i(TAG, "dialog: %s", e.getMessage());
            if (!activity.isFinishing()) activity.finish();
        }
    }
}
