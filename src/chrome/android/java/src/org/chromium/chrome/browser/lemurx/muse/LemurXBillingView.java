// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.lemurx.LemurXConsoleShell;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 升级和加购画在同一页。选中一档再点继续，才会去付款页。没付完的单不加额度。
 */
@NullMarked
public class LemurXBillingView extends ScrollView {
    private static final int PAPER = 0xFFF7F5F2;
    private static final int INK = 0xFF132631;
    private static final int MUTED = 0xFF5C6B73;
    private static final int CARD = 0xFFFFFFFF;
    private static final int LINE = 0xFFE4E0DA;
    private static final int ACCENT = 0xFF1A56DB;

    /** 付款页打开之后关掉设置，让当前标签露出来。 */
    public interface Host {
        void closeSettings();
    }

    private final Host mHost;
    private final LinearLayout mBody;
    private final TextView mStatus;
    private @Nullable JSONObject mSnapshot;
    private @Nullable String mSelected;

    public LemurXBillingView(Context context, Host host) {
        super(context);
        mHost = host;
        setFillViewport(true);
        setBackgroundColor(PAPER);
        int pad = MusePage.dp(context, 20);
        mBody = new LinearLayout(context);
        mBody.setOrientation(LinearLayout.VERTICAL);
        mBody.setPadding(pad, pad, pad, MusePage.dp(context, 28));
        addView(mBody, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        mStatus = text(context, 15, MUTED);
        mStatus.setText(R.string.lemurx_billing_loading);
        mBody.addView(mStatus);
    }

    public void reload() {
        LemurXBilling.load((json, error) -> {
            if (json == null) {
                mStatus.setText(
                        error == null
                                ? getContext().getString(R.string.lemurx_billing_unavailable)
                                : LemurXBilling.message(getContext(), error));
                return;
            }
            mSnapshot = json;
            if (mSelected == null) mSelected = "plus";
            render();
        });
    }

    private void render() {
        JSONObject json = mSnapshot;
        if (json == null) return;
        mBody.removeAllViews();
        Context context = getContext();
        boolean zh = LemurXBilling.chinese();
        String plan = zh ? json.optString("planName") : json.optString("planNameEn");
        TextView title = text(context, 22, INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setText(plan);
        mBody.addView(title);

        TextView used = text(context, 14, MUTED);
        used.setText(
                context.getString(
                        R.string.lemurx_billing_summary,
                        json.optInt("percent"),
                        LemurXBilling.resetDay(json.optString("resetAt"))));
        used.setPadding(0, MusePage.dp(context, 6), 0, MusePage.dp(context, 10));
        mBody.addView(used);

        ProgressBar bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(json.optInt("percent"));
        mBody.addView(bar, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, MusePage.dp(context, 8)));

        long packs = json.optLong("packTokens");
        if (packs > 0) {
            TextView extra = text(context, 14, MUTED);
            extra.setText(context.getString(R.string.lemurx_billing_pack_left, formatCount(packs)));
            extra.setPadding(0, MusePage.dp(context, 8), 0, 0);
            mBody.addView(extra);
        }

        TextView lead = text(context, 15, INK);
        lead.setText(R.string.lemurx_billing_lead);
        lead.setPadding(0, MusePage.dp(context, 18), 0, MusePage.dp(context, 12));
        mBody.addView(lead);

        addSkus(json.optJSONArray("plans"));
        TextView more = text(context, 16, ACCENT);
        more.setText(R.string.lemurx_billing_buy_more);
        more.setTypeface(Typeface.DEFAULT_BOLD);
        more.setPadding(0, MusePage.dp(context, 8), 0, MusePage.dp(context, 12));
        mBody.addView(more);
        addSkus(json.optJSONArray("packs"));

        TextView button = text(context, 16, 0xFFFFFFFF);
        button.setText(R.string.lemurx_billing_continue);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(ACCENT);
        shape.setCornerRadius(MusePage.dp(context, 24));
        button.setBackground(shape);
        int v = MusePage.dp(context, 14);
        button.setPadding(v, v, v, v);
        button.setOnClickListener(v1 -> buy());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = MusePage.dp(context, 8);
        mBody.addView(button, lp);

        TextView fine = text(context, 12, MUTED);
        fine.setText(json.optBoolean("paymentReady")
                ? R.string.lemurx_billing_fine
                : R.string.lemurx_billing_pay_off);
        fine.setPadding(0, MusePage.dp(context, 12), 0, 0);
        mBody.addView(fine);
    }

    private void addSkus(@Nullable JSONArray rows) {
        if (rows == null) return;
        Context context = getContext();
        boolean zh = LemurXBilling.chinese();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String id = row.optString("id");
            boolean on = id.equals(mSelected);
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            int pad = MusePage.dp(context, 16);
            card.setPadding(pad, pad, pad, pad);
            GradientDrawable plate = new GradientDrawable();
            plate.setColor(CARD);
            plate.setCornerRadius(MusePage.dp(context, 16));
            plate.setStroke(MusePage.dp(context, on ? 2 : 1), on ? ACCENT : LINE);
            card.setBackground(plate);
            TextView name = text(context, 17, INK);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setText(zh ? row.optString("name") : row.optString("nameEn"));
            TextView price = text(context, 14, MUTED);
            price.setText(LemurXBilling.price(row.optInt("cents"))
                    + "  ·  "
                    + (zh ? row.optString("detail") : row.optString("detailEn")));
            card.addView(name);
            card.addView(price);
            card.setOnClickListener(v -> {
                mSelected = id;
                render();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = MusePage.dp(context, 10);
            mBody.addView(card, lp);
        }
    }

    private void buy() {
        String sku = mSelected;
        if (sku == null) return;
        Context context = getContext();
        if (mSnapshot != null && !mSnapshot.optBoolean("signedIn")) {
            Toast.makeText(context, R.string.lemurx_billing_sign_in, Toast.LENGTH_LONG).show();
            return;
        }
        LemurXBilling.checkout(sku, (url, error) -> {
            if (url == null) {
                Toast.makeText(context, LemurXBilling.message(context, error), Toast.LENGTH_LONG).show();
                return;
            }
            LemurXConsoleShell.openBillingPage(url);
            mHost.closeSettings();
        });
    }

    private static TextView text(Context context, int sp, int color) {
        TextView view = new TextView(context);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private String formatCount(long tokens) {
        if (LemurXBilling.chinese()) {
            if (tokens >= 100_000_000) return (tokens / 100_000_000.0) + " 亿";
            return (tokens / 10_000) + " 万";
        }
        if (tokens >= 1_000_000) return (tokens / 1_000_000) + " million";
        return Long.toString(tokens);
    }
}
