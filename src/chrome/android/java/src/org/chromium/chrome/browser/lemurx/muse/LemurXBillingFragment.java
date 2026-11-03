// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

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
import org.chromium.components.browser_ui.settings.EmbeddableSettingsPage;

/** 设置里的升级页：本周额度、两个套餐、加购包。付款页在当前标签打开。 */
@NullMarked
public class LemurXBillingFragment extends Fragment implements EmbeddableSettingsPage {
    private final SettableMonotonicObservableSupplier<String> mPageTitle =
            ObservableSuppliers.createMonotonic();
    private @Nullable LemurXBillingView mView;

    @Override
    public View onCreateView(
            LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        mPageTitle.set(getString(R.string.lemurx_billing_upgrade));
        mView = new LemurXBillingView(requireContext(), () -> {
            if (getActivity() != null) getActivity().finish();
        });
        return mView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mView != null) mView.reload();
    }

    @Override
    public void onDestroyView() {
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
}
