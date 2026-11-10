// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.firebase;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.concurrent.Future;

/**
 * play-services-measurement-impl 24.0.0 会调用 {@code Futures.propagateCancellation}，
 * 公开的 Guava 里没有这个方法。测量库的字节码被改到这里：新 future 被取消时，取消它依赖的 future。
 */
public final class GuavaFuturesCompat {
    private GuavaFuturesCompat() {}

    public static void propagateCancellation(ListenableFuture<?> future, Future<?> related) {
        if (future == null || related == null) {
            return;
        }
        future.addListener(
                () -> {
                    if (future.isCancelled()) {
                        related.cancel(false);
                    }
                },
                MoreExecutors.directExecutor());
    }
}
