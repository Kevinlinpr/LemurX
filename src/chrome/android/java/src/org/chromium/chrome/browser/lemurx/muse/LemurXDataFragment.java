// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;

import org.chromium.base.supplier.MonotonicObservableSupplier;
import org.chromium.base.supplier.ObservableSuppliers;
import org.chromium.base.supplier.SettableMonotonicObservableSupplier;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.settings.ProfileDependentSetting;
import org.chromium.components.browser_ui.settings.EmbeddableSettingsPage;
import org.chromium.ui.modaldialog.ModalDialogManager;
import org.chromium.ui.modaldialog.ModalDialogManagerHolder;

/**
 * ☰ 设置 → 个人数据。把 {@link MuseDataView} 包成 SettingsActivity 里的一页：密码 / 支付 / 地址
 * （Chromium 原生入口）和给 LemurX 的资料。全局数据，所以放这里而不是底部导航栏。
 */
@NullMarked
public class LemurXDataFragment extends Fragment
        implements EmbeddableSettingsPage, ProfileDependentSetting, MuseDataView.Host {
    private final SettableMonotonicObservableSupplier<String> mPageTitle =
            ObservableSuppliers.createMonotonic();
    private @Nullable Profile mProfile;
    private @Nullable MuseDataView mView;

    @Override
    public View onCreateView(
            LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        mPageTitle.set(getString(R.string.lemurx_muse_data));
        mView = new MuseDataView(requireContext(), this);
        return mView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mView != null) mView.refreshNow();
    }

    @Override
    public void onDestroyView() {
        if (mView != null) mView.destroy();
        mView = null;
        super.onDestroyView();
    }

    @Override
    public MonotonicObservableSupplier<String> getPageTitle() {
        return mPageTitle;
    }

    @Override
    public @AnimationType int getAnimationType() {
        return AnimationType.PROPERTY;
    }

    @Override
    public void setProfile(Profile profile) {
        mProfile = profile;
    }

    @Override
    public @Nullable Profile profile() {
        return mProfile;
    }

    @Override
    public @Nullable ModalDialogManager dialogs() {
        Activity activity = getActivity();
        if (activity instanceof ModalDialogManagerHolder) {
            return ((ModalDialogManagerHolder) activity).getModalDialogManager();
        }
        return null;
    }
}
