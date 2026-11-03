// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.content.Context;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 设置里的词元额度。数字来自服务端本周已经记下来的用量，购买要等付款回调入账。
 */
@NullMarked
public final class LemurXBilling {
    /** 服务端额度用完时放在对话错误里的代码，界面换成这句话。 */
    public static final String CODE_QUOTA = "aero-quota";

    private LemurXBilling() {}

    public static boolean chinese() {
        return Locale.getDefault().getLanguage().startsWith("zh");
    }

    public static void load(MuseApi.Callback<JSONObject> callback) {
        MuseApi.get().get("/api/billing", (value, error) -> {
            JSONObject json = value instanceof JSONObject ? (JSONObject) value : null;
            callback.onResult(json, error);
        });
    }

    public static void checkout(String sku, MuseApi.Callback<String> callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("sku", sku);
        } catch (org.json.JSONException e) {
            callback.onResult(null, e.getMessage());
            return;
        }
        MuseApi.get().send("POST", "/api/billing/checkout", body, (value, error) -> {
            String url = null;
            if (value instanceof JSONObject) url = ((JSONObject) value).optString("url", null);
            callback.onResult(url == null || url.isEmpty() ? null : url, error);
        });
    }

    /** 设置页那一行：套餐名，加上已用百分比和重置日。 */
    public static void bind(androidx.preference.Preference preference, JSONObject json) {
        Context context = preference.getContext();
        boolean zh = chinese();
        preference.setTitle(zh ? json.optString("planName") : json.optString("planNameEn"));
        preference.setSummary(
                context.getString(
                        R.string.lemurx_billing_summary,
                        json.optInt("percent"),
                        resetDay(json.optString("resetAt"))));
    }

    public static String resetDay(String iso) {
        try {
            Date date = Date.from(OffsetDateTime.parse(iso).toInstant());
            SimpleDateFormat format =
                    new SimpleDateFormat(chinese() ? "M月d日" : "MMM d", Locale.getDefault());
            format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            return format.format(date);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 把服务端的代码换成这句话。别的短错误原样留着。 */
    public static String message(Context context, @Nullable String error) {
        if (error == null) return "";
        if (error.contains(CODE_QUOTA)) return context.getString(R.string.lemurx_billing_empty);
        if (error.contains("aero-signin")) return context.getString(R.string.lemurx_billing_sign_in);
        if (error.contains("aero-pay-off")) return context.getString(R.string.lemurx_billing_pay_off);
        return error;
    }

    public static String price(int cents) {
        return String.format(Locale.US, "US$%.2f", cents / 100.0);
    }
}
