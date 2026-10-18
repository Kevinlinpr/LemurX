// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.search;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;

import org.chromium.base.Log;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.regional_capabilities.RegionalCapabilitiesServiceFactory;
import org.chromium.chrome.browser.search_engines.settings.SearchEngineAdapter;
import org.chromium.chrome.browser.ui.favicon.FaviconHelper;
import org.chromium.components.search_engines.StarterPackId;
import org.chromium.components.search_engines.TemplateUrl;
import org.chromium.components.search_engines.TemplateUrlService;
import org.chromium.url.GURL;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 搜索引擎快速选择。
 *
 * <p>Chromium 的地址栏聚焦后左侧显示默认搜索引擎的 logo（StatusView）。LemurX 把这个 logo 变成一个
 * 按钮：点一下在它下面弹出搜索引擎列表，选中即切换默认搜索引擎，地址栏不失焦、键盘不收起，接着输入
 * 就用新引擎搜。它顶替的是 Chromium 自带 AI 模式（fusebox / Composeplate）的那个「+」入口——那个
 * 功能已经在 feature 层关掉（kAimEnabled / kOmniboxMultimodalInput），LemurX 自己的 agent 在
 * OpenMuse 控制台里。
 *
 * <p>列表内容和「设置 → 搜索引擎」一致：预置引擎按原顺序，再加上最近用过的自定义引擎
 * （{@link SearchEngineAdapter#sortAndFilterUnnecessaryTemplateUrl}），去掉 @bookmarks / @history
 * 这类 starter pack 关键字和扩展提供的条目——它们不是网页搜索引擎。
 *
 * <p>由 omnibox 模块的 StatusMediator 调用（见 patches/…StatusMediator.java.patch）。这个类所在的
 * GN 目标不依赖 omnibox 模块，避免循环依赖。
 */
@NullMarked
public final class LemurXSearchEnginePicker {
    private static final String TAG = "LemurXSearch";

    /** 列表宽度 / 行高 / 图标尺寸（dp），对齐地址栏下拉建议的视觉密度。 */
    private static final int WIDTH_DP = 264;

    private static final int ROW_HEIGHT_DP = 48;
    private static final int ICON_DP = 20;
    private static final int PADDING_DP = 16;

    /** 当前打开着的弹层；同时最多一个。 */
    private static @Nullable ListPopupWindow sPopup;

    private LemurXSearchEnginePicker() {}

    /**
     * 在 {@code anchor}（地址栏左侧的引擎 logo）下方弹出引擎列表。
     *
     * @param context 地址栏所在 Activity 的 Context，用它的主题画列表。
     * @param anchor 锚点 View。
     * @param profile 当前 profile，用来取地区能力和 favicon。
     * @param service 已加载的 {@link TemplateUrlService}；没加载完就什么都不做。
     */
    public static void show(
            Context context, View anchor, Profile profile, TemplateUrlService service) {
        dismiss();
        if (!service.isLoaded()) return;

        TemplateUrl current = service.getDefaultSearchEngineTemplateUrl();
        List<TemplateUrl> engines = new ArrayList<>(service.getTemplateUrls());
        boolean eea = false;
        try {
            eea = RegionalCapabilitiesServiceFactory.getForProfile(profile).isInEeaCountry();
        } catch (Exception e) {
            // 拿不到地区信息就按非 EEA 排序，只影响顺序。
        }
        SearchEngineAdapter.sortAndFilterUnnecessaryTemplateUrl(engines, current, eea);
        Iterator<TemplateUrl> it = engines.iterator();
        while (it.hasNext()) {
            TemplateUrl url = it.next();
            if (url.getStarterPackId() != StarterPackId.NONE
                    || url.getProvidingExtensionId() != null) {
                it.remove();
            }
        }
        if (engines.isEmpty()) return;

        String currentKeyword = current == null ? null : current.getKeyword();
        EngineAdapter adapter = new EngineAdapter(context, profile, engines, currentKeyword);
        ListPopupWindow popup = new ListPopupWindow(context);
        popup.setAnchorView(anchor);
        popup.setAdapter(adapter);
        // 非模态：弹层不抢焦点，地址栏保持聚焦、键盘不收起；点外面仍然会关掉它。
        popup.setModal(false);
        popup.setWidth(dp(context, WIDTH_DP));
        popup.setVerticalOffset(dp(context, 4));
        popup.setBackgroundDrawable(
                AppCompatResources.getDrawable(context, R.drawable.lemurx_search_popup_bg));
        popup.setOnItemClickListener(
                (parent, view, position, id) -> {
                    if (position < 0 || position >= engines.size()) return;
                    String keyword = engines.get(position).getKeyword();
                    try {
                        if (!keyword.equals(currentKeyword)) service.setSearchEngine(keyword);
                    } catch (Exception e) {
                        Log.i(TAG, "setSearchEngine: %s", e.getMessage());
                    }
                    dismiss();
                });
        popup.setOnDismissListener(
                () -> {
                    adapter.destroy();
                    if (sPopup == popup) sPopup = null;
                });
        sPopup = popup;
        try {
            popup.show();
        } catch (Exception e) {
            // anchor 已经不在窗口里之类的情况：安静放弃。
            Log.i(TAG, "show: %s", e.getMessage());
            sPopup = null;
            adapter.destroy();
        }
    }

    /** 关掉正在显示的列表（没有就什么都不做）。 */
    public static void dismiss() {
        ListPopupWindow popup = sPopup;
        sPopup = null;
        if (popup != null && popup.isShowing()) popup.dismiss();
    }

    private static int dp(Context context, int value) {
        return Math.round(
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        value,
                        context.getResources().getDisplayMetrics()));
    }

    /** 一行一个引擎：favicon（没有缓存就用地球图标）、名字、当前默认的打勾。 */
    private static final class EngineAdapter extends BaseAdapter {
        private final Context mContext;
        private final Profile mProfile;
        private final List<TemplateUrl> mEngines;
        private final @Nullable String mCurrentKeyword;
        private final Map<String, Bitmap> mIcons = new HashMap<>();
        private @Nullable FaviconHelper mFavicons;
        private final int mIconPx;

        EngineAdapter(
                Context context,
                Profile profile,
                List<TemplateUrl> engines,
                @Nullable String currentKeyword) {
            mContext = context;
            mProfile = profile;
            mEngines = engines;
            mCurrentKeyword = currentKeyword;
            mIconPx = dp(context, ICON_DP);
            try {
                mFavicons = new FaviconHelper();
            } catch (Exception e) {
                mFavicons = null;
            }
            for (TemplateUrl url : engines) fetchIcon(url);
        }

        void destroy() {
            if (mFavicons != null) {
                mFavicons.destroy();
                mFavicons = null;
            }
        }

        private void fetchIcon(TemplateUrl url) {
            if (mFavicons == null) return;
            GURL origin;
            try {
                origin = new GURL(url.getURL()).getOrigin();
            } catch (Exception e) {
                return;
            }
            if (origin.isEmpty()) return;
            String keyword = url.getKeyword();
            try {
                mFavicons.getLocalFaviconImageForURL(
                        mProfile,
                        origin,
                        mIconPx,
                        /* fallbackToHost= */ true,
                        (image, iconUrl) -> {
                            if (image == null || mFavicons == null) return;
                            mIcons.put(keyword, image);
                            notifyDataSetChanged();
                        });
            } catch (Exception e) {
                Log.i(TAG, "favicon %s: %s", keyword, e.getMessage());
            }
        }

        @Override
        public int getCount() {
            return mEngines.size();
        }

        @Override
        public Object getItem(int position) {
            return mEngines.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, @Nullable View convertView, ViewGroup parent) {
            Row row = convertView instanceof Row ? (Row) convertView : new Row(mContext, mIconPx);
            TemplateUrl url = mEngines.get(position);
            boolean selected = url.getKeyword().equals(mCurrentKeyword);
            row.bind(url.getShortName(), mIcons.get(url.getKeyword()), selected);
            return row;
        }
    }

    private static final class Row extends LinearLayout {
        private final ImageView mIcon;
        private final TextView mName;
        private final ImageView mCheck;

        Row(Context context, int iconPx) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int pad = dp(context, PADDING_DP);
            setPadding(pad, 0, pad, 0);
            setMinimumHeight(dp(context, ROW_HEIGHT_DP));
            setLayoutParams(
                    new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            TypedValue bg = new TypedValue();
            if (context.getTheme()
                    .resolveAttribute(android.R.attr.selectableItemBackground, bg, true)) {
                setBackgroundResource(bg.resourceId);
            }

            mIcon = new ImageView(context);
            mIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addView(mIcon, new LayoutParams(iconPx, iconPx));

            mName = new TextView(context);
            mName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            mName.setSingleLine(true);
            mName.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TypedValue color = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, color, true)) {
                mName.setTextColor(
                        AppCompatResources.getColorStateList(context, color.resourceId));
            }
            LayoutParams nameParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            nameParams.setMarginStart(dp(context, 14));
            nameParams.setMarginEnd(dp(context, 12));
            addView(mName, nameParams);

            mCheck = new ImageView(context);
            mCheck.setImageDrawable(
                    AppCompatResources.getDrawable(context, R.drawable.lemurx_search_ic_check));
            addView(mCheck, new LayoutParams(dp(context, 22), dp(context, 22)));
        }

        void bind(String name, @Nullable Bitmap icon, boolean selected) {
            Resources res = getResources();
            if (icon != null) {
                mIcon.setImageDrawable(new BitmapDrawable(res, icon));
            } else {
                mIcon.setImageDrawable(
                        AppCompatResources.getDrawable(
                                getContext(), R.drawable.lemurx_search_ic_engine));
            }
            mName.setText(name);
            mCheck.setVisibility(selected ? VISIBLE : INVISIBLE);
            setContentDescription(
                    selected ? res.getString(R.string.lemurx_search_engine_current, name) : name);
        }
    }
}
