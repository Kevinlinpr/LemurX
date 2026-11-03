// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.jni_zero.CalledByNative;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.lemurx.LemurXConsoleShell;
import org.chromium.chrome.browser.lemurx.LemurXHaptics;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Aero 本机保险库：Android Keystore AES-GCM 包着 SharedPreferences 密文。
 *
 * <p>个人数据落点：
 * <ul>
 *   <li>网站账号 → 本 vault 的 logins 记录。每个网站可以有多套账号，其中一套是 Aero 主要使用的。
 *       口令只在本机解开，不进日志，不上传。
 *   <li>银行卡 / 地址 → Chrome Autofill（设备加密；设置 → 支付 / 地址）；智能体读不到
 *   <li>Tailscale master auth key、computer run 全文 → 本 vault；云端只有不透明引用 / metadata
 *   <li>「给 Aero 的资料」memories → 服务端，只放偏好，禁止口令 / 卡号
 * </ul>
 */
@NullMarked
public final class LemurXVault {
    private static final String TAG = "LemurXVault";
    private static final String PREFS = "aero-vault";
    private static final String KEY_ALIAS = "aero_vault_aes";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TAILSCALE_JOIN = "tailscale.join";
    private static final String RUN_PREFIX = "run:";
    private static final String LOGINS = "logins";
    private static @Nullable Dialog sOffer;
    private static @Nullable Runnable sAccountsChanged;

    private LemurXVault() {}

    private static volatile String sVaultBucket = "";
    private static volatile @Nullable SharedPreferences sVaultPrefs;

    /** 保险库按账号分桶，密文仍只在这台设备上。换账号就换一份。 */
    private static SharedPreferences prefs() {
        String bucket = LemurXAccount.localBucket();
        SharedPreferences cached = sVaultPrefs;
        if (bucket.equals(sVaultBucket) && cached != null) return cached;
        synchronized (LemurXVault.class) {
            if (bucket.equals(sVaultBucket) && sVaultPrefs != null) return sVaultPrefs;
            Context context = ContextUtils.getApplicationContext();
            SharedPreferences current =
                    context.getSharedPreferences("aero-vault-" + bucket, Context.MODE_PRIVATE);
            if (!"local".equals(bucket) && !current.contains("bucket-ready")) {
                SharedPreferences legacy = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                java.util.Map<String, ?> old = legacy.getAll();
                if (!old.isEmpty()) {
                    SharedPreferences.Editor edit = current.edit();
                    for (java.util.Map.Entry<String, ?> entry : old.entrySet()) {
                        if (entry.getValue() instanceof String) {
                            edit.putString(entry.getKey(), (String) entry.getValue());
                        }
                    }
                    edit.putBoolean("bucket-ready", true);
                    edit.commit();
                    legacy.edit().clear().commit();
                } else {
                    current.edit().putBoolean("bucket-ready", true).apply();
                }
            }
            sVaultBucket = bucket;
            sVaultPrefs = current;
            return current;
        }
    }

    static void put(String id, @Nullable String plaintext) {
        if (id == null || id.isEmpty() || id.length() > 120) return;
        SharedPreferences.Editor edit = prefs().edit();
        if (plaintext == null || plaintext.isEmpty()) {
            edit.remove(id).apply();
            return;
        }
        String sealed = seal(plaintext);
        if (sealed == null) return;
        edit.putString(id, sealed).apply();
    }

    static @Nullable String get(String id) {
        if (id == null || id.isEmpty()) return null;
        String sealed = prefs().getString(id, null);
        if (sealed == null || sealed.isEmpty()) return null;
        return open(sealed);
    }

    static void putTailscaleMaster(String key) {
        put(TAILSCALE_JOIN, key);
    }

    static @Nullable String tailscaleMaster() {
        return get(TAILSCALE_JOIN);
    }

    static void putRunOutput(String runId, @Nullable String output) {
        if (runId == null || runId.isEmpty()) return;
        put(RUN_PREFIX + runId, output);
    }

    static @Nullable String getRunOutput(String runId) {
        if (runId == null || runId.isEmpty()) return null;
        return get(RUN_PREFIX + runId);
    }

    private static @Nullable SecretKey key() {
        try {
            KeyStore store = KeyStore.getInstance(ANDROID_KEYSTORE);
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) {
                KeyStore.SecretKeyEntry entry =
                        (KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null);
                return entry == null ? null : entry.getSecretKey();
            }
            KeyGenerator generator =
                    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            generator.init(
                    new KeyGenParameterSpec.Builder(
                                    KEY_ALIAS,
                                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build());
            return generator.generateKey();
        } catch (Exception e) {
            Log.i(TAG, "keystore: %s", e.getMessage());
            return null;
        }
    }

    /** 给档案里的敏感条目加密。口令仍然只走网站账号那一套，不要把明文写进日志。 */
    static @Nullable String sealText(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return null;
        return seal(plaintext);
    }

    /** 解开档案里的敏感条目。调用方用完即丢，不要放进对话上下文。 */
    static @Nullable String openText(String sealed) {
        if (sealed == null || sealed.isEmpty()) return null;
        return open(sealed);
    }

    private static @Nullable String seal(String plaintext) {
        try {
            SecretKey secret = key();
            if (secret == null) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secret);
            byte[] iv = cipher.getIV();
            byte[] body = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(body, 0, all, iv.length, body.length);
            return Base64.encodeToString(all, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.i(TAG, "seal: %s", e.getMessage());
            return null;
        }
    }

    private static @Nullable String open(String sealed) {
        try {
            SecretKey secret = key();
            if (secret == null) return null;
            byte[] all = Base64.decode(sealed, Base64.DEFAULT);
            if (all.length < 13) return null;
            byte[] iv = new byte[12];
            System.arraycopy(all, 0, iv, 0, 12);
            byte[] body = new byte[all.length - 12];
            System.arraycopy(all, 12, body, 0, body.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secret, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.i(TAG, "open: %s", e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------- 网站账号

    /** 一套保存在本机的网站账号。口令只在调用方手里，不要写进日志。 */
    public static final class Account {
        public final String id;
        public final String host;
        public final String username;
        public final String password;
        public final boolean primary;

        Account(String id, String host, String username, String password, boolean primary) {
            this.id = id;
            this.host = host;
            this.username = username;
            this.password = password;
            this.primary = primary;
        }
    }

    /** 比较用的主机名：去掉 www，小写。 */
    public static String hostOf(@Nullable String url) {
        if (url == null || url.isEmpty()) return "";
        String raw = url.trim();
        try {
            String host = java.net.URI.create(raw.contains("://") ? raw : "https://" + raw).getHost();
            if (host == null || host.isEmpty()) host = raw;
            host = host.toLowerCase(java.util.Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            return host;
        } catch (RuntimeException e) {
            return raw.toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static ArrayList<Account> accounts() {
        ArrayList<Account> out = new ArrayList<>();
        JSONArray all = readLogins();
        for (int i = 0; i < all.length(); i++) {
            JSONObject row = all.optJSONObject(i);
            if (row == null) continue;
            String host = row.optString("host", "");
            String password = row.optString("password", "");
            if (host.isEmpty() || password.isEmpty()) continue;
            out.add(new Account(
                    row.optString("id", ""),
                    host,
                    row.optString("username", ""),
                    password,
                    row.optBoolean("primary", false)));
        }
        return out;
    }

    /** 同一网站、同一账号、同一口令已经在库里。 */
    public static boolean hasSame(String host, String username, String password) {
        String wantHost = hostOf(host);
        String wantUser = username == null ? "" : username.trim();
        for (Account account : accounts()) {
            if (!wantHost.equals(account.host)) continue;
            if (!wantUser.equalsIgnoreCase(account.username.trim())) continue;
            if (password.equals(account.password)) return true;
        }
        return false;
    }

    public static boolean hasUsername(String host, String username) {
        String wantHost = hostOf(host);
        String wantUser = username == null ? "" : username.trim();
        for (Account account : accounts()) {
            if (wantHost.equals(account.host) && wantUser.equalsIgnoreCase(account.username.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 写入或更新一套账号。同一网站同一用户名只留一套。
     * {@code primary} 为真时，这个网站上的其他账号不再是 Aero 主要使用的。
     */
    public static void saveAccount(String host, String username, String password, boolean primary) {
        String wantHost = hostOf(host);
        String wantUser = username == null ? "" : username.trim();
        if (wantHost.isEmpty() || password == null || password.isEmpty()) return;
        JSONArray all = readLogins();
        boolean anyPrimary = false;
        int found = -1;
        for (int i = 0; i < all.length(); i++) {
            JSONObject row = all.optJSONObject(i);
            if (row == null || !wantHost.equals(row.optString("host", ""))) continue;
            if (row.optBoolean("primary", false)) anyPrimary = true;
            if (wantUser.equalsIgnoreCase(row.optString("username", "").trim())) found = i;
        }
        if (!primary && !anyPrimary) primary = true;
        if (primary) {
            for (int i = 0; i < all.length(); i++) {
                JSONObject row = all.optJSONObject(i);
                if (row != null && wantHost.equals(row.optString("host", ""))) {
                    try {
                        row.put("primary", false);
                    } catch (Exception ignored) {
                        // 键是常量
                    }
                }
            }
        }
        try {
            JSONObject row = found >= 0 ? all.getJSONObject(found) : new JSONObject();
            if (found < 0) {
                row.put("id", UUID.randomUUID().toString());
                all.put(row);
            }
            row.put("host", wantHost);
            row.put("username", wantUser);
            row.put("password", password);
            row.put("primary", primary);
            writeLogins(all);
        } catch (Exception e) {
            Log.i(TAG, "save account: %s", e.getMessage());
        }
    }

    public static void setPrimary(String id) {
        if (id == null || id.isEmpty()) return;
        JSONArray all = readLogins();
        String host = "";
        for (int i = 0; i < all.length(); i++) {
            JSONObject row = all.optJSONObject(i);
            if (row != null && id.equals(row.optString("id", ""))) host = row.optString("host", "");
        }
        if (host.isEmpty()) return;
        try {
            for (int i = 0; i < all.length(); i++) {
                JSONObject row = all.optJSONObject(i);
                if (row == null || !host.equals(row.optString("host", ""))) continue;
                row.put("primary", id.equals(row.optString("id", "")));
            }
            writeLogins(all);
        } catch (Exception e) {
            Log.i(TAG, "primary: %s", e.getMessage());
        }
    }

    public static void deleteAccount(String id) {
        if (id == null || id.isEmpty()) return;
        JSONArray all = readLogins();
        JSONArray next = new JSONArray();
        String host = "";
        boolean removedPrimary = false;
        for (int i = 0; i < all.length(); i++) {
            JSONObject row = all.optJSONObject(i);
            if (row == null) continue;
            if (id.equals(row.optString("id", ""))) {
                host = row.optString("host", "");
                removedPrimary = row.optBoolean("primary", false);
                continue;
            }
            next.put(row);
        }
        if (removedPrimary && !host.isEmpty()) {
            for (int i = 0; i < next.length(); i++) {
                JSONObject row = next.optJSONObject(i);
                if (row != null && host.equals(row.optString("host", ""))) {
                    try {
                        row.put("primary", true);
                    } catch (Exception ignored) {
                        // 键是常量
                    }
                    break;
                }
            }
        }
        writeLogins(next);
    }

    private static JSONArray readLogins() {
        String raw = get(LOGINS);
        if (raw == null || raw.isEmpty()) return new JSONArray();
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void writeLogins(JSONArray all) {
        put(LOGINS, all.toString());
    }

    /**
     * 浏览器认出一次登录后调用。不把口令交给系统密码服务，改由 Aero 询问要不要加密存在本机。
     * 口令不进日志。
     */
    @CalledByNative
    private static void offerFromBrowser(String username, String password, String origin) {
        if (password == null || password.isEmpty()) return;
        Activity activity = LemurXConsoleShell.activity();
        if (activity == null) return;
        String host = hostOf(origin);
        String name = username == null ? "" : username;
        ThreadUtils.runOnUiThread(() -> showOffer(activity, host, name, password));
    }

    /** 个人数据里手动添加或修改。改的时候口令留空表示沿用原来的。 */
    public static void setOnAccountsChanged(@Nullable Runnable listener) {
        sAccountsChanged = listener;
    }

    public static void editAccount(Activity activity, @Nullable Account existing) {
        ThreadUtils.runOnUiThread(() -> showEditor(activity, existing));
    }

    private static void showOffer(Activity activity, String host, String username, String password) {
        if (activity.isFinishing()) return;
        if (host.isEmpty() || password.isEmpty()) return;
        if (hasSame(host, username, password)) return;
        if (sOffer != null && sOffer.isShowing()) return;
        boolean update = hasUsername(host, username);
        boolean primary = true;
        for (Account account : accounts()) {
            if (host.equals(account.host) && account.primary
                    && !username.trim().equalsIgnoreCase(account.username.trim())) {
                primary = false;
            }
        }
        sOffer = buildOfferDialog(activity, host, username, password, update, primary);
        sOffer.show();
    }

    private static void showEditor(Activity activity, @Nullable Account existing) {
        if (activity.isFinishing()) return;
        if (sOffer != null && sOffer.isShowing()) sOffer.dismiss();
        String host = existing == null ? "" : existing.host;
        String username = existing == null ? "" : existing.username;
        sOffer = buildLoginDialog(
                activity, host, username, "", true, existing != null,
                existing == null || existing.primary, existing);
        sOffer.show();
    }

    /**
     * 登录成功后的一句确认。一键写入本机密码库，不展示口令，也不让人改字段。
     * 之后要改，去个人数据里编辑。
     */
    private static Dialog buildOfferDialog(
            Activity activity, String host, String username, String password, boolean update,
            boolean primary) {
        Context context = activity;
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 20);
        card.setPadding(pad, pad, pad, dp(context, 12));
        card.setBackground(MusePage.rounded(
                MusePage.night(context) ? 0xFF132028 : 0xFFF4F7F8, 22, context));
        TextView who = new TextView(context);
        who.setText(R.string.lemurx_login_offer_who);
        who.setTextColor(MusePage.accent(context));
        who.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        who.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(who);
        TextView body = new TextView(context);
        String shownUser = username == null ? "" : username.trim();
        int message;
        if (update) {
            message = shownUser.isEmpty()
                    ? R.string.lemurx_login_offer_update_noname
                    : R.string.lemurx_login_offer_update;
        } else {
            message = shownUser.isEmpty()
                    ? R.string.lemurx_login_offer_save_noname
                    : R.string.lemurx_login_offer_save;
        }
        body.setText(shownUser.isEmpty()
                ? context.getString(message, host)
                : context.getString(message, host, shownUser));
        body.setTextColor(MusePage.text(context));
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        body.setLineSpacing(0, 1.3f);
        body.setPadding(0, dp(context, 10), 0, dp(context, 12));
        card.addView(body);
        TextView hidden = new TextView(context);
        hidden.setText(R.string.lemurx_login_password_hidden);
        hidden.setTextColor(MusePage.muted(context));
        hidden.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hidden.setBackground(MusePage.rounded(MusePage.surface(context), 14, context));
        hidden.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        LinearLayout.LayoutParams hiddenLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hiddenLp.bottomMargin = dp(context, 14);
        card.addView(hidden, hiddenLp);
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        TextView later = action(context, R.string.lemurx_login_later, false);
        TextView save = action(context, R.string.lemurx_login_offer_save_button, true);
        later.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            saveAccount(host, shownUser, password, primary);
            dialog.dismiss();
        });
        actions.addView(later);
        actions.addView(save);
        card.addView(actions);
        FrameLayout frame = new FrameLayout(context);
        int edge = dp(context, 24);
        frame.setPadding(edge, edge, edge, edge);
        frame.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(frame);
        dialog.setOnDismissListener(d -> {
            if (sOffer == dialog) sOffer = null;
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    private static Dialog buildLoginDialog(
            Activity activity, String host, String username, String password, boolean hostEditable,
            boolean update, boolean primary, @Nullable Account existing) {
        Context context = activity;
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 20);
        card.setPadding(pad, pad, pad, dp(context, 12));
        card.setBackground(MusePage.rounded(
                MusePage.night(context) ? 0xFF132028 : 0xFFF4F7F8, 22, context));
        TextView kicker = new TextView(context);
        kicker.setText(R.string.lemurx_login_save_title);
        kicker.setTextColor(MusePage.text(context));
        kicker.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        kicker.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(kicker);
        TextView body = new TextView(context);
        body.setText(update ? R.string.lemurx_login_update_body : R.string.lemurx_login_save_body);
        body.setTextColor(MusePage.muted(context));
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setLineSpacing(0, 1.25f);
        body.setPadding(0, dp(context, 8), 0, dp(context, 14));
        card.addView(body);
        EditText site = field(context, R.string.lemurx_login_site, host, false);
        site.setEnabled(hostEditable);
        card.addView(site);
        EditText user = field(context, R.string.lemurx_login_username, username, false);
        card.addView(user);
        if (password.isEmpty()) {
            EditText secret = field(context, R.string.lemurx_login_password, "", true);
            secret.setHint(existing == null
                    ? context.getString(R.string.lemurx_login_password)
                    : context.getString(R.string.lemurx_login_password_keep));
            card.addView(secret);
            card.setTag(secret);
        } else {
            TextView hidden = new TextView(context);
            hidden.setText(R.string.lemurx_login_password_hidden);
            hidden.setTextColor(MusePage.text(context));
            hidden.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            hidden.setBackground(MusePage.rounded(MusePage.surface(context), 12, context));
            hidden.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
            LinearLayout.LayoutParams hiddenLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hiddenLp.bottomMargin = dp(context, 8);
            card.addView(hidden, hiddenLp);
        }
        CheckBox main = new CheckBox(context);
        main.setText(R.string.lemurx_login_primary_check);
        main.setTextColor(MusePage.text(context));
        main.setChecked(primary);
        card.addView(main);
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        TextView later = action(context, R.string.lemurx_login_later, false);
        TextView save = action(context, R.string.lemurx_login_save, true);
        later.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            String typedHost = site.getText().toString().trim();
            String typedUser = user.getText().toString().trim();
            String typedSecret = password;
            Object tagged = card.getTag();
            if (tagged instanceof EditText) {
                String entered = ((EditText) tagged).getText().toString();
                if (!entered.isEmpty()) typedSecret = entered;
                ((EditText) tagged).setText("");
            }
            if (typedSecret.isEmpty() && existing != null) typedSecret = existing.password;
            if (typedSecret.isEmpty()) {
                body.setText(R.string.lemurx_login_need_password);
                return;
            }
            if (existing != null) deleteAccount(existing.id);
            saveAccount(typedHost, typedUser, typedSecret, main.isChecked());
            dialog.dismiss();
        });
        actions.addView(later);
        actions.addView(save);
        card.addView(actions);
        FrameLayout frame = new FrameLayout(context);
        int edge = dp(context, 24);
        frame.setPadding(edge, edge, edge, edge);
        frame.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(frame);
        dialog.setOnDismissListener(d -> {
            if (sOffer == dialog) sOffer = null;
            if (sAccountsChanged != null) sAccountsChanged.run();
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    private static EditText field(Context context, int hint, String value, boolean secret) {
        EditText input = new EditText(context);
        input.setHint(hint);
        input.setText(value);
        input.setSingleLine(true);
        input.setTextColor(MusePage.text(context));
        input.setHintTextColor(MusePage.muted(context));
        input.setBackground(MusePage.rounded(MusePage.surface(context), 12, context));
        input.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        if (secret) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(context, 8);
        input.setLayoutParams(lp);
        return input;
    }

    private static TextView action(Context context, int label, boolean filled) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
        if (filled) {
            view.setTextColor(MusePage.onAccent(context));
            view.setBackground(MusePage.rounded(MusePage.accent(context), 16, context));
        } else {
            view.setTextColor(MusePage.muted(context));
        }
        return view;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
