// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.chromium.base.ThreadUtils;
import org.chromium.chrome.browser.lemurx.LemurXConsoleShell;
import org.chromium.ui.base.WindowAndroid;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 社区广场。进来先看大家的帖子，右上角加号再发帖。
 * 发帖带上已经登录的 Google 名字和头像。邮箱和私人对话不出现在这里。
 */
@NullMarked
class MuseCommunityView extends FrameLayout {
    private static final ExecutorService sAvatarLoad = Executors.newFixedThreadPool(2);
    private static final Map<String, Bitmap> sAvatarBits = new ConcurrentHashMap<>();

    private static final int MAX_PHOTOS = 9;

    private final MusePage.Links mLinks;
    private final LinearLayout mList;
    private final LinearLayout mPhotos;
    private final EditText mInput;
    private final EditText mLink;
    private final TextView mAction;
    private final TextView mAttached;
    private final View mCompose;
    private final FrameLayout mDetail;
    private final ImageView mZoom;
    private final TextView mError;
    private static @Nullable MuseCommunityView sOpen;
    private final List<byte[]> mPhotoBytes = new ArrayList<>();
    private @Nullable String mShareFileName;
    private @Nullable String mShareFileKind;
    private @Nullable String mShareFileText;
    private boolean mJoined;
    private boolean mBusy;

    MuseCommunityView(Context context, MusePage.Links links) {
        super(context);
        mLinks = links;
        sOpen = this;
        int pad = MusePage.pagePadding(context);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        mError = new TextView(context);
        mError.setTextColor(MusePage.error(context));
        mError.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mError.setPadding(pad, dp(8), pad, 0);
        mError.setVisibility(GONE);
        column.addView(mError);

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        mList = new LinearLayout(context);
        mList.setOrientation(LinearLayout.VERTICAL);
        mList.setPadding(pad, dp(8), pad, pad);
        scroll.addView(mList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroller = new ScrollView(context);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setBackgroundColor(MusePage.bg(context));
        scroller.setVisibility(GONE);
        scroller.setClipToPadding(false);
        mCompose = scroller;
        LinearLayout compose = new LinearLayout(context);
        compose.setOrientation(LinearLayout.VERTICAL);
        compose.setPadding(pad, dp(8), pad, dp(24));

        TextView composeTitle = new TextView(context);
        composeTitle.setText(R.string.lemurx_muse_community_write);
        composeTitle.setTextColor(MusePage.text(context));
        composeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        composeTitle.setTypeface(Typeface.DEFAULT_BOLD);
        compose.addView(composeTitle);

        TextView photoLabel = label(context, R.string.lemurx_muse_community_photos);
        LinearLayout.LayoutParams photoLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        photoLabelLp.topMargin = dp(16);
        compose.addView(photoLabel, photoLabelLp);
        mPhotos = new LinearLayout(context);
        LinearLayout.LayoutParams photoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(84));
        photoLp.topMargin = dp(8);
        compose.addView(photoRow(context), photoLp);

        TextView linkLabel = label(context, R.string.lemurx_muse_community_link);
        LinearLayout.LayoutParams linkLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        linkLabelLp.topMargin = dp(16);
        compose.addView(linkLabel, linkLabelLp);
        mLink = new EditText(context);
        mLink.setHint(R.string.lemurx_muse_community_link_hint);
        mLink.setHintTextColor(MusePage.muted(context));
        mLink.setTextColor(MusePage.text(context));
        mLink.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mLink.setSingleLine(true);
        mLink.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        mLink.setBackground(MusePage.rounded(MusePage.surface(context), 16, context));
        mLink.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams linkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        linkLp.topMargin = dp(8);
        compose.addView(mLink, linkLp);

        mInput = new EditText(context);
        mInput.setHint(R.string.lemurx_muse_community_caption);
        mInput.setHintTextColor(MusePage.muted(context));
        mInput.setTextColor(MusePage.text(context));
        mInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mInput.setMinLines(5);
        mInput.setGravity(Gravity.TOP | Gravity.START);
        mInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        mInput.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        mInput.setBackground(MusePage.rounded(MusePage.surface(context), 16, context));
        mInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        mInput.setVerticalScrollBarEnabled(false);
        mInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                revealCursor();
            }
        });
        mInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) revealCursor();
        });
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(12);
        compose.addView(mInput, inputLp);

        mAttached = new TextView(context);
        mAttached.setTextColor(MusePage.text(context));
        mAttached.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mAttached.setTypeface(Typeface.DEFAULT_BOLD);
        mAttached.setVisibility(GONE);
        mAttached.setPadding(dp(14), dp(12), dp(14), dp(12));
        mAttached.setBackground(MusePage.rounded(MusePage.surface(context), 16, context));
        LinearLayout.LayoutParams attachedLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        attachedLp.topMargin = dp(12);
        compose.addView(mAttached, attachedLp);

        mAction = new TextView(context);
        mAction.setText(R.string.lemurx_muse_community_post);
        mAction.setTextColor(MusePage.onAccent(context));
        mAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mAction.setTypeface(Typeface.DEFAULT_BOLD);
        mAction.setGravity(Gravity.CENTER);
        mAction.setBackground(MusePage.rounded(MusePage.accent(context), 16, context));
        mAction.setPadding(dp(16), dp(12), dp(16), dp(12));
        mAction.setOnClickListener(v -> submit());
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionLp.topMargin = dp(12);
        compose.addView(mAction, actionLp);
        scroller.addView(compose, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroller.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop) revealCursor();
        });
        addView(scroller, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mDetail = new FrameLayout(context);
        mDetail.setBackgroundColor(MusePage.bg(context));
        mDetail.setVisibility(GONE);
        mDetail.setClickable(true);
        addView(mDetail, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mZoom = new ImageView(context);
        mZoom.setBackgroundColor(0xF010181C);
        mZoom.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mZoom.setVisibility(GONE);
        mZoom.setOnClickListener(v -> mZoom.setVisibility(GONE));
        addView(mZoom, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        refreshPhotos();
    }

    private TextView label(Context context, int text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(MusePage.text(context));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    /** 一排缩略图，末尾是加号。最多九张，和狐猴一样。 */
    private View photoRow(Context context) {
        android.widget.HorizontalScrollView row = new android.widget.HorizontalScrollView(context);
        row.setHorizontalScrollBarEnabled(false);
        mPhotos.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(mPhotos, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** 右上角加号。空白发帖页，发出去之前都可以改。 */
    void openCompose() {
        mInput.setText("");
        mLink.setText("");
        mPhotoBytes.clear();
        refreshPhotos();
        clearAttachment();
        showError(null);
        presentCompose();
    }

    /** 从对话带进来的草稿。先改字，点发布才发出去。 */
    void openShare(
            String text,
            @Nullable String fileName,
            @Nullable String fileKind,
            @Nullable String fileText) {
        mLink.setText("");
        mPhotoBytes.clear();
        refreshPhotos();
        mInput.setText(text);
        if (fileName != null && !fileName.isEmpty()) {
            mShareFileName = fileName;
            mShareFileKind = fileKind;
            mShareFileText = fileText;
            mAttached.setText(getContext().getString(R.string.lemurx_muse_community_attached, fileName));
            mAttached.setVisibility(VISIBLE);
        } else {
            clearAttachment();
        }
        showError(null);
        presentCompose();
    }

    private void presentCompose() {
        mCompose.setVisibility(VISIBLE);
        mInput.requestFocus();
        mInput.post(this::revealCursor);
    }

    /** 光标那一行留在键盘上方，长文可以继续往下滚到发布按钮。 */
    private void revealCursor() {
        if (!(mCompose instanceof ScrollView) || mCompose.getVisibility() != VISIBLE) return;
        if (!mInput.isFocused()) return;
        ScrollView scroller = (ScrollView) mCompose;
        android.text.Layout layout = mInput.getLayout();
        if (layout == null) {
            mInput.post(this::revealCursor);
            return;
        }
        int pos = mInput.getSelectionEnd();
        if (pos < 0) pos = mInput.length();
        int line = layout.getLineForOffset(Math.max(0, pos));
        int lineBottom = mInput.getTop() + layout.getLineBottom(line) + dp(88);
        int target = lineBottom - scroller.getHeight();
        if (target < 0) target = 0;
        scroller.smoothScrollTo(0, target);
    }

    private void clearAttachment() {
        mShareFileName = null;
        mShareFileKind = null;
        mShareFileText = null;
        mAttached.setText("");
        mAttached.setVisibility(GONE);
    }

    static boolean closeCompose() {
        MuseCommunityView cover = MusePage.communityCover();
        if (cover != null && cover.closeTop()) return true;
        if (sOpen == null || sOpen == cover) return false;
        return sOpen.closeTop();
    }

    /** 放大图、帖子详情、发帖页各算一层。只收最上面那一层。 */
    private boolean closeTop() {
        if (mZoom.getVisibility() == VISIBLE) {
            mZoom.setVisibility(GONE);
            return true;
        }
        if (mDetail.getVisibility() == VISIBLE) {
            mDetail.setVisibility(GONE);
            return true;
        }
        if (mCompose.getVisibility() != VISIBLE) return false;
        hideCompose();
        return true;
    }

    private void hideCompose() {
        mCompose.setVisibility(GONE);
        hideKeyboard();
    }

    private int dp(int value) {
        return MusePage.dp(getContext(), value);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus && LemurXAccount.isSignedIn() && !mJoined) refreshNow();
    }

    void refreshNow() {
        if (!LemurXAccount.isSignedIn()) {
            loadFeed();
            return;
        }
        JSONObject identity = googleIdentity();
        if (identity == null) {
            loadFeed();
            return;
        }
        MuseApi.get().send("POST", "/api/community/join", identity, (value, error) -> {
            if (error != null) {
                showError(error);
                loadFeed();
                return;
            }
            if (value instanceof JSONObject) show((JSONObject) value);
        });
    }

    private void loadFeed() {
        MuseApi.get().get("/api/community", (value, error) -> {
            if (error != null) {
                showError(error);
                return;
            }
            if (value instanceof JSONObject) show((JSONObject) value);
        });
    }

    /** Google 显示名和 https 头像。名字里有邮箱就不拿去社区。 */
    private static @Nullable JSONObject googleIdentity() {
        LemurXAccount.Profile profile = LemurXAccount.profile();
        if (profile == null) return null;
        String name = profile.nickname == null ? "" : profile.nickname.trim();
        if (name.isEmpty() || name.indexOf('@') >= 0) return null;
        JSONObject body = new JSONObject();
        try {
            body.put("name", name);
            String avatar = profile.avatar == null ? "" : profile.avatar.trim();
            if (avatar.startsWith("https://")) body.put("avatar", avatar);
        } catch (Exception e) {
            return null;
        }
        return body;
    }

    private void refreshPhotos() {
        mPhotos.removeAllViews();
        Context context = getContext();
        for (int i = 0; i < mPhotoBytes.size(); i++) {
            final int index = i;
            FrameLayout tile = new FrameLayout(context);
            ImageView thumb = new ImageView(context);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap bitmap = BitmapFactory.decodeByteArray(
                    mPhotoBytes.get(i), 0, mPhotoBytes.get(i).length);
            if (bitmap != null) thumb.setImageBitmap(bitmap);
            tile.addView(thumb, new FrameLayout.LayoutParams(dp(72), dp(72)));
            TextView remove = new TextView(context);
            remove.setText("×");
            remove.setGravity(Gravity.CENTER);
            remove.setTextColor(MusePage.onAccent(context));
            remove.setBackground(MusePage.rounded(MusePage.accent(context), 10, context));
            remove.setOnClickListener(v -> {
                mPhotoBytes.remove(index);
                refreshPhotos();
            });
            FrameLayout.LayoutParams x = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.END);
            tile.addView(remove, x);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(72), dp(72));
            lp.setMarginEnd(dp(8));
            mPhotos.addView(tile, lp);
        }
        if (mPhotoBytes.size() >= MAX_PHOTOS) return;
        TextView add = new TextView(context);
        add.setText("+");
        add.setGravity(Gravity.CENTER);
        add.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        add.setTextColor(MusePage.text(context));
        add.setBackground(MusePage.rounded(MusePage.surface(context), 12, context));
        add.setOnClickListener(v -> pickPhotos());
        mPhotos.addView(add, new LinearLayout.LayoutParams(dp(72), dp(72)));
    }

    private void pickPhotos() {
        if (mPhotoBytes.size() >= MAX_PHOTOS) {
            showError(getContext().getString(R.string.lemurx_muse_community_max_photos));
            return;
        }
        WindowAndroid window = LemurXConsoleShell.windowAndroid();
        if (window == null) {
            showError(getContext().getString(R.string.lemurx_muse_attach_failed));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        window.showIntent(intent, (resultCode, data) -> onPhotosPicked(resultCode, data), null);
    }

    private void onPhotosPicked(int resultCode, @Nullable Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        sAvatarLoad.execute(() -> {
            List<byte[]> added = new ArrayList<>();
            int remain = MAX_PHOTOS - mPhotoBytes.size();
            for (Uri uri : uris) {
                if (added.size() >= remain) break;
                byte[] jpeg = jpegOf(uri);
                if (jpeg != null) added.add(jpeg);
            }
            ThreadUtils.postOnUiThread(() -> {
                mPhotoBytes.addAll(added);
                refreshPhotos();
                if (uris.size() > added.size() && mPhotoBytes.size() >= MAX_PHOTOS) {
                    showError(getContext().getString(R.string.lemurx_muse_community_max_photos));
                }
            });
        });
    }

    private @Nullable byte[] jpegOf(Uri uri) {
        try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (raw.size() + n > 8_000_000) return null;
                raw.write(buf, 0, n);
            }
            byte[] bytes = raw.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            if (bitmap == null) return null;
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int quality = 72;
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
                while (out.size() > 900_000 && quality > 40) {
                    quality -= 8;
                    out.reset();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
                }
                return out.size() == 0 ? null : out.toByteArray();
            } finally {
                bitmap.recycle();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void submit() {
        if (!LemurXAccount.isSignedIn()) {
            Activity activity = LemurXConsoleShell.activity();
            if (activity != null) LemurXSignIn.showFromBrowser(activity);
            return;
        }
        if (!mJoined) {
            JSONObject identity = googleIdentity();
            if (identity == null) {
                Activity activity = LemurXConsoleShell.activity();
                if (activity != null) LemurXSignIn.showFromBrowser(activity);
                return;
            }
            MuseApi.get().send("POST", "/api/community/join", identity, (value, error) -> {
                if (error != null) {
                    showError(error);
                    return;
                }
                mJoined = true;
                submit();
            });
            return;
        }
        if (mBusy) return;
        String typed = mInput.getText() == null ? "" : mInput.getText().toString().trim();
        String link = mLink.getText() == null ? "" : mLink.getText().toString().trim();
        boolean hasFile = mShareFileName != null && !mShareFileName.isEmpty();
        if (typed.isEmpty() && link.isEmpty() && mPhotoBytes.isEmpty() && !hasFile) {
            showError(getContext().getString(R.string.lemurx_muse_community_need));
            return;
        }
        if (!link.isEmpty() && !link.contains("://")) link = "https://" + link;
        mBusy = true;
        mAction.setText(R.string.lemurx_muse_community_uploading);
        uploadAt(0, new JSONArray(), typed, link);
    }

    private void uploadAt(int index, JSONArray ids, String text, String link) {
        if (index >= mPhotoBytes.size()) {
            sendPost(ids, text, link);
            return;
        }
        MuseApi.get().postImage("/api/community/media", mPhotoBytes.get(index), (value, error) -> {
            if (error != null || !(value instanceof JSONObject)) {
                mBusy = false;
                mAction.setText(R.string.lemurx_muse_community_post);
                showError(error == null
                        ? getContext().getString(R.string.lemurx_muse_community_upload_failed)
                        : error);
                return;
            }
            String id = ((JSONObject) value).optString("id", "");
            if (id.isEmpty()) {
                mBusy = false;
                mAction.setText(R.string.lemurx_muse_community_post);
                showError(getContext().getString(R.string.lemurx_muse_community_upload_failed));
                return;
            }
            ids.put(id);
            uploadAt(index + 1, ids, text, link);
        });
    }

    private void sendPost(JSONArray ids, String text, String link) {
        JSONObject body = new JSONObject();
        String fileName = mShareFileName;
        String fileKind = mShareFileKind;
        String fileText = mShareFileText;
        try {
            if (fileName != null && !fileName.isEmpty()) {
                body.put("kind", "agent");
                body.put("text", text.isEmpty() ? fileName : text);
                body.put("fileName", fileName);
                body.put("fileKind", fileKind == null ? "text" : fileKind);
                String excerpt = fileText == null ? "" : fileText;
                if (excerpt.length() > 8000) excerpt = excerpt.substring(0, 8000);
                body.put("fileText", excerpt);
            } else {
                body.put("kind", "note");
                body.put("text", text);
            }
            if (!link.isEmpty()) body.put("url", link);
            if (ids.length() > 0) body.put("images", ids);
        } catch (Exception e) {
            mBusy = false;
            return;
        }
        mAction.setText(R.string.lemurx_muse_community_post);
        MuseApi.get().send("POST", "/api/community/posts", body, (value, error) -> {
            mBusy = false;
            mAction.setText(R.string.lemurx_muse_community_post);
            if (error != null) {
                showError(error);
                return;
            }
            mInput.setText("");
            mLink.setText("");
            mPhotoBytes.clear();
            refreshPhotos();
            clearAttachment();
            hideCompose();
            if (value instanceof JSONObject) show((JSONObject) value);
        });
    }

    private void show(JSONObject root) {
        mError.setVisibility(GONE);
        mJoined = root.optBoolean("joined", false) && LemurXAccount.isSignedIn();
        mList.removeAllViews();
        JSONArray posts = root.optJSONArray("posts");
        if (posts == null || posts.length() == 0) {
            TextView empty = new TextView(getContext());
            empty.setText(R.string.lemurx_muse_community_empty);
            empty.setTextColor(MusePage.muted(getContext()));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            empty.setPadding(dp(4), dp(24), dp(4), 0);
            mList.addView(empty);
        } else {
            for (int i = 0; i < posts.length(); i++) {
                JSONObject post = posts.optJSONObject(i);
                if (post != null) mList.addView(postCard(post));
            }
        }
    }

    private View postCard(JSONObject post) {
        Context context = getContext();
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(2), dp(16), dp(2), 0);
        card.setOnClickListener(v -> openDetail(post));
        String kind = post.optString("kind", "note");
        card.addView(authorRow(post));
        String note = post.optString("text", "");
        if (!note.isEmpty()) {
            TextView said = new TextView(context);
            said.setText(note);
            said.setTextColor(MusePage.text(context));
            said.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            said.setLineSpacing(0, 1.35f);
            said.setMaxLines(8);
            said.setEllipsize(TextUtils.TruncateAt.END);
            said.setPadding(0, dp(10), 0, 0);
            card.addView(said);
        }
        JSONArray images = post.optJSONArray("images");
        if (images != null && images.length() > 0) card.addView(imageGrid(images));
        String url = post.optString("url", "");
        if (!url.isEmpty() && !"agent".equals(kind)) {
            card.addView(linkCard(post.optString("title", ""), url));
        } else if ("agent".equals(kind)) {
            TextView file = new TextView(context);
            file.setText(post.optString("fileName", ""));
            file.setTextColor(MusePage.text(context));
            file.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            file.setTypeface(Typeface.DEFAULT_BOLD);
            file.setPadding(dp(12), dp(12), dp(12), dp(12));
            file.setBackground(MusePage.rounded(MusePage.surface(context), 14, context));
            String fileName = post.optString("fileName", "notes");
            String fileKind = post.optString("fileKind", "text");
            String fileText = post.optString("fileText", "");
            file.setOnClickListener(v -> LemurXFiles.open(context, fileName, fileKind, fileText));
            LinearLayout.LayoutParams fileLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            fileLp.topMargin = dp(10);
            file.setLayoutParams(fileLp);
            card.addView(file);
        }
        card.addView(actionRow(post));
        View line = new View(context);
        line.setBackgroundColor(MusePage.line(context));
        card.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
        return card;
    }

    private View authorRow(JSONObject post) {
        Context context = getContext();
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView photo = avatarView(post.optString("avatar", ""), post.optString("name", ""));
        head.addView(photo, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView who = new TextView(context);
        who.setText(post.optString("name", ""));
        who.setTextColor(MusePage.text(context));
        who.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        who.setTypeface(Typeface.DEFAULT_BOLD);
        names.addView(who);
        String at = post.optString("at", "");
        if (at.length() >= 16) {
            TextView when = new TextView(context);
            when.setText(at.substring(0, 16).replace('T', ' '));
            when.setTextColor(MusePage.muted(context));
            when.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            names.addView(when);
        }
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.setMarginStart(dp(10));
        head.addView(names, nameLp);
        return head;
    }

    private View linkCard(String title, String url) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(MusePage.rounded(MusePage.surface(context), 14, context));
        TextView mark = new TextView(context);
        mark.setText("↗");
        mark.setGravity(Gravity.CENTER);
        mark.setTextColor(MusePage.text(context));
        mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mark.setBackground(MusePage.rounded(MusePage.bg(context), 10, context));
        row.addView(mark, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView heading = new TextView(context);
        heading.setText(title.isEmpty() ? url : title);
        heading.setTextColor(MusePage.text(context));
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setMaxLines(1);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(heading);
        TextView address = new TextView(context);
        address.setText(url);
        address.setTextColor(MusePage.muted(context));
        address.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        address.setMaxLines(1);
        address.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(address);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginStart(dp(10));
        row.addView(texts, textLp);
        row.setOnClickListener(v -> {
            if (url.startsWith("http://") || url.startsWith("https://")) mLinks.openInNewTab(url);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        row.setLayoutParams(lp);
        return row;
    }

    private View actionRow(JSONObject post) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(12));
        row.addView(actionButton(context.getString(
                R.string.lemurx_muse_community_like, post.optInt("likes", 0)), post.optBoolean("liked"),
                v -> react(post, "like")));
        row.addView(actionButton(context.getString(
                R.string.lemurx_muse_community_comment, post.optInt("comments", 0)), false,
                v -> openDetail(post)));
        row.addView(actionButton(context.getString(
                R.string.lemurx_muse_community_collect, post.optInt("collects", 0)), post.optBoolean("collected"),
                v -> react(post, "collect")));
        row.addView(actionButton(context.getString(
                R.string.lemurx_muse_community_views, post.optInt("views", 0)), false, v -> openDetail(post)));
        return row;
    }

    private TextView actionButton(String label, boolean on, View.OnClickListener click) {
        TextView view = new TextView(getContext());
        view.setText(label);
        view.setTextColor(on ? MusePage.text(getContext()) : MusePage.muted(getContext()));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        if (on) view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(6), dp(16), dp(6));
        view.setOnClickListener(click);
        return view;
    }

    private void react(JSONObject post, String kind) {
        if (!LemurXAccount.isSignedIn()) {
            Activity activity = LemurXConsoleShell.activity();
            if (activity != null) LemurXSignIn.showFromBrowser(activity);
            return;
        }
        String id = post.optString("id", "");
        if (id.isEmpty()) return;
        MuseApi.get().send("POST", "/api/community/posts/" + id + "/" + kind, new JSONObject(),
                (value, error) -> {
                    if (error != null) {
                        showError(error);
                        return;
                    }
                    if (value instanceof JSONObject) show((JSONObject) value);
                });
    }

    private void openDetail(JSONObject post) {
        Context context = getContext();
        mDetail.removeAllViews();
        ScrollView scroll = new ScrollView(context);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(12), dp(16), dp(24));
        TextView back = new TextView(context);
        back.setText(R.string.lemurx_muse_community_back);
        back.setTextColor(MusePage.text(context));
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        back.setPadding(0, 0, 0, dp(12));
        back.setOnClickListener(v -> mDetail.setVisibility(GONE));
        column.addView(back);
        column.addView(authorRow(post));
        String note = post.optString("text", "");
        if (!note.isEmpty()) {
            TextView said = new TextView(context);
            said.setText(note);
            said.setTextColor(MusePage.text(context));
            said.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            said.setLineSpacing(0, 1.4f);
            said.setPadding(0, dp(12), 0, 0);
            column.addView(said);
        }
        JSONArray images = post.optJSONArray("images");
        if (images != null && images.length() > 0) column.addView(imageGrid(images));
        String url = post.optString("url", "");
        if (!url.isEmpty()) column.addView(linkCard(post.optString("title", ""), url));
        column.addView(actionRow(post));
        LinearLayout comments = new LinearLayout(context);
        comments.setOrientation(LinearLayout.VERTICAL);
        column.addView(comments);
        EditText input = new EditText(context);
        input.setHint(R.string.lemurx_muse_community_comment_hint);
        input.setHintTextColor(MusePage.muted(context));
        input.setTextColor(MusePage.text(context));
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setBackground(MusePage.rounded(MusePage.surface(context), 16, context));
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(8);
        column.addView(input, inputLp);
        TextView send = new TextView(context);
        send.setText(R.string.lemurx_muse_community_comment_send);
        send.setTextColor(MusePage.onAccent(context));
        send.setGravity(Gravity.CENTER);
        send.setBackground(MusePage.rounded(MusePage.accent(context), 16, context));
        send.setPadding(dp(16), dp(10), dp(16), dp(10));
        send.setOnClickListener(v -> sendComment(post, input, comments));
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sendLp.topMargin = dp(8);
        column.addView(send, sendLp);
        scroll.addView(column);
        mDetail.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mDetail.setVisibility(VISIBLE);
        loadComments(post.optString("id", ""), comments);
    }

    private void loadComments(String id, LinearLayout comments) {
        if (id.isEmpty()) return;
        MuseApi.get().get("/api/community/posts/" + id + "/comments", (value, error) -> {
            if (!(value instanceof JSONObject)) return;
            paintComments(comments, ((JSONObject) value).optJSONArray("comments"));
        });
    }

    private void paintComments(LinearLayout comments, @Nullable JSONArray list) {
        comments.removeAllViews();
        Context context = getContext();
        if (list == null || list.length() == 0) {
            TextView empty = new TextView(context);
            empty.setText(R.string.lemurx_muse_community_no_comments);
            empty.setTextColor(MusePage.muted(context));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            empty.setPadding(0, dp(8), 0, 0);
            comments.addView(empty);
            return;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            TextView line = new TextView(context);
            line.setText(item.optString("name", "") + "  " + item.optString("text", ""));
            line.setTextColor(MusePage.text(context));
            line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            line.setPadding(0, dp(10), 0, 0);
            comments.addView(line);
        }
    }

    private void sendComment(JSONObject post, EditText input, LinearLayout comments) {
        if (!LemurXAccount.isSignedIn()) {
            Activity activity = LemurXConsoleShell.activity();
            if (activity != null) LemurXSignIn.showFromBrowser(activity);
            return;
        }
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        if (text.isEmpty()) return;
        JSONObject body = new JSONObject();
        try {
            body.put("text", text);
        } catch (Exception e) {
            return;
        }
        MuseApi.get().send("POST", "/api/community/posts/" + post.optString("id", "") + "/comments", body,
                (value, error) -> {
                    if (error != null) {
                        showError(error);
                        return;
                    }
                    input.setText("");
                    if (value instanceof JSONObject) {
                        paintComments(comments, ((JSONObject) value).optJSONArray("comments"));
                    }
                    refreshNow();
                });
    }

    private View imageGrid(JSONArray images) {
        LinearLayout grid = new LinearLayout(getContext());
        grid.setOrientation(LinearLayout.HORIZONTAL);
        grid.setPadding(0, dp(8), 0, 0);
        int count = Math.min(images.length(), MAX_PHOTOS);
        for (int i = 0; i < count; i++) {
            String path = images.optString(i, "");
            if (!path.startsWith("/api/community/media/")) continue;
            ImageView cell = new ImageView(getContext());
            cell.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cell.setBackground(MusePage.rounded(MusePage.bg(getContext()), 8, getContext()));
            Bitmap cached = sAvatarBits.get(path);
            if (cached != null) cell.setImageBitmap(cached);
            else loadPostImage(cell, path);
            cell.setOnClickListener(v -> {
                Bitmap bitmap = sAvatarBits.get(path);
                if (bitmap == null) return;
                mZoom.setImageBitmap(bitmap);
                mZoom.setVisibility(VISIBLE);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(96), dp(96));
            lp.setMarginEnd(dp(6));
            grid.addView(cell, lp);
        }
        return grid;
    }

    private void loadPostImage(ImageView cell, String path) {
        MuseApi.get().getBytes(path, (bytes, error) -> {
            if (error != null || bytes == null || bytes.length == 0) return;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) return;
            sAvatarBits.put(path, bitmap);
            if (cell.isAttachedToWindow()) cell.setImageBitmap(bitmap);
        });
    }

    private ImageView avatarView(String url, String name) {
        ImageView view = new ImageView(getContext());
        view.setContentDescription(name);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImageBitmap(letterAvatar(name, dp(36)));
        Bitmap cached = url.startsWith("https://") ? sAvatarBits.get(url) : null;
        if (cached != null) {
            view.setImageBitmap(cached);
            return view;
        }
        if (!url.startsWith("https://") || sAvatarBits.size() > 48) return view;
        sAvatarLoad.execute(() -> {
            Bitmap bitmap = fetchAvatar(url);
            if (bitmap == null) return;
            sAvatarBits.put(url, bitmap);
            ThreadUtils.postOnUiThread(() -> {
                if (view.isAttachedToWindow()) view.setImageBitmap(bitmap);
            });
        });
        return view;
    }

    private Bitmap letterAvatar(String name, int size) {
        Bitmap bitmap = Bitmap.createBitmap(Math.max(size, 1), Math.max(size, 1), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(MusePage.night(getContext()) ? 0xFF2C3A42 : 0xFFE7EEF2);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        paint.setColor(MusePage.text(getContext()));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(size * 0.42f);
        String letter = name == null || name.trim().isEmpty()
                ? "?"
                : name.trim().substring(0, 1).toUpperCase();
        float y = size / 2f - (paint.descent() + paint.ascent()) / 2f;
        canvas.drawText(letter, size / 2f, y, paint);
        return bitmap;
    }

    /** 对话里把这段回答带进发帖页。不直接发出去。 */
    static void shareNote(String text) {
        String body = text == null ? "" : text.trim();
        if (body.isEmpty()) return;
        if (body.length() > 2000) body = body.substring(0, 2000);
        openEditor(body, null, null, null);
    }

    /** 把 Aero 做出的文件带进发帖页，文案可以改，点发布才发出去。 */
    static void shareWork(String name, String kind, String text) {
        String fileName = name == null ? "" : name.trim();
        if (fileName.isEmpty()) return;
        String caption = text == null ? "" : text.trim();
        if (caption.length() > 2000) caption = caption.substring(0, 2000);
        String fileText = text == null ? "" : text;
        if (fileText.length() > 8000) fileText = fileText.substring(0, 8000);
        openEditor(caption, fileName, kind, fileText);
    }

    private static void openEditor(
            String text, @Nullable String fileName, @Nullable String fileKind, @Nullable String fileText) {
        LemurXConsoleShell.goCommunity();
        MuseCommunityView view = MusePage.communityCover();
        if (view != null) view.openShare(text, fileName, fileKind, fileText);
    }

    private static @Nullable Bitmap fetchAvatar(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(true);
            if (connection.getResponseCode() != 200) return null;
            if (connection.getURL() == null || !"https".equals(connection.getURL().getProtocol())) {
                return null;
            }
            int length = connection.getContentLength();
            if (length > 512_000) return null;
            Bitmap raw;
            try (InputStream in = connection.getInputStream()) {
                raw = BitmapFactory.decodeStream(in);
            }
            if (raw == null) return null;
            int size = 96;
            Bitmap scaled = Bitmap.createScaledBitmap(raw, size, size, true);
            Bitmap circle = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(circle);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
            return circle;
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void showError(@Nullable String error) {
        mError.setText(error == null ? "" : error);
        mError.setVisibility(TextUtils.isEmpty(error) ? GONE : VISIBLE);
    }

    private void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(mInput.getWindowToken(), 0);
    }
}
