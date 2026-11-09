// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.lemurx.LemurXKillSwitch;
import org.chromium.chrome.browser.lemurx.agent.LemurXDeviceBridge;
import org.chromium.chrome.browser.lemurx.agent.LemurXDeviceConfig;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LemurX 原生客户端访问智能体服务（OpenMuse server，Hono）的最小 HTTP 层。
 *
 * <ul>
 *   <li>会话：{@code POST /api/session} 换一个 Bearer token，存在私有 SharedPreferences 里；
 *       401 时自动重新换一次。已登录时带上 aero 票据，会话主人是这个账号；没票据就是游客。
 *       token、票据和访问密钥只在内存 / 私有存储里流转，不打日志。
 *   <li>JSON：{@link #get} / {@link #send}，后台线程发请求，结果回到 UI 线程。
 *   <li>对话：{@link #run} 走 AG-UI 协议（{@code POST /api/copilotkit/agent/default/run} →
 *       SSE 事件流），逐 token 回调，可取消。
 * </ul>
 *
 * <p>服务器地址在「设置 → 智能体服务」里改；Agent 设备桥（lemurx.agent）默认也连这个地址。
 */
@NullMarked
public final class MuseApi {
    private static final String TAG = "LemurXMuse";

    private static final String PREF_FILE = "lemurx_muse";
    private static final String KEY_API_URL = "api_url";
    private static final String KEY_TOKEN = "session_token";
    private static final String KEY_ACCESS_KEY = "access_key";
    /** 这台安装自己的游客密钥；不进日志，登录后用来认领游客桶。 */
    private static final String KEY_GUEST_KEY = "guest_key";
    /** 当前这份会话 token 是不是带着 aero 票据换的。登录状态变了就要重换。 */
    private static final String KEY_SESSION_SIGNED_IN = "session_signed_in";

    /**
     * 对外测试包默认走公网。局域网测服只在公网健康检查失败、或更新清单缺失时兜底。
     * 健康检查失败时不再改去另一台机器。
     */
    public static final String DEFAULT_API_URL = "https://api.aero.osmangroupx.com";
    public static final String LAN_API_URL = "http://192.168.1.111:8787";
    /** 公网发布地址。更新探测在当前服务没有清单时再问一次这里。 */
    private static final String PUBLIC_API_URL = "https://api.aero.osmangroupx.com";
    private static @Nullable String sLiveUrl;

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    /**
     * 对话流上服务端每 15 秒有一条心跳。45 秒一个字节都没有，就是连接死了，不是模型还在想。
     * 死了就收尾，让界面去把已经写好的回复拉回来，而不是停在「reading a page」上干等。
     */
    private static final int STREAM_READ_TIMEOUT_MS = 45_000;

    private static @Nullable MuseApi sInstance;

    private final ExecutorService mExecutor = Executors.newCachedThreadPool();
    private final Object mTokenLock = new Object();

    /** UI 线程回调：{@code error} 为 null 表示成功。 */
    public interface Callback<T> {
        void onResult(@Nullable T value, @Nullable String error);
    }

    /** AG-UI 事件回调，全部在 UI 线程。 */
    public interface RunListener {
        void onTextStart(String messageId);

        void onTextDelta(String messageId, String delta);

        void onTextEnd(String messageId);

        /** 工具调用开始（名字），用来显示"正在…"。 */
        void onToolCall(String toolName);

        void onToolResult(String toolCallId, String content);

        /**
         * 自己电脑上的一次运行进度（CUSTOM own_computer）。
         * value 含 phase / computerRunId / command / line|output。
         */
        default void onOwnComputer(JSONObject value) {}

        void onFinished();

        void onError(String message);

        /** 邮箱游标。接上直播后用来把进度写到本机，下次从这里接着看。 */
        default void onTurnCursor(int cursor) {}
    }

    /** 一次进行中的对话请求。 */
    public static final class Run {
        private final AtomicBoolean mCancelled = new AtomicBoolean();
        private volatile @Nullable HttpURLConnection mConnection;

        public void cancel() {
            mCancelled.set(true);
            HttpURLConnection connection = mConnection;
            if (connection != null) {
                // disconnect 会让阻塞中的 read 抛异常，读线程据此退出。
                new Thread(connection::disconnect).start();
            }
        }

        public boolean isCancelled() {
            return mCancelled.get();
        }
    }

    private MuseApi() {}

    public static MuseApi get() {
        ThreadUtils.assertOnUiThread();
        if (sInstance == null) sInstance = new MuseApi();
        return sInstance;
    }

    // ---------------------------------------------------------------- 配置

    private static SharedPreferences prefs() {
        return ContextUtils.getApplicationContext()
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    /** 服务器地址，无尾部斜杠。 */
    public static String apiUrl() {
        String url = null;
        try {
            url = prefs().getString(KEY_API_URL, null);
        } catch (Exception e) {
            // 读不到就用默认
        }
        if (TextUtils.isEmpty(url) || retired(url)) url = DEFAULT_API_URL;
        if (sLiveUrl != null) url = sLiveUrl;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    /** 旧的明文 IP 和临时隧道已经关了，存下来的地址不能再用。 */
    private static boolean retired(String url) {
        String lower = url.toLowerCase(java.util.Locale.US);
        return lower.contains("trycloudflare.com") || lower.contains("18.143.226.219");
    }

    /** 健康检查选定当前能通的地址（公网优先，局域网兜底）。 */
    public static void preferReachable(@Nullable String url) {
        if (TextUtils.isEmpty(url)) {
            sLiveUrl = null;
            return;
        }
        sLiveUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public static void setApiUrl(@Nullable String url) {
        SharedPreferences.Editor editor = prefs().edit();
        if (TextUtils.isEmpty(url)) {
            editor.remove(KEY_API_URL);
        } else {
            editor.putString(KEY_API_URL, url.trim());
        }
        // 换服务器后旧 token 必然失效。
        editor.remove(KEY_TOKEN).apply();
    }

    /** live 模式服务器的访问密钥；sample 模式留空。 */
    public static boolean hasAccessKey() {
        return !TextUtils.isEmpty(accessKey());
    }

    /** 设置里手填的优先；没有就用打包时注入的那份。 */
    private static String accessKey() {
        String key = null;
        try {
            key = prefs().getString(KEY_ACCESS_KEY, null);
        } catch (Exception e) {
            // 读不到就用打包进去的
        }
        if (TextUtils.isEmpty(key)) key = LemurXDeviceConfig.packagedAccessKey();
        return key == null ? "" : key.trim();
    }

    public static void setAccessKey(@Nullable String key) {
        SharedPreferences.Editor editor = prefs().edit();
        if (TextUtils.isEmpty(key)) {
            editor.remove(KEY_ACCESS_KEY);
        } else {
            editor.putString(KEY_ACCESS_KEY, key.trim());
        }
        editor.remove(KEY_TOKEN).apply();
    }

    /** 这台安装的游客密钥。没有就现造一份，之后一直用。 */
    public static String guestKey() {
        SharedPreferences p = prefs();
        String key = p.getString(KEY_GUEST_KEY, null);
        if (TextUtils.isEmpty(key)) {
            key = UUID.randomUUID().toString();
            p.edit().putString(KEY_GUEST_KEY, key).apply();
        }
        return key;
    }

    public static @Nullable String accountId() {
        return LemurXAccount.accountId();
    }

    /** 退出：丢掉本机的会话 token。 */
    public static void signOut() {
        prefs().edit().remove(KEY_TOKEN).apply();
    }

    /** 登录或退出账号后换主人：清掉旧会话和旧设备令牌，设备桥按新身份重新登记。 */
    public static void rebind() {
        signOut();
        LemurXDeviceConfig.setDeviceToken(null);
        LemurXDeviceBridge.reconnectIdentity();
    }

    /**
     * 用当前会话给这台手机登记一把设备令牌。测服只允许登录用户登记，会话里要带着
     * aero 票据；游客会话会被拒绝。令牌只进私有存储，不打日志。后台线程。
     */
    public static boolean enrollThisDevice() {
        MuseApi api = sInstance != null ? sInstance : new MuseApi();
        try {
            boolean ok = api.enrollOnce(true);
            if (ok) Log.i(TAG, "device enrolled");
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "device enroll: %s", e.getMessage());
            return false;
        }
    }

    private boolean enrollOnce(boolean retryAuth) throws IOException, JSONException {
        String token = ensureToken();
        JSONObject body = new JSONObject();
        body.put("name", LemurXDeviceConfig.deviceName());
        HttpURLConnection connection = open("POST", "/api/devices", token, false);
        try {
            writeBody(connection, body.toString());
            int status = connection.getResponseCode();
            String text =
                    readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status == 401 && retryAuth) {
                clearToken(token);
                return enrollOnce(false);
            }
            if (status >= 400) throw new IOException(errorMessage(status, text));
            String deviceToken = new JSONObject(text).optString("token", "");
            if (TextUtils.isEmpty(deviceToken)) throw new IOException("device enroll returned no token");
            LemurXDeviceConfig.setDeviceToken(deviceToken);
            return true;
        } finally {
            connection.disconnect();
        }
    }

    public static boolean hasSession() {
        return !TextUtils.isEmpty(prefs().getString(KEY_TOKEN, null));
    }

    // ---------------------------------------------------------------- JSON 请求

    /** GET {@code path}（以 /api/… 开头），结果是 JSON 对象或数组（包在 {@link JSONObject} 的 "value" 里时为数组）。 */
    public void get(String path, Callback<Object> callback) {
        request("GET", path, null, callback);
    }

    public void send(String method, String path, @Nullable JSONObject body, Callback<Object> callback) {
        request(method, path, body, callback);
    }

    /** 社区发帖前上传一张 JPEG。结果是 {"id": "..."}。 */
    public void postImage(String path, byte[] jpeg, Callback<Object> callback) {
        mExecutor.execute(
                () -> {
                    Object value = null;
                    String error = null;
                    try {
                        value = postImageBlocking(path, jpeg, true);
                    } catch (Exception e) {
                        error = describe(e);
                    }
                    final Object v = value;
                    final String err = error;
                    ThreadUtils.postOnUiThread(() -> callback.onResult(v, err));
                });
    }

    /** 带登录态把一张社区图片拉下来。 */
    public void getBytes(String path, Callback<byte[]> callback) {
        mExecutor.execute(
                () -> {
                    byte[] value = null;
                    String error = null;
                    try {
                        value = getBytesBlocking(path, true);
                    } catch (Exception e) {
                        error = describe(e);
                    }
                    final byte[] v = value;
                    final String err = error;
                    ThreadUtils.postOnUiThread(() -> callback.onResult(v, err));
                });
    }

    private void request(
            String method, String path, @Nullable JSONObject body, Callback<Object> callback) {
        mExecutor.execute(
                () -> {
                    Object value = null;
                    String error = null;
                    try {
                        value = requestBlocking(method, path, body, true);
                    } catch (Exception e) {
                        if (staleConnection(e)) {
                            try {
                                value = requestBlocking(method, path, body, true);
                            } catch (Exception again) {
                                error = describe(again);
                            }
                        } else {
                            error = describe(e);
                        }
                    }
                    final Object v = value;
                    final String err = error;
                    ThreadUtils.postOnUiThread(() -> callback.onResult(v, err));
                });
    }

    /** 后台线程。401 时重新登录一次再试。 */
    private Object requestBlocking(
            String method, String path, @Nullable JSONObject body, boolean retryAuth)
            throws IOException, JSONException {
        String token = ensureToken();
        HttpURLConnection connection = open(method, path, token, false);
        try {
            if (body != null) writeBody(connection, body.toString());
            int status = connection.getResponseCode();
            if (status == 401 && retryAuth) {
                clearToken(token);
                return requestBlocking(method, path, body, false);
            }
            String text = readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status >= 400) {
                if (LemurXKillSwitch.isDisabledStatus(status, text)) {
                    ThreadUtils.postOnUiThread(LemurXKillSwitch::activate);
                }
                throw new IOException(errorMessage(status, text));
            }
            return parseJson(text);
        } finally {
            connection.disconnect();
        }
    }

    private static Object parseJson(String text) throws JSONException {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return new JSONObject();
        if (trimmed.startsWith("[")) return new JSONArray(trimmed);
        return new JSONObject(trimmed);
    }

    private static String errorMessage(int status, String text) {
        try {
            JSONObject json = new JSONObject(text);
            String error = json.optString("error", "");
            if (!error.isEmpty()) return error;
            String message = json.optString("message", "");
            if (!message.isEmpty()) return message;
        } catch (JSONException e) {
            // 不是 JSON 就用状态码
        }
        return "HTTP " + status;
    }

    // ---------------------------------------------------------------- 会话

    /**
     * GET /api/health。关停探测用。公网不通时，只有局域网自己也通了才改走局域网；
     * 已经钉在局域网上又失败，就清掉，改回公网。结果回到 UI 线程。
     */
    public void probeHealth(Callback<JSONObject> callback) {
        mExecutor.execute(
                () -> {
                    try {
                        String current = apiUrl();
                        JSONObject json = healthOnce(current);
                        if (json == null && LAN_API_URL.equals(current)) {
                            preferReachable(null);
                            json = healthOnce(DEFAULT_API_URL);
                        } else if (json == null) {
                            JSONObject lan = healthOnce(LAN_API_URL);
                            if (lan != null) {
                                preferReachable(LAN_API_URL);
                                json = lan;
                            }
                        }
                        if (json == null) {
                            ThreadUtils.postOnUiThread(() -> callback.onResult(null, "unreachable"));
                            return;
                        }
                        final JSONObject out = json;
                        ThreadUtils.postOnUiThread(() -> callback.onResult(out, null));
                    } catch (Exception e) {
                        ThreadUtils.postOnUiThread(() -> callback.onResult(null, describe(e)));
                    }
                });
    }

    private static @Nullable JSONObject healthOnce(String base) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(base + "/api/health").openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            int status = connection.getResponseCode();
            String text =
                    readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            JSONObject json;
            try {
                json = new JSONObject(text);
            } catch (JSONException e) {
                json = new JSONObject();
                json.put("ok", false);
            }
            if (status == 410) json.put("disabled", true);
            return json;
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** GET /api/app/release。不带登录态。当前服务没有清单时再问公网，公网不通再问局域网。结果回到 UI 线程。 */
    public void probeRelease(Callback<JSONObject> callback) {
        mExecutor.execute(
                () -> {
                    try {
                        JSONObject json = releaseOnce(apiUrl());
                        if (json == null && !PUBLIC_API_URL.equals(apiUrl())) {
                            json = releaseOnce(PUBLIC_API_URL);
                        }
                        if (json == null && !LAN_API_URL.equals(apiUrl())) {
                            json = releaseOnce(LAN_API_URL);
                        }
                        if (json == null) {
                            ThreadUtils.postOnUiThread(() -> callback.onResult(null, "unreachable"));
                            return;
                        }
                        final JSONObject out = json;
                        ThreadUtils.postOnUiThread(() -> callback.onResult(out, null));
                    } catch (Exception e) {
                        ThreadUtils.postOnUiThread(() -> callback.onResult(null, describe(e)));
                    }
                });
    }

    private static @Nullable JSONObject releaseOnce(String base) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(base + "/api/app/release").openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            int status = connection.getResponseCode();
            if (status != 200) return null;
            String text = readAll(connection.getInputStream());
            return new JSONObject(text);
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * 拿一个可用的 token。本地会话还在、而且登录状态没变，就继续用；否则 POST /api/session。
     * 已登录带上 aero 票据，guestKey 仍带上，服务端会把游客桶认领到这个账号。后台线程。
     */
    private String ensureToken() throws IOException, JSONException {
        synchronized (mTokenLock) {
            String ticket = LemurXAccount.aeroTicket();
            boolean signedIn = ticket != null;
            String token = prefs().getString(KEY_TOKEN, null);
            boolean wasSignedIn = prefs().getBoolean(KEY_SESSION_SIGNED_IN, false);
            if (!TextUtils.isEmpty(token) && signedIn == wasSignedIn) return token;
            JSONObject body = new JSONObject();
            String accessKey = accessKey();
            if (!TextUtils.isEmpty(accessKey)) body.put("accessKey", accessKey);
            body.put("guestKey", guestKey());
            if (ticket != null) body.put("ticket", ticket);
            HttpURLConnection connection = open("POST", "/api/session", null, false);
            try {
                writeBody(connection, body.toString());
                int status = connection.getResponseCode();
                String text =
                        readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
                if (status >= 400) {
                    if (LemurXKillSwitch.isDisabledStatus(status, text)) {
                        ThreadUtils.postOnUiThread(LemurXKillSwitch::activate);
                    }
                    throw new IOException(errorMessage(status, text));
                }
                JSONObject json = new JSONObject(text);
                token = json.getString("token");
                prefs().edit()
                        .putString(KEY_TOKEN, token)
                        .putBoolean(KEY_SESSION_SIGNED_IN, signedIn)
                        .apply();
                return token;
            } finally {
                connection.disconnect();
            }
        }
    }

    /** 只在 token 还是当初那一个时才清掉：并发的另一条请求可能已经换了新的。 */
    private void clearToken(String stale) {
        synchronized (mTokenLock) {
            if (stale.equals(prefs().getString(KEY_TOKEN, null))) {
                prefs().edit().remove(KEY_TOKEN).apply();
            }
        }
    }

    // ---------------------------------------------------------------- 对话流（AG-UI）

    /**
     * 跑一轮对话。{@code messages} 是 AG-UI 消息数组（含刚发出的用户消息；最后一条用户消息的
     * content 可以是 [文字, 图片] 这样的多模态数组）。{@code context} 是 AG-UI 的 context 数组
     * （{description, value}），服务端会拼进系统提示；可为 null。
     *
     * @param threadId 会话 id（= 标签页的会话 id，服务端用它关联委派出去的任务）。
     */
    public Run run(
            String threadId, JSONArray messages, @Nullable JSONArray context, RunListener listener) {
        Run run = new Run();
        mExecutor.execute(() -> runBlocking(run, threadId, messages, context, listener, true));
        return run;
    }

    /**
     * 接上服务端还在跑的一轮：先回放 {@code after} 之后的事件，再跟直播。
     * 只断 SSE 不会停 Agent；用户点停止要另外走 {@link #abortTurn}。
     */
    public Run watchTurn(String threadId, int after, RunListener listener) {
        Run run = new Run();
        mExecutor.execute(() -> watchTurnBlocking(run, threadId, after, listener));
        return run;
    }

    /** 用户点了停止：服务端才真正 abort 这一轮。 */
    public void abortTurn(String threadId) {
        send("DELETE", "/api/turns/" + threadId, new JSONObject(), (value, error) -> {});
    }

    /** 回放邮箱里的事件：调用方已经在 UI 线程上。 */
    public static boolean dispatchNow(JSONObject event, RunListener listener) {
        ThreadUtils.assertOnUiThread();
        return dispatchEvent(event, listener, true);
    }

    private void runBlocking(
            Run run,
            String threadId,
            JSONArray messages,
            @Nullable JSONArray context,
            RunListener listener,
            boolean retryStale) {
        HttpURLConnection connection = null;
        boolean finished = false;
        boolean sawLine = false;
        try {
            String token = ensureToken();
            JSONObject body = new JSONObject();
            body.put("threadId", threadId);
            body.put("runId", UUID.randomUUID().toString());
            body.put("state", new JSONObject());
            body.put("messages", messages);
            body.put("tools", new JSONArray());
            body.put("context", context != null ? context : new JSONArray());
            body.put("forwardedProps", new JSONObject());
            connection = open("POST", "/api/copilotkit/agent/default/run", token, true);
            run.mConnection = connection;
            if (run.isCancelled()) return;
            connection.setRequestProperty("Accept", "text/event-stream");
            writeBody(connection, body.toString());
            int status = connection.getResponseCode();
            if (status == 401) {
                clearToken(token);
                // 重新换 token 后再来一次；只重试一回。
                token = ensureToken();
                connection.disconnect();
                connection = open("POST", "/api/copilotkit/agent/default/run", token, true);
                run.mConnection = connection;
                connection.setRequestProperty("Accept", "text/event-stream");
                writeBody(connection, body.toString());
                status = connection.getResponseCode();
            }
            if (status >= 400) {
                String text = readAll(connection.getErrorStream());
                if (LemurXKillSwitch.isDisabledStatus(status, text)) {
                    ThreadUtils.postOnUiThread(LemurXKillSwitch::activate);
                }
                throw new IOException(errorMessage(status, text));
            }
            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sawLine = true;
                if (run.isCancelled()) return;
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        finished |= dispatch(data.toString(), listener);
                        data.setLength(0);
                    }
                    continue;
                }
                if (line.startsWith("data:")) {
                    String chunk = line.substring(5);
                    if (chunk.startsWith(" ")) chunk = chunk.substring(1);
                    if (data.length() > 0) data.append('\n');
                    data.append(chunk);
                }
                // event: / id: / 注释行忽略
            }
            if (data.length() > 0) finished |= dispatch(data.toString(), listener);
            // 流自己断了、没有 RUN_FINISHED：按掉线处理，远程这一轮去接邮箱，不要当成跑完。
            if (!finished) {
                ThreadUtils.postOnUiThread(() -> listener.onError("unexpected end of stream"));
            }
        } catch (Exception e) {
            if (run.isCancelled()) return;
            // 流还没开始就撞上被拆掉的空闲连接：重开一次。已经收到事件就不要重跑，避免操作执行两遍。
            if (retryStale && !sawLine && staleConnection(e)) {
                if (connection != null) connection.disconnect();
                connection = null;
                runBlocking(run, threadId, messages, context, listener, false);
                return;
            }
            Throwable cause = e.getCause();
            Log.w(
                    TAG,
                    "run failed url=%s type=%s msg=%s cause=%s",
                    apiUrl(),
                    e.getClass().getSimpleName(),
                    e.getMessage(),
                    cause == null ? "" : cause.getClass().getSimpleName() + " " + cause.getMessage());
            String message = describe(e);
            ThreadUtils.postOnUiThread(() -> listener.onError(message));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void watchTurnBlocking(Run run, String threadId, int after, RunListener listener) {
        HttpURLConnection connection = null;
        boolean finished = false;
        try {
            String token = ensureToken();
            String path = "/api/turns/" + threadId + "/stream?after=" + Math.max(0, after);
            connection = open("GET", path, token, true);
            run.mConnection = connection;
            if (run.isCancelled()) return;
            connection.setRequestProperty("Accept", "text/event-stream");
            int status = connection.getResponseCode();
            if (status == 401) {
                clearToken(token);
                token = ensureToken();
                connection.disconnect();
                connection = open("GET", path, token, true);
                run.mConnection = connection;
                connection.setRequestProperty("Accept", "text/event-stream");
                status = connection.getResponseCode();
            }
            if (status >= 400) {
                String text = readAll(connection.getErrorStream());
                if (LemurXKillSwitch.isDisabledStatus(status, text)) {
                    ThreadUtils.postOnUiThread(LemurXKillSwitch::activate);
                }
                throw new IOException(errorMessage(status, text));
            }
            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (run.isCancelled()) return;
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        finished |= dispatchWatch(data.toString(), listener);
                        data.setLength(0);
                        if (finished) break;
                    }
                    continue;
                }
                if (line.startsWith("data:")) {
                    String chunk = line.substring(5);
                    if (chunk.startsWith(" ")) chunk = chunk.substring(1);
                    if (data.length() > 0) data.append('\n');
                    data.append(chunk);
                }
            }
            if (!finished && data.length() > 0) finished |= dispatchWatch(data.toString(), listener);
            if (!finished) {
                ThreadUtils.postOnUiThread(() -> listener.onError("unexpected end of stream"));
            }
        } catch (Exception e) {
            if (run.isCancelled()) return;
            String message = describe(e);
            ThreadUtils.postOnUiThread(() -> listener.onError(message));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** 邮箱直播包一层 cursor/done；里面才是原来的 AG-UI 事件。 */
    private static boolean dispatchWatch(String json, RunListener listener) {
        JSONObject wrap;
        try {
            wrap = new JSONObject(json);
        } catch (JSONException e) {
            return false;
        }
        JSONObject event = wrap.optJSONObject("event");
        boolean done = wrap.optBoolean("done", false);
        int cursor = wrap.optInt("cursor", -1);
        if (cursor >= 0) ThreadUtils.postOnUiThread(() -> listener.onTurnCursor(cursor));
        boolean finished = false;
        if (event != null) {
            finished = dispatchEvent(event, listener, false);
        } else if (wrap.has("type")) {
            finished = dispatchEvent(wrap, listener, false);
        }
        return finished || done;
    }

    /** 一条 SSE 事件 → 回调。返回 true 表示这轮已结束（RUN_FINISHED / RUN_ERROR）。 */
    private static boolean dispatch(String json, RunListener listener) {
        JSONObject event;
        try {
            event = new JSONObject(json);
        } catch (JSONException e) {
            Log.i(TAG, "skip non-json event");
            return false;
        }
        return dispatchEvent(event, listener, false);
    }

    private static boolean dispatchEvent(JSONObject event, RunListener listener, boolean onUi) {
        String type = event.optString("type", "");
        switch (type) {
            case "TEXT_MESSAGE_START": {
                String id = event.optString("messageId", "");
                if ("assistant".equals(event.optString("role", "assistant"))) {
                    emit(onUi, () -> listener.onTextStart(id));
                }
                return false;
            }
            case "TEXT_MESSAGE_CONTENT":
            case "TEXT_MESSAGE_CHUNK": {
                String id = event.optString("messageId", "");
                String delta = event.optString("delta", "");
                if (!delta.isEmpty()) emit(onUi, () -> listener.onTextDelta(id, delta));
                return false;
            }
            case "TEXT_MESSAGE_END": {
                String id = event.optString("messageId", "");
                emit(onUi, () -> listener.onTextEnd(id));
                return false;
            }
            case "TOOL_CALL_START": {
                String name = event.optString("toolCallName", "");
                emit(onUi, () -> listener.onToolCall(name));
                return false;
            }
            case "TOOL_CALL_RESULT": {
                String id = event.optString("toolCallId", "");
                String content = event.optString("content", "");
                emit(onUi, () -> listener.onToolResult(id, content));
                return false;
            }
            case "CUSTOM": {
                if (!"own_computer".equals(event.optString("name", ""))) return false;
                Object raw = event.opt("value");
                JSONObject value;
                if (raw instanceof JSONObject) {
                    value = (JSONObject) raw;
                } else if (raw instanceof String) {
                    try {
                        value = new JSONObject((String) raw);
                    } catch (JSONException e) {
                        return false;
                    }
                } else {
                    return false;
                }
                JSONObject payload = value;
                emit(onUi, () -> listener.onOwnComputer(payload));
                return false;
            }
            case "RUN_FINISHED":
                emit(onUi, listener::onFinished);
                return true;
            case "RUN_ERROR": {
                String message = event.optString("message", "");
                if (LemurXBilling.CODE_QUOTA.equals(message)) {
                    message =
                            ContextUtils.getApplicationContext()
                                    .getString(R.string.lemurx_billing_empty);
                } else if (message.isEmpty()
                        || "fetch failed".equalsIgnoreCase(message)
                        || "fetched failed".equalsIgnoreCase(message)) {
                    message =
                            ContextUtils.getApplicationContext()
                                    .getString(R.string.lemurx_muse_model_fetch_failed);
                }
                final String m = message;
                emit(onUi, () -> listener.onError(m));
                return true;
            }
            default:
                return false;
        }
    }

    private static void emit(boolean onUi, Runnable action) {
        if (onUi) {
            action.run();
            return;
        }
        ThreadUtils.postOnUiThread(action);
    }

    private Object postImageBlocking(String path, byte[] jpeg, boolean retryAuth)
            throws IOException, JSONException {
        String token = ensureToken();
        HttpURLConnection connection = open("POST", path, token, false);
        try {
            connection.setRequestProperty("Content-Type", "image/jpeg");
            connection.setFixedLengthStreamingMode(jpeg.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(jpeg);
            }
            int status = connection.getResponseCode();
            if (status == 401 && retryAuth) {
                clearToken(token);
                return postImageBlocking(path, jpeg, false);
            }
            String text = readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status >= 400) throw new IOException(errorMessage(status, text));
            return parseJson(text);
        } finally {
            connection.disconnect();
        }
    }

    private byte[] getBytesBlocking(String path, boolean retryAuth) throws IOException, JSONException {
        String token = ensureToken();
        HttpURLConnection connection = open("GET", path, token, false);
        try {
            int status = connection.getResponseCode();
            if (status == 401 && retryAuth) {
                clearToken(token);
                return getBytesBlocking(path, false);
            }
            if (status >= 400) {
                String text = readAll(connection.getErrorStream());
                throw new IOException(errorMessage(status, text));
            }
            return readBytes(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    // ---------------------------------------------------------------- 底层

    private static HttpURLConnection open(
            String method, String path, @Nullable String token, boolean stream) throws IOException {
        URL url = new URL(apiUrl() + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(stream ? STREAM_READ_TIMEOUT_MS : READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        if (!"GET".equals(method)) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
        }
        return connection;
    }

    private static void writeBody(HttpURLConnection connection, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(bytes);
        }
    }

    private static byte[] readBytes(@Nullable InputStream in) throws IOException {
        if (in == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream stream = in) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = stream.read(buf)) > 0) {
                if (out.size() + n > 2_500_000) throw new IOException("That image is too large");
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
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

    /** 给用户看的错误文字：不带类名、不带地址里的任何凭据。 */
    private static String describe(Exception e) {
        String message = e.getMessage();
        Throwable cause = e.getCause();
        String causeMsg = cause == null ? "" : String.valueOf(cause.getMessage());
        String blob = ((message == null ? "" : message) + " " + causeMsg).toLowerCase();
        if (e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException) {
            return "Cannot reach the agent server (" + apiUrl() + ")";
        }
        if (e instanceof java.net.SocketTimeoutException) return "The agent server timed out";
        if (e instanceof javax.net.ssl.SSLException
                || blob.contains("ssl")
                || blob.contains("handshake")
                || blob.contains("cleartext")
                || blob.contains("err_connection")
                || blob.contains("err_name_not_resolved")
                || blob.contains("fetched failed")) {
            return "Cannot reach the agent server (" + apiUrl() + ")";
        }
        if (staleConnection(e)) return "The agent server closed the connection. Send again.";
        if (TextUtils.isEmpty(message)) return e.getClass().getSimpleName();
        return message;
    }

    /** 服务器拆掉了空闲连接，客户端还拿它发请求。 */
    private static boolean staleConnection(Exception e) {
        String message = e.getMessage();
        Throwable cause = e.getCause();
        String causeMsg = cause == null ? "" : String.valueOf(cause.getMessage());
        String blob = ((message == null ? "" : message) + " " + causeMsg).toLowerCase();
        return blob.contains("unexpected end of stream") || blob.contains("connection reset");
    }
}
