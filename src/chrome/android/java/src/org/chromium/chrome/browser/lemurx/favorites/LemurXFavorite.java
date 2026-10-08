// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.favorites;

import android.text.TextUtils;

import org.chromium.build.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One tile on the new tab page favorites grid (port of Lemur's FavoriteWeb).
 *
 * <p>A favorite is either a URL shortcut, a folder holding up to {@link #FOLDER_CAPACITY} URL
 * shortcuts, or one of the two synthetic tiles the grid manages itself (the "+" tile and the
 * seeded Bookmarks / History shortcuts). Folder children carry {@code parentId} of the folder row.
 */
public class LemurXFavorite {
    /** Regular URL shortcut. */
    public static final int TYPE_URL = 0;

    /** Folder ("More") tile; {@link #children} holds its shortcuts. */
    public static final int TYPE_FOLDER = 1;

    /** Seeded shortcut to the bookmark manager. */
    public static final int TYPE_BOOKMARKS = 2;

    /** Seeded shortcut to history. */
    public static final int TYPE_HISTORY = 3;

    /** The "+" tile. Never persisted. */
    public static final int TYPE_ADD = 100;

    /** Max top-level tiles (Lemur: 10 + the "+" tile). */
    public static final int HOME_CAPACITY = 10;

    /** Max shortcuts inside one folder. */
    public static final int FOLDER_CAPACITY = 10;

    public long id;
    public int type;
    public String url = "";
    public String title = "";
    public int orderIndex;
    public long parentId;

    /** Folder contents (TYPE_FOLDER only). */
    public final List<LemurXFavorite> children = new ArrayList<>();

    /** Whether the "+" tile is currently shown. */
    public boolean visible = true;

    public LemurXFavorite() {}

    public LemurXFavorite(int type, String url, String title) {
        this.type = type;
        this.url = url == null ? "" : url;
        this.title = title == null ? "" : title;
    }

    public static LemurXFavorite url(String url, String title) {
        return new LemurXFavorite(TYPE_URL, url, title);
    }

    public static LemurXFavorite add() {
        LemurXFavorite f = new LemurXFavorite(TYPE_ADD, "", "");
        f.id = -1;
        return f;
    }

    public boolean isFolder() {
        return type == TYPE_FOLDER;
    }

    public boolean isAdd() {
        return type == TYPE_ADD;
    }

    public boolean isLocal() {
        return type == TYPE_BOOKMARKS || type == TYPE_HISTORY;
    }

    /** Whether this tile can be merged into another one to form a folder. */
    public boolean canJoin() {
        return type == TYPE_URL;
    }

    public int childCount() {
        return children.size();
    }

    public @Nullable LemurXFavorite findChild(long childId) {
        for (LemurXFavorite c : children) {
            if (c.id == childId) return c;
        }
        return null;
    }

    /** Display title: falls back to the host for untitled URL shortcuts. */
    public String displayTitle(String moreLabel) {
        if (isFolder()) return TextUtils.isEmpty(title) ? moreLabel : title;
        if (!TextUtils.isEmpty(title)) return title;
        return hostOf(url);
    }

    static String hostOf(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String s = url;
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        if (s.startsWith("www.")) s = s.substring(4);
        return s;
    }

    /** Copies url/title/type from {@code other} into this row (used when a folder collapses). */
    void becomeCopyOf(LemurXFavorite other) {
        type = other.type;
        url = other.url;
        title = other.title;
        children.clear();
    }

    /** Snapshot of the persisted fields, detached from {@link #children}. */
    LemurXFavorite shallowCopy() {
        LemurXFavorite f = new LemurXFavorite(type, url, title);
        f.id = id;
        f.orderIndex = orderIndex;
        f.parentId = parentId;
        return f;
    }

    @Override
    public String toString() {
        return "Favorite{" + id + "," + type + "," + title + "," + url + ",p=" + parentId + "}";
    }
}
