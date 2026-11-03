// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.agent;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.lemurx.muse.LemurXVault;
import org.chromium.chrome.browser.profiles.Profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 站点账号走 Aero 自己的本机保险库。每个网站可以有多套账号，其中一套是 Aero 主要使用的。
 * 这里只在手机上按当前站点取出一对，交给页面上的真人输入。密码不进日志、不进返回给服务端的 JSON。
 *
 * <p>云端只能看见不透明引用（{@link #refOf}）和位数下标；真正取值、填入、移动都在本机完成，
 * 回执里只有「填了第 N 位」这类收据，绝不带回明文。
 */
@NullMarked
final class LemurXPasswords {
    static final int NONE = 0;
    static final int ONE = 1;
    static final int MANY = 2;

    /** 取出的一对。用完把引用丢掉；Java 字符串无法擦掉内容。 */
    static final class Login {
        final String username;
        final String password;

        Login(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }

    static final class Lookup {
        int kind = NONE;
        @Nullable Login login;
        /** 同一个站点有多个账号时，只放用户名，给模型挑。 */
        String choices = "";
    }

    /** 给云端看的凭证条目：只有不透明引用和可见标签，没有口令。 */
    static final class Ref {
        final String ref;
        final String host;
        final String username;
        final boolean primary;

        Ref(String ref, String host, String username, boolean primary) {
            this.ref = ref;
            this.host = host;
            this.username = username;
            this.primary = primary;
        }
    }

    private LemurXPasswords() {}

    /** 本机稳定引用：云端只能带着它和下标回来，解不开口令。 */
    static String refOf(String host, String username) {
        String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        String u = username == null ? "" : username.trim();
        return "pwd:" + h + ":" + u;
    }

    static boolean isPasswordRef(String ref) {
        return ref != null && ref.startsWith("pwd:") && ref.length() > 4 && ref.length() <= 240;
    }

    /**
     * 按页面地址在 Aero 的本机账号里找。没有指定用户名时用这个网站上 Aero 主要使用的那一套。
     * {@code profile} 不再参与取值，留着是为了调用处不用改。
     */
    static Lookup lookup(Profile profile, String pageUrl, String username) {
        Lookup lookup = new Lookup();
        if (profile == null || pageUrl == null || pageUrl.isEmpty()) return lookup;
        return pick(LemurXVault.hostOf(pageUrl), username == null ? "" : username.trim());
    }

    /**
     * 按不透明引用解析本机口令。ref 形如 {@code pwd:host:username}。
     * 成功时 login 非空；失败时 kind 为 NONE / MANY。
     */
    static Lookup lookupRef(Profile profile, String ref) {
        Lookup lookup = new Lookup();
        if (!isPasswordRef(ref)) return lookup;
        String body = ref.substring(4);
        int colon = body.indexOf(':');
        if (colon <= 0) return lookup;
        String host = body.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String username = body.substring(colon + 1).trim();
        if (profile == null || host.isEmpty()) return lookup;
        return pick(host, username);
    }

    private static Lookup pick(String host, String username) {
        Lookup lookup = new Lookup();
        if (host.isEmpty()) return lookup;
        ArrayList<LemurXVault.Account> matches = new ArrayList<>();
        LemurXVault.Account main = null;
        for (LemurXVault.Account account : LemurXVault.accounts()) {
            if (!host.equals(account.host) || account.password.isEmpty()) continue;
            if (!username.isEmpty() && !username.equalsIgnoreCase(account.username.trim())) continue;
            matches.add(account);
            if (account.primary) main = account;
        }
        if (matches.isEmpty()) return lookup;
        LemurXVault.Account chosen = null;
        if (!username.isEmpty() || matches.size() == 1) chosen = matches.get(0);
        else if (main != null) chosen = main;
        if (chosen != null) {
            lookup.kind = ONE;
            lookup.login = new Login(chosen.username, chosen.password);
            return lookup;
        }
        lookup.kind = MANY;
        StringBuilder text = new StringBuilder();
        for (LemurXVault.Account account : matches) {
            if (text.length() > 0) text.append(", ");
            if (text.length() > 300) break;
            text.append(account.username.isEmpty() ? "(no name)" : account.username);
            if (account.primary) text.append(" [primary]");
        }
        lookup.choices = text.toString();
        return lookup;
    }

    /** 当前页站点可用的不透明引用清单（无口令）。 */
    static List<Ref> listRefs(Profile profile, String pageUrl) {
        ArrayList<Ref> out = new ArrayList<>();
        if (profile == null || pageUrl == null || pageUrl.isEmpty()) return out;
        String wantHost = hostOf(pageUrl);
        if (wantHost.isEmpty()) return out;
        ArrayList<LemurXVault.Account> rest = new ArrayList<>();
        for (LemurXVault.Account account : LemurXVault.accounts()) {
            if (!wantHost.equals(account.host) || account.password.isEmpty()) continue;
            Ref ref = new Ref(refOf(wantHost, account.username), wantHost, account.username,
                    account.primary);
            if (account.primary) out.add(0, ref);
            else rest.add(account);
            if (out.size() + rest.size() >= 20) break;
        }
        for (LemurXVault.Account account : rest) {
            if (out.size() >= 20) break;
            out.add(new Ref(refOf(wantHost, account.username), wantHost, account.username, false));
        }
        return out;
    }

    /**
     * 取出引用对应口令的第 index 个字符（1-based）。失败返回 null。
     * 调用方用完必须立刻丢掉返回值，且不得写入日志 / JSON。
     */
    static @Nullable String charAt(Profile profile, String ref, int index) {
        if (index < 1 || index > 256) return null;
        Lookup found = lookupRef(profile, ref);
        if (found.kind != ONE || found.login == null) return null;
        String secret = found.login.password;
        found.login = null;
        if (secret == null || index > secret.length()) return null;
        return secret.substring(index - 1, index);
    }

    /** 比较用的主机名：去掉 www，小写。没有主机名时返回空。 */
    static String hostOf(String url) {
        return LemurXVault.hostOf(url);
    }
}
