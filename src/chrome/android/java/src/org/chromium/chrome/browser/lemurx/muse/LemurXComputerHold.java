// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import org.chromium.base.SplitCompatService;
import org.chromium.build.annotations.IdentifierNameString;
import org.chromium.build.annotations.NullMarked;

/**
 * 远程电脑命令还在跑时钉住的前台服务。本机浏览器操作必须看着屏幕；电脑上的命令
 * 离开 Aero 也要继续，由这一层把进程和网络保住。
 */
@NullMarked
public class LemurXComputerHold extends SplitCompatService {
    @SuppressWarnings("FieldCanBeFinal")
    private static @IdentifierNameString String sImplClassName =
            "org.chromium.chrome.browser.lemurx.muse.LemurXComputerHoldImpl";

    public LemurXComputerHold() {
        super(sImplClassName);
    }
}
