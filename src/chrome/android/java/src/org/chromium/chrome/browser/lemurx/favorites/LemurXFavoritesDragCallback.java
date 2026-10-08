// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.favorites;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.chromium.build.annotations.Nullable;

import java.util.List;

/**
 * Drag behaviour for a favorites grid (port of Lemur's DragHelperCallbackHandler +
 * FavoriteDragHelperCallback):
 *
 * <ul>
 *   <li>drag to reorder (the "+" tile never moves),
 *   <li>hover a URL tile over another tile to highlight it, release to merge them into a folder
 *       ("join"),
 *   <li>inside an open folder, drag a tile above/below the panel to move it back to the home page
 *       ("remove").
 * </ul>
 *
 * Dragging starts programmatically ({@link ItemTouchHelper#startDrag}) on touch-down in edit
 * mode, so {@link #isLongPressDragEnabled()} is false.
 */
class LemurXFavoritesDragCallback extends ItemTouchHelper.Callback {
    /** Decisions the callback delegates to the coordinator. */
    interface Listener {
        /** Whether {@code selected} may be merged into {@code target}. */
        boolean canJoin(LemurXFavorite selected, LemurXFavorite target);

        /** {@code origin} was dropped on {@code target}; merge them. */
        void onJoin(LemurXFavorite origin, LemurXFavorite target);

        /** Whether tiles may currently leave this (folder) grid for the home page. */
        boolean canRemove();

        /** A tile was dragged out of the folder panel; move it to the home page. */
        void onRemove(LemurXFavorite f);

        /** Home page view the removed tile flies towards (the "+" tile). */
        @Nullable View removeAnchor();

        /** Folder panel bounds in the grid's coordinate space (top, bottom) for drag-out. */
        float[] removeBounds();

        /** Order changed; persist. */
        void onOrderChanged(LemurXFavoritesAdapter adapter);

        /** Drag-out threshold crossed / uncrossed (Lemur shows a frozen cover of the panel). */
        void onRemoveArmed(boolean armed);
    }

    private final LemurXFavoritesAdapter mAdapter;
    private final Listener mListener;

    private RecyclerView.@Nullable ViewHolder mSelected;
    private RecyclerView.@Nullable ViewHolder mJoinTarget;
    private float mLastX;
    private float mLastY;
    private boolean mRemoveArmed;
    private boolean mAnimating;
    private boolean mMoved;

    LemurXFavoritesDragCallback(LemurXFavoritesAdapter adapter, Listener listener) {
        mAdapter = adapter;
        mListener = listener;
    }

    @Override
    public boolean isLongPressDragEnabled() {
        return false;
    }

    @Override
    public boolean isItemViewSwipeEnabled() {
        return false;
    }

    @Override
    public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
        LemurXFavorite f = mAdapter.at(viewHolder.getBindingAdapterPosition());
        if (f == null || f.isAdd()) return makeMovementFlags(0, 0);
        return makeMovementFlags(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT,
                0);
    }

    @Override
    public boolean canDropOver(
            RecyclerView recyclerView, RecyclerView.ViewHolder current, RecyclerView.ViewHolder target) {
        LemurXFavorite f = mAdapter.at(target.getBindingAdapterPosition());
        return f != null && !f.isAdd();
    }

    @Override
    public boolean onMove(
            RecyclerView recyclerView,
            RecyclerView.ViewHolder viewHolder,
            RecyclerView.ViewHolder target) {
        boolean moved =
                mAdapter.move(
                        viewHolder.getBindingAdapterPosition(), target.getBindingAdapterPosition());
        if (moved) mMoved = true;
        return moved;
    }

    @Override
    public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {}

    @Override
    public float getMoveThreshold(RecyclerView.ViewHolder viewHolder) {
        // Called continuously while dragging: track drag-out and re-validate the join target.
        updateRemoveArmed(viewHolder);
        if (mJoinTarget != null && !checkJoin(viewHolder, mJoinTarget)) clearJoin();
        return super.getMoveThreshold(viewHolder);
    }

    @Override
    public RecyclerView.@Nullable ViewHolder chooseDropTarget(
            RecyclerView.ViewHolder selected,
            List<RecyclerView.ViewHolder> dropTargets,
            int curX,
            int curY) {
        RecyclerView.ViewHolder target = super.chooseDropTarget(selected, dropTargets, curX, curY);
        if (target != null) {
            clearJoin();
            return target;
        }
        boolean joined = false;
        for (RecyclerView.ViewHolder candidate : dropTargets) {
            if (checkJoin(selected, candidate)) {
                joined = true;
                break;
            }
        }
        if (!joined) clearJoin();
        return null;
    }

    /** Lemur: join when the dragged tile sits within half a tile of the candidate. */
    private boolean checkJoin(RecyclerView.ViewHolder selected, RecyclerView.ViewHolder candidate) {
        LemurXFavorite s = mAdapter.at(selected.getBindingAdapterPosition());
        LemurXFavorite t = mAdapter.at(candidate.getBindingAdapterPosition());
        if (s == null || t == null || s == t) return false;
        if (!mListener.canJoin(s, t)) return false;
        float charge = selected.itemView.getWidth() / 2f;
        float sx = selected.itemView.getX();
        float sy = selected.itemView.getY();
        float tx = candidate.itemView.getX();
        float ty = candidate.itemView.getY();
        if (Math.abs(sx - tx) < charge && Math.abs(sy - ty) < charge) {
            mLastX = sx;
            mLastY = sy;
            if (mJoinTarget != candidate) {
                clearJoin();
                mJoinTarget = candidate;
                if (candidate instanceof LemurXFavoritesAdapter.Holder) {
                    ((LemurXFavoritesAdapter.Holder) candidate).showJoinRing(true);
                }
            }
            return true;
        }
        return false;
    }

    private void clearJoin() {
        if (mJoinTarget == null) return;
        if (mJoinTarget instanceof LemurXFavoritesAdapter.Holder) {
            ((LemurXFavoritesAdapter.Holder) mJoinTarget).showJoinRing(false);
        }
        mJoinTarget = null;
    }

    private void updateRemoveArmed(RecyclerView.ViewHolder viewHolder) {
        if (!mAdapter.isGroup() || !mListener.canRemove()) {
            setRemoveArmed(false);
            return;
        }
        float[] bounds = mListener.removeBounds();
        View v = viewHolder.itemView;
        boolean out = v.getY() + v.getHeight() < bounds[0] || v.getY() > bounds[1];
        if (out) {
            mLastX = v.getX();
            mLastY = v.getY();
        }
        setRemoveArmed(out);
    }

    private void setRemoveArmed(boolean armed) {
        if (mRemoveArmed == armed) return;
        mRemoveArmed = armed;
        mListener.onRemoveArmed(armed);
    }

    @Override
    public long getAnimationDuration(
            RecyclerView recyclerView, int animationType, float animateDx, float animateDy) {
        // Suppress the snap-back animation when we are about to run our own join/remove animation.
        if (mRemoveArmed || (mJoinTarget != null && mSelected != null)) {
            if (mSelected != null) mSelected.itemView.setVisibility(View.INVISIBLE);
            return 0;
        }
        return super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy);
    }

    @Override
    public void onSelectedChanged(RecyclerView.@Nullable ViewHolder viewHolder, int actionState) {
        if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
            if (mJoinTarget != null && mSelected != null) {
                animateJoin();
            } else if (mRemoveArmed && mSelected != null) {
                animateRemove();
            }
        } else if (viewHolder != null) {
            mSelected = viewHolder;
            mMoved = false;
            viewHolder.itemView.setScaleX(1.2f);
            viewHolder.itemView.setScaleY(1.2f);
        }
        super.onSelectedChanged(viewHolder, actionState);
    }

    @Override
    public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
        if (mJoinTarget == null && !mRemoveArmed) {
            super.clearView(recyclerView, viewHolder);
            viewHolder.itemView.setScaleX(1f);
            viewHolder.itemView.setScaleY(1f);
            if (mMoved) {
                mMoved = false;
                mListener.onOrderChanged(mAdapter);
            }
            mSelected = null;
        }
    }

    private void animateJoin() {
        if (mAnimating || mSelected == null || mJoinTarget == null) return;
        final RecyclerView.ViewHolder selected = mSelected;
        final RecyclerView.ViewHolder target = mJoinTarget;
        final LemurXFavorite origin = mAdapter.at(selected.getBindingAdapterPosition());
        final LemurXFavorite into = mAdapter.at(target.getBindingAdapterPosition());
        if (origin == null || into == null) {
            resetAfterAnimation(selected);
            return;
        }
        mAnimating = true;
        View v = selected.itemView;
        v.setX(mLastX);
        v.setY(mLastY);
        final float offsetX = target.itemView.getX() - mLastX;
        final float offsetY = target.itemView.getY() - mLastY;
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(200);
        anim.addUpdateListener(
                a -> {
                    float t = (float) a.getAnimatedValue();
                    v.setX(mLastX + offsetX * t);
                    v.setY(mLastY + offsetY * t);
                    v.setScaleX(1.2f * (1 - t));
                    v.setScaleY(1.2f * (1 - t));
                    v.setAlpha(1 - t);
                });
        anim.addListener(
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        clearJoin();
                        mListener.onJoin(origin, into);
                        resetAfterAnimation(selected);
                    }
                });
        v.postDelayed(
                () -> {
                    v.setVisibility(View.VISIBLE);
                    anim.start();
                },
                10);
    }

    private void animateRemove() {
        if (mAnimating || mSelected == null) return;
        final RecyclerView.ViewHolder selected = mSelected;
        final LemurXFavorite f = mAdapter.at(selected.getBindingAdapterPosition());
        View anchor = mListener.removeAnchor();
        if (f == null) {
            setRemoveArmed(false);
            resetAfterAnimation(selected);
            return;
        }
        mAnimating = true;
        View v = selected.itemView;
        v.setX(mLastX);
        v.setY(mLastY);
        // The anchor lives in another RecyclerView; fly towards the panel's edge in that direction.
        float[] bounds = mListener.removeBounds();
        float targetY = mLastY < bounds[0] ? bounds[0] - v.getHeight() * 2 : bounds[1] + v.getHeight();
        float targetX = anchor != null ? anchor.getX() : mLastX;
        final float offsetX = targetX - mLastX;
        final float offsetY = targetY - mLastY;
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(200);
        anim.addUpdateListener(
                a -> {
                    float t = (float) a.getAnimatedValue();
                    v.setX(mLastX + offsetX * t);
                    v.setY(mLastY + offsetY * t);
                    v.setAlpha(1 - t);
                });
        anim.addListener(
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        setRemoveArmed(false);
                        mListener.onRemove(f);
                        resetAfterAnimation(selected);
                    }
                });
        v.postDelayed(
                () -> {
                    v.setVisibility(View.VISIBLE);
                    anim.start();
                },
                10);
    }

    private void resetAfterAnimation(RecyclerView.ViewHolder holder) {
        View v = holder.itemView;
        v.postDelayed(
                () -> {
                    v.setTranslationX(0);
                    v.setTranslationY(0);
                    v.setAlpha(1f);
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    v.setVisibility(View.VISIBLE);
                    mSelected = null;
                    mAnimating = false;
                },
                50);
    }
}
