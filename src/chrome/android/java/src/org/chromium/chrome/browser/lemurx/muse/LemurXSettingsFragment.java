// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

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
import org.chromium.chrome.browser.settings.ChromeBaseSettingsFragment;
import org.chromium.chrome.browser.settings.SettingsNavigationFactory;
import org.chromium.components.browser_ui.settings.EmbeddableSettingsPage;
import org.chromium.components.browser_ui.settings.SettingsNavigation.SettingsFragment;

/**
 * LemurX 设置：每个原生页面左上角 ☰ 打开的就是它，跑在 Chrome 的 SettingsActivity 里。
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
    private @Nullable Preference mSignOut;

    /** Google 账号选择页的回执；必须在 fragment STARTED 之前注册，所以放在字段初始化里。 */
    private final ActivityResultLauncher<Intent> mGoogleLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> onGoogleResult(result.getData()));

    /** 正在显示的登录底栏；登录成功后关掉。 */
    private @Nullable Dialog mSignInSheet;
    private @Nullable View mGoogleButton;
    private boolean mGoogleInProgress;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        Context context = getPreferenceManager().getContext();
        mPageTitle.set(context.getString(R.string.lemurx_muse_settings_title));
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);

        // ---- 账号
        PreferenceCategory account = category(context, screen, R.string.lemurx_muse_settings_account);
        mAccount = new Preference(context);
        mAccount.setKey("lemurx_account");
        mAccount.setOnPreferenceClickListener(
                p -> {
                    if (LemurXAccount.isSignedIn()) {
                        showProfile();
                    } else {
                        showSignIn();
                    }
                    return true;
                });
        LemurXSettingsIcons.set(mAccount, R.drawable.lemurx_muse_ic_user);
        account.addPreference(mAccount);
        mSignOut = new Preference(context);
        mSignOut.setKey("lemurx_sign_out");
        mSignOut.setTitle(R.string.lemurx_muse_settings_sign_out);
        LemurXSettingsIcons.set(mSignOut, R.drawable.lemurx_muse_ic_logout);
        mSignOut.setOnPreferenceClickListener(
                p -> {
                    signOut();
                    return true;
                });
        account.addPreference(mSignOut);
        if (SHOW_SERVER_SETTINGS) addAccountServer(context, account);
        updateAccount();

        // ---- 个人数据
        PreferenceCategory data = category(context, screen, R.string.lemurx_muse_data);
        Preference personal = new Preference(context);
        personal.setKey("lemurx_data");
        personal.setTitle(R.string.lemurx_muse_settings_data);
        personal.setSummary(R.string.lemurx_muse_settings_data_hint);
        LemurXSettingsIcons.set(personal, R.drawable.lemurx_muse_ic_sparkles);
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
        if (mAccount == null || mSignOut == null) return;
        LemurXAccount.Profile profile = LemurXAccount.profile();
        if (profile != null) {
            mAccount.setTitle(profile.displayName());
            mAccount.setSummary(TextUtils.isEmpty(profile.email)
                    ? getString(R.string.lemurx_muse_settings_signed_in)
                    : profile.email);
            mSignOut.setVisible(true);
        } else {
            mAccount.setTitle(R.string.lemurx_muse_settings_sign_in);
            mAccount.setSummary(R.string.lemurx_muse_settings_sign_in_hint);
            mSignOut.setVisible(false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (LemurXAccount.isSignedIn()) LemurXAccount.refreshProfile(error -> updateAccount());
    }

    /**
     * 登录底栏：Lemur 浏览器的 "Dialog/Google Login"（OverseasLoginHubFragment +
     * dialog_overseas_login_hub.xml）——圆角卡片、拖拽把手、应用图标、标题、说明、"使用 Google
     * 继续"、"暂不"、法律说明。代码构建，不用 XML。
     */
    private void showSignIn() {
        Context context = requireContext();
        Activity activity = getActivity();
        if (activity == null) return;
        int pad = MusePage.dp(context, 20);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(MusePage.rounded(0xFFEBEBEB, 24, context));
        card.setPadding(pad, MusePage.dp(context, 10), pad, pad);

        View handle = new View(context);
        handle.setBackground(MusePage.rounded(0xFFC8C8C8, 2, context));
        card.addView(handle, new LinearLayout.LayoutParams(MusePage.dp(context, 36), MusePage.dp(context, 4)));

        ImageView icon = new ImageView(context);
        icon.setImageResource(R.mipmap.app_icon);
        icon.setClipToOutline(true);
        icon.setOutlineProvider(
                new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setOval(0, 0, view.getWidth(), view.getHeight());
                    }
                });
        LinearLayout.LayoutParams iconLp =
                new LinearLayout.LayoutParams(MusePage.dp(context, 56), MusePage.dp(context, 56));
        iconLp.topMargin = MusePage.dp(context, 22);
        card.addView(icon, iconLp);

        TextView title = new TextView(context);
        title.setText(R.string.lemurx_login_title);
        title.setTextColor(Color.BLACK);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = wrap();
        titleLp.topMargin = MusePage.dp(context, 14);
        card.addView(title, titleLp);

        TextView description = new TextView(context);
        description.setText(R.string.lemurx_login_description);
        description.setTextColor(0x99000000);
        description.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        description.setGravity(Gravity.CENTER);
        description.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams descLp = wrap();
        descLp.topMargin = MusePage.dp(context, 6);
        card.addView(description, descLp);

        // "使用 Google 继续"：白底、细边、胶囊
        LinearLayout google = new LinearLayout(context);
        google.setOrientation(LinearLayout.HORIZONTAL);
        google.setGravity(Gravity.CENTER);
        GradientDrawable googleBg = MusePage.rounded(Color.WHITE, 29, context);
        googleBg.setStroke(MusePage.dp(context, 1), 0xFFEBEBEB);
        google.setBackground(googleBg);
        google.setClickable(true);
        google.setFocusable(true);
        ImageView googleIcon = new ImageView(context);
        googleIcon.setImageResource(R.drawable.lemurx_login_ic_google);
        google.addView(googleIcon, new LinearLayout.LayoutParams(MusePage.dp(context, 20), MusePage.dp(context, 20)));
        TextView googleLabel = new TextView(context);
        googleLabel.setText(R.string.lemurx_login_google);
        googleLabel.setTextColor(Color.BLACK);
        googleLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        googleLabel.setTypeface(googleLabel.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams labelLp = wrap();
        labelLp.setMarginStart(MusePage.dp(context, 10));
        google.addView(googleLabel, labelLp);
        LinearLayout.LayoutParams googleLp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, MusePage.dp(context, 52));
        googleLp.topMargin = MusePage.dp(context, 26);
        card.addView(google, googleLp);
        google.setOnClickListener(v -> startGoogleSignIn(activity));
        mGoogleButton = google;

        TextView notNow = new TextView(context);
        notNow.setText(R.string.lemurx_login_not_now);
        notNow.setTextColor(0x99000000);
        notNow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        notNow.setGravity(Gravity.CENTER);
        notNow.setPadding(0, MusePage.dp(context, 14), 0, MusePage.dp(context, 14));
        notNow.setClickable(true);
        notNow.setFocusable(true);
        card.addView(notNow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView legal = new TextView(context);
        legal.setText(R.string.lemurx_login_legal);
        legal.setTextColor(0x4D000000);
        legal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        legal.setGravity(Gravity.CENTER);
        card.addView(legal, wrap());

        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(card);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setDimAmount(0.5f);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.y = MusePage.dp(context, 16);
            window.setAttributes(lp);
            // 两侧留 16dp，像 Lemur 的 setMargin(16)
            int margin = MusePage.dp(context, 16);
            View decor = window.getDecorView();
            decor.setPadding(margin, 0, margin, 0);
        }
        notNow.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(
                d -> {
                    if (mSignInSheet == dialog) {
                        mSignInSheet = null;
                        mGoogleButton = null;
                    }
                });
        mSignInSheet = dialog;
        dialog.show();
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void startGoogleSignIn(Activity activity) {
        if (mGoogleInProgress) return;
        mGoogleInProgress = true;
        setGoogleBusy(true);
        LemurXGoogleSignIn.start(
                activity,
                intent -> {
                    try {
                        mGoogleLauncher.launch(intent);
                    } catch (RuntimeException e) {
                        onGoogleFailed(getString(R.string.lemurx_login_google_failed));
                    }
                },
                this::onGoogleFailed);
    }

    private void onGoogleResult(@Nullable Intent data) {
        Context context = getContext();
        if (context == null) {
            mGoogleInProgress = false;
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
                                    mGoogleInProgress = false;
                                    setGoogleBusy(false);
                                    if (error != null) {
                                        toast(error);
                                        return;
                                    }
                                    Dialog sheet = mSignInSheet;
                                    if (sheet != null) sheet.dismiss();
                                    updateAccount();
                                });
                    }

                    @Override
                    public void onError(int statusCode, String message) {
                        mGoogleInProgress = false;
                        setGoogleBusy(false);
                        // 用户自己取消的不用提示
                        if (!LemurXGoogleSignIn.isCancelled(statusCode)) toast(message);
                    }
                });
    }

    private void onGoogleFailed(String message) {
        mGoogleInProgress = false;
        setGoogleBusy(false);
        toast(message);
    }

    private void setGoogleBusy(boolean busy) {
        View button = mGoogleButton;
        if (button == null) return;
        button.setEnabled(!busy);
        button.setAlpha(busy ? 0.5f : 1f);
    }

    private void toast(String message) {
        Context context = getContext();
        if (context != null) Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    private void signOut() {
        Activity activity = getActivity();
        if (activity != null) LemurXGoogleSignIn.signOut(activity);
        LemurXAccount.signOut(error -> updateAccount());
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
                .setNegativeButton(android.R.string.ok, null)
                .setPositiveButton(R.string.lemurx_muse_settings_sign_out, (d, w) -> signOut())
                .show();
    }
}
