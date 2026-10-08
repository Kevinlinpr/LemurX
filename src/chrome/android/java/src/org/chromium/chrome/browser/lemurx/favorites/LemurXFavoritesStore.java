// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.favorites;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.chromium.base.Callback;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.SequencedTaskRunner;
import org.chromium.base.task.TaskTraits;
import org.chromium.components.embedder_support.util.UrlConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite-backed storage for the favorites grid (port of Lemur's FavoriteWebDBHelper +
 * FavoriteBeanDao, table layout kept compatible: {@code _id, url, title, order_index, type,
 * group_id}).
 *
 * <p>All database work runs on one background sequence; results are posted back to the UI thread.
 * Mutations are fire-and-forget from the UI thread -- the in-memory tree owned by the adapter is
 * the source of truth while the page is shown, the database only has to agree with it by the next
 * launch.
 */
public class LemurXFavoritesStore {
    private static final String TAG = "LemurXFavStore";
    private static final String DB_NAME = "lemurx_favorites.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "favorite_web";

    private static LemurXFavoritesStore sInstance;

    private final Helper mHelper;
    private final SequencedTaskRunner mRunner =
            PostTask.createSequencedTaskRunner(TaskTraits.USER_VISIBLE_MAY_BLOCK);

    public static LemurXFavoritesStore getInstance() {
        ThreadUtils.assertOnUiThread();
        if (sInstance == null) {
            sInstance = new LemurXFavoritesStore(ContextUtils.getApplicationContext());
        }
        return sInstance;
    }

    private LemurXFavoritesStore(Context context) {
        mHelper = new Helper(context);
    }

    /** Loads the whole tree (top-level tiles, folders populated) and delivers it on the UI thread. */
    public void load(Callback<List<LemurXFavorite>> callback) {
        mRunner.execute(
                () -> {
                    List<LemurXFavorite> tree;
                    try {
                        tree = loadTreeSync();
                    } catch (Throwable t) {
                        Log.w(TAG, "load failed: %s", t.toString());
                        tree = new ArrayList<>();
                    }
                    final List<LemurXFavorite> result = tree;
                    PostTask.postTask(TaskTraits.UI_DEFAULT, () -> callback.onResult(result));
                });
    }

    /** Inserts {@code f}; its {@link LemurXFavorite#id} is filled in before {@code onDone} runs. */
    public void insert(LemurXFavorite f, Runnable onDone) {
        final LemurXFavorite snapshot = f.shallowCopy();
        mRunner.execute(
                () -> {
                    long id = -1;
                    try {
                        id = mHelper.getWritableDatabase().insert(TABLE, null, values(snapshot));
                    } catch (Throwable t) {
                        Log.w(TAG, "insert failed: %s", t.toString());
                    }
                    // Assign on this sequence too, so a queued update()/delete() for the same
                    // row (which reads f.id when it runs) sees the new id.
                    if (id > 0) f.id = id;
                    final long newId = id;
                    PostTask.postTask(
                            TaskTraits.UI_DEFAULT,
                            () -> {
                                if (newId > 0) f.id = newId;
                                if (onDone != null) onDone.run();
                            });
                });
    }

    public void update(LemurXFavorite f) {
        final LemurXFavorite snapshot = f.shallowCopy();
        mRunner.execute(
                () -> {
                    long id = f.id;
                    if (id <= 0) return;
                    try {
                        mHelper.getWritableDatabase()
                                .update(TABLE, values(snapshot), "_id=?", new String[] {"" + id});
                    } catch (Throwable t) {
                        Log.w(TAG, "update failed: %s", t.toString());
                    }
                });
    }

    /** Deletes the row and, for folders, every child row. */
    public void delete(LemurXFavorite f) {
        mRunner.execute(
                () -> {
                    long id = f.id;
                    if (id <= 0) return;
                    try {
                        SQLiteDatabase db = mHelper.getWritableDatabase();
                        db.delete(TABLE, "_id=? OR group_id=?", new String[] {"" + id, "" + id});
                    } catch (Throwable t) {
                        Log.w(TAG, "delete failed: %s", t.toString());
                    }
                });
    }

    /** Rewrites order_index for every row in {@code ordered} to its list position. */
    public void updateOrder(List<LemurXFavorite> ordered) {
        final List<LemurXFavorite> rows = new ArrayList<>(ordered);
        final long[] parents = new long[rows.size()];
        for (int i = 0; i < rows.size(); i++) parents[i] = rows.get(i).parentId;
        mRunner.execute(
                () -> {
                    SQLiteDatabase db = mHelper.getWritableDatabase();
                    db.beginTransaction();
                    try {
                        for (int i = 0; i < rows.size(); i++) {
                            long id = rows.get(i).id;
                            if (id <= 0) continue;
                            ContentValues cv = new ContentValues();
                            cv.put("order_index", i);
                            cv.put("group_id", parents[i]);
                            db.update(TABLE, cv, "_id=?", new String[] {"" + id});
                        }
                        db.setTransactionSuccessful();
                    } catch (Throwable t) {
                        Log.w(TAG, "updateOrder failed: %s", t.toString());
                    } finally {
                        db.endTransaction();
                    }
                });
    }

    // ---------------------------------------------------------------- background

    private List<LemurXFavorite> loadTreeSync() {
        SQLiteDatabase db = mHelper.getWritableDatabase();
        List<LemurXFavorite> all = new ArrayList<>();
        try (Cursor c =
                db.query(
                        TABLE,
                        new String[] {"_id", "url", "title", "order_index", "type", "group_id"},
                        null,
                        null,
                        null,
                        null,
                        "order_index ASC, _id ASC")) {
            while (c.moveToNext()) {
                LemurXFavorite f = new LemurXFavorite();
                f.id = c.getLong(0);
                f.url = c.isNull(1) ? "" : c.getString(1);
                f.title = c.isNull(2) ? "" : c.getString(2);
                f.orderIndex = c.getInt(3);
                f.type = c.getInt(4);
                f.parentId = c.getLong(5);
                all.add(f);
            }
        }
        if (all.isEmpty()) {
            all = seedSync(db);
        }
        Map<Long, LemurXFavorite> byId = new HashMap<>();
        for (LemurXFavorite f : all) byId.put(f.id, f);
        List<LemurXFavorite> top = new ArrayList<>();
        List<LemurXFavorite> orphans = new ArrayList<>();
        for (LemurXFavorite f : all) {
            if (f.parentId <= 0) {
                top.add(f);
                continue;
            }
            LemurXFavorite parent = byId.get(f.parentId);
            if (parent != null && parent.isFolder()) {
                parent.children.add(f);
            } else {
                orphans.add(f);
            }
        }
        // Children of a folder that no longer exists come back to the home page.
        for (LemurXFavorite o : orphans) {
            o.parentId = 0;
            top.add(o);
        }
        // Folders that ended up empty or with a single child collapse (Lemur does the same
        // interactively; this handles crashes mid-edit).
        List<LemurXFavorite> fixed = new ArrayList<>();
        for (LemurXFavorite f : top) {
            if (f.isFolder() && f.children.size() <= 1) {
                if (f.children.isEmpty()) {
                    db.delete(TABLE, "_id=?", new String[] {String.valueOf(f.id)});
                    continue;
                }
                LemurXFavorite only = f.children.get(0);
                f.becomeCopyOf(only);
                db.update(TABLE, values(f), "_id=?", new String[] {String.valueOf(f.id)});
                db.delete(TABLE, "_id=?", new String[] {String.valueOf(only.id)});
            }
            fixed.add(f);
        }
        return fixed;
    }

    /** First run: Lemur seeds local shortcuts; LemurX seeds Bookmarks and History. */
    private List<LemurXFavorite> seedSync(SQLiteDatabase db) {
        List<LemurXFavorite> seeds = new ArrayList<>();
        LemurXFavorite bookmarks =
                new LemurXFavorite(
                        LemurXFavorite.TYPE_BOOKMARKS, UrlConstants.BOOKMARKS_NATIVE_URL, "");
        LemurXFavorite history =
                new LemurXFavorite(LemurXFavorite.TYPE_HISTORY, UrlConstants.HISTORY_URL, "");
        seeds.add(bookmarks);
        seeds.add(history);
        db.beginTransaction();
        try {
            for (int i = 0; i < seeds.size(); i++) {
                LemurXFavorite f = seeds.get(i);
                f.orderIndex = i;
                f.id = db.insert(TABLE, null, values(f));
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return seeds;
    }

    private static ContentValues values(LemurXFavorite f) {
        ContentValues cv = new ContentValues();
        cv.put("url", f.url);
        cv.put("title", f.title);
        cv.put("order_index", f.orderIndex);
        cv.put("type", f.type);
        cv.put("group_id", f.parentId);
        return cv;
    }

    private static class Helper extends SQLiteOpenHelper {
        Helper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(
                    "CREATE TABLE "
                            + TABLE
                            + " (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                            + " url TEXT, title TEXT, order_index INTEGER DEFAULT 0,"
                            + " type INTEGER DEFAULT 0, group_id INTEGER DEFAULT 0)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
    }
}
