// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.favorites;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.chromium.base.Log;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.bookmarks.BookmarkModel;
import org.chromium.chrome.browser.history.BrowsingHistoryBridge;
import org.chromium.chrome.browser.history.HistoryItem;
import org.chromium.chrome.browser.history.HistoryProvider;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.components.bookmarks.BookmarkId;
import org.chromium.components.bookmarks.BookmarkItem;
import org.chromium.components.browser_ui.bottomsheet.BottomSheetContent;
import org.chromium.components.browser_ui.bottomsheet.BottomSheetController;
import org.chromium.ui.widget.Toast;
import org.chromium.url.GURL;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Add shortcut" bottom sheet (port of Lemur's FavoriteSelectorBottomSheetContent): pick from
 * Bookmarks or History, or type a URL. Every row adds straight into the grid through {@link
 * Sink}; rows already on the grid show as added.
 */
class LemurXFavoritePicker implements BottomSheetContent {
    private static final String TAG = "LemurXFavPicker";
    private static final int MAX_ROWS = 200;

    /** Where picked entries go. */
    interface Sink {
        /** Adds the shortcut; returns false when there is no room. */
        boolean add(String url, String title);

        /** URLs currently on the grid (top level + folders). */
        Set<String> existingUrls();
    }

    private static class Row {
        final String url;
        final String title;

        Row(String url, String title) {
            this.url = url;
            this.title = title;
        }
    }

    private final Context mContext;
    private final Profile mProfile;
    private final BottomSheetController mController;
    private final Sink mSink;
    private final LemurXFavoriteIcons mIcons;
    private final View mView;
    private final RecyclerView mList;
    private final TextView mEmpty;
    private final View mUrlArea;
    private final TextView[] mTabs = new TextView[3];
    private final RowAdapter mAdapter = new RowAdapter();
    private final Set<String> mAdded = new HashSet<>();

    private @Nullable BrowsingHistoryBridge mHistory;
    private final List<Row> mBookmarks = new ArrayList<>();
    private final List<Row> mHistoryRows = new ArrayList<>();
    private boolean mBookmarksLoaded;
    private boolean mHistoryLoaded;
    private int mTab;

    LemurXFavoritePicker(
            Context context,
            Profile profile,
            BottomSheetController controller,
            LemurXFavoriteIcons icons,
            Sink sink) {
        mContext = context;
        mProfile = profile;
        mController = controller;
        mIcons = icons;
        mSink = sink;
        mAdded.addAll(sink.existingUrls());

        mView = LayoutInflater.from(context).inflate(R.layout.lemurx_fav_picker, null);
        mList = mView.findViewById(R.id.lemurx_fav_picker_list);
        mEmpty = mView.findViewById(R.id.lemurx_fav_picker_empty);
        mUrlArea = mView.findViewById(R.id.lemurx_fav_url_area);
        mTabs[0] = mView.findViewById(R.id.lemurx_fav_tab_bookmarks);
        mTabs[1] = mView.findViewById(R.id.lemurx_fav_tab_history);
        mTabs[2] = mView.findViewById(R.id.lemurx_fav_tab_url);
        for (int i = 0; i < mTabs.length; i++) {
            final int index = i;
            mTabs[i].setOnClickListener(v -> selectTab(index));
        }
        mList.setLayoutManager(new LinearLayoutManager(context));
        mList.setAdapter(mAdapter);

        EditText urlInput = mView.findViewById(R.id.lemurx_fav_url_input);
        EditText titleInput = mView.findViewById(R.id.lemurx_fav_url_title_input);
        mView.findViewById(R.id.lemurx_fav_url_add)
                .setOnClickListener(
                        v -> {
                            String url = normalizeUrl(urlInput.getText().toString());
                            if (url == null) {
                                urlInput.setError(
                                        context.getString(R.string.lemurx_fav_add_url_hint));
                                return;
                            }
                            String title = titleInput.getText().toString().trim();
                            if (mSink.add(url, title)) {
                                mAdded.add(url);
                                urlInput.setText("");
                                titleInput.setText("");
                                Toast.makeText(context, R.string.lemurx_fav_added, Toast.LENGTH_SHORT)
                                        .show();
                            }
                        });
        selectTab(0);
    }

    void show() {
        mController.requestShowContent(this, true);
    }

    private void selectTab(int index) {
        mTab = index;
        for (int i = 0; i < mTabs.length; i++) mTabs[i].setSelected(i == index);
        mUrlArea.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        mList.setVisibility(index == 2 ? View.GONE : View.VISIBLE);
        mEmpty.setVisibility(View.GONE);
        if (index == 0) {
            if (!mBookmarksLoaded) loadBookmarks();
            else render(mBookmarks, R.string.lemurx_fav_empty_bookmarks);
        } else if (index == 1) {
            if (!mHistoryLoaded) loadHistory();
            else render(mHistoryRows, R.string.lemurx_fav_empty_history);
        }
    }

    private void render(List<Row> rows, int emptyRes) {
        mAdapter.setRows(rows);
        if (rows.isEmpty()) {
            mEmpty.setText(emptyRes);
            mEmpty.setVisibility(View.VISIBLE);
        } else {
            mEmpty.setVisibility(View.GONE);
        }
    }

    private void loadBookmarks() {
        try {
            BookmarkModel model = BookmarkModel.getForProfile(mProfile);
            model.finishLoadingBookmarkModel(
                    () -> {
                        mBookmarks.clear();
                        try {
                            List<BookmarkId> roots = model.getTopLevelFolderIds();
                            // Mobile bookmarks first, like the bookmark manager.
                            BookmarkId mobile = model.getMobileFolderId();
                            if (mobile != null) {
                                roots.remove(mobile);
                                roots.add(0, mobile);
                            }
                            for (BookmarkId root : roots) collect(model, root, 0);
                        } catch (Throwable t) {
                            Log.w(TAG, "bookmark walk failed: %s", t.toString());
                        }
                        mBookmarksLoaded = true;
                        if (mTab == 0) render(mBookmarks, R.string.lemurx_fav_empty_bookmarks);
                    });
        } catch (Throwable t) {
            Log.w(TAG, "BookmarkModel unavailable: %s", t.toString());
            mBookmarksLoaded = true;
            render(mBookmarks, R.string.lemurx_fav_empty_bookmarks);
        }
    }

    private void collect(BookmarkModel model, BookmarkId folder, int depth) {
        if (depth > 8 || mBookmarks.size() >= MAX_ROWS) return;
        for (BookmarkId id : model.getChildIds(folder)) {
            if (mBookmarks.size() >= MAX_ROWS) return;
            BookmarkItem item = model.getBookmarkById(id);
            if (item == null) continue;
            if (item.isFolder()) {
                collect(model, id, depth + 1);
            } else {
                GURL url = item.getUrl();
                if (url != null && url.isValid()) {
                    mBookmarks.add(new Row(url.getSpec(), item.getTitle()));
                }
            }
        }
    }

    private void loadHistory() {
        try {
            mHistory = new BrowsingHistoryBridge(mProfile);
            mHistory.setObserver(
                    new HistoryProvider.BrowsingHistoryObserver() {
                        @Override
                        public void onQueryHistoryComplete(
                                List<HistoryItem> items, boolean hasMorePotentialMatches) {
                            Set<String> seen = new HashSet<>();
                            mHistoryRows.clear();
                            for (HistoryItem item : items) {
                                GURL url = item.getUrl();
                                if (url == null || !url.isValid()) continue;
                                String spec = url.getSpec();
                                if (!seen.add(spec)) continue;
                                mHistoryRows.add(new Row(spec, item.getTitle()));
                                if (mHistoryRows.size() >= MAX_ROWS) break;
                            }
                            mHistoryLoaded = true;
                            if (mTab == 1) render(mHistoryRows, R.string.lemurx_fav_empty_history);
                        }

                        @Override
                        public void onHistoryDeleted() {}

                        @Override
                        public void hasOtherFormsOfBrowsingData(boolean hasOtherForms) {}

                        @Override
                        public void onQueryAppsComplete(List<String> items) {}
                    });
            mHistory.queryHistory("", null);
        } catch (Throwable t) {
            Log.w(TAG, "history unavailable: %s", t.toString());
            mHistoryLoaded = true;
            render(mHistoryRows, R.string.lemurx_fav_empty_history);
        }
    }

    static @Nullable String normalizeUrl(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return null;
        if (!s.contains("://")) s = "https://" + s;
        GURL g = new GURL(s);
        if (!g.isValid()) return null;
        return g.getSpec();
    }

    // ---------------------------------------------------------------- rows

    private class RowAdapter extends RecyclerView.Adapter<RowHolder> {
        private final List<Row> mRows = new ArrayList<>();

        void setRows(List<Row> rows) {
            mRows.clear();
            mRows.addAll(rows);
            notifyDataSetChanged();
        }

        @Override
        public RowHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v =
                    LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.lemurx_fav_picker_row, parent, false);
            return new RowHolder(v);
        }

        @Override
        public void onBindViewHolder(RowHolder holder, int position) {
            Row row = mRows.get(position);
            holder.title.setText(TextUtils.isEmpty(row.title) ? LemurXFavorite.hostOf(row.url) : row.title);
            holder.url.setText(row.url);
            holder.iconUrl = row.url;
            Bitmap cached = mIcons.cached(row.url);
            holder.icon.setImageBitmap(cached);
            if (cached == null) {
                mIcons.get(
                        row.url,
                        bmp -> {
                            if (row.url.equals(holder.iconUrl)) holder.icon.setImageBitmap(bmp);
                        });
            }
            boolean added = mAdded.contains(row.url);
            holder.action.setText(added ? R.string.lemurx_fav_added : R.string.lemurx_fav_add);
            holder.action.setAlpha(added ? 0.5f : 1f);
            View.OnClickListener add =
                    v -> {
                        if (mAdded.contains(row.url)) return;
                        if (mSink.add(row.url, row.title)) {
                            mAdded.add(row.url);
                            notifyItemChanged(holder.getBindingAdapterPosition());
                        }
                    };
            holder.action.setOnClickListener(add);
            holder.itemView.setOnClickListener(add);
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }
    }

    private static class RowHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView url;
        final TextView action;
        @Nullable String iconUrl;

        RowHolder(View v) {
            super(v);
            icon = v.findViewById(R.id.lemurx_fav_row_icon);
            title = v.findViewById(R.id.lemurx_fav_row_title);
            url = v.findViewById(R.id.lemurx_fav_row_url);
            action = v.findViewById(R.id.lemurx_fav_row_action);
            icon.setOutlineProvider(
                    new ViewOutlineProvider() {
                        @Override
                        public void getOutline(View view, Outline outline) {
                            outline.setOval(0, 0, view.getWidth(), view.getHeight());
                        }
                    });
            icon.setClipToOutline(true);
        }
    }

    // ---------------------------------------------------------------- BottomSheetContent

    @Override
    public View getContentView() {
        return mView;
    }

    @Override
    public @Nullable View getToolbarView() {
        return null;
    }

    @Override
    public int getVerticalScrollOffset() {
        return mList.getVisibility() == View.VISIBLE ? mList.computeVerticalScrollOffset() : 0;
    }

    @Override
    public void destroy() {
        if (mHistory != null) {
            mHistory.destroy();
            mHistory = null;
        }
    }

    @Override
    public int getPriority() {
        return ContentPriority.HIGH;
    }

    @Override
    public boolean swipeToDismissEnabled() {
        return true;
    }

    @Override
    public float getHalfHeightRatio() {
        return 0.6f;
    }

    @Override
    public float getFullHeightRatio() {
        return 0.9f;
    }

    @Override
    public int getSheetHalfHeightAccessibilityStringId() {
        return R.string.lemurx_fav_sheet_opened_half;
    }

    @Override
    public int getSheetFullHeightAccessibilityStringId() {
        return R.string.lemurx_fav_sheet_opened_full;
    }

    @Override
    public int getSheetClosedAccessibilityStringId() {
        return R.string.lemurx_fav_sheet_closed;
    }
}
