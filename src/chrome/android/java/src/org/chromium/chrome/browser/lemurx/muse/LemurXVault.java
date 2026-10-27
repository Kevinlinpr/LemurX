// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
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
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.lemurx.LemurXHaptics;
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
 * <p>个人数据落点（flagship wave 4，钉在代码里）：
 * <ul>
 *   <li>密码 / passkey → Chromium PasswordStore（设备加密；设置 → 密码）
 *   <li>银行卡 / 地址 → Chrome Autofill（设备加密；设置 → 支付 / 地址）；智能体读不到
 *   <li>Tailscale master auth key、computer run 全文 → 本 vault；云端只有不透明引用 / metadata
 *   <li>「给 Aero 的资料」memories → 服务端，只放偏好，禁止口令 / 卡号
 * </ul>
 */
@NullMarked
final class LemurXVault {
    private static final String TAG = "LemurXVault";
    private static final String PREFS = "aero-vault";
    private static final String KEY_ALIAS = "aero_vault_aes";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TAILSCALE_JOIN = "tailscale.join";
    private static final String RUN_PREFIX = "run:";

    private LemurXVault() {}

    private static SharedPreferences prefs() {
        return ContextUtils.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
}
