// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.agent;

import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.password_manager.PasswordStoreBridge;
import org.chromium.chrome.browser.password_manager.PasswordStoreBridge.PasswordStoreObserver;
import org.chromium.chrome.browser.password_manager.PasswordStoreCredential;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.url.GURL;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 站点账号走 Chromium 自己的密码库（和 Chrome 同一套：本机加密、保存提示、设置页里增删改）。
 * 这里只在手机上按当前站点取出一对，交给页面上的真人输入。密码不进日志、不进返回给服务端的 JSON。
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

    private LemurXPasswords() {}

    /**
     * 按页面地址在密码库里找。不要在界面线程上调用：密码库是异步把清单送回来的，这里会等它。
     */
    static Lookup lookup(Profile profile, String pageUrl, String username) {
        Lookup lookup = new Lookup();
        if (pageUrl == null || pageUrl.isEmpty()) return lookup;
        CountDownLatch ready = new CountDownLatch(1);
        PasswordStoreObserver observer = new PasswordStoreObserver() {
            @Override
            public void onSavedPasswordsChanged(int count) {
                ready.countDown();
            }

            @Override
            public void onEdit(PasswordStoreCredential credential) {}
        };
        PasswordStoreBridge[] holder = new PasswordStoreBridge[1];
        ThreadUtils.runOnUiThreadBlocking(() -> {
            try {
                PasswordStoreBridge bridge = new PasswordStoreBridge(profile);
                holder[0] = bridge;
                bridge.addObserver(observer, true);
            } catch (RuntimeException e) {
                ready.countDown();
            }
        });
        try {
            ready.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        PasswordStoreCredential[] credentials = ThreadUtils.runOnUiThreadBlocking(() -> {
            PasswordStoreBridge bridge = holder[0];
            if (bridge == null) return new PasswordStoreCredential[0];
            try {
                bridge.removeObserver(observer);
                return bridge.getAllCredentials();
            } catch (RuntimeException e) {
                return new PasswordStoreCredential[0];
            } finally {
                bridge.destroy();
                holder[0] = null;
            }
        });
        if (credentials == null) return lookup;
        String wantHost = hostOf(pageUrl);
        if (wantHost.isEmpty()) return lookup;
        String wantUser = username == null ? "" : username.trim();
        ArrayList<Login> matches = new ArrayList<>();
        for (PasswordStoreCredential credential : credentials) {
            if (credential == null) continue;
            GURL url = credential.getUrl();
            if (url == null || !wantHost.equals(hostOf(url.getSpec()))) continue;
            String name = credential.getUsername();
            String secret = credential.getPassword();
            if (name == null || secret == null || secret.isEmpty()) continue;
            if (!wantUser.isEmpty() && !wantUser.equalsIgnoreCase(name.trim())) continue;
            matches.add(new Login(name, secret));
        }
        if (matches.isEmpty()) return lookup;
        if (matches.size() == 1 || !wantUser.isEmpty()) {
            lookup.kind = ONE;
            lookup.login = matches.get(0);
            return lookup;
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Login login : matches) names.add(login.username);
        if (names.size() == 1) {
            lookup.kind = ONE;
            lookup.login = matches.get(0);
            return lookup;
        }
        lookup.kind = MANY;
        StringBuilder text = new StringBuilder();
        for (String name : names) {
            if (text.length() > 0) text.append(", ");
            if (text.length() > 300) break;
            text.append(name);
        }
        lookup.choices = text.toString();
        return lookup;
    }

    /** 比较用的主机名：去掉 www，小写。没有主机名时返回空。 */
    private static String hostOf(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            String host = URI.create(url).getHost();
            if (host == null || host.isEmpty()) return "";
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            return host;
        } catch (RuntimeException e) {
            return "";
        }
    }
}
