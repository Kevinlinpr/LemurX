// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.content.res.AppCompatResources;

import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import org.chromium.base.supplier.MonotonicObservableSupplier;
import org.chromium.base.supplier.ObservableSuppliers;
import org.chromium.base.supplier.SettableMonotonicObservableSupplier;
import org.chromium.base.version_info.VersionInfo;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.lemurx.LemurXConsoleShell;
import org.chromium.chrome.browser.settings.ChromeBaseSettingsFragment;
import org.chromium.chrome.browser.settings.SettingsNavigationFactory;
import org.chromium.components.browser_ui.settings.EmbeddableSettingsPage;
import org.chromium.components.browser_ui.settings.SettingsNavigation.SettingsFragment;

/**
 * 设置页。左上角 ☰ 由 {@link MuseDrawer} 从左侧滑出同一组入口；这一页留给那些入口
 * 再往下走（个人数据、全部设置）时仍可打开。
 *
 * <p>分组（对应 Lemur 浏览器"个人中心"的结构，见 lemur_project chrome/lemur/user）：
 *
 * <ul>
 *   <li>账号：登录 / 账号资料 / 退出（{@link LemurXAccount}）。
 *   <li>个人数据：密码 / 支付 / 地址 / 给 LemurX 的资料（{@link LemurXDataFragment}），全局的，
 *       所以不在底部导航栏里。
 *   <li>智能体服务：服务器地址、访问密钥、重新连接（{@link MuseApi}）。
 *   <li>浏览器：密码与自动填充、搜索引擎、全部 Chromium 设置。
 *   <li>关于：版本。
 * </ul>
 *
 * <p>偏好项全部代码创建，不用 XML，方便和原生页面一起维护。
 */
@NullMarked
public class LemurXSettingsFragment extends ChromeBaseSettingsFragment
        implements EmbeddableSettingsPage {
    private final SettableMonotonicObservableSupplier<String> mPageTitle =
            ObservableSuppliers.createMonotonic();

    /**
     * 服务器地址类的设置（账号网关、智能体服务地址 / 访问密钥 / 重新连接）要不要给用户看。
     * 这些是部署参数，不是用户的事：产品里一律不显示，值用代码里的默认；调试时改成 true。
     */
    private static final boolean SHOW_SERVER_SETTINGS = false;

    private @Nullable Preference mAccount;
    private @Nullable Preference mBilling;

    /** Google 账号选择页的回执；必须在 fragment STARTED 之前注册，所以放在字段初始化里。 */
    private final ActivityResultLauncher<Intent> mGoogleLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> onGoogleResult(result.getData()));

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        Context context = getPreferenceManager().getContext();
        mPageTitle.set(context.getString(R.string.lemurx_muse_settings_title));
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);

        // ---- 词元额度。点进去升级或加购；数字是服务端本周已经记下的用量。
        PreferenceCategory usage = category(context, screen, R.string.lemurx_billing_heading);
        mBilling = new Preference(context);
        mBilling.setKey("lemurx_billing");
        mBilling.setTitle(R.string.lemurx_billing_loading);
        LemurXSettingsIcons.set(mBilling, R.drawable.lemurx_muse_ic_card);
        mBilling.setOnPreferenceClickListener(
                p -> {
                    SettingsNavigationFactory.createSettingsNavigation()
                            .startSettings(context, LemurXBillingFragment.class);
                    return true;
                });
        usage.addPreference(mBilling);

        // ---- 账号
        PreferenceCategory account = category(context, screen, R.string.lemurx_muse_settings_account);
        // 和 Chrome SignInPreference 同一套原生行：account_management_account_row。
        // 登录 / 退出只改 title、summary、icon，不换 layout、不插删行，避免卡片被挤扁。
        mAccount = new Preference(context);
        mAccount.setKey("lemurx_account");
        mAccount.setLayoutResource(R.layout.account_management_account_row);
        mAccount.setIcon(AppCompatResources.getDrawable(context, R.drawable.account_circle_with_bg));
        mAccount.setOnPreferenceClickListener(
                p -> {
                    if (LemurXAccount.isSignedIn()) {
                        showProfile();
                    } else {
                        showSignIn();
                    }
                    return true;
                });
        account.addPreference(mAccount);
        if (SHOW_SERVER_SETTINGS) addAccountServer(context, account);
        updateAccount();

        // ---- 个人数据
        PreferenceCategory data = category(context, screen, R.string.lemurx_muse_data);
        Preference personal = new Preference(context);
        personal.setKey("lemurx_data");
        personal.setTitle(R.string.lemurx_muse_settings_data);
        personal.setSummary(R.string.lemurx_muse_settings_data_hint);
        LemurXSettingsIcons.set(personal, R.drawable.lemurx_muse_ic_data);
        personal.setOnPreferenceClickListener(
                p -> {
                    SettingsNavigationFactory.createSettingsNavigation()
                            .startSettings(context, LemurXDataFragment.class);
                    return true;
                });
        data.addPreference(personal);

        // ---- 智能体服务
        if (SHOW_SERVER_SETTINGS) addAgentServer(context, screen);

        // ---- 浏览器
        PreferenceCategory browser = category(context, screen, R.string.lemurx_muse_settings_browser);
        browser.addPreference(chromeSettings(context, "lemurx_autofill",
                R.string.lemurx_muse_settings_autofill, SettingsFragment.AUTOFILL_AND_PASSWORDS,
                R.drawable.lemurx_muse_ic_key));
        browser.addPreference(chromeSettings(context, "lemurx_search_engine",
                R.string.lemurx_muse_settings_search_engine, SettingsFragment.SEARCH_ENGINE,
                R.drawable.lemurx_console_ic_search));
        browser.addPreference(chromeSettings(context, "lemurx_privacy",
                R.string.lemurx_muse_settings_privacy, SettingsFragment.PRIVACY,
                R.drawable.lemurx_muse_ic_shield));
        browser.addPreference(chromeSettings(context, "lemurx_all_settings",
                R.string.lemurx_muse_settings_all, SettingsFragment.MAIN,
                R.drawable.lemurx_muse_ic_settings));

        // ---- 关于
        PreferenceCategory about = category(context, screen, R.string.lemurx_muse_settings_about);
        Preference version = new Preference(context);
        version.setKey("lemurx_version");
        version.setTitle(R.string.lemurx_muse_brand);
        version.setSummary(VersionInfo.getProductVersion());
        version.setSelectable(false);
        LemurXSettingsIcons.set(version, R.drawable.lemurx_muse_ic_info);
        about.addPreference(version);

        setPreferenceScreen(screen);
    }

    /** 账号网关地址（部署参数，默认不显示，见 SHOW_SERVER_SETTINGS）。 */
    private void addAccountServer(Context context, PreferenceCategory account) {
        EditTextPreference gateway = new EditTextPreference(context);
        gateway.setKey("lemurx_account_gateway");
        gateway.setTitle(R.string.lemurx_muse_settings_account_server);
        gateway.setDialogTitle(R.string.lemurx_muse_settings_account_server);
        LemurXSettingsIcons.set(gateway, R.drawable.lemurx_muse_ic_server);
        gateway.setPersistent(false);
        gateway.setText(LemurXAccount.gateway());
        gateway.setSummary(LemurXAccount.gateway());
        gateway.setOnBindEditTextListener(e -> e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI));
        gateway.setOnPreferenceChangeListener(
                (p, value) -> {
                    LemurXAccount.setGateway(value == null ? null : value.toString());
                    p.setSummary(LemurXAccount.gateway());
                    return true;
                });
        account.addPreference(gateway);
    }

    /** 智能体服务：服务器地址、访问密钥、重新连接（部署参数，默认不显示，见 SHOW_SERVER_SETTINGS）。 */
    private void addAgentServer(Context context, PreferenceScreen screen) {
        PreferenceCategory agent = category(context, screen, R.string.lemurx_muse_settings_agent);
        EditTextPreference api = new EditTextPreference(context);
        api.setKey("lemurx_agent_url");
        api.setTitle(R.string.lemurx_muse_settings_agent_server);
        api.setDialogTitle(R.string.lemurx_muse_settings_agent_server);
        LemurXSettingsIcons.set(api, R.drawable.lemurx_muse_ic_server);
        api.setPersistent(false);
        api.setText(MuseApi.apiUrl());
        api.setSummary(MuseApi.apiUrl());
        api.setOnBindEditTextListener(e -> e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI));
        api.setOnPreferenceChangeListener(
                (p, value) -> {
                    MuseApi.setApiUrl(value == null ? null : value.toString());
                    p.setSummary(MuseApi.apiUrl());
                    return true;
                });
        agent.addPreference(api);
        EditTextPreference key = new EditTextPreference(context);
        key.setKey("lemurx_agent_key");
        key.setTitle(R.string.lemurx_muse_settings_agent_key);
        key.setDialogTitle(R.string.lemurx_muse_settings_agent_key);
        LemurXSettingsIcons.set(key, R.drawable.lemurx_muse_ic_key);
        key.setPersistent(false);
        key.setText("");
        key.setSummary(keySummary());
        key.setOnBindEditTextListener(
                e -> e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
        key.setOnPreferenceChangeListener(
                (p, value) -> {
                    MuseApi.setAccessKey(value == null ? null : value.toString());
                    p.setSummary(keySummary());
                    return true;
                });
        agent.addPreference(key);
        Preference reconnect = new Preference(context);
        reconnect.setKey("lemurx_agent_reconnect");
        reconnect.setTitle(R.string.lemurx_muse_settings_agent_reconnect);
        reconnect.setSummary(R.string.lemurx_muse_settings_agent_reconnect_hint);
        LemurXSettingsIcons.set(reconnect, R.drawable.lemurx_muse_ic_refresh);
        reconnect.setOnPreferenceClickListener(
                p -> {
                    MuseApi.signOut();
                    Toast.makeText(context, R.string.lemurx_muse_settings_agent_reconnected, Toast.LENGTH_SHORT).show();
                    return true;
                });
        agent.addPreference(reconnect);
    }

    private int keySummary() {
        return MuseApi.hasAccessKey()
                ? R.string.lemurx_muse_settings_agent_key_set
                : R.string.lemurx_muse_settings_agent_key_unset;
    }

    private static PreferenceCategory category(Context context, PreferenceScreen screen, int title) {
        PreferenceCategory category = new PreferenceCategory(context);
        category.setTitle(title);
        screen.addPreference(category);
        return category;
    }

    private static Preference chromeSettings(
            Context context, String key, int title, @SettingsFragment int fragment, int icon) {
        Preference preference = new Preference(context);
        preference.setKey(key);
        preference.setTitle(title);
        LemurXSettingsIcons.set(preference, icon);
        preference.setOnPreferenceClickListener(
                p -> {
                    SettingsNavigationFactory.createSettingsNavigation().startSettings(context, fragment);
                    return true;
                });
        return preference;
    }

    @Override
    public MonotonicObservableSupplier<String> getPageTitle() {
        return mPageTitle;
    }

    @Override
    public @AnimationType int getAnimationType() {
        return AnimationType.PROPERTY;
    }

    // ---------------------------------------------------------------- 账号

    private void updateAccount() {
        if (mAccount == null) return;
        Context context = getContext();
        if (context == null) return;
        LemurXAccount.Profile profile = LemurXAccount.profile();
        if (profile != null) {
            mAccount.setTitle(profile.displayName());
            mAccount.setSummary(TextUtils.isEmpty(profile.email)
                    ? getString(R.string.lemurx_muse_settings_signed_in)
                    : profile.email);
            // 原生账号行图标槽是 40dp，和 SignInPreference / ProfileDataCache 一致。
            int size = MusePage.dp(context, 40);
            LemurXAccount.loadAvatar(
                    size,
                    drawable -> {
                        if (mAccount == null || !isAdded() || drawable == null) return;
                        mAccount.setIcon(drawable);
                    });
        } else {
            mAccount.setTitle(R.string.lemurx_muse_settings_sign_in);
            mAccount.setSummary(R.string.lemurx_muse_settings_sign_in_hint);
            mAccount.setIcon(
                    AppCompatResources.getDrawable(context, R.drawable.account_circle_with_bg));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (LemurXAccount.isSignedIn()) LemurXAccount.refreshProfile(error -> updateAccount());
        refreshBilling();
    }

    /** 把本周额度和重置日写到设置第一组。读不到就留一句，不挡别的设置。 */
    private void refreshBilling() {
        Preference preference = mBilling;
        if (preference == null) return;
        LemurXBilling.load((json, error) -> {
            if (!isAdded() || mBilling == null) return;
            if (json == null) {
                mBilling.setSummary(R.string.lemurx_billing_unavailable);
                return;
            }
            LemurXBilling.bind(mBilling, json);
        });
    }

    /** 登录底栏在 {@link LemurXSignIn}。设置页用自己的 Activity Result 打开 Google 账号选择页。 */
    private void showSignIn() {
        Activity activity = getActivity();
        if (activity == null) return;
        LemurXSignIn.show(
                activity,
                intent -> {
                    try {
                        mGoogleLauncher.launch(intent);
                    } catch (RuntimeException e) {
                        LemurXSignIn.fail(getString(R.string.lemurx_login_google_failed));
                    }
                },
                this::toast);
    }

    private void onGoogleResult(@Nullable Intent data) {
        Context context = getContext();
        if (context == null) {
            LemurXSignIn.setBusy(false);
            return;
        }
        LemurXGoogleSignIn.handleResult(
                context,
                data,
                new LemurXGoogleSignIn.Callback() {
                    @Override
                    public void onAccount(LemurXGoogleSignIn.Account account) {
                        LemurXAccount.signInWithGoogle(
                                account,
                                error -> {
                                    LemurXSignIn.setBusy(false);
                                    if (error != null) {
                                        toast(error);
                                        return;
                                    }
                                    LemurXSignIn.dismiss();
                                    MuseApi.rebind();
                                    MusePage.applyAccountMode();
                                    updateAccount();
                                });
                    }

                    @Override
                    public void onError(int statusCode, String message) {
                        LemurXSignIn.setBusy(false);
                        // 用户自己取消的不用提示
                        if (!LemurXGoogleSignIn.isCancelled(statusCode)) toast(message);
                    }
                });
    }

    private void toast(String message) {
        Context context = getContext();
        if (context != null) Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    private void signOut() {
        Activity activity = getActivity();
        if (activity != null) LemurXGoogleSignIn.signOut(activity);
        LemurXAccount.signOut(
                error -> {
                    MuseApi.rebind();
                    LemurXConsoleShell.onSignedOut();
                    updateAccount();
                });
    }

    private void showProfile() {
        LemurXAccount.Profile profile = LemurXAccount.profile();
        if (profile == null) return;
        Context context = requireContext();
        StringBuilder text = new StringBuilder();
        if (!TextUtils.isEmpty(profile.nickname)) {
            text.append(getString(R.string.lemurx_muse_settings_nickname)).append(": ").append(profile.nickname).append('\n');
        }
        if (!TextUtils.isEmpty(profile.email)) {
            text.append(getString(R.string.lemurx_muse_settings_email)).append(": ").append(profile.email).append('\n');
        }
        if (!TextUtils.isEmpty(profile.userNo)) {
            text.append(getString(R.string.lemurx_muse_settings_user_no)).append(": ").append(profile.userNo).append('\n');
        }
        new AlertDialog.Builder(context)
                .setTitle(profile.displayName())
                .setMessage(text.toString().trim())
                .setNeutralButton(R.string.lemurx_muse_settings_sign_out, (d, w) -> signOut())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
