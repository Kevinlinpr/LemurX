// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.lemurx.LemurXConsoleShell;
import org.chromium.chrome.browser.lemurx.LemurXHaptics;
import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 用户自己的电脑。Aero 不提供云主机。账号共享一台 Tailscale 节点：任一已登录设备交
 * joinToken 即可让节点上线，后交 key 的设备不会顶掉已在线的节点。
 *
 * <p>Wave 4：命令全文输出只留本机（{@link LemurXVault}）；服务端 own-computer-runs 只有
 * metadata。交给云端的是一次性 joinToken，用完即丢。
 *
 * <p>Wave 5：节点离线时服务端自动重置 statedir；本页 inline 展示离线并提供「让 Aero 进去」
 * 重新交 key，不自动弹窗。电脑 :8770 的 deviceToken 按账号共享，换机不覆盖。
 */
@NullMarked
final class LemurXComputer {
    private static final String PREFS = "aero-computer";
    private static final int MAX_LOG = 8000;
    private static final int MAX_RUNS = 24;

    /**
     * 打开应用时每个对话都会问一次节点状态。并到同一次请求里，避免两路同时
     * {@code tailscale up} 把刚连上的登录拆掉。
     */
    private static boolean sOnlineInFlight;
    private static final List<MuseApi.Callback<Object>> sAfterOnline = new ArrayList<>();

    /**
     * 用已经保存的登录把这个账号的节点拉上线。没有登录记录时服务端保持离线，这里不交新的 key。
     * {@code done} 在界面线程收到 {@code /api/tailnet} 的结果；可以是 null。
     */
    static void ensureNodeOnline(MuseApi.@Nullable Callback<Object> done) {
        if (!LemurXAccount.isSignedIn()) {
            if (done != null) done.onResult(null, null);
            return;
        }
        if (done != null) sAfterOnline.add(done);
        if (sOnlineInFlight) return;
        sOnlineInFlight = true;
        MuseApi.get().get("/api/tailnet", (value, error) -> {
            sOnlineInFlight = false;
            List<MuseApi.Callback<Object>> waiters = new ArrayList<>(sAfterOnline);
            sAfterOnline.clear();
            for (MuseApi.Callback<Object> waiter : waiters) waiter.onResult(value, error);
        });
    }

    /** 聊天和本机记录共用的一次运行。对话里点预览读这条命令和输出。 */
    static final class Run {
        final String id;
        final String command;
        final String source;
        /** 页内开跑时记下的对话线程；挂卡片只认这个，不猜窗口。 */
        @Nullable String threadId;
        @Nullable String host;
        final StringBuilder output = new StringBuilder();
        final String startedAt;
        volatile boolean done;
        volatile boolean ok = true;
        @Nullable String error;

        Run(String id, String command, String source) {
            this.id = id;
            this.command = command;
            this.source = source;
            this.startedAt = isoNow();
        }

        String preview() {
            String body = output.toString().trim();
            if (body.isEmpty()) {
                return done ? (ok ? "" : (error != null ? error : "")) : "…";
            }
            if (body.length() > 240) return body.substring(body.length() - 240);
            return body;
        }
    }

    private static final Map<String, Run> sRuns = new LinkedHashMap<>();
    private static @Nullable Page sLivePage;
    private static @Nullable String sFocusId;

    private LemurXComputer() {}

    private static String isoNow() {
        // UTC, enough for store ordering; no java.time dependency on older API levels.
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        return String.format(Locale.US, "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH),
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                cal.get(java.util.Calendar.SECOND),
                cal.get(java.util.Calendar.MILLISECOND));
    }

    static @Nullable Run getRun(String id) {
        synchronized (sRuns) {
            return sRuns.get(id);
        }
    }

    static List<Run> recentRuns() {
        synchronized (sRuns) {
            return new ArrayList<>(sRuns.values());
        }
    }

    private static void putRun(Run run) {
        synchronized (sRuns) {
            sRuns.put(run.id, run);
            while (sRuns.size() > MAX_RUNS) {
                String first = sRuns.keySet().iterator().next();
                sRuns.remove(first);
            }
        }
    }

    /** 页内或对话开始的一次运行。已有 id 时复用（聊天 SSE 带过来的）。 */
    static Run beginRun(String id, String command, String source) {
        return beginRun(id, command, source, null, null);
    }

    static Run beginRun(
            String id,
            String command,
            String source,
            @Nullable String threadId,
            @Nullable String host) {
        String key = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
        Run run;
        synchronized (sRuns) {
            run = sRuns.get(key);
            if (run == null) {
                run = new Run(key, command, source);
                sRuns.put(key, run);
                while (sRuns.size() > MAX_RUNS) {
                    String first = sRuns.keySet().iterator().next();
                    sRuns.remove(first);
                }
            }
        }
        if (threadId != null && !threadId.isEmpty()) run.threadId = threadId;
        if (host != null && !host.isEmpty()) run.host = host;
        sFocusId = key;
        Page page = sLivePage;
        if (page != null) page.showRun(run, /*reset=*/ true);
        return run;
    }

    static void appendRun(String id, String line) {
        Run run;
        synchronized (sRuns) {
            run = sRuns.get(id);
            if (run == null) return;
            if (run.output.length() > 0) run.output.append('\n');
            run.output.append(line);
            if (run.output.length() > MAX_LOG) {
                run.output.delete(0, run.output.length() - MAX_LOG);
            }
        }
        Page page = sLivePage;
        if (page != null && id.equals(sFocusId)) page.appendVisible(line);
    }

    static void endRun(String id, boolean ok, @Nullable String error, @Nullable String output) {
        Run run;
        synchronized (sRuns) {
            run = sRuns.get(id);
            if (run == null) return;
            if (output != null && run.output.length() == 0) run.output.append(output);
            run.done = true;
            run.ok = ok;
            run.error = error;
        }
        Page page = sLivePage;
        // 页内直连结束时清掉状态行；对话工具跑完时对话可能还在继续。
        if (page != null && id.equals(sFocusId)) {
            page.finishVisible(error, "page".equals(run.source));
        }
        LemurXVault.putRunOutput(run.id, run.output.toString());
        persistRun(run);
        if ("page".equals(run.source)) {
            MusePage.offerComputerRun(run.id, run.command, run.threadId);
        }
    }

    /** 对话工具结果一次性落库（没有行级进度时也能跳转）。 */
    static Run mirrorChatResult(
            String id, String command, @Nullable String output, boolean ok, @Nullable String error) {
        return mirrorChatResult(id, command, output, ok, error, null);
    }

    static Run mirrorChatResult(
            String id,
            String command,
            @Nullable String output,
            boolean ok,
            @Nullable String error,
            @Nullable String threadId) {
        String key = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
        Run run;
        synchronized (sRuns) {
            run = sRuns.get(key);
            if (run == null) {
                run = new Run(key, command, "chat");
                putRun(run);
            }
            run.output.setLength(0);
            if (output != null) run.output.append(output);
            run.done = true;
            run.ok = ok;
            run.error = error;
            if (threadId != null && !threadId.isEmpty()) run.threadId = threadId;
        }
        sFocusId = key;
        Page page = sLivePage;
        if (page != null) page.showRun(run, /*reset=*/ true);
        LemurXVault.putRunOutput(run.id, run.output.toString());
        persistRun(run);
        return run;
    }

    /** 会话存档 / 服务端 metadata 带回的卡片：全文优先从本机 vault 取。 */
    static void hydrate(
            String id,
            String command,
            @Nullable String output,
            boolean ok,
            boolean done,
            @Nullable String error,
            @Nullable String threadId,
            @Nullable String host) {
        if (id == null || id.isEmpty()) return;
        String local = LemurXVault.getRunOutput(id);
        String body = local != null ? local : (output == null ? "" : output);
        Run run;
        synchronized (sRuns) {
            run = sRuns.get(id);
            if (run == null) {
                run = new Run(id, command == null ? "" : command, "chat");
                putRun(run);
            }
            run.output.setLength(0);
            if (body != null) run.output.append(body);
            run.done = done;
            run.ok = ok;
            run.error = error;
            if (threadId != null && !threadId.isEmpty()) run.threadId = threadId;
            if (host != null && !host.isEmpty()) run.host = host;
        }
        if (!done) {
            MuseApi.get().get("/api/own-computer-runs/" + id, (val, err) -> {
                if (err == null && val instanceof JSONObject) {
                    JSONObject row = (JSONObject) val;
                    Run live;
                    synchronized (sRuns) {
                        live = sRuns.get(id);
                        if (live == null) return;
                        live.done = row.optBoolean("done", true);
                        live.ok = row.optBoolean("ok", true);
                        live.error = row.optString("error", null);
                    }
                    MusePage.offerComputerRun(live.id, live.command, live.threadId);
                }
            });
        }

    }

    /**
     * 服务端只收 metadata（run id / host / command / 时间 / 状态 / threadId）。
     * 全文输出只进 {@link LemurXVault}，永不 PUT 到云端。
     */
    static void persistRun(Run run) {
        if (run == null || run.id.isEmpty()) return;
        LemurXVault.putRunOutput(run.id, run.output.toString());
        JSONObject body = new JSONObject();
        try {
            body.put("command", run.command);
            body.put("ok", run.ok);
            body.put("done", run.done);
            body.put("source", run.source);
            body.put("startedAt", run.startedAt);
            if (run.host != null) body.put("host", run.host);
            if (run.threadId != null) body.put("threadId", run.threadId);
            if (run.error != null) body.put("error", run.error);
        } catch (org.json.JSONException e) {
            return;
        }
        MuseApi.get().send("PUT", "/api/own-computer-runs/" + run.id, body, (value, error) -> {});
    }

    /**
     * 点卡片：先本机表，再本机 vault，再服务端 metadata（无全文时显示空输出提示）。
     */
    static void focusRun(String id) {
        if (id == null || id.isEmpty()) return;
        Run run = getRun(id);
        if (run != null) {
            if (run.output.length() == 0) {
                String local = LemurXVault.getRunOutput(id);
                if (local != null && !local.isEmpty()) run.output.append(local);
            }
            sFocusId = id;
            Page page = sLivePage;
            if (page != null) page.showRun(run, /*reset=*/ true);
            return;
        }
        String local = LemurXVault.getRunOutput(id);
        if (local != null) {
            hydrate(id, "", local, true, true, null, null, null);
            Run loaded = getRun(id);
            sFocusId = id;
            Page page = sLivePage;
            if (loaded != null && page != null) page.showRun(loaded, /*reset=*/ true);
        }
        sFocusId = id;
        MuseApi.get().get("/api/own-computer-runs/" + id, (value, error) -> {
            if (error != null || !(value instanceof JSONObject)) {
                Run missing = getRun(id);
                Page page = sLivePage;
                if (missing == null && page != null && id.equals(sFocusId)) {
                    page.showMetadataOnly(id);
                }
                return;
            }
            JSONObject row = (JSONObject) value;
            // 服务端不再返回 output；本机 vault 没有时保持空，页面显示 metadata-only。
            hydrate(
                    row.optString("id", id),
                    row.optString("command", ""),
                    LemurXVault.getRunOutput(id),
                    row.optBoolean("ok", true),
                    row.optBoolean("done", true),
                    row.optString("error", null),
                    row.optString("threadId", null),
                    row.optString("host", null));
            Run loaded = getRun(id);
            Page page = sLivePage;
            if (page != null && id.equals(sFocusId)) {
                if (loaded != null) page.showRun(loaded, /*reset=*/ true);
                else page.showMetadataOnly(id);
            }
        });
    }

    /** Tailscale 的地址：100.64.0.0/10，或 *.ts.net。别的地址一律不连。 */
    static boolean allowed(String host) {
        if (host.endsWith(".ts.net")) {
            return host.matches("[a-z0-9]([a-z0-9-]{0,62}\\.)+ts\\.net") && !host.contains("..");
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) return false;
            for (int c = 0; c < parts[i].length(); c++) {
                if (parts[i].charAt(c) < '0' || parts[i].charAt(c) > '9') return false;
            }
            n[i] = Integer.parseInt(parts[i]);
            if (n[i] > 255) return false;
        }
        return n[0] == 100 && n[1] >= 64 && n[1] <= 127;
    }

    static String normalize(String raw) {
        String host = raw.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("https://")) host = host.substring(8);
        else if (host.startsWith("http://")) host = host.substring(7);
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int at = host.indexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) host = host.substring(0, colon);
        return host;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 电脑地址和命令钥匙按账号分开。同一部手机换账号时，不能把上一个账号的电脑交出去。 */
    private static String slot() {
        String id = LemurXAccount.accountId();
        return id == null || id.isEmpty() ? "none" : id;
    }

    private static String prefKey(String name) {
        return name + ":" + slot();
    }

    /**
     * 旧版本把地址和钥匙写在手机上，不区分账号。第一次读到时划给当前账号，然后删掉那份公用记录。
     */
    private static void migrateAccount(SharedPreferences stored) {
        String hostKey = prefKey("host");
        String tokenKey = prefKey("token");
        if (stored.contains(hostKey) || stored.contains(tokenKey)) return;
        String legacyHost = stored.getString("host", "");
        String legacyToken = stored.getString("token", "");
        SharedPreferences.Editor edit = stored.edit();
        if (legacyHost != null && !legacyHost.isEmpty()) edit.putString(hostKey, legacyHost);
        if (legacyToken != null && !legacyToken.isEmpty()) edit.putString(tokenKey, legacyToken);
        edit.remove("host").remove("token").apply();
    }

    static String host(Context context) {
        SharedPreferences stored = prefs(context);
        migrateAccount(stored);
        String value = stored.getString(prefKey("host"), "");
        return value == null ? "" : value;
    }

    /** 对话里当前要操作的对象。本机浏览器，或某台已经被控制的电脑地址。 */
    static final String TARGET_BROWSER = "browser";

    /**
     * 对话打开时默认是本机浏览器。电脑页记下的那台机器只决定命令打到哪里，
     * 不会自动变成这里的选中项。上一版误把那台机器当成默认，进来时清一次。
     */
    static String operateTarget(Context context) {
        SharedPreferences stored = prefs(context);
        if (!stored.getBoolean("operate_defaults_browser", false)) {
            stored.edit()
                    .putString("operate", TARGET_BROWSER)
                    .putBoolean("operate_defaults_browser", true)
                    .apply();
            return TARGET_BROWSER;
        }
        String value = stored.getString("operate", TARGET_BROWSER);
        return value == null || value.isEmpty() ? TARGET_BROWSER : value;
    }

    /**
     * 这台是不是命令真正打过去的电脑。网上别的节点（手机、别的机器）不算。
     * 地址可以是 IP、主机名，或带尾巴的 DNS 名。
     */
    static boolean isControlledComputer(JSONObject device, String host) {
        if (device == null || device.optBoolean("self", false)) return false;
        if (host == null) return false;
        String want = host.trim();
        if (want.isEmpty()) return false;
        String ip = device.optString("ip", "");
        if (ip.isEmpty()) return false;
        String name = device.optString("name", "");
        String dns = device.optString("dns", "");
        if (want.equalsIgnoreCase(ip) || want.equalsIgnoreCase(name) || want.equalsIgnoreCase(dns)) {
            return true;
        }
        String lower = want.toLowerCase(Locale.US);
        return dns.toLowerCase(Locale.US).startsWith(lower + ".");
    }

    static void saveOperateTarget(Context context, String target) {
        String cleaned = target == null || target.isEmpty() ? TARGET_BROWSER : target;
        prefs(context).edit().putString("operate", cleaned).apply();
    }

    /**
     * 账号共享的电脑 :8770 Bearer。本机没有时才生成；服务端若已有账号 token，会通过
     * join/host 响应写回 {@link #saveToken}，后到的手机不得覆盖服务端那一把。
     */
    static String token(Context context) {
        rememberSlot();
        if (sAccountToken != null && sAccountToken.length() >= 8) {
            saveToken(context, sAccountToken);
            return sAccountToken;
        }
        SharedPreferences prefs = prefs(context);
        migrateAccount(prefs);
        String current = prefs.getString(prefKey("token"), "");
        if (current != null && !current.isEmpty()) return current;
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder(24);
        for (byte b : bytes) {
            out.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        String created = out.toString();
        prefs.edit().putString(prefKey("token"), created).apply();
        return created;
    }

    static void saveHost(Context context, String host) {
        prefs(context).edit().putString(prefKey("host"), host).apply();
    }

    /** 最近一次 /api/tailnet 带回的账号地址。null 表示这部手机还没问过服务器。 */
    private static @Nullable String sServerHost;
    /** 账号上的命令钥匙。换账号时清掉，避免把上一份钥匙交给新账号。 */
    private static @Nullable String sAccountToken;
    private static @Nullable String sTokenSlot;
    /** 已经交给账号的本机地址，避免每次打开页面都再传一次。 */
    private static @Nullable String sPublishedHost;

    /** 换了 Aero 账号就忘掉上一个账号在内存里的地址和钥匙。 */
    private static void rememberSlot() {
        String slot = slot();
        if (slot.equals(sTokenSlot)) return;
        sTokenSlot = slot;
        sServerHost = null;
        sAccountToken = null;
        sPublishedHost = null;
    }

    /**
     * 电脑地址属于账号，不属于某一台手机。服务器上有地址时以它为准，写回这部手机。
     * 第一部手机选过的电脑如果还没进账号记录，后加入的手机会把整张设备列表滤成空的。
     */
    static void noteServerHost(Context context, @Nullable String serverHost) {
        rememberSlot();
        String remote = serverHost == null ? "" : serverHost.trim();
        sServerHost = remote;
        if (!remote.isEmpty() && !remote.equalsIgnoreCase(host(context))) {
            saveHost(context, remote);
        }
    }

    /**
     * 登录状态下的 /api/tailnet。地址和命令钥匙都属于这个账号。
     * 后加入的手机在自己造钥匙之前先收下账号里的那一把。
     */
    static void noteServerAccount(Context context, JSONObject body) {
        noteServerHost(context, body.optString("host", ""));
        if (!body.has("deviceToken")) return;
        String key = body.optString("deviceToken", "");
        if (key.length() < 8) return;
        sAccountToken = key;
        saveToken(context, key);
    }

    /**
     * 只有这部手机记得电脑地址、账号记录还是空的时候，把地址交给账号。
     * 后到的手机只读账号记录，不会用自己新造的地址盖掉这一台。
     */
    static void publishLocalHostIfNeeded(Context context) {
        rememberSlot();
        if (sServerHost == null || !sServerHost.isEmpty()) return;
        if (!LemurXAccount.isSignedIn()) return;
        String local = host(context);
        if (local.isEmpty() || local.equalsIgnoreCase(sPublishedHost)) return;
        sPublishedHost = local;
        JSONObject body = new JSONObject();
        try {
            body.put("host", local);
            body.put("token", token(context));
        } catch (org.json.JSONException e) {
            sPublishedHost = null;
            return;
        }
        MuseApi.get().send("POST", "/api/tailnet/host", body, (value, error) -> {
            if (!(value instanceof JSONObject)) {
                sPublishedHost = null;
                return;
            }
            JSONObject res = (JSONObject) value;
            String kept = res.optString("host", "");
            if (!kept.isEmpty()) {
                sServerHost = kept;
                saveHost(context, kept);
            }
            String accountToken = res.optString("deviceToken", "");
            if (!accountToken.isEmpty()) saveToken(context, accountToken);
        });
    }

    /** 同步服务端返回的账号级 deviceToken，使各手机脚本钥匙一致。 */
    static void saveToken(Context context, String token) {
        if (token == null || token.length() < 8) return;
        prefs(context).edit().putString(prefKey("token"), token).apply();
    }

    /**
     * 登录 / 列出机器 / 离线时 inline 交 joinToken。命令由对话在已经组好的网络里执行。
     * 不自动弹窗；用户点「让 Aero 进去」才交 key。
     */
    static final class Page extends ScrollView {
        private final TextView mLogin;
        private final ImageView mLoginIcon;
        private final LinearLayout mJoin;
        private final TextView mJoinHint;
        private final EditText mKey;
        private final EditText mHost;
        private final TextView mHand;
        private final LinearLayout mDevices;
        private boolean mAwaitingLogin;
        /** 账号登录页盖住这一层时先记住，回来并且有了票据再打开 Tailscale。 */
        private boolean mResumeTailscale;
        /** 游客会话被拒后只换一次正式会话，避免登录按钮来回空转。 */
        private boolean mSessionRetried;
        private boolean mNeedsJoin;
        private @Nullable String mStatusHost;
        private final Runnable mPollDevices = this::refresh;
        private final TextView mStatus;

        Page(Context context) {
            super(context);
            sLivePage = this;
            setVerticalScrollBarEnabled(false);
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            int pad = MusePage.dp(context, 16);
            column.setPadding(pad, MusePage.dp(context, 8), pad, MusePage.dp(context, 24));

            column.addView(body(context, context.getString(R.string.lemurx_muse_computer_intro)));

            LinearLayout login = new LinearLayout(context);
            login.setOrientation(LinearLayout.HORIZONTAL);
            login.setGravity(Gravity.CENTER);
            int inset = MusePage.dp(context, 12);
            login.setPadding(inset, inset, inset, inset);
            login.setBackground(MusePage.rounded(MusePage.accent(context), 14, context));
            login.setClickable(true);
            login.setFocusable(true);
            mLoginIcon = new ImageView(context);
            mLoginIcon.setImageResource(R.drawable.lemurx_muse_ic_login);
            mLoginIcon.setColorFilter(MusePage.onAccent(context));
            int icon = MusePage.dp(context, 20);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(icon, icon);
            iconLp.setMarginEnd(MusePage.dp(context, 8));
            login.addView(mLoginIcon, iconLp);
            mLogin = new TextView(context);
            mLogin.setText(R.string.lemurx_muse_computer_login);
            mLogin.setTextColor(MusePage.onAccent(context));
            mLogin.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            mLogin.setTypeface(Typeface.DEFAULT_BOLD);
            login.addView(mLogin);
            login.setOnClickListener(v -> {
                LemurXHaptics.tick(v);
                login();
            });
            column.addView(login, gap(context));

            mJoin = new LinearLayout(context);
            mJoin.setOrientation(LinearLayout.VERTICAL);
            int joinPad = MusePage.dp(context, 12);
            mJoin.setPadding(joinPad, joinPad, joinPad, joinPad);
            mJoin.setBackground(MusePage.rounded(MusePage.surface(context), 12, context));
            mJoinHint = body(context, context.getString(R.string.lemurx_muse_computer_needs_join));
            mJoin.addView(mJoinHint);
            TextView keyHint = body(context, context.getString(R.string.lemurx_muse_computer_key_hint));
            mJoin.addView(keyHint, gap(context));
            mKey = new EditText(context);
            mKey.setHint(R.string.lemurx_muse_computer_key);
            mKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            mKey.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            mKey.setTextColor(MusePage.text(context));
            mKey.setHintTextColor(MusePage.muted(context));
            mJoin.addView(mKey, gap(context));
            mHost = new EditText(context);
            mHost.setHint(R.string.lemurx_muse_computer_host);
            mHost.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            mHost.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            mHost.setTextColor(MusePage.text(context));
            mHost.setHintTextColor(MusePage.muted(context));
            String known = host(context);
            if (!known.isEmpty()) mHost.setText(known);
            mJoin.addView(mHost, gap(context));
            LinearLayout hand = new LinearLayout(context);
            hand.setOrientation(LinearLayout.HORIZONTAL);
            hand.setGravity(Gravity.CENTER);
            hand.setPadding(inset, inset, inset, inset);
            hand.setBackground(MusePage.rounded(MusePage.accent(context), 14, context));
            hand.setClickable(true);
            hand.setFocusable(true);
            mHand = new TextView(context);
            mHand.setText(R.string.lemurx_muse_computer_hand);
            mHand.setTextColor(MusePage.onAccent(context));
            mHand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            mHand.setTypeface(Typeface.DEFAULT_BOLD);
            hand.addView(mHand);
            hand.setOnClickListener(v -> {
                LemurXHaptics.tick(v);
                handOver();
            });
            mJoin.addView(hand, gap(context));
            mJoin.setVisibility(View.GONE);
            column.addView(mJoin, gap(context));

            mDevices = new LinearLayout(context);
            mDevices.setOrientation(LinearLayout.VERTICAL);
            column.addView(mDevices, gap(context));

            mStatus = body(context, "");
            mStatus.setVisibility(View.GONE);
            column.addView(mStatus);

            addView(column, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            post(this::refresh);
        }

        @Override
        protected void onDetachedFromWindow() {
            mAwaitingLogin = false;
            removeCallbacks(mPollDevices);
            super.onDetachedFromWindow();
            if (sLivePage == this) sLivePage = null;
        }

        @Override
        public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus);
            if (!hasWindowFocus || !mResumeTailscale) return;
            if (LemurXAccount.aeroTicket() == null) return;
            mResumeTailscale = false;
            MuseApi.rebind();
            startTailscaleLogin();
        }

        /**
         * 新设备上浏览器可以已登录，但智能体会话还是游客。游客打不开 Tailscale，
         * 服务器只回 Sign in to use this feature，按钮看起来就像没点上。
         * 先补上账号票据并换成正式会话，再打开登录页。
         */
        private void login() {
            if (LemurXAccount.aeroTicket() != null) {
                startTailscaleLogin();
                return;
            }
            mLogin.setText(R.string.lemurx_muse_computer_logging);
            status("");
            Activity activity = LemurXConsoleShell.activity();
            new Thread(() -> {
                LemurXAccount.ensureAeroTicket(activity, true);
                ThreadUtils.postOnUiThread(() -> {
                    if (!isAttachedToWindow()) return;
                    if (LemurXAccount.aeroTicket() == null) {
                        paintLogin(false);
                        status(getContext().getString(R.string.lemurx_muse_computer_need_account));
                        mResumeTailscale = true;
                        if (activity != null && !activity.isFinishing()) {
                            LemurXSignIn.showFromBrowser(activity);
                        }
                        return;
                    }
                    MuseApi.rebind();
                    startTailscaleLogin();
                });
            }, "aero-ticket").start();
        }

        /** 在 Aero 的标签页里打开 Tailscale 登录页。这个账号对应网络里的一台终端。 */
        private void startTailscaleLogin() {
            Context context = getContext();
            mLogin.setText(R.string.lemurx_muse_computer_logging);
            MuseApi.get().send("POST", "/api/tailnet/login", new JSONObject(), (value, error) -> {
                if (error != null || !(value instanceof JSONObject)) {
                    if (error != null && error.contains("Sign in to use this feature")
                            && !mSessionRetried && LemurXAccount.aeroTicket() != null) {
                        mSessionRetried = true;
                        MuseApi.rebind();
                        startTailscaleLogin();
                        return;
                    }
                    paintLogin(false);
                    status(error == null
                            ? context.getString(R.string.lemurx_muse_computer_login_fail)
                            : accountGate(context, error));
                    return;
                }
                JSONObject body = (JSONObject) value;
                paintDevices(body.optJSONArray("devices"), body.optBoolean("joined", false));
                String url = body.optString("authUrl", "");
                if (body.optBoolean("joined", false)) {
                    mAwaitingLogin = false;
                    mNeedsJoin = false;
                    showJoin(false);
                    paintLogin(true);
                    return;
                }
                if (url.startsWith("https://login.tailscale.com/")) {
                    if (!LemurXConsoleShell.openPage(url)) {
                        paintLogin(false);
                        status(context.getString(R.string.lemurx_muse_computer_login_fail));
                        return;
                    }
                    mAwaitingLogin = true;
                    mLogin.setText(R.string.lemurx_muse_computer_waiting);
                    status("");
                    // 登录页是 Aero 的标签。先把这一层让开，登完再回到机器列表。这是往前走，不把侧边栏再打开。
                    MusePage.closeComputer();
                    MusePage.dropDrawerReturn();
                    removeCallbacks(mPollDevices);
                    postDelayed(mPollDevices, 2000);
                    return;
                }
                paintLogin(false);
                status(context.getString(R.string.lemurx_muse_computer_login_fail));
            });
        }

        private void paintLogin(boolean joined) {
            mLogin.setText(joined
                    ? R.string.lemurx_muse_computer_in
                    : R.string.lemurx_muse_computer_login);
            mLoginIcon.setImageResource(joined
                    ? R.drawable.lemurx_muse_ic_server
                    : R.drawable.lemurx_muse_ic_login);
        }

        private void showJoin(boolean show) {
            mJoin.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                mJoinHint.setText(R.string.lemurx_muse_computer_needs_join);
                String known = host(getContext());
                if (known.isEmpty() && mStatusHost != null && !mStatusHost.isEmpty()) {
                    known = mStatusHost;
                }
                if (!known.isEmpty() && (mHost.getText() == null || mHost.getText().length() == 0)) {
                    mHost.setText(known);
                }
                boolean needHost = known.isEmpty();
                mHost.setVisibility(needHost ? View.VISIBLE : View.GONE);
            }
        }

        /** 账号级状态 + 设备列表；离线时展示 inline 交 key，不弹窗。 */
        private void refresh() {
            if (!isAttachedToWindow()) return;
            MuseApi.get().get("/api/tailnet", (value, error) -> {
                if (!isAttachedToWindow()) return;
                if (error != null || !(value instanceof JSONObject)) {
                    if (error != null) status(accountGate(getContext(), error));
                    loadDevices();
                    return;
                }
                JSONObject body = (JSONObject) value;
                boolean joined = body.optBoolean("joined", false);
                boolean needsJoin = body.optBoolean("needsJoin", !joined);
                // 旧服务不带 online。不能把缺字段当成节点掉线，否则外网一重启就要求重新登录。
                boolean online = body.has("online") ? body.optBoolean("online") : joined;
                String statusHost = body.optString("host", "");
                mStatusHost = statusHost;
                noteServerAccount(getContext(), body);
                mNeedsJoin = needsJoin || !joined || !online;
                if (mNeedsJoin) {
                    paintLogin(false);
                    showJoin(true);
                    if (!online && joined) {
                        status(getContext().getString(R.string.lemurx_muse_computer_node_offline));
                    }
                } else {
                    showJoin(false);
                    paintLogin(true);
                }
                loadDevices();
            });
        }

        private void loadDevices() {
            if (!isAttachedToWindow()) return;
            MuseApi.get().get("/api/tailnet/devices", (value, error) -> {
                if (!isAttachedToWindow() || error != null || !(value instanceof JSONObject)) {
                    if (mAwaitingLogin && isAttachedToWindow()) postDelayed(mPollDevices, 2000);
                    return;
                }
                JSONObject body = (JSONObject) value;
                boolean joined = body.optBoolean("joined", false);
                boolean needsJoin = body.optBoolean("needsJoin", false);
                if (needsJoin) {
                    mNeedsJoin = true;
                    showJoin(true);
                    paintLogin(false);
                }
                paintDevices(body.optJSONArray("devices"), joined && !needsJoin);
                if (joined && !needsJoin) {
                    boolean waiting = mAwaitingLogin;
                    mAwaitingLogin = false;
                    mNeedsJoin = false;
                    showJoin(false);
                    paintLogin(true);
                    if (waiting) MusePage.showComputer();
                    return;
                }
                if (mAwaitingLogin) postDelayed(mPollDevices, 2000);
            });
        }

        private void paintDevices(@Nullable JSONArray devices, boolean joined) {
            mDevices.removeAllViews();
            if (!joined || devices == null) return;
            Context context = getContext();
            if (devices.length() == 0) {
                mDevices.addView(body(context, context.getString(R.string.lemurx_muse_computer_none)));
                return;
            }
            boolean selfOffline = false;
            String controlled = host(context);
            boolean shown = false;
            for (int i = 0; i < devices.length(); i++) {
                JSONObject device = devices.optJSONObject(i);
                if (device == null) continue;
                boolean self = device.optBoolean("self", false);
                boolean online = device.optBoolean("online", false);
                // Aero 自己的节点只用来判断网络还在不在，不占这一页。
                if (self) {
                    if (!online) selfOffline = true;
                    continue;
                }
                // 网上其他设备也不列。这一页只放已经接上、命令可以打过去的那台电脑。
                if (!isControlledComputer(device, controlled)) continue;
                shown = true;
                String name = device.optString("name", "");
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int inset = MusePage.dp(context, 12);
                row.setPadding(inset, inset, inset, inset);
                int ink = MusePage.text(context);
                row.setBackground(MusePage.rounded(MusePage.surface(context), 12, context));
                ImageView icon = new ImageView(context);
                icon.setImageResource(deviceIcon(device.optString("os", ""), false));
                icon.setColorFilter(ink);
                int size = MusePage.dp(context, 28);
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(size, size);
                iconLp.setMarginEnd(MusePage.dp(context, 12));
                row.addView(icon, iconLp);
                TextView label = new TextView(context);
                String state = context.getString(online
                        ? R.string.lemurx_muse_computer_online
                        : R.string.lemurx_muse_computer_offline);
                label.setText(name + "\n" + state);
                label.setTextColor(ink);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                row.addView(label, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                mDevices.addView(row, gap(context));
            }
            if (!shown) {
                mDevices.addView(body(context, context.getString(R.string.lemurx_muse_computer_none)));
            } else {
                publishLocalHostIfNeeded(context);
            }
            if (selfOffline) {
                mNeedsJoin = true;
                showJoin(true);
                status(context.getString(R.string.lemurx_muse_computer_node_offline));
            }
        }

    /** 机器种类：这个账号、手机、电脑。对话里的按钮也用这一套。 */
    static int deviceIcon(String os, boolean self) {
            if (self) return R.drawable.lemurx_muse_ic_server;
            String kind = os.toLowerCase(Locale.US);
            if (kind.contains("android") || kind.contains("ios") || kind.contains("iphone")) {
                return R.drawable.lemurx_muse_ic_smartphone;
            }
            return R.drawable.lemurx_muse_ic_monitor;
        }

        /** 一次性 joinToken；成功后清输入框，不落库、不打日志。 */
        private void handOver() {
            Context context = getContext();
            String key = mKey.getText() == null ? "" : mKey.getText().toString().trim();
            if (!key.startsWith("tskey-auth-") || key.length() < 20) {
                status(context.getString(R.string.lemurx_muse_computer_bad_key));
                return;
            }
            String rawHost = mHost.getText() == null ? "" : mHost.getText().toString();
            if (rawHost.isEmpty()) rawHost = host(context);
            if (rawHost.isEmpty() && mStatusHost != null) rawHost = mStatusHost;
            String cleaned = normalize(rawHost);
            if (!allowed(cleaned)) {
                status(context.getString(R.string.lemurx_muse_computer_bad));
                mHost.setVisibility(View.VISIBLE);
                return;
            }
            mHand.setText(R.string.lemurx_muse_computer_handing);
            JSONObject body = new JSONObject();
            try {
                body.put("joinToken", key);
                body.put("host", cleaned);
                body.put("token", token(context));
            } catch (org.json.JSONException e) {
                mHand.setText(R.string.lemurx_muse_computer_hand);
                return;
            }
            MuseApi.get().send("POST", "/api/tailnet", body, (value, error) -> {
                mHand.setText(R.string.lemurx_muse_computer_hand);
                if (error != null || !(value instanceof JSONObject)) {
                    status(error == null
                            ? context.getString(R.string.lemurx_muse_computer_hand_fail)
                            : error);
                    return;
                }
                JSONObject res = (JSONObject) value;
                mKey.setText("");
                saveHost(context, cleaned);
                String accountToken = res.optString("deviceToken", "");
                if (!accountToken.isEmpty()) saveToken(context, accountToken);
                mNeedsJoin = false;
                showJoin(false);
                paintLogin(true);
                status(context.getString(R.string.lemurx_muse_computer_hand_ok));
                refresh();
            });
        }

        /** 对话卡片点进来。这一页只列出机器，运行过程留在对话里。 */
        void focusRun(String id) {
            LemurXComputer.focusRun(id);
        }

        void showRun(Run run, boolean reset) {
            if (reset && run.done && !run.ok && run.error != null) status(run.error);
        }
        /** 本机没有全文、服务端也只有 metadata 时的明确空态。 */
        void showMetadataOnly(String id) {}

        void appendVisible(String line) {}

        void finishVisible(@Nullable String error, boolean clearStatus) {
            if (error != null) status(error);
            else if (clearStatus) status("");
        }

        /** 游客被拒时换成让人去登录账号的说明，不要只留一句英文。 */
        private static String accountGate(Context context, String error) {
            if (error.contains("Sign in to use this feature")) {
                return context.getString(R.string.lemurx_muse_computer_need_account);
            }
            return error;
        }

        private void status(String text) {
            mStatus.setText(text);
            mStatus.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        }

        private static TextView body(Context context, String text) {
            TextView view = new TextView(context);
            view.setText(text);
            view.setTextColor(MusePage.muted(context));
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            view.setLineSpacing(0, 1.35f);
            return view;
        }

        private static LinearLayout.LayoutParams gap(Context context) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = MusePage.dp(context, 10);
            return lp;
        }

    }
}
