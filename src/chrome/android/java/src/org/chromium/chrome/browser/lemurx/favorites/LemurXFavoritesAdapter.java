// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.favorites;

import android.graphics.Bitmap;
import android.graphics.Outline;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for one favorites grid -- the home page grid or the grid inside an open
 * folder (port of Lemur's FavoritesDragAdapter, with all state kept in the coordinator).
 *
 * <p>Item types: URL / local shortcut ({@code lemurx_fav_item}), folder ({@code
 * lemurx_fav_folder_item}), and the "+" tile ({@code lemurx_fav_add_item}) which is always the
 * last item and only rendered when {@link LemurXFavorite#visible}.
 */
class LemurXFavoritesAdapter extends RecyclerView.Adapter<LemurXFavoritesAdapter.Holder> {
    private static final int VIEW_TILE = 0;
    private static final int VIEW_FOLDER = 1;
    private static final int VIEW_ADD = 2;

    /** What the adapter needs from the coordinator. */
    interface Host {
        boolean isEditMode();

        @Nullable LemurXFavorite selected();

        /** Localised caption for {@code f} (folders default to "More", seeded tiles to their names). */
        String titleOf(LemurXFavorite f);

        LemurXFavoriteIcons icons();

        void onTileClicked(LemurXFavorite f, Holder holder);

        void onTileLongPressed(LemurXFavorite f, Holder holder);

        /** Touch-down on a tile while editing: select it and start dragging. */
        void onTileTouchDown(LemurXFavorite f, Holder holder, LemurXFavoritesAdapter adapter);

        void onDeleteClicked(LemurXFavorite f, LemurXFavoritesAdapter adapter);

        void onAddClicked(LemurXFavoritesAdapter adapter);
    }

    static class Holder extends RecyclerView.ViewHolder {
        final @Nullable View selectRing;
        final @Nullable View joinRing;
        final @Nullable ImageView icon;
        final @Nullable LemurXMultiCircleView folderIcon;
        final @Nullable TextView name;
        final @Nullable ImageView delete;
        /** URL the icon currently shown belongs to; guards async favicon callbacks. */
        @Nullable String iconUrl;
        /** Folder child URLs the multi-circle icon was built for. */
        @Nullable List<String> folderIconUrls;

        Holder(View view) {
            super(view);
            selectRing = view.findViewById(R.id.lemurx_fav_select_ring);
            joinRing = view.findViewById(R.id.lemurx_fav_join_ring);
            View iconView = view.findViewById(R.id.lemurx_fav_icon);
            icon = iconView instanceof ImageView ? (ImageView) iconView : null;
            folderIcon =
                    iconView instanceof LemurXMultiCircleView
                            ? (LemurXMultiCircleView) iconView
                            : null;
            name = view.findViewById(R.id.lemurx_fav_name);
            delete = view.findViewById(R.id.lemurx_fav_delete);
            if (icon != null) {
                icon.setOutlineProvider(
                        new ViewOutlineProvider() {
                            @Override
                            public void getOutline(View v, Outline outline) {
                                outline.setOval(0, 0, v.getWidth(), v.getHeight());
                            }
                        });
                icon.setClipToOutline(true);
            }
        }

        void showSelectRing(boolean show) {
            if (selectRing != null) {
                selectRing.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
            }
        }

        void showJoinRing(boolean show) {
            if (joinRing != null) joinRing.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
            if (name != null) name.setVisibility(show ? View.INVISIBLE : View.VISIBLE);
        }
    }

    private final Host mHost;
    private final boolean mIsGroup;
    private final List<LemurXFavorite> mItems = new ArrayList<>();
    private final LemurXFavorite mAddTile = LemurXFavorite.add();
    private @Nullable RecyclerView mRecyclerView;
    /** The "+" tile's view, used as the fly-to anchor when a tile leaves a folder. */
    private @Nullable View mAddView;

    LemurXFavoritesAdapter(Host host, boolean isGroup) {
        mHost = host;
        mIsGroup = isGroup;
        mAddTile.visible = isGroup;
        setHasStableIds(false);
    }

    boolean isGroup() {
        return mIsGroup;
    }

    /** Persisted tiles, i.e. everything but the "+" tile, in display order. */
    List<LemurXFavorite> tiles() {
        List<LemurXFavorite> out = new ArrayList<>(mItems);
        out.remove(mAddTile);
        return out;
    }

    int tileCount() {
        return mItems.contains(mAddTile) ? mItems.size() - 1 : mItems.size();
    }

    LemurXFavorite addTile() {
        return mAddTile;
    }

    @Nullable View addView() {
        return mAddView;
    }

    @Nullable RecyclerView recyclerView() {
        return mRecyclerView;
    }

    void setTiles(List<LemurXFavorite> tiles) {
        mItems.clear();
        mItems.addAll(tiles);
        mItems.add(mAddTile);
        notifyDataSetChanged();
    }

    /** Whether the "+" tile can still take another shortcut (capacity check). */
    boolean hasRoom() {
        int cap = mIsGroup ? LemurXFavorite.FOLDER_CAPACITY : LemurXFavorite.HOME_CAPACITY;
        return tileCount() < cap;
    }

    void setAddVisible(boolean visible) {
        if (mAddTile.visible == visible) return;
        mAddTile.visible = visible;
        int pos = mItems.indexOf(mAddTile);
        if (pos >= 0) notifyItemChanged(pos);
    }

    boolean isAddVisible() {
        return mAddTile.visible;
    }

    /** Appends {@code f} before the "+" tile. */
    void append(LemurXFavorite f) {
        int pos = mItems.indexOf(mAddTile);
        if (pos < 0) pos = mItems.size();
        mItems.add(pos, f);
        notifyItemInserted(pos);
    }

    void remove(LemurXFavorite f) {
        int pos = mItems.indexOf(f);
        if (pos < 0) return;
        mItems.remove(pos);
        notifyItemRemoved(pos);
    }

    void refresh(LemurXFavorite f) {
        int pos = mItems.indexOf(f);
        if (pos >= 0) notifyItemChanged(pos);
    }

    void refreshAll() {
        notifyDataSetChanged();
    }

    int positionOf(LemurXFavorite f) {
        return mItems.indexOf(f);
    }

    @Nullable LemurXFavorite at(int position) {
        return position >= 0 && position < mItems.size() ? mItems.get(position) : null;
    }

    /** Swaps two tiles (never the "+" tile). Returns false if not allowed. */
    boolean move(int from, int to) {
        if (from < 0 || to < 0 || from >= mItems.size() || to >= mItems.size()) return false;
        if (mItems.get(from) == mAddTile || mItems.get(to) == mAddTile) return false;
        if (from < to) {
            for (int i = from; i < to; i++) java.util.Collections.swap(mItems, i, i + 1);
        } else {
            for (int i = from; i > to; i--) java.util.Collections.swap(mItems, i, i - 1);
        }
        notifyItemMoved(from, to);
        return true;
    }

    /** Shows the selection ring only on {@code f} (or nowhere when null). */
    void showSelection(@Nullable LemurXFavorite f) {
        if (mRecyclerView == null) return;
        RecyclerView.LayoutManager lm = mRecyclerView.getLayoutManager();
        if (lm == null) return;
        for (int i = 0; i < mItems.size(); i++) {
            View v = lm.findViewByPosition(i);
            if (v == null) continue;
            RecyclerView.ViewHolder vh = mRecyclerView.getChildViewHolder(v);
            if (vh instanceof Holder) {
                ((Holder) vh).showSelectRing(f != null && mItems.get(i) == f);
            }
        }
    }

    @Override
    public void onAttachedToRecyclerView(RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        mRecyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        mRecyclerView = null;
    }

    @Override
    public int getItemViewType(int position) {
        LemurXFavorite f = mItems.get(position);
        if (f.isAdd()) return VIEW_ADD;
        if (f.isFolder()) return VIEW_FOLDER;
        return VIEW_TILE;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        int layout =
                viewType == VIEW_ADD
                        ? R.layout.lemurx_fav_add_item
                        : viewType == VIEW_FOLDER
                                ? R.layout.lemurx_fav_folder_item
                                : R.layout.lemurx_fav_item;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
        LemurXFavorite f = mItems.get(position);
        boolean editing = mHost.isEditMode();
        if (f.isAdd()) {
            mAddView = holder.itemView;
            holder.itemView.setVisibility(f.visible ? View.VISIBLE : View.INVISIBLE);
            holder.itemView.setOnClickListener(v -> mHost.onAddClicked(this));
            holder.itemView.setOnLongClickListener(null);
            holder.itemView.setOnTouchListener(null);
            return;
        }
        holder.itemView.setVisibility(View.VISIBLE);
        holder.itemView.setAlpha(1f);
        holder.itemView.setScaleX(1f);
        holder.itemView.setScaleY(1f);
        holder.itemView.setTranslationX(0);
        holder.itemView.setTranslationY(0);

        if (holder.name != null) {
            holder.name.setText(mHost.titleOf(f));
            holder.name.setVisibility(View.VISIBLE);
        }
        holder.showJoinRing(false);
        holder.showSelectRing(editing && mHost.selected() == f);
        if (holder.delete != null) {
            holder.delete.setVisibility(editing ? View.VISIBLE : View.INVISIBLE);
            holder.delete.setOnClickListener(
                    v -> {
                        holder.showSelectRing(false);
                        mHost.onDeleteClicked(f, this);
                    });
        }
        bindIcon(holder, f);

        holder.itemView.setOnClickListener(v -> mHost.onTileClicked(f, holder));
        holder.itemView.setOnLongClickListener(
                v -> {
                    mHost.onTileLongPressed(f, holder);
                    return true;
                });
        holder.itemView.setOnTouchListener(
                (v, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN && mHost.isEditMode()) {
                        mHost.onTileTouchDown(f, holder, this);
                    }
                    return false;
                });
        holder.itemView.setContentDescription(mHost.titleOf(f));
    }

    private void bindIcon(Holder holder, LemurXFavorite f) {
        LemurXFavoriteIcons icons = mHost.icons();
        if (f.isFolder()) {
            if (holder.folderIcon == null) return;
            List<String> urls = new ArrayList<>();
            for (int i = 0; i < f.children.size() && i < 3; i++) urls.add(f.children.get(i).url);
            holder.folderIconUrls = urls;
            List<Bitmap> bitmaps = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            for (String url : urls) {
                bitmaps.add(icons.cached(url));
                colors.add(0xFFB3B3B3);
            }
            holder.folderIcon.setIcons(bitmaps, colors);
            // Fill in whatever is missing asynchronously.
            for (int i = 0; i < urls.size(); i++) {
                final int index = i;
                final String url = urls.get(i);
                if (bitmaps.get(i) != null) continue;
                icons.get(
                        url,
                        bmp -> {
                            if (holder.folderIconUrls != urls) return;
                            List<Bitmap> now = new ArrayList<>();
                            for (String u : urls) now.add(icons.cached(u));
                            now.set(index, bmp);
                            holder.folderIcon.setIcons(now, colors);
                        });
            }
            return;
        }
        if (holder.icon == null) return;
        if (f.type == LemurXFavorite.TYPE_BOOKMARKS) {
            holder.iconUrl = null;
            holder.icon.setImageResource(R.drawable.lemurx_fav_local_bookmarks);
            return;
        }
        if (f.type == LemurXFavorite.TYPE_HISTORY) {
            holder.iconUrl = null;
            holder.icon.setImageResource(R.drawable.lemurx_fav_local_history);
            return;
        }
        final String url = f.url;
        holder.iconUrl = url;
        Bitmap cached = icons.cached(url);
        if (cached != null) {
            holder.icon.setImageBitmap(cached);
            return;
        }
        holder.icon.setImageDrawable(null);
        icons.get(
                url,
                bmp -> {
                    if (!url.equals(holder.iconUrl)) return;
                    holder.icon.setImageBitmap(bmp);
                });
    }
}
