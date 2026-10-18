// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.chromium.base.ContextUtils;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LemurX 账号：Lemur 浏览器账号系统（paipai 网关）的精简客户端，从 lemur_project 的
 * {@code chrome/lemur/login}（PaipaiAuthApi / PaipaiUserApi / PaipaiDeviceInterceptor /
 * PaipaiSessionHelper / OverseasAuthPresenter）迁移而来，去掉了 Retrofit / RxJava / Gson，用
 * HttpURLConnection + org.json。
 *
 * <p>登录方式和 Lemur 浏览器的海外版一样：只有 Google 登录（{@link LemurXGoogleSignIn} 通过 Play
 * 服务拿到 id token），不再有邮箱 + 密码。接口和原项目一致（都挂在 {@code <网关>/api/v1/} 下）：
 *
 * <ul>
 *   <li>{@code POST user/browser/login} {loginType:3, username:<Google id token>}——对应
 *       OverseasAuthPresenter.loginWithGoogle。Google 账号的昵称 / 头像 / 邮箱先存成本地兜底，
 *       服务器资料里对应字段为空时沿用（PaipaiAccountSyncer.rememberGoogleFallback 的做法）。
 *   <li>{@code POST user/browser/token/refresh} {refreshToken}
 *   <li>{@code POST user/browser/logout}
 *   <li>{@code GET user/current/user} → {id, userNo, username, nickname, avatar, email, bio}
 * </ul>
 *
 * <p>请求头：{@code X-Device-ID}（首次生成的安装 id）、登录接口以外带
 * {@code Authorization: Bearer <token>}。token / refreshToken 存在私有 SharedPreferences，密码不落盘，
 * 任何地方都不打日志。
 *
 * <p>没迁过来的：Google 登录（依赖 Play 服务 sign-in 库）、修改密码 / 找回密码 / 绑定手机邮箱的
 * 验证码流程、邀请码、注销账号、与 Chrome 同步的联动。网关地址见 {@link #DEFAULT_GATEWAY}，可在
 * 设置里改。
 */
@NullMarked
public final class LemurXAccount {
    private static final String PREF_FILE = "lemurx_account";
    private static final String KEY_GATEWAY = "gateway";
    private static final String KEY_DEVICE_ID = "installation_id";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_REFRESH = "refresh_token";
    private static final String KEY_NICKNAME = "nickname";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_USER_NO = "user_no";
    private static final String KEY_AVATAR = "avatar";

    /** lemur_project chrome/lemur/backend_config.gni 里的 PAIPAI_BASE_URL 默认值。 */
    public static final String DEFAULT_GATEWAY = "http://192.168.1.111:6666";

    private static final String API_PREFIX = "/api/v1/";
    /** PaipaiAuthApi.LOGIN_TYPE_GOOGLE。 */
    private static final int LOGIN_TYPE_GOOGLE = 3;
    private static final int TIMEOUT_MS = 15_000;

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();

    /** UI 线程回调；error 为 null 表示成功。 */
    public interface Callback {
        void onResult(@Nullable String error);
    }

    /** 已登录账号的展示信息。 */
    public static final class Profile {
        public final String nickname;
        public final String email;
        public final String userNo;
        public final String avatar;

        Profile(String nickname, String email, String userNo, String avatar) {
            this.nickname = nickname;
            this.email = email;
            this.userNo = userNo;
            this.avatar = avatar;
        }

        public String displayName() {
            if (!TextUtils.isEmpty(nickname)) return nickname;
            if (!TextUtils.isEmpty(email)) return email;
            return userNo;
        }
    }

    private LemurXAccount() {}

    private static SharedPreferences prefs() {
        return ContextUtils.getApplicationContext()
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- 状态

    public static String gateway() {
        String url = prefs().getString(KEY_GATEWAY, null);
        if (TextUtils.isEmpty(url)) url = DEFAULT_GATEWAY;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    public static void setGateway(@Nullable String url) {
        SharedPreferences.Editor editor = prefs().edit();
        if (TextUtils.isEmpty(url)) {
            editor.remove(KEY_GATEWAY);
        } else {
            editor.putString(KEY_GATEWAY, url.trim());
        }
        editor.apply();
    }

    public static boolean isSignedIn() {
        return !TextUtils.isEmpty(prefs().getString(KEY_TOKEN, null));
    }

    public static @Nullable Profile profile() {
        if (!isSignedIn()) return null;
        SharedPreferences p = prefs();
        return new Profile(
                p.getString(KEY_NICKNAME, ""),
                p.getString(KEY_EMAIL, ""),
                p.getString(KEY_USER_NO, ""),
                p.getString(KEY_AVATAR, ""));
    }

    private static String deviceId() {
        SharedPreferences p = prefs();
        String id = p.getString(KEY_DEVICE_ID, null);
        if (TextUtils.isEmpty(id)) {
            id = UUID.randomUUID().toString();
            p.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    private static void clearSession() {
        prefs().edit()
                .remove(KEY_TOKEN)
                .remove(KEY_REFRESH)
                .remove(KEY_NICKNAME)
                .remove(KEY_EMAIL)
                .remove(KEY_USER_NO)
                .remove(KEY_AVATAR)
                .apply();
    }

    // ---------------------------------------------------------------- 动作

    /**
     * 用 Google 账号登录：把 Play 服务给的 id token 交给网关换 LemurX 会话。
     *
     * @param google Google 账号信息（id token 必有；昵称 / 头像 / 邮箱用作资料兜底）
     */
    public static void signInWithGoogle(LemurXGoogleSignIn.Account google, Callback callback) {
        ThreadUtils.assertOnUiThread();
        sExecutor.execute(
                () -> {
                    String error = null;
                    try {
                        JSONObject body = new JSONObject();
                        body.put("loginType", LOGIN_TYPE_GOOGLE);
                        body.put("username", google.idToken);
                        JSONObject response = call("POST", "user/browser/login", body, false);
                        if (!ok(response)) throw new IOException(message(response));
                        storeAuth(response.optJSONObject("data"));
                        // 先记 Google 资料，服务器那份有值再覆盖。
                        prefs().edit()
                                .putString(KEY_NICKNAME, nonNull(google.displayName))
                                .putString(KEY_EMAIL, nonNull(google.email))
                                .putString(KEY_AVATAR, nonNull(google.photoUrl))
                                .apply();
                        try {
                            fetchProfileBlocking();
                        } catch (IOException e) {
                            // 登录已经成功；资料拉不到不算失败，下次 onResume 再刷。
                        }
                    } catch (Exception e) {
                        error = describe(e);
                    }
                    final String err = error;
                    ThreadUtils.postOnUiThread(() -> callback.onResult(err));
                });
    }

    private static String nonNull(@Nullable String s) {
        return s == null ? "" : s;
    }

    /** 刷新账号资料（昵称 / 邮箱）；token 失效时尝试 refresh，再不行就登出。 */
    public static void refreshProfile(Callback callback) {
        ThreadUtils.assertOnUiThread();
        if (!isSignedIn()) {
            callback.onResult(null);
            return;
        }
        sExecutor.execute(
                () -> {
                    String error = null;
                    try {
                        fetchProfileBlocking();
                    } catch (Exception e) {
                        error = describe(e);
                    }
                    final String err = error;
                    ThreadUtils.postOnUiThread(() -> callback.onResult(err));
                });
    }

    public static void signOut(Callback callback) {
        ThreadUtils.assertOnUiThread();
        sExecutor.execute(
                () -> {
                    try {
                        call("POST", "user/browser/logout", new JSONObject(), true);
                    } catch (Exception e) {
                        // 服务器不可达也照样登出本机
                    }
                    clearSession();
                    ThreadUtils.postOnUiThread(() -> callback.onResult(null));
                });
    }

    // ---------------------------------------------------------------- 后台实现

    private static void fetchProfileBlocking() throws IOException, JSONException {
        JSONObject response = call("GET", "user/current/user", null, true);
        if (response.optInt("httpStatus") == 401 || code(response) == 401) {
            if (!refreshBlocking()) {
                clearSession();
                throw new IOException("Signed out: session expired");
            }
            response = call("GET", "user/current/user", null, true);
        }
        if (!ok(response)) throw new IOException(message(response));
        JSONObject data = response.optJSONObject("data");
        if (data == null) return;
        // 服务器字段为空时保留本地（Google 账号）那份。
        SharedPreferences p = prefs();
        String nickname = first(data, "nickname");
        String email = first(data, "email", "username");
        String avatar = first(data, "avatar");
        p.edit()
                .putString(KEY_NICKNAME, nickname.isEmpty() ? p.getString(KEY_NICKNAME, "") : nickname)
                .putString(KEY_EMAIL, email.isEmpty() ? p.getString(KEY_EMAIL, "") : email)
                .putString(KEY_USER_NO, data.optString("userNo", ""))
                .putString(KEY_AVATAR, avatar.isEmpty() ? p.getString(KEY_AVATAR, "") : avatar)
                .apply();
    }

    private static boolean refreshBlocking() {
        String refresh = prefs().getString(KEY_REFRESH, null);
        if (TextUtils.isEmpty(refresh)) return false;
        try {
            JSONObject body = new JSONObject();
            body.put("refreshToken", refresh);
            JSONObject response = call("POST", "user/browser/token/refresh", body, false);
            if (!ok(response)) return false;
            storeAuth(response.optJSONObject("data"));
            return isSignedIn();
        } catch (Exception e) {
            return false;
        }
    }

    private static void storeAuth(@Nullable JSONObject data) throws IOException {
        if (data == null) throw new IOException("Empty sign-in response");
        String token = first(data, "token", "accessToken", "access_token");
        if (TextUtils.isEmpty(token)) throw new IOException("Sign-in response has no token");
        String refresh = first(data, "refreshToken", "refreshtoken", "refresh_token");
        SharedPreferences.Editor editor = prefs().edit().putString(KEY_TOKEN, token);
        if (!TextUtils.isEmpty(refresh)) editor.putString(KEY_REFRESH, refresh);
        editor.apply();
    }

    private static String first(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty() && !"null".equals(v)) return v;
        }
        return "";
    }

    private static int code(JSONObject response) {
        return response.optInt("code", -1);
    }

    private static boolean ok(JSONObject response) {
        int code = code(response);
        int http = response.optInt("httpStatus", 200);
        return http < 400 && (code == 0 || code == 200);
    }

    private static String message(JSONObject response) {
        String m = response.optString("message", "");
        if (m.isEmpty()) m = response.optString("msg", "");
        if (m.isEmpty()) m = "Request failed (" + response.optInt("httpStatus", 0) + ")";
        return m;
    }

    /** 一次请求；返回体 JSON 附加 httpStatus。非 JSON 的返回体包成 {httpStatus, message}。 */
    private static JSONObject call(
            String method, String path, @Nullable JSONObject body, boolean auth) throws IOException {
        URL url = new URL(gateway() + API_PREFIX + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("X-Device-ID", deviceId());
            if (auth) {
                String token = prefs().getString(KEY_TOKEN, null);
                if (!TextUtils.isEmpty(token)) {
                    connection.setRequestProperty("Authorization", "Bearer " + token.trim());
                }
            }
            if (body != null) {
                connection.setDoOutput(true);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            String text = readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            JSONObject json;
            try {
                json = new JSONObject(text.trim().isEmpty() ? "{}" : text);
            } catch (JSONException e) {
                json = new JSONObject();
                try {
                    json.put("message", "HTTP " + status);
                } catch (JSONException ignored) {
                    // 不会发生
                }
            }
            try {
                json.put("httpStatus", status);
            } catch (JSONException ignored) {
                // 不会发生
            }
            return json;
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(@Nullable InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private static String describe(Exception e) {
        if (e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException) {
            return "Cannot reach the account server (" + gateway() + ")";
        }
        if (e instanceof java.net.SocketTimeoutException) return "The account server timed out";
        String message = e.getMessage();
        return TextUtils.isEmpty(message) ? e.getClass().getSimpleName() : message;
    }
}
