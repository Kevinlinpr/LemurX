// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.firebase;

import android.content.Context;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.analytics.FirebaseAnalytics;

import org.chromium.base.Log;

/**
 * 浏览器进程里初始化 Firebase Analytics。只在 {@code lemurx_enable_firebase=true} 时编进来。
 *
 * <p>自动采集会话、首次打开和互动。不采集广告 ID，也不接管 Chromium 自己的 FCM 注册。
 * 包名和 {@code google-services.json} 不一致（测试包）时直接跳过。
 */
public final class LemurXFirebase {
    private static final String TAG = "LemurXFirebase";
    private static boolean sInitialized;

    private LemurXFirebase() {}

    public static synchronized void initialize(Context context) {
        try {
            if (sInitialized || !FirebaseConfig.PACKAGE_NAME.equals(context.getPackageName())) {
                return;
            }
            FirebaseOptions options =
                    new FirebaseOptions.Builder()
                            .setApplicationId(context.getString(R.string.google_app_id))
                            .setApiKey(context.getString(R.string.google_api_key))
                            .setProjectId(context.getString(R.string.project_id))
                            .setGcmSenderId(context.getString(R.string.gcm_defaultSenderId))
                            .setStorageBucket(context.getString(R.string.google_storage_bucket))
                            .build();
            FirebaseApp.initializeApp(context.getApplicationContext(), options);
            FirebaseAnalytics.getInstance(context.getApplicationContext())
                    .setAnalyticsCollectionEnabled(true);
            sInitialized = true;
            Log.i(TAG, "Firebase Analytics enabled");
        } catch (Throwable t) {
            Log.e(TAG, "Firebase Analytics init failed", t);
        }
    }
}
