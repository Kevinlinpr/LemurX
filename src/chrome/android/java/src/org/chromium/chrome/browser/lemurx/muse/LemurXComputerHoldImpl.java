// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.SplitCompatService;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.components.browser_ui.notifications.ForegroundServiceUtils;

/** {@link LemurXComputerHold} 的实现：有远程命令时 startForeground，跑完就停。 */
@NullMarked
public class LemurXComputerHoldImpl extends SplitCompatService.Impl {
    private static final String TAG = "LemurXHold";
    private static final String CHANNEL = "lemurx_computer";
    private static final int NOTIFY_ID = 7302;
    private static boolean sHeld;

    /** 对话里还有远程电脑命令时钉住；没有了就放开。 */
    static void sync(boolean hold) {
        ThreadUtils.assertOnUiThread();
        if (hold == sHeld) return;
        sHeld = hold;
        Context ctx = ContextUtils.getApplicationContext();
        Intent intent = new Intent(ctx, LemurXComputerHold.class);
        try {
            if (hold) {
                ForegroundServiceUtils.getInstance().startForegroundService(intent);
            } else {
                ctx.stopService(intent);
            }
        } catch (Exception e) {
            Log.i(TAG, "sync: %s", e.getMessage());
            sHeld = !hold;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = holdNotification();
        if (notification != null) {
            ForegroundServiceUtils.getInstance()
                    .startForeground(
                            getService(),
                            NOTIFY_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
        if (!sHeld) {
            getService().stopSelf(startId);
            return Service.START_NOT_STICKY;
        }
        return Service.START_STICKY;
    }

    @Override
    public @Nullable IBinder onBind(Intent intent) {
        return null;
    }

    private @Nullable Notification holdNotification() {
        Context ctx = ContextUtils.getApplicationContext();
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                        new NotificationChannel(
                                CHANNEL,
                                ctx.getString(R.string.lemurx_muse_computer_hold_channel),
                                NotificationManager.IMPORTANCE_LOW));
            }
            Notification.Builder b =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? new Notification.Builder(ctx, CHANNEL)
                            : new Notification.Builder(ctx);
            b.setContentTitle(ctx.getString(R.string.lemurx_muse_computer_hold_title))
                    .setContentText(ctx.getString(R.string.lemurx_muse_computer_hold_text))
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true);
            Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (open != null) {
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                b.setContentIntent(
                        PendingIntent.getActivity(
                                ctx,
                                NOTIFY_ID,
                                open,
                                PendingIntent.FLAG_UPDATE_CURRENT
                                        | PendingIntent.FLAG_IMMUTABLE));
            }
            return b.build();
        } catch (Exception e) {
            Log.i(TAG, "notify: %s", e.toString());
            return null;
        }
    }
}
