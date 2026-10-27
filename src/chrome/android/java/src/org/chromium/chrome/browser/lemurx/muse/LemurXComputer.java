// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
 * 用户自己的电脑。Aero 不提供云主机。这一页登录 Tailscale 之后，这个账号就是网络里的一台终端，
 * 下面列出同一账号的机器。对话里的 {@code use_own_computer} 在已经组好的网络里执行。
 *
 * <p>Wave 4：命令全文输出只留本机（{@link LemurXVault}）；服务端 own-computer-runs 只有
 * metadata。Tailscale master auth key 也只进本机 vault，交给云端的是一次性 joinToken。
 */
@NullMarked
final class LemurXComputer {
    private static final String PREFS = "aero-computer";
    private static final int MAX_LOG = 8000;
    private static final int MAX_RUNS = 24;

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

    static String host(Context context) {
        String value = prefs(context).getString("host", "");
        return value == null ? "" : value;
    }

    /** 只存在这部手机上。脚本里那一把钥匙和它相同，请求头带上，电脑才执行。 */
    static String token(Context context) {
        SharedPreferences prefs = prefs(context);
        String current = prefs.getString("token", "");
        if (current != null && !current.isEmpty()) return current;
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder(24);
        for (byte b : bytes) {
            out.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        String created = out.toString();
        prefs.edit().putString("token", created).apply();
        return created;
    }

    static void saveHost(Context context, String host) {
        prefs(context).edit().putString("host", host).apply();
    }

    /** 这一页只负责登录和列出机器。命令由对话在已经组好的网络里执行。 */
    static final class Page extends ScrollView {
        private final TextView mLogin;
        private final ImageView mLoginIcon;
        private final LinearLayout mDevices;
        private boolean mAwaitingLogin;
        private final Runnable mPollDevices = this::loadDevices;
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

            mDevices = new LinearLayout(context);
            mDevices.setOrientation(LinearLayout.VERTICAL);
            column.addView(mDevices, gap(context));

            mStatus = body(context, "");
            mStatus.setVisibility(View.GONE);
            column.addView(mStatus);

            addView(column, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            post(this::loadDevices);
        }

        @Override
        protected void onDetachedFromWindow() {
            mAwaitingLogin = false;
            removeCallbacks(mPollDevices);
            super.onDetachedFromWindow();
            if (sLivePage == this) sLivePage = null;
        }

        /** 在 Aero 的标签页里打开 Tailscale 登录页。这个账号对应网络里的一台终端。 */
        private void login() {
            Context context = getContext();
            mLogin.setText(R.string.lemurx_muse_computer_logging);
            MuseApi.get().send("POST", "/api/tailnet/login", new JSONObject(), (value, error) -> {
                if (error != null || !(value instanceof JSONObject)) {
                    paintLogin(false);
                    status(error == null
                            ? context.getString(R.string.lemurx_muse_computer_login_fail)
                            : error);
                    return;
                }
                JSONObject body = (JSONObject) value;
                paintDevices(body.optJSONArray("devices"), body.optBoolean("joined", false));
                String url = body.optString("authUrl", "");
                if (body.optBoolean("joined", false)) {
                    mAwaitingLogin = false;
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
                    // 登录页是 Aero 的标签。先把这一层让开，登完再回到机器列表。
                    MusePage.closeComputer();
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

        private void loadDevices() {
            if (!isAttachedToWindow()) return;
            MuseApi.get().get("/api/tailnet/devices", (value, error) -> {
                if (!isAttachedToWindow() || error != null || !(value instanceof JSONObject)) {
                    if (mAwaitingLogin && isAttachedToWindow()) postDelayed(mPollDevices, 2000);
                    return;
                }
                JSONObject body = (JSONObject) value;
                boolean joined = body.optBoolean("joined", false);
                paintDevices(body.optJSONArray("devices"), joined);
                if (joined) {
                    boolean waiting = mAwaitingLogin;
                    mAwaitingLogin = false;
                    paintLogin(true);
                    if (waiting) MusePage.showComputer();
                    return;
                }
                if (mAwaitingLogin) postDelayed(mPollDevices, 2000);
            });
        }

        private void paintDevices(@Nullable JSONArray devices, boolean joined) {
            mDevices.removeAllViews();
            if (!joined || devices == null || devices.length() == 0) return;
            Context context = getContext();
            for (int i = 0; i < devices.length(); i++) {
                JSONObject device = devices.optJSONObject(i);
                if (device == null) continue;
                boolean self = device.optBoolean("self", false);
                String name = device.optString("name", "");
                String ip = device.optString("ip", "");
                boolean online = device.optBoolean("online", false);
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int inset = MusePage.dp(context, 12);
                row.setPadding(inset, inset, inset, inset);
                int ink = self ? MusePage.onAccent(context) : MusePage.text(context);
                row.setBackground(MusePage.rounded(
                        self ? MusePage.accent(context) : MusePage.surface(context), 12, context));
                ImageView icon = new ImageView(context);
                icon.setImageResource(deviceIcon(device.optString("os", ""), self));
                icon.setColorFilter(ink);
                int size = MusePage.dp(context, 28);
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(size, size);
                iconLp.setMarginEnd(MusePage.dp(context, 12));
                row.addView(icon, iconLp);
                TextView label = new TextView(context);
                String state = context.getString(online
                        ? R.string.lemurx_muse_computer_online
                        : R.string.lemurx_muse_computer_offline);
                String who = self ? context.getString(R.string.lemurx_muse_computer_self) : ip;
                label.setText(name + "\n" + state + " · " + who);
                label.setTextColor(ink);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                row.addView(label, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                if (!self && !ip.isEmpty()) {
                    row.setOnClickListener(v -> {
                        LemurXHaptics.tick(v);
                        chooseDevice(ip, name);
                    });
                }
                mDevices.addView(row, gap(context));
            }
        }

        /** 机器种类：这个账号、手机、电脑。 */
        private static int deviceIcon(String os, boolean self) {
            if (self) return R.drawable.lemurx_muse_ic_server;
            String kind = os.toLowerCase(Locale.US);
            if (kind.contains("android") || kind.contains("ios") || kind.contains("iphone")) {
                return R.drawable.lemurx_muse_ic_smartphone;
            }
            return R.drawable.lemurx_muse_ic_monitor;
        }

        private void chooseDevice(String ip, String name) {
            Context context = getContext();
            saveHost(context, ip);
            status(context.getString(R.string.lemurx_muse_computer_picked, name));
            JSONObject body = new JSONObject();
            try {
                body.put("host", ip);
                body.put("token", token(context));
            } catch (org.json.JSONException e) {
                return;
            }
            MuseApi.get().send("POST", "/api/tailnet/host", body, (value, error) -> {
                if (error != null) status(error);
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
