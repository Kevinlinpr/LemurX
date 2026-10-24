// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx;

import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.chromium.base.ActivityState;
import org.chromium.base.ApplicationStatus;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.lemurx.muse.MuseApi;
import org.chromium.chrome.browser.lemurx.muse.MusePage;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/**
 * 打开应用、以及回到前台时，问当前这台服务器要不要换包。版本号、是否强制、
 * 包的地址都在服务器上。测试服的清单带 {@code channel:test}，对话框上会标出来。
 * 包本身不进 API 进程，由 {@code /releases/} 分发。
 */
@NullMarked
public final class LemurXAppUpdate {
    private static final String TAG = "LemurXUpdate";
    private static final long POLL_MS = 400;
    /** 回到前台时不要每次都打服务器。一分钟内问过就用上一次的结果。 */
    private static final long PROBE_INTERVAL_MS = 60_000;
    private static final int CYAN = 0xFF2EE6D6;
    private static final int TEST_MARK = 0xFF7A4B00;
    private static final int TEST_MARK_BG = 0xFFFFE3A3;

    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private static @Nullable Activity sActivity;
    private static ApplicationStatus.@Nullable ActivityStateListener sResumeListener;
    private static boolean sShown;
    private static boolean sProbeInFlight;
    private static long sLastProbe;
    /** 用户关掉的那一版。同一版不再弹，更新的版本号会再问一次。 */
    private static long sDismissedCode;
    private static boolean sDownloading;
    private static long sDownloadId = -1;
    private static @Nullable Dialog sDialog;
    private static @Nullable TextView sMessage;
    private static @Nullable FrameLayout sTrack;
    private static @Nullable View sFill;
    private static @Nullable TextView sProgress;
    private static @Nullable TextView sAction;

    private LemurXAppUpdate() {}

    public static void attach(Activity activity) {
        ThreadUtils.assertOnUiThread();
        sActivity = activity;
        watchResume(activity);
        maybeProbe(activity);
    }

    public static void detach(Activity activity) {
        if (sActivity != activity) return;
        if (sResumeListener != null) {
            ApplicationStatus.unregisterActivityStateListener(sResumeListener);
            sResumeListener = null;
        }
        sHandler.removeCallbacksAndMessages(null);
        sActivity = null;
        sDialog = null;
        sMessage = null;
        sTrack = null;
        sFill = null;
        sProgress = null;
        sAction = null;
        if (!sDownloading) sShown = false;
    }

    /** 回到前台再问一次。人在公司外面改完包，手机下次打开就能看见。 */
    private static void watchResume(Activity activity) {
        if (sResumeListener != null) {
            ApplicationStatus.unregisterActivityStateListener(sResumeListener);
        }
        sResumeListener =
                (watched, state) -> {
                    if (watched != sActivity || state != ActivityState.RESUMED) return;
                    maybeProbe(activity);
                };
        ApplicationStatus.registerStateListenerForActivity(sResumeListener, activity);
    }

    private static void maybeProbe(Activity activity) {
        if (sShown || sDownloading || sProbeInFlight) return;
        long now = SystemClock.uptimeMillis();
        if (sLastProbe != 0 && now - sLastProbe < PROBE_INTERVAL_MS) return;
        sLastProbe = now;
        sProbeInFlight = true;
        MuseApi.get()
                .probeRelease(
                        (json, error) -> {
                            sProbeInFlight = false;
                            if (json == null || sActivity != activity) return;
                            consider(activity, json);
                        });
    }

    private static void consider(Activity activity, JSONObject json) {
        long latest = json.optLong("versionCode", 0);
        String url = json.optString("url", "");
        if (latest <= 0 || !sameServer(url)) return;
        if (latest == sDismissedCode) return;
        long installed = installedVersion(activity);
        if (installed <= 0 || latest <= installed) {
            Log.i(TAG, "up to date local=%d latest=%d", installed, latest);
            return;
        }
        boolean force = json.optBoolean("force", true);
        boolean testChannel = "test".equals(json.optString("channel", ""));
        String name = json.optString("versionName", Long.toString(latest));
        String notes = json.optString("notes", "");
        show(activity, latest, name, notes, url, force, testChannel);
    }

    /**
     * 正式包只走 https。测试服是明文的，只接受和当前对话服务同一台机器、同一个端口上的包。
     */
    private static boolean sameServer(String url) {
        if (url.startsWith("https://")) return true;
        if (!url.startsWith("http://")) return false;
        Uri release = Uri.parse(url);
        Uri base = Uri.parse(MuseApi.apiUrl());
        String host = release.getHost();
        if (host == null || base.getHost() == null || !host.equalsIgnoreCase(base.getHost())) {
            return false;
        }
        int releasePort = release.getPort() == -1 ? 80 : release.getPort();
        int basePort = base.getPort() == -1 ? 80 : base.getPort();
        return releasePort == basePort;
    }

    private static long installedVersion(Activity activity) {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return info.getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    private static void show(
            Activity activity,
            long versionCode,
            String versionName,
            String notes,
            String url,
            boolean force,
            boolean testChannel) {
        if (sShown || activity.isFinishing() || activity.isDestroyed()) return;
        sShown = true;
        String label = testChannel ? versionName + " · " + versionCode : versionName;
        String message = !notes.isEmpty()
                ? notes
                : testChannel && !force
                        ? activity.getString(R.string.lemurx_update_test_message, label)
                        : activity.getString(R.string.lemurx_update_message, versionName);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(MusePage.bg(activity), 28, activity));
        int pad = dp(activity, 22);
        card.setPadding(pad, dp(activity, 22), pad, dp(activity, 18));

        ImageView logo = new ImageView(activity);
        logo.setImageResource(R.mipmap.app_icon);
        logo.setContentDescription(activity.getString(R.string.lemurx_update_title));
        logo.setClipToOutline(true);
        logo.setOutlineProvider(
                new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, android.graphics.Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getWidth() * 0.26f);
                    }
                });
        card.addView(logo, new LinearLayout.LayoutParams(dp(activity, 56), dp(activity, 56)));

        if (testChannel) {
            TextView mark = new TextView(activity);
            mark.setText(R.string.lemurx_update_test_channel);
            mark.setTextColor(TEST_MARK);
            mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            mark.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            mark.setBackground(rounded(TEST_MARK_BG, 999, activity));
            mark.setPadding(dp(activity, 10), dp(activity, 4), dp(activity, 10), dp(activity, 4));
            LinearLayout.LayoutParams markLp = wrap();
            markLp.topMargin = dp(activity, 16);
            card.addView(mark, markLp);
        }

        TextView kicker = new TextView(activity);
        kicker.setText(label);
        kicker.setTextColor(MusePage.chipText(activity));
        kicker.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        kicker.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        kicker.setBackground(rounded(MusePage.chipBg(activity), 999, activity));
        kicker.setPadding(dp(activity, 10), dp(activity, 4), dp(activity, 10), dp(activity, 4));
        LinearLayout.LayoutParams kickerLp = wrap();
        kickerLp.topMargin = testChannel ? dp(activity, 8) : dp(activity, 16);
        card.addView(kicker, kickerLp);

        TextView title = new TextView(activity);
        title.setText(R.string.lemurx_update_title);
        title.setTextColor(MusePage.text(activity));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = wrap();
        titleLp.topMargin = dp(activity, 14);
        card.addView(title, titleLp);

        TextView body = new TextView(activity);
        body.setText(message);
        body.setTextColor(MusePage.muted(activity));
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        body.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(activity, 8);
        card.addView(body, bodyLp);

        FrameLayout track = new FrameLayout(activity);
        track.setBackground(rounded(MusePage.track(activity), 999, activity));
        track.setVisibility(View.GONE);
        View fill = new View(activity);
        fill.setBackground(rounded(CYAN, 999, activity));
        track.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 12));
        barLp.topMargin = dp(activity, 18);
        card.addView(track, barLp);

        TextView progress = new TextView(activity);
        progress.setTextColor(MusePage.text(activity));
        progress.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        progress.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = wrap();
        progressLp.topMargin = dp(activity, 8);
        card.addView(progress, progressLp);

        TextView action = new TextView(activity);
        action.setText(R.string.lemurx_update_action);
        action.setTextColor(MusePage.onAccent(activity));
        action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        action.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        action.setGravity(Gravity.CENTER);
        action.setBackground(rounded(MusePage.accent(activity), 16, activity));
        action.setPadding(0, dp(activity, 14), 0, dp(activity, 14));
        action.setOnClickListener(
                v -> {
                    LemurXHaptics.click(v);
                    startOrPermit(activity, versionCode, url);
                });
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionLp.topMargin = dp(activity, 18);
        card.addView(action, actionLp);

        if (!force) {
            TextView later = new TextView(activity);
            later.setText(android.R.string.cancel);
            later.setTextColor(MusePage.muted(activity));
            later.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            later.setGravity(Gravity.CENTER);
            later.setPadding(0, dp(activity, 12), 0, dp(activity, 2));
            later.setOnClickListener(
                    v -> {
                        sDismissedCode = versionCode;
                        Dialog open = sDialog;
                        if (open != null) open.dismiss();
                    });
            card.addView(later, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        Dialog dialog = new Dialog(activity);
        dialog.setContentView(card);
        dialog.setCancelable(!force);
        dialog.setCanceledOnTouchOutside(!force);
        if (!force) {
            dialog.setOnDismissListener(d -> sDismissedCode = versionCode);
        }
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.86f);
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        sDialog = dialog;
        sMessage = body;
        sTrack = track;
        sFill = fill;
        sProgress = progress;
        sAction = action;
        try {
            dialog.show();
        } catch (Exception e) {
            Log.i(TAG, "dialog: %s", e.getMessage());
            sShown = false;
            sDialog = null;
        }
    }

    private static void startOrPermit(Activity activity, long versionCode, String url) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            try {
                activity.startActivity(
                        new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                                .setData(Uri.parse("package:" + activity.getPackageName())));
            } catch (Exception e) {
                Log.i(TAG, "install permission: %s", e.getMessage());
            }
            return;
        }
        DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            setMessage(activity.getString(R.string.lemurx_update_failed));
            return;
        }
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setTitle(activity.getString(R.string.lemurx_update_title));
        request.setMimeType("application/vnd.android.package-archive");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        request.setAllowedOverMetered(true);
        String fileName = "Aero-" + versionCode + ".apk";
        File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir != null) {
            File existing = new File(dir, fileName);
            if (existing.exists() && !existing.delete()) {
                Log.i(TAG, "could not replace previous package");
            }
        }
        request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName);
        try {
            sDownloadId = manager.enqueue(request);
        } catch (Exception e) {
            Log.i(TAG, "enqueue: %s", e.getMessage());
            setMessage(activity.getString(R.string.lemurx_update_failed));
            return;
        }
        sDownloading = true;
        Dialog dialog = sDialog;
        if (dialog != null) dialog.setCancelable(false);
        TextView action = sAction;
        if (action != null) {
            action.setEnabled(false);
            action.setTextColor(MusePage.onDisabled(activity));
            action.setBackground(rounded(MusePage.disabled(activity), 16, activity));
            action.setText(R.string.lemurx_update_downloading);
        }
        paintProgress(activity, 0, -1);
        poll(activity, manager);
    }

    private static void poll(Activity activity, DownloadManager manager) {
        sHandler.postDelayed(
                () -> {
                    if (sActivity != activity || sDownloadId < 0) return;
                    int status = DownloadManager.STATUS_PENDING;
                    long got = 0;
                    long total = -1;
                    try (Cursor cursor = manager.query(
                            new DownloadManager.Query().setFilterById(sDownloadId))) {
                        if (cursor != null && cursor.moveToFirst()) {
                            status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                            int gotCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                            int totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                            if (gotCol >= 0) got = cursor.getLong(gotCol);
                            if (totalCol >= 0) total = cursor.getLong(totalCol);
                        }
                    } catch (Exception e) {
                        status = DownloadManager.STATUS_FAILED;
                    }
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        paintProgress(activity, total > 0 ? total : got, total > 0 ? total : got);
                        setMessage(activity.getString(R.string.lemurx_update_install));
                        install(activity, manager);
                        return;
                    }
                    if (status == DownloadManager.STATUS_FAILED) {
                        sDownloading = false;
                        setMessage(activity.getString(R.string.lemurx_update_failed));
                        TextView action = sAction;
                        if (action != null) {
                            action.setEnabled(true);
                            action.setTextColor(MusePage.onAccent(activity));
                            action.setBackground(rounded(MusePage.accent(activity), 16, activity));
                            action.setText(R.string.lemurx_update_action);
                        }
                        return;
                    }
                    paintProgress(activity, got, total);
                    poll(activity, manager);
                },
                POLL_MS);
    }

    private static void paintProgress(Activity activity, long got, long total) {
        FrameLayout track = sTrack;
        View fill = sFill;
        TextView progress = sProgress;
        if (track == null || fill == null || progress == null) return;
        track.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        if (total > 0) {
            int pct = (int) Math.min(100, (got * 100) / total);
            progress.setText(activity.getString(
                    R.string.lemurx_update_progress, pct, megabytes(got), megabytes(total)));
            track.post(() -> setFill(track, fill, pct));
        } else {
            progress.setText(activity.getString(R.string.lemurx_update_downloading));
            track.post(() -> setFill(track, fill, 8));
        }
    }

    private static void setFill(FrameLayout track, View fill, int pct) {
        int width = track.getWidth();
        if (width <= 0) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fill.getLayoutParams();
        int next = pct <= 0 ? 0 : Math.max(track.getHeight(), width * pct / 100);
        if (lp.width == next) return;
        lp.width = next;
        fill.setLayoutParams(lp);
    }

    private static String megabytes(long bytes) {
        double mb = Math.max(0, bytes) / 1048576.0;
        if (mb >= 10) return String.format(Locale.getDefault(), "%.0f MB", mb);
        return String.format(Locale.getDefault(), "%.1f MB", mb);
    }

    private static void setMessage(String text) {
        TextView message = sMessage;
        if (message != null) message.setText(text);
    }

    private static void install(Activity activity, DownloadManager manager) {
        Uri uri = manager.getUriForDownloadedFile(sDownloadId);
        if (uri == null) return;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.i(TAG, "install: %s", e.getMessage());
        }
    }

    private static GradientDrawable rounded(int color, float radiusDp, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusDp * context.getResources().getDisplayMetrics().density);
        return drawable;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
