// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
 *       这个 token 是浏览器会话，只能调资料接口，不能拿去登记手机。
 *   <li>{@code POST user/aero/login} 同一份 Google id token，换智能体会话用的 aero 票据。
 *       票据和浏览器 token 分开存。测服只认这张票据签发的 {@code user:} 会话，游客会话登记会被拒绝。
 *   <li>{@code POST user/browser/token/refresh} {refreshToken}
 *   <li>{@code POST user/browser/logout}
 *   <li>{@code GET user/current/user} → {id, userNo, username, nickname, avatar, email, bio}
 * </ul>
 *
 * <p>请求头：{@code X-Device-ID}（首次生成的安装 id）、登录接口以外带
 * {@code Authorization: Bearer <token>}。token / refreshToken / aero 票据存在私有 SharedPreferences，
 * 密码和 Google id token 不落盘，任何地方都不打日志。
 *
 * <p>没迁过来的：修改密码 / 找回密码 / 绑定手机邮箱的验证码流程、邀请码、注销账号、与 Chrome
 * 同步的联动。网关地址见 {@link #DEFAULT_GATEWAY}，可在设置里改。
 */
@NullMarked
public final class LemurXAccount {
    private static final String TAG = "LemurXAccount";
    private static final String PREF_FILE = "lemurx_account";
    private static final String KEY_GATEWAY = "gateway";
    private static final String KEY_DEVICE_ID = "installation_id";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_REFRESH = "refresh_token";
    /** paipai 签的 aero 票据。只交给智能体会话，不当浏览器接口的 Bearer。 */
    private static final String KEY_AERO_TICKET = "aero_ticket";
    /** 静默补票据失败后的冷却，避免登记退避期间反复打 Google 和网关。 */
    private static long sNextSilentMs;
    private static final String KEY_NICKNAME = "nickname";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_USER_NO = "user_no";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_AVATAR = "avatar";

    /**
     * 账号网关。登录接口挂在这个地址的 {@code /api/v1/} 下，所以这里不带路径。
     * 测服是 http://192.168.1.111:6666/api/v1 。
     */
    public static final String DEFAULT_GATEWAY = "http://192.168.1.111:6666";
    /** 上一版写进偏好里的生产网关。读到就清掉，否则改默认值也不会生效。 */
    private static final String PREVIOUS_GATEWAY = "https://gateway.paipai.life";

    private static final String API_PREFIX = "/api/v1/";
    /** PaipaiAuthApi.LOGIN_TYPE_GOOGLE。 */
    private static final int LOGIN_TYPE_GOOGLE = 3;
    private static final int TIMEOUT_MS = 15_000;

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static final int AVATAR_BYTES_MAX = 512_000;
    /** 同一张头像一天内不再请求。磁盘上的图跨进程还在。 */
    private static final long AVATAR_DISK_MS = 24L * 60L * 60L * 1000L;
    /** 资料接口不用每次打开侧边栏都打。头像走磁盘缓存。 */
    private static final long PROFILE_REFRESH_MS = 30L * 60L * 1000L;
    private static @Nullable String sAvatarCacheKey;
    private static @Nullable Drawable sAvatarCache;
    private static long sProfileFetchedAt;

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
        if (PREVIOUS_GATEWAY.equals(url)) {
            prefs().edit().remove(KEY_GATEWAY).apply();
            url = null;
        }
        if (TextUtils.isEmpty(url)) url = DEFAULT_GATEWAY;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.endsWith("/api/v1")) url = url.substring(0, url.length() - "/api/v1".length());
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

    /** 网关用户 id，给智能体当登录主人。未登录为 null。 */
    public static @Nullable String accountId() {
        if (!isSignedIn()) return null;
        String id = prefs().getString(KEY_USER_ID, null);
        return TextUtils.isEmpty(id) ? null : id;
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

    /**
     * 设置页头像。同一账号的照片落在缓存目录里，一天内再打开侧边栏或设置不再请求。
     * 没有照片就用名字首字。只走 https。回调在 UI 线程。
     */
    public static void loadAvatar(int sizePx, org.chromium.base.Callback<Drawable> callback) {
        ThreadUtils.assertOnUiThread();
        Profile profile = profile();
        if (profile == null) {
            callback.onResult(null);
            return;
        }
        Resources resources = ContextUtils.getApplicationContext().getResources();
        String url = profile.avatar == null ? "" : profile.avatar.trim();
        Drawable placeholder = initialsAvatar(resources, profile.displayName(), sizePx);
        if (TextUtils.isEmpty(url)) {
            callback.onResult(placeholder);
            return;
        }
        if (url.equals(sAvatarCacheKey) && sAvatarCache != null) {
            callback.onResult(sAvatarCache);
            return;
        }
        String userId = accountId();
        File disk = userId == null ? null : avatarFile(userId);
        if (disk != null && disk.isFile() && disk.length() > 0) {
            Drawable stored = circleFromFile(resources, disk, sizePx);
            if (stored != null) {
                sAvatarCacheKey = url;
                sAvatarCache = stored;
                callback.onResult(stored);
                if (System.currentTimeMillis() - disk.lastModified() < AVATAR_DISK_MS) return;
            }
        } else {
            callback.onResult(placeholder);
        }
        final String want = url;
        final String name = profile.displayName();
        final File target = disk;
        sExecutor.execute(
                () -> {
                    Drawable photo = downloadAvatar(resources, want, sizePx, target);
                    Drawable result = photo != null ? photo : initialsAvatar(resources, name, sizePx);
                    ThreadUtils.postOnUiThread(
                            () -> {
                                Profile now = profile();
                                if (now == null || !want.equals(now.avatar == null ? "" : now.avatar.trim())) {
                                    return;
                                }
                                sAvatarCacheKey = want;
                                sAvatarCache = result;
                                callback.onResult(result);
                            });
                });
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
        sAvatarCacheKey = null;
        sAvatarCache = null;
        sProfileFetchedAt = 0L;
        String userId = prefs().getString(KEY_USER_ID, null);
        if (!TextUtils.isEmpty(userId)) {
            File disk = avatarFile(userId);
            if (disk.isFile() && !disk.delete()) {
                Log.i(TAG, "avatar cache: still there");
            }
        }
        prefs().edit()
                .remove(KEY_TOKEN)
                .remove(KEY_REFRESH)
                .remove(KEY_AERO_TICKET)
                .remove(KEY_NICKNAME)
                .remove(KEY_EMAIL)
                .remove(KEY_USER_NO)
                .remove(KEY_USER_ID)
                .remove(KEY_AVATAR)
                .apply();
    }

    // ---------------------------------------------------------------- 动作

    /**
     * 用 Google 账号登录：id token 先换浏览器会话，再换一张 aero 票据给智能体。
     * 票据换不来时账号登录仍然算成功，登记手机会在下次静默补票时再试。
     *
     * @param google Google 账号信息（id token 必有；昵称 / 头像 / 邮箱用作资料兜底）
     */
    public static void signInWithGoogle(LemurXGoogleSignIn.Account google, Callback callback) {
        ThreadUtils.assertOnUiThread();
        sExecutor.execute(
                () -> {
                    String error = null;
                    try {
                        signInWithGoogleBlocking(google);
                    } catch (Exception e) {
                        error = describe(e);
                    }
                    final String err = error;
                    ThreadUtils.postOnUiThread(() -> callback.onResult(err));
                });
    }

    /**
     * 本机还没有 aero 票据时，用 Play 服务缓存的 Google 账号静默补上。
     * 浏览器会话也不在的话，连同账号登录一起补。必须在后台线程调用。
     */
    public static void ensureAeroTicket(@Nullable Activity activity) {
        if (aeroTicket() != null) return;
        long now = System.currentTimeMillis();
        if (now < sNextSilentMs) return;
        sNextSilentMs = now + 60_000L;
        Log.i(TAG, "aero ticket: signedIn=%s", isSignedIn());
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Log.i(TAG, "aero ticket: no foreground activity");
            return;
        }
        LemurXGoogleSignIn.Account google = LemurXGoogleSignIn.silentAccount(activity);
        if (google == null) {
            Log.i(TAG, "aero ticket: google silent sign-in unavailable; sign in again");
            return;
        }
        try {
            if (isSignedIn()) {
                // 换账号后旧票据作废。新票据要等 aero 登录成功才写回去。
                prefs().edit().remove(KEY_AERO_TICKET).commit();
                storeAeroTicket(exchangeAeroTicket(google.idToken));
            } else {
                signInWithGoogleBlocking(google);
            }
            if (aeroTicket() != null) Log.i(TAG, "aero ticket: stored");
        } catch (Exception e) {
            Log.w(TAG, "aero ticket: %s", e.getMessage());
        }
    }

    /** 浏览器登录成功才算登录成功。aero 票据换不来只记日志，留给下次静默补票。 */
    private static void signInWithGoogleBlocking(LemurXGoogleSignIn.Account google)
            throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("loginType", LOGIN_TYPE_GOOGLE);
        body.put("username", google.idToken);
        JSONObject response = call("POST", "user/browser/login", body, false);
        if (!ok(response)) throw new IOException(message(response));
        storeAuth(response.optJSONObject("data"));
        // 换账号后旧票据作废。新票据要等 aero 登录成功才写回去。
        prefs().edit().remove(KEY_AERO_TICKET).commit();
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
        try {
            storeAeroTicket(exchangeAeroTicket(google.idToken));
        } catch (Exception e) {
            Log.w(TAG, "aero ticket: %s", e.getMessage());
        }
    }

    /** 当前账号的 aero 票据。未登录，或这次登录还没换到票据时，返回 null。 */
    public static @Nullable String aeroTicket() {
        if (!isSignedIn()) return null;
        String ticket = prefs().getString(KEY_AERO_TICKET, null);
        return TextUtils.isEmpty(ticket) ? null : ticket;
    }

    /** 用 Google id token 换 aero 票据。调用方负责保存；这里不落盘，也不写日志。 */
    private static String exchangeAeroTicket(String idToken) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("loginType", LOGIN_TYPE_GOOGLE);
        body.put("username", idToken);
        JSONObject response = call("POST", "user/aero/login", body, false);
        if (!ok(response)) throw new IOException(message(response));
        JSONObject data = response.optJSONObject("data");
        if (data == null) throw new IOException("Empty sign-in response");
        String ticket = first(data, "token", "accessToken", "access_token");
        if (TextUtils.isEmpty(ticket)) throw new IOException("Sign-in response has no token");
        return ticket;
    }

    private static void storeAeroTicket(String ticket) {
        prefs().edit().putString(KEY_AERO_TICKET, ticket).commit();
    }

    private static String nonNull(@Nullable String s) {
        return s == null ? "" : s;
    }

    /** 刷新账号资料（昵称 / 邮箱）。半小时内重复打开不再打接口。token 失效时尝试 refresh，再不行就登出。 */
    public static void refreshProfile(Callback callback) {
        ThreadUtils.assertOnUiThread();
        if (!isSignedIn()) {
            callback.onResult(null);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - sProfileFetchedAt < PROFILE_REFRESH_MS) {
            callback.onResult(null);
            return;
        }
        sProfileFetchedAt = now;
        sExecutor.execute(
                () -> {
                    String error = null;
                    try {
                        fetchProfileBlocking();
                    } catch (Exception e) {
                        error = describe(e);
                    }
                    final String err = error;
                    ThreadUtils.postOnUiThread(
                            () -> {
                                if (err != null) sProfileFetchedAt = 0L;
                                callback.onResult(err);
                            });
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
        String userId = first(data, "id", "userId", "user_id");
        if (userId.isEmpty()) userId = data.optString("userNo", "");
        SharedPreferences.Editor editor = p.edit()
                .putString(KEY_NICKNAME, nickname.isEmpty() ? p.getString(KEY_NICKNAME, "") : nickname)
                .putString(KEY_EMAIL, email.isEmpty() ? p.getString(KEY_EMAIL, "") : email)
                .putString(KEY_USER_NO, data.optString("userNo", ""))
                .putString(KEY_AVATAR, avatar.isEmpty() ? p.getString(KEY_AVATAR, "") : avatar);
        if (!userId.isEmpty() && userId.matches("[A-Za-z0-9_-]{1,64}")) {
            editor.putString(KEY_USER_ID, userId);
        }
        editor.apply();
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

    // ---------------------------------------------------------------- 头像

    private static @Nullable Drawable downloadAvatar(
            Resources resources, String url, int sizePx, @Nullable File disk) {
        HttpURLConnection connection = null;
        try {
            URL parsed = new URL(sizedAvatarUrl(url));
            if (!"https".equalsIgnoreCase(parsed.getProtocol())) return null;
            connection = (HttpURLConnection) parsed.openConnection();
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(8_000);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(true);
            if (connection.getResponseCode() != 200) return null;
            byte[] bytes = readBytes(connection.getInputStream(), AVATAR_BYTES_MAX);
            if (bytes == null || bytes.length == 0) return null;
            if (disk != null) writeAvatar(disk, bytes);
            return circleFromBytes(resources, bytes, sizePx);
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static File avatarFile(String userId) {
        File dir = new File(ContextUtils.getApplicationContext().getCacheDir(), "aero-avatar");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.i(TAG, "avatar cache: no directory");
        }
        return new File(dir, Integer.toHexString(userId.hashCode()) + ".img");
    }

    private static void writeAvatar(File disk, byte[] bytes) {
        File tmp = new File(disk.getParentFile(), disk.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        } catch (IOException e) {
            if (tmp.exists() && !tmp.delete()) Log.i(TAG, "avatar cache: tmp left");
            return;
        }
        if (!tmp.renameTo(disk)) {
            if (tmp.exists() && !tmp.delete()) Log.i(TAG, "avatar cache: rename failed");
        }
    }

    private static @Nullable Drawable circleFromFile(Resources resources, File disk, int sizePx) {
        byte[] bytes;
        try (InputStream in = new java.io.FileInputStream(disk)) {
            bytes = readBytes(in, AVATAR_BYTES_MAX);
        } catch (IOException e) {
            return null;
        }
        if (bytes == null) return null;
        return circleFromBytes(resources, bytes, sizePx);
    }

    private static @Nullable Drawable circleFromBytes(Resources resources, byte[] bytes, int sizePx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        BitmapFactory.Options real = new BitmapFactory.Options();
        real.inSampleSize = avatarSample(bounds.outWidth, bounds.outHeight, sizePx);
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, real);
        if (decoded == null) return null;
        return circleAvatar(resources, decoded, sizePx);
    }

    /** Google 头像 URL 常见 `=s96-c`，换成设置页要用的尺寸。 */
    private static String sizedAvatarUrl(String url) {
        if (url.contains("=s") && url.contains("googleusercontent.com")) {
            return url.replaceAll("=s\\d+(-c)?", "=s128-c");
        }
        return url;
    }

    private static int avatarSample(int width, int height, int sizePx) {
        int edge = Math.max(1, Math.min(width, height));
        int sample = 1;
        while (edge / (sample * 2) >= sizePx) sample *= 2;
        return sample;
    }

    private static Drawable circleAvatar(Resources resources, Bitmap source, int sizePx) {
        int width = source.getWidth();
        int height = source.getHeight();
        int edge = Math.min(width, height);
        Bitmap square = Bitmap.createBitmap(source, (width - edge) / 2, (height - edge) / 2, edge, edge);
        Bitmap scaled = edge == sizePx ? square : Bitmap.createScaledBitmap(square, sizePx, sizePx, true);
        Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float radius = sizePx / 2f;
        canvas.drawCircle(radius, radius, radius, paint);
        return new BitmapDrawable(resources, out);
    }

    private static Drawable initialsAvatar(Resources resources, String name, int sizePx) {
        Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFF5B8DEF);
        float radius = sizePx / 2f;
        canvas.drawCircle(radius, radius, radius, paint);
        String letter = "?";
        if (!TextUtils.isEmpty(name)) {
            int first = name.offsetByCodePoints(0, 1);
            letter = name.substring(0, first).toUpperCase(Locale.ROOT);
        }
        paint.setColor(0xFFFFFFFF);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(sizePx * 0.42f);
        paint.setFakeBoldText(true);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(letter, radius, radius - (metrics.ascent + metrics.descent) / 2f, paint);
        return new BitmapDrawable(resources, out);
    }

    private static @Nullable byte[] readBytes(@Nullable InputStream in, int max) throws IOException {
        if (in == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) return null;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
