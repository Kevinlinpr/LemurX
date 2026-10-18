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
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.json.JSONArray;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LemurX 原生客户端访问智能体服务（OpenMuse server，Hono）的最小 HTTP 层。
 *
 * <ul>
 *   <li>会话：{@code POST /api/session} 换一个 Bearer token，存在私有 SharedPreferences 里；
 *       401 时自动重新换一次。token 和访问密钥只在内存 / 私有存储里流转，不打日志。
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

    /** 家里 / 外出走公网隧道；局域网备一份，隧道抖的时候还能聊。 */
    public static final String DEFAULT_API_URL =
            "https://released-mumbai-rare-apply.trycloudflare.com";
    public static final String LAN_API_URL = "http://192.168.0.37:8787";
    private static @Nullable String sLiveUrl;

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    /** 对话流一次回复可能跑很久（工具调用、浏览网页）。 */
    private static final int STREAM_READ_TIMEOUT_MS = 10 * 60_000;

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

        void onFinished();

        void onError(String message);
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
        if (TextUtils.isEmpty(url) || url.contains("trycloudflare.com")) url = DEFAULT_API_URL;
        if (sLiveUrl != null) url = sLiveUrl;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
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
        return !TextUtils.isEmpty(prefs().getString(KEY_ACCESS_KEY, null));
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

    /** 退出：丢掉本机的会话 token。 */
    public static void signOut() {
        prefs().edit().remove(KEY_TOKEN).apply();
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

    private void request(
            String method, String path, @Nullable JSONObject body, Callback<Object> callback) {
        mExecutor.execute(
                () -> {
                    Object value = null;
                    String error = null;
                    try {
                        value = requestBlocking(method, path, body, true);
                    } catch (Exception e) {
                        error = describe(e);
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

    /** GET /api/health。关停探测用；公网不通就改走局域网。结果回到 UI 线程。 */
    public void probeHealth(Callback<JSONObject> callback) {
        mExecutor.execute(
                () -> {
                    try {
                        JSONObject json = healthOnce(apiUrl());
                        if (json == null && !LAN_API_URL.equals(apiUrl())) {
                            preferReachable(LAN_API_URL);
                            json = healthOnce(LAN_API_URL);
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

    /** 拿一个可用的 token：本地有就用，没有就 POST /api/session 换一个。后台线程。 */
    private String ensureToken() throws IOException, JSONException {
        synchronized (mTokenLock) {
            String token = prefs().getString(KEY_TOKEN, null);
            if (!TextUtils.isEmpty(token)) return token;
            JSONObject body = new JSONObject();
            String accessKey = prefs().getString(KEY_ACCESS_KEY, null);
            if (!TextUtils.isEmpty(accessKey)) body.put("accessKey", accessKey);
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
                prefs().edit().putString(KEY_TOKEN, token).apply();
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
        mExecutor.execute(() -> runBlocking(run, threadId, messages, context, listener));
        return run;
    }

    private void runBlocking(
            Run run,
            String threadId,
            JSONArray messages,
            @Nullable JSONArray context,
            RunListener listener) {
        HttpURLConnection connection = null;
        boolean finished = false;
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
            if (!finished) ThreadUtils.postOnUiThread(listener::onFinished);
        } catch (Exception e) {
            if (run.isCancelled()) return;
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

    /** 一条 SSE 事件 → 回调。返回 true 表示这轮已结束（RUN_FINISHED / RUN_ERROR）。 */
    private static boolean dispatch(String json, RunListener listener) {
        JSONObject event;
        try {
            event = new JSONObject(json);
        } catch (JSONException e) {
            Log.i(TAG, "skip non-json event");
            return false;
        }
        String type = event.optString("type", "");
        switch (type) {
            case "TEXT_MESSAGE_START": {
                String id = event.optString("messageId", "");
                if ("assistant".equals(event.optString("role", "assistant"))) {
                    ThreadUtils.postOnUiThread(() -> listener.onTextStart(id));
                }
                return false;
            }
            case "TEXT_MESSAGE_CONTENT": {
                String id = event.optString("messageId", "");
                String delta = event.optString("delta", "");
                if (!delta.isEmpty()) ThreadUtils.postOnUiThread(() -> listener.onTextDelta(id, delta));
                return false;
            }
            case "TEXT_MESSAGE_END": {
                String id = event.optString("messageId", "");
                ThreadUtils.postOnUiThread(() -> listener.onTextEnd(id));
                return false;
            }
            case "TOOL_CALL_START": {
                String name = event.optString("toolCallName", "");
                ThreadUtils.postOnUiThread(() -> listener.onToolCall(name));
                return false;
            }
            case "TOOL_CALL_RESULT": {
                String id = event.optString("toolCallId", "");
                String content = event.optString("content", "");
                ThreadUtils.postOnUiThread(() -> listener.onToolResult(id, content));
                return false;
            }
            case "RUN_FINISHED":
                ThreadUtils.postOnUiThread(listener::onFinished);
                return true;
            case "RUN_ERROR": {
                String message = event.optString("message", "");
                if (message.isEmpty()
                        || "fetch failed".equalsIgnoreCase(message)
                        || "fetched failed".equalsIgnoreCase(message)) {
                    message =
                            ContextUtils.getApplicationContext()
                                    .getString(R.string.lemurx_muse_model_fetch_failed);
                }
                final String m = message;
                ThreadUtils.postOnUiThread(() -> listener.onError(m));
                return true;
            }
            default:
                return false;
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
        if (TextUtils.isEmpty(message)) return e.getClass().getSimpleName();
        return message;
    }
}
