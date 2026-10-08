// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.favorites;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.chromium.base.Callback;
import org.chromium.base.Log;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.components.browser_ui.bottomsheet.BottomSheetController;
import org.chromium.content_public.browser.LoadUrlParams;
import org.chromium.ui.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Favorites ("常用网址") grid on the new tab page -- the LemurX port of Lemur's
 * FavoriteWebCoordinator. Replaces Chromium's Most Visited tiles; see
 * NewTabPageCoordinator#initializeMostVisitedTilesCoordinator.
 *
 * <p>Interaction model (matches Lemur):
 *
 * <ul>
 *   <li>Tap a tile: opens the URL in the current tab. Tap a folder: expands it in a panel.
 *   <li>Tap empty space: shows the "+" tile for 5 s.
 *   <li>Long-press a tile: edit mode -- selection ring, delete badges, "+" tile, and a rename
 *       field below the grid for the selected tile. Tap outside to leave.
 *   <li>In edit mode, touch-down on a tile selects it and starts a drag: drop elsewhere to
 *       reorder, drop onto another tile to merge both into a folder; inside a folder, drag past
 *       the panel edge to move the tile back to the home page.
 *   <li>"+" opens a bottom sheet listing Bookmarks / History plus a URL field.
 * </ul>
 *
 * The grid is a plain child view of the NTP layout; nothing here touches the Lua runtime, so
 * the page behaves the same with scripts off.
 */
public class LemurXFavoritesCoordinator
        implements LemurXFavoritesAdapter.Host, LemurXFavoritesDragCallback.Listener {
    private static final String TAG = "LemurXFavorites";
    private static final int COLUMNS = 5;
    private static final long ADD_TILE_TIMEOUT_MS = 5000;

    /** Opens URLs for us (NewTabPageManager#loadUrl). */
    public interface Navigator {
        void loadUrl(LoadUrlParams params);
    }

    private final Activity mActivity;
    private final Profile mProfile;
    private final Navigator mNavigator;
    private final @Nullable BottomSheetController mBottomSheetController;
    private final LemurXFavoritesStore mStore;
    private final LemurXFavoriteIcons mIcons;

    private final View mRoot;
    private final RecyclerView mGrid;
    private final View mGroupPanel;
    private final RecyclerView mGroupGrid;
    private final ImageView mGroupCover;
    private final View mEditArea;
    private final EditText mEditName;
    private final View mEditConfirm;

    private final LemurXFavoritesAdapter mAdapter = new LemurXFavoritesAdapter(this, false);
    private @Nullable LemurXFavoritesAdapter mGroupAdapter;
    private @Nullable ItemTouchHelper mGroupTouchHelper;
    private @Nullable LemurXFavorite mOpenFolder;
    private @Nullable View mFolderAnimView;

    private boolean mEditMode;
    private @Nullable LemurXFavorite mSelected;
    private boolean mAddTileShown;
    private final Runnable mHideAddTile = () -> mAdapter.setAddVisible(false);
    private @Nullable ValueAnimator mGroupAnimator;
    private @Nullable ValueAnimator mEditAnimator;
    private boolean mDestroyed;
    private final ItemTouchHelper mTouchHelper;

    /**
     * @param parent The NTP layout; the grid is inserted at {@code index} with {@code lp}.
     */
    public LemurXFavoritesCoordinator(
            Activity activity,
            Profile profile,
            Navigator navigator,
            @Nullable BottomSheetController bottomSheetController,
            ViewGroup parent,
            int index,
            ViewGroup.LayoutParams lp) {
        mActivity = activity;
        mProfile = profile;
        mNavigator = navigator;
        mBottomSheetController = bottomSheetController;
        mStore = LemurXFavoritesStore.getInstance();
        int iconPx = activity.getResources().getDimensionPixelSize(R.dimen.lemurx_fav_icon_size);
        mIcons = new LemurXFavoriteIcons(activity, profile, iconPx);

        mRoot = LayoutInflater.from(activity).inflate(R.layout.lemurx_ntp_favorites, parent, false);
        parent.addView(mRoot, Math.min(index, parent.getChildCount()), lp);
        mGrid = mRoot.findViewById(R.id.lemurx_fav_grid);
        mGroupPanel = mRoot.findViewById(R.id.lemurx_fav_group_panel);
        mGroupGrid = mRoot.findViewById(R.id.lemurx_fav_group_grid);
        mGroupCover = mRoot.findViewById(R.id.lemurx_fav_group_cover);
        mEditArea = mRoot.findViewById(R.id.lemurx_fav_edit_area);
        mEditName = mRoot.findViewById(R.id.lemurx_fav_edit_name);
        mEditConfirm = mRoot.findViewById(R.id.lemurx_fav_edit_confirm);

        mGrid.setLayoutManager(new GridLayoutManager(activity, COLUMNS));
        mGrid.addItemDecoration(new EdgeAlignedGridDecoration(COLUMNS, iconPx));
        mGrid.setItemAnimator(null);
        mGrid.setAdapter(mAdapter);
        mTouchHelper = new ItemTouchHelper(new LemurXFavoritesDragCallback(mAdapter, this));
        mTouchHelper.attachToRecyclerView(mGrid);

        mGroupGrid.setLayoutManager(new GridLayoutManager(activity, COLUMNS));
        mGroupGrid.addItemDecoration(new EdgeAlignedGridDecoration(COLUMNS, iconPx));
        mGroupGrid.setItemAnimator(null);

        // Tap on blank grid space (not on a tile) counts as "outside".
        mGrid.setOnTouchListener(
                (v, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                            && mGrid.findChildViewUnder(event.getX(), event.getY()) == null) {
                        handleOutsideTouch();
                    }
                    return false;
                });
        mGroupGrid.setOnTouchListener(
                (v, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                            && mGroupGrid.findChildViewUnder(event.getX(), event.getY()) == null) {
                        handleOutsideTouch();
                    }
                    return false;
                });
        mGroupCover.setOnTouchListener((v, e) -> true);

        mEditName.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

                    @Override
                    public void onTextChanged(CharSequence s, int st, int b, int c) {}

                    @Override
                    public void afterTextChanged(Editable s) {
                        boolean enabled = !TextUtils.isEmpty(s) && mSelected != null;
                        mEditConfirm.setEnabled(enabled);
                        mEditConfirm.setAlpha(enabled ? 1f : 0.5f);
                    }
                });
        mRoot.findViewById(R.id.lemurx_fav_edit_clear).setOnClickListener(v -> mEditName.setText(""));
        mEditConfirm.setOnClickListener(v -> confirmRename());

        mStore.load(this::onLoaded);
    }

    /** The grid's root view (a direct child of the NTP layout). */
    public View getView() {
        return mRoot;
    }

    /** Matches the search box width (NewTabPageCoordinator#unifyElementWidths). */
    public void setWidth(int widthPx) {
        ViewGroup.LayoutParams lp = mRoot.getLayoutParams();
        if (lp == null) return;
        int target = widthPx > 0 ? widthPx : ViewGroup.LayoutParams.MATCH_PARENT;
        if (lp.width == target) return;
        lp.width = target;
        mRoot.setLayoutParams(lp);
    }

    /** Tap anywhere on the page that is not a tile: close folder / leave edit / toggle "+". */
    public void handleOutsideTouch() {
        if (mDestroyed) return;
        if (mGroupAnimator != null && mGroupAnimator.isRunning()) return;
        if (mEditAnimator != null && mEditAnimator.isRunning()) return;
        if (mGroupPanel.getVisibility() != View.GONE) {
            closeFolder();
            return;
        }
        if (mEditMode) {
            exitEditMode();
        } else {
            toggleAddTile();
        }
    }

    /** Back press while editing or with a folder open consumes the event. */
    public boolean onBackPressed() {
        if (mGroupPanel.getVisibility() != View.GONE) {
            closeFolder();
            return true;
        }
        if (mEditMode) {
            exitEditMode();
            return true;
        }
        return false;
    }

    public void destroy() {
        mDestroyed = true;
        mGrid.removeCallbacks(mHideAddTile);
        if (mGroupAnimator != null) mGroupAnimator.cancel();
        if (mEditAnimator != null) mEditAnimator.cancel();
        mIcons.destroy();
        ViewGroup parent = (ViewGroup) mRoot.getParent();
        if (parent != null) parent.removeView(mRoot);
    }

    // ---------------------------------------------------------------- data

    private void onLoaded(List<LemurXFavorite> tree) {
        if (mDestroyed) return;
        mAdapter.setTiles(tree);
    }

    private Set<String> allUrls() {
        Set<String> urls = new HashSet<>();
        for (LemurXFavorite f : mAdapter.tiles()) {
            if (f.isFolder()) {
                for (LemurXFavorite c : f.children) urls.add(c.url);
            } else {
                urls.add(f.url);
            }
        }
        return urls;
    }

    /**
     * Adds a URL shortcut to whichever grid the "+" was pressed in (Lemur: addFavoriteWeb).
     * Returns false and toasts when there is no room.
     */
    private boolean addUrl(String url, String title, LemurXFavoritesAdapter into) {
        LemurXFavorite f = LemurXFavorite.url(url, title);
        if (into.isGroup() && mOpenFolder != null) {
            if (!into.hasRoom()) {
                Toast.makeText(mActivity, R.string.lemurx_fav_folder_full, Toast.LENGTH_SHORT).show();
                return false;
            }
            f.parentId = mOpenFolder.id;
            f.orderIndex = mOpenFolder.children.size();
            mOpenFolder.children.add(f);
            into.append(f);
            mAdapter.refresh(mOpenFolder);
            mStore.insert(f, null);
            return true;
        }
        if (mAdapter.hasRoom()) {
            f.orderIndex = mAdapter.tileCount();
            mAdapter.append(f);
            mStore.insert(f, null);
            return true;
        }
        // Home page full: Lemur slips it into the first folder with room.
        for (LemurXFavorite folder : mAdapter.tiles()) {
            if (folder.isFolder() && folder.childCount() < LemurXFavorite.FOLDER_CAPACITY) {
                f.parentId = folder.id;
                f.orderIndex = folder.children.size();
                folder.children.add(f);
                mAdapter.refresh(folder);
                mStore.insert(f, null);
                return true;
            }
        }
        Toast.makeText(mActivity, R.string.lemurx_fav_full, Toast.LENGTH_SHORT).show();
        return false;
    }

    private void deleteTile(LemurXFavorite f, LemurXFavoritesAdapter from) {
        if (from.isGroup() && mOpenFolder != null) {
            mOpenFolder.children.remove(f);
            from.remove(f);
            mStore.delete(f);
            if (f == mSelected) selectDefault(from);
            collapseFolderIfNeeded(mOpenFolder);
            return;
        }
        from.remove(f);
        mStore.delete(f);
        if (f == mSelected) selectDefault(from);
    }

    /** A folder with one child becomes that child; with none it disappears. */
    private void collapseFolderIfNeeded(LemurXFavorite folder) {
        if (folder.children.size() > 1) {
            mAdapter.refresh(folder);
            return;
        }
        if (folder.children.isEmpty()) {
            closeFolder();
            mAdapter.remove(folder);
            mStore.delete(folder);
            return;
        }
        LemurXFavorite only = folder.children.get(0);
        closeFolder();
        folder.becomeCopyOf(only);
        mAdapter.refresh(folder);
        mStore.update(folder);
        mStore.delete(only);
    }

    // ---------------------------------------------------------------- edit mode

    private void enterEditMode(LemurXFavorite selected) {
        if (mEditMode) return;
        mEditMode = true;
        mSelected = selected;
        mGrid.removeCallbacks(mHideAddTile);
        mAdapter.setAddVisible(true);
        mAdapter.refreshAll();
        if (mGroupAdapter != null) mGroupAdapter.refreshAll();
        showEditArea(true);
        updateEditField();
        mGrid.postDelayed(() -> activeAdapter().showSelection(mSelected), 200);
    }

    private void exitEditMode() {
        if (!mEditMode) return;
        mEditMode = false;
        mSelected = null;
        mAdapter.showSelection(null);
        mAdapter.setAddVisible(false);
        mAdapter.refreshAll();
        if (mGroupAdapter != null) mGroupAdapter.refreshAll();
        showEditArea(false);
        hideKeyboard();
    }

    private void select(LemurXFavorite f, LemurXFavoritesAdapter in) {
        mSelected = f;
        in.showSelection(f);
        updateEditField();
    }

    private void selectDefault(LemurXFavoritesAdapter in) {
        if (in.tileCount() > 0) {
            LemurXFavorite first = in.at(0);
            if (first != null && !first.isAdd()) {
                select(first, in);
                return;
            }
        }
        mSelected = null;
        if (!in.isGroup()) exitEditMode();
        else updateEditField();
    }

    private void updateEditField() {
        if (mSelected == null) {
            mEditName.setText("");
            return;
        }
        mEditName.setText(titleOf(mSelected));
        mEditName.setSelection(mEditName.getText().length());
    }

    private void confirmRename() {
        if (mSelected == null) return;
        String name = mEditName.getText().toString().trim();
        if (name.isEmpty()) return;
        mSelected.title = name;
        mStore.update(mSelected);
        mAdapter.refresh(mSelected);
        if (mGroupAdapter != null) mGroupAdapter.refresh(mSelected);
        if (mOpenFolder != null) mAdapter.refresh(mOpenFolder);
        hideKeyboard();
        exitEditMode();
    }

    private void showEditArea(boolean show) {
        if (show == (mEditArea.getVisibility() == View.VISIBLE)) return;
        if (mEditAnimator != null) mEditAnimator.cancel();
        if (show) mEditArea.setVisibility(View.VISIBLE);
        mEditAnimator = ValueAnimator.ofFloat(show ? 0f : 1f, show ? 1f : 0f);
        mEditAnimator.setDuration(200);
        mEditAnimator.addUpdateListener(a -> mEditArea.setAlpha((float) a.getAnimatedValue()));
        mEditAnimator.addListener(
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (!show) mEditArea.setVisibility(View.GONE);
                    }
                });
        mEditAnimator.start();
    }

    private void toggleAddTile() {
        mAddTileShown = !mAddTileShown;
        mAdapter.setAddVisible(mAddTileShown);
        mGrid.removeCallbacks(mHideAddTile);
        if (mAddTileShown) {
            mGrid.postDelayed(
                    () -> {
                        mAddTileShown = false;
                        if (!mEditMode) mHideAddTile.run();
                    },
                    ADD_TILE_TIMEOUT_MS);
        }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm =
                    (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(mEditName.getWindowToken(), 0);
            mEditName.clearFocus();
        } catch (Throwable t) {
            Log.w(TAG, "hideKeyboard: %s", t.toString());
        }
    }

    private LemurXFavoritesAdapter activeAdapter() {
        return mGroupAdapter != null ? mGroupAdapter : mAdapter;
    }

    // ---------------------------------------------------------------- folders

    private void openFolder(LemurXFavorite folder, View anchor) {
        if (mGroupPanel.getVisibility() != View.GONE || mDestroyed) return;
        mOpenFolder = folder;
        mFolderAnimView = anchor.findViewById(R.id.lemurx_fav_icon);
        mGroupPanel.setPivotX(anchor.getX() + anchor.getWidth() / 2f);
        mGroupPanel.setPivotY(anchor.getY() + anchor.getHeight() / 2f);
        if (mFolderAnimView != null) {
            mFolderAnimView.setPivotX(mFolderAnimView.getWidth() / 2f);
            mFolderAnimView.setPivotY(mFolderAnimView.getHeight() / 2f);
        }
        mGroupAdapter = new LemurXFavoritesAdapter(this, true);
        mGroupAdapter.setTiles(folder.children);
        mGroupAdapter.setAddVisible(true);
        mGroupGrid.setAdapter(mGroupAdapter);
        if (mGroupTouchHelper != null) mGroupTouchHelper.attachToRecyclerView(null);
        mGroupTouchHelper =
                new ItemTouchHelper(new LemurXFavoritesDragCallback(mGroupAdapter, this));
        mGroupTouchHelper.attachToRecyclerView(mGroupGrid);
        mGroupPanel.setVisibility(View.VISIBLE);
        animateFolder(true);
    }

    private void closeFolder() {
        if (mGroupPanel.getVisibility() == View.GONE) return;
        animateFolder(false);
    }

    private void animateFolder(boolean show) {
        if (mGroupAnimator != null) mGroupAnimator.cancel();
        hideCover();
        final View animView = mFolderAnimView;
        mGroupAnimator = ValueAnimator.ofFloat(show ? 0f : 1f, show ? 1f : 0f);
        mGroupAnimator.setDuration(200);
        mGroupAnimator.addUpdateListener(
                a -> {
                    float v = (float) a.getAnimatedValue();
                    if (animView != null) {
                        animView.setScaleX(0.2f * v + 1f);
                        animView.setScaleY(0.2f * v + 1f);
                    }
                    mGroupPanel.setAlpha(v);
                    mGroupPanel.setScaleX(v);
                    mGroupPanel.setScaleY(v);
                });
        mGroupAnimator.addListener(
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (show) {
                            if (mEditMode && mGroupAdapter != null) selectDefault(mGroupAdapter);
                            return;
                        }
                        mGroupPanel.setVisibility(View.GONE);
                        mGroupGrid.setAdapter(null);
                        if (mGroupTouchHelper != null) {
                            mGroupTouchHelper.attachToRecyclerView(null);
                            mGroupTouchHelper = null;
                        }
                        mGroupAdapter = null;
                        LemurXFavorite folder = mOpenFolder;
                        mOpenFolder = null;
                        mFolderAnimView = null;
                        if (folder != null) mAdapter.refresh(folder);
                        if (mEditMode) {
                            // Back on the home grid: select the folder we just left.
                            if (folder != null && mAdapter.positionOf(folder) >= 0) {
                                select(folder, mAdapter);
                            } else {
                                selectDefault(mAdapter);
                            }
                        }
                    }
                });
        mGroupAnimator.start();
    }

    /** Freeze the panel while a tile flies out of it (Lemur onGroupCover/showCover). */
    private void showCover() {
        try {
            int w = mGroupPanel.getWidth();
            int h = mGroupPanel.getHeight();
            if (w <= 0 || h <= 0) return;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            mGroupPanel.draw(new Canvas(bmp));
            mGroupCover.setImageBitmap(bmp);
            mGroupCover.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            Log.w(TAG, "cover failed: %s", t.toString());
        }
    }

    private void hideCover() {
        mGroupCover.setVisibility(View.INVISIBLE);
        mGroupCover.setImageDrawable(null);
    }

    // ---------------------------------------------------------------- Adapter.Host

    @Override
    public boolean isEditMode() {
        return mEditMode;
    }

    @Override
    public @Nullable LemurXFavorite selected() {
        return mSelected;
    }

    @Override
    public String titleOf(LemurXFavorite f) {
        if (f.isFolder()) {
            return TextUtils.isEmpty(f.title) ? mActivity.getString(R.string.lemurx_fav_more) : f.title;
        }
        if (TextUtils.isEmpty(f.title)) {
            if (f.type == LemurXFavorite.TYPE_BOOKMARKS) {
                return mActivity.getString(R.string.lemurx_fav_bookmarks);
            }
            if (f.type == LemurXFavorite.TYPE_HISTORY) {
                return mActivity.getString(R.string.lemurx_fav_history);
            }
        }
        return f.displayTitle("");
    }

    @Override
    public LemurXFavoriteIcons icons() {
        return mIcons;
    }

    @Override
    public void onTileClicked(LemurXFavorite f, LemurXFavoritesAdapter.Holder holder) {
        if (mEditMode) {
            // In edit mode a tap on a folder still opens it so its contents can be edited.
            if (f.isFolder() && mGroupAdapter == null) openFolder(f, holder.itemView);
            return;
        }
        if (f.isFolder()) {
            openFolder(f, holder.itemView);
            return;
        }
        if (TextUtils.isEmpty(f.url)) return;
        try {
            mNavigator.loadUrl(new LoadUrlParams(f.url));
        } catch (Throwable t) {
            Log.w(TAG, "loadUrl failed: %s", t.toString());
        }
    }

    @Override
    public void onTileLongPressed(LemurXFavorite f, LemurXFavoritesAdapter.Holder holder) {
        if (!mEditMode) enterEditMode(f);
    }

    @Override
    public void onTileTouchDown(
            LemurXFavorite f, LemurXFavoritesAdapter.Holder holder, LemurXFavoritesAdapter adapter) {
        if (!mEditMode) return;
        select(f, adapter);
        ItemTouchHelper helper = adapter.isGroup() ? mGroupTouchHelper : mTouchHelper;
        if (helper != null) helper.startDrag(holder);
    }

    @Override
    public void onDeleteClicked(LemurXFavorite f, LemurXFavoritesAdapter adapter) {
        deleteTile(f, adapter);
    }

    @Override
    public void onAddClicked(LemurXFavoritesAdapter adapter) {
        if (mBottomSheetController == null) return;
        final LemurXFavoritesAdapter into = adapter;
        new LemurXFavoritePicker(
                        mActivity,
                        mProfile,
                        mBottomSheetController,
                        mIcons,
                        new LemurXFavoritePicker.Sink() {
                            @Override
                            public boolean add(String url, String title) {
                                return addUrl(url, title, into);
                            }

                            @Override
                            public Set<String> existingUrls() {
                                return allUrls();
                            }
                        })
                .show();
    }

    // ---------------------------------------------------------------- Drag.Listener

    @Override
    public boolean canJoin(LemurXFavorite selected, LemurXFavorite target) {
        if (mGroupAdapter != null) return false; // no nested folders
        if (!selected.canJoin() || target.isAdd()) return false;
        if (target.isFolder()) return target.childCount() < LemurXFavorite.FOLDER_CAPACITY;
        return target.canJoin();
    }

    @Override
    public void onJoin(LemurXFavorite origin, LemurXFavorite target) {
        if (mAdapter.positionOf(origin) < 0 || mAdapter.positionOf(target) < 0) return;
        if (!target.isFolder()) {
            // Lemur: the target row turns into the folder, a copy of it becomes the first child.
            LemurXFavorite first = target.shallowCopy();
            first.id = 0;
            first.parentId = target.id;
            first.orderIndex = 0;
            target.type = LemurXFavorite.TYPE_FOLDER;
            target.title = "";
            target.url = "";
            target.children.clear();
            target.children.add(first);
            mStore.update(target);
            mStore.insert(first, null);
        }
        origin.parentId = target.id;
        origin.orderIndex = target.children.size();
        target.children.add(origin);
        mStore.update(origin);
        mAdapter.remove(origin);
        mAdapter.refresh(target);
        mGrid.postDelayed(() -> select(target, mAdapter), 200);
    }

    @Override
    public boolean canRemove() {
        return mGroupAdapter != null && mAdapter.hasRoom();
    }

    @Override
    public void onRemove(LemurXFavorite f) {
        if (mOpenFolder == null || mGroupAdapter == null) return;
        LemurXFavorite folder = mOpenFolder;
        folder.children.remove(f);
        mGroupAdapter.remove(f);
        f.parentId = 0;
        f.orderIndex = mAdapter.tileCount();
        mAdapter.append(f);
        mStore.update(f);
        hideCover();
        collapseFolderIfNeeded(folder);
        if (mGroupPanel.getVisibility() != View.GONE) {
            // Folder still open (2+ left): keep editing inside it.
            if (mGroupAdapter != null) selectDefault(mGroupAdapter);
        } else {
            mGrid.postDelayed(() -> select(f, mAdapter), 200);
        }
    }

    @Override
    public @Nullable View removeAnchor() {
        return mAdapter.addView();
    }

    @Override
    public float[] removeBounds() {
        // Bounds of the panel expressed in the group grid's coordinate space.
        float top = -(mGroupGrid.getTop()) - dp(10);
        float bottom = mGroupPanel.getHeight() - mGroupGrid.getTop() + dp(10);
        return new float[] {top, bottom};
    }

    @Override
    public void onOrderChanged(LemurXFavoritesAdapter adapter) {
        List<LemurXFavorite> ordered = adapter.tiles();
        for (int i = 0; i < ordered.size(); i++) ordered.get(i).orderIndex = i;
        if (adapter.isGroup() && mOpenFolder != null) {
            mOpenFolder.children.clear();
            mOpenFolder.children.addAll(ordered);
            mAdapter.refresh(mOpenFolder);
        }
        mStore.updateOrder(ordered);
    }

    @Override
    public void onRemoveArmed(boolean armed) {
        if (armed) showCover();
        else hideCover();
    }

    private float dp(int v) {
        return v * mActivity.getResources().getDisplayMetrics().density;
    }

    /**
     * Spreads tiles so the first/last icon in each row sit flush with the grid edges (same inset
     * as the search box), with equal gaps between (Lemur EdgeAlignedGridDecoration).
     */
    private static final class EdgeAlignedGridDecoration extends RecyclerView.ItemDecoration {
        private final int mCols;
        private final int mIconPx;

        EdgeAlignedGridDecoration(int cols, int iconPx) {
            mCols = Math.max(2, cols);
            mIconPx = iconPx;
        }

        @Override
        public void getItemOffsets(
                Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
            int position = parent.getChildAdapterPosition(view);
            if (position == RecyclerView.NO_POSITION) return;
            int width = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            if (width <= 0 || width < mIconPx * mCols) return;
            int col = position % mCols;
            int cell = width / mCols;
            int gap = (width - mIconPx * mCols) / (mCols - 1);
            int desiredLeft = col * (mIconPx + gap);
            int cellLeft = col * cell;
            int offset = desiredLeft - cellLeft;
            outRect.left = Math.max(0, offset);
            outRect.right = Math.max(0, cell - mIconPx - offset);
        }
    }

    /** Test/debug helper: reloads the grid from storage. */
    public void reload(@Nullable Callback<Integer> onDone) {
        mStore.load(
                tree -> {
                    onLoaded(tree);
                    if (onDone != null) onDone.onResult(tree.size());
                });
    }
}
