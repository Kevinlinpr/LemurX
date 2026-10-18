// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.tasks.Task;

import org.chromium.base.Log;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;

/**
 * Google 登录（Play 服务）：拿到 id token 交给 {@link LemurXAccount#signInWithGoogle}。从
 * lemur_project 的 {@code lemur.login.google.GoogleSignInHelper} 移植，去掉了统计埋点。
 *
 * <p>流程和 Lemur 浏览器一致：先把 Google SDK 缓存的账号登出（业务登出不会清它，不登出的话用户
 * 没法重新选账号），再拉起系统的账号选择页；回来后从 Intent 里解出账号，id token 为空算失败。
 *
 * <p>Web client id 是 Google Cloud 里 OAuth "Web application" 类型的客户端（requestIdToken 用），
 * 不是机密。但 Google 还要求同一个 Cloud 项目里登记这个应用的包名 + 签名 SHA-1（"Android" 类型
 * 客户端），没登记时 Play 服务返回 DEVELOPER_ERROR(10)。
 */
@NullMarked
public final class LemurXGoogleSignIn {
    private static final String TAG = "LemurXGoogle";

    /** 回来的 Google 账号：id token 必有，其余用作资料兜底。 */
    public static final class Account {
        public final String idToken;
        public final @Nullable String displayName;
        public final @Nullable String photoUrl;
        public final @Nullable String email;

        Account(
                String idToken,
                @Nullable String displayName,
                @Nullable String photoUrl,
                @Nullable String email) {
            this.idToken = idToken;
            this.displayName = displayName;
            this.photoUrl = photoUrl;
            this.email = email;
        }
    }

    /** UI 线程回调。 */
    public interface Callback {
        void onAccount(Account account);

        /**
         * @param statusCode Play 服务状态码（{@link CommonStatusCodes} / {@link
         *     GoogleSignInStatusCodes}）
         * @param message 给人看的说明（已本地化）
         */
        void onError(int statusCode, String message);
    }

    private LemurXGoogleSignIn() {}

    private static GoogleSignInClient client(Activity activity) {
        String clientId = activity.getString(R.string.lemurx_google_web_client_id);
        GoogleSignInOptions options =
                new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestIdToken(clientId)
                        .requestEmail()
                        .requestProfile()
                        .build();
        return GoogleSignIn.getClient(activity, options);
    }

    /**
     * 开始登录：先登出 SDK 缓存的账号，再把账号选择页的 Intent 交给 launcher。
     *
     * @param launch 收到 Intent 后负责 startActivityForResult 的一方
     * @param failed 起不来时的回调（已给出本地化说明）
     */
    public static void start(
            Activity activity, org.chromium.base.Callback<Intent> launch,
            org.chromium.base.Callback<String> failed) {
        final GoogleSignInClient client;
        try {
            client = client(activity);
        } catch (RuntimeException e) {
            Log.i(TAG, "init: %s", e.getMessage());
            failed.onResult(activity.getString(R.string.lemurx_login_google_failed));
            return;
        }
        client.signOut()
                .addOnCompleteListener(
                        task -> {
                            if (activity.isFinishing() || activity.isDestroyed()) return;
                            if (!task.isSuccessful()) {
                                Log.i(TAG, "pre sign-out failed: %s",
                                        String.valueOf(task.getException()));
                                failed.onResult(
                                        activity.getString(R.string.lemurx_login_google_failed));
                                return;
                            }
                            try {
                                launch.onResult(client.getSignInIntent());
                            } catch (RuntimeException e) {
                                Log.i(TAG, "launch: %s", e.getMessage());
                                failed.onResult(
                                        activity.getString(R.string.lemurx_login_google_failed));
                            }
                        });
    }

    /** 账号选择页回来：解出账号或错误。 */
    public static void handleResult(Context context, @Nullable Intent data, Callback callback) {
        Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
        try {
            GoogleSignInAccount account = task.getResult(ApiException.class);
            String token = account != null ? account.getIdToken() : null;
            if (account == null || TextUtils.isEmpty(token)) {
                Log.i(TAG, "empty id token");
                callback.onError(
                        CommonStatusCodes.ERROR,
                        context.getString(R.string.lemurx_login_google_failed));
                return;
            }
            String name = account.getDisplayName();
            if (TextUtils.isEmpty(name)) name = account.getGivenName();
            Uri photo = account.getPhotoUrl();
            callback.onAccount(
                    new Account(token, name, photo == null ? null : photo.toString(),
                            account.getEmail()));
        } catch (ApiException e) {
            int code = e.getStatusCode();
            Log.i(TAG, "sign-in status %d (%s)", code, statusName(code));
            callback.onError(code, describe(context, code));
        } catch (Exception e) {
            Log.i(TAG, "sign-in: %s", e.getMessage());
            callback.onError(
                    CommonStatusCodes.ERROR, context.getString(R.string.lemurx_login_google_failed));
        }
    }

    /** 让 Google SDK 也忘掉账号（业务登出时一起调）。 */
    public static void signOut(Activity activity) {
        try {
            client(activity).signOut();
        } catch (RuntimeException e) {
            Log.i(TAG, "sign-out: %s", e.getMessage());
        }
    }

    private static String describe(Context context, int code) {
        switch (code) {
            case CommonStatusCodes.NETWORK_ERROR:
            case CommonStatusCodes.TIMEOUT:
                return context.getString(R.string.lemurx_login_google_network_error);
            case GoogleSignInStatusCodes.SIGN_IN_CANCELLED:
            case CommonStatusCodes.CANCELED:
                return context.getString(R.string.lemurx_login_google_cancelled);
            case CommonStatusCodes.DEVELOPER_ERROR:
                return context.getString(R.string.lemurx_login_google_not_configured);
            default:
                return context.getString(R.string.lemurx_login_google_failed);
        }
    }

    /** 是不是用户自己取消的（不用弹错误）。 */
    public static boolean isCancelled(int code) {
        return code == GoogleSignInStatusCodes.SIGN_IN_CANCELLED
                || code == CommonStatusCodes.CANCELED;
    }

    private static String statusName(int code) {
        switch (code) {
            case CommonStatusCodes.DEVELOPER_ERROR:
                return "DEVELOPER_ERROR: package name / SHA-1 / OAuth client not registered";
            case CommonStatusCodes.NETWORK_ERROR:
                return "NETWORK_ERROR";
            case CommonStatusCodes.INTERNAL_ERROR:
                return "INTERNAL_ERROR";
            case CommonStatusCodes.TIMEOUT:
                return "TIMEOUT";
            case CommonStatusCodes.CANCELED:
                return "CANCELED";
            case GoogleSignInStatusCodes.SIGN_IN_CANCELLED:
                return "SIGN_IN_CANCELLED";
            case GoogleSignInStatusCodes.SIGN_IN_CURRENTLY_IN_PROGRESS:
                return "SIGN_IN_CURRENTLY_IN_PROGRESS";
            case GoogleSignInStatusCodes.SIGN_IN_FAILED:
                return "SIGN_IN_FAILED";
            default:
                return "UNKNOWN";
        }
    }
}
