// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.agent;

import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.browser.contextmenu.ContextMenuCoordinator;
import org.chromium.content.browser.input.DateTimeChooserAndroid;
import org.chromium.content.browser.input.PopupItemType;
import org.chromium.content.browser.input.SelectPopup;
import org.chromium.content.browser.input.SelectPopupItem;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.base.ime.TextInputType;
import org.chromium.ui.modaldialog.DialogDismissalCause;
import org.chromium.ui.modaldialog.ModalDialogManager;
import org.chromium.ui.modaldialog.ModalDialogManager.ModalDialogType;
import org.chromium.ui.modaldialog.ModalDialogProperties;
import org.chromium.ui.modelutil.PropertyModel;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 原生对话框桥：网页之外、但挡在网页前面的东西——JS alert/confirm/prompt（模态对话框）、
 * {@code <select>} 弹层、日期/时间选择器、长按菜单。它们不在网页的无障碍树里，也不在截图里
 * （截图只拍网页表面），所以 {@code read} 单独报告一个 {@code dialog} 字段，{@code input}
 * 用 {@code type:"dialog"} 来回答。
 *
 * <p>回答都走 Chromium 自己的对话框控制器——和用户点按钮走的是同一条路。页面只看到结果
 * （confirm 返回 true、select 的 change 事件、input 的 value），看不到是谁点的。
 *
 * <p>全部方法只能在 UI 线程调用。
 */
final class LemurXDialogs {
    private LemurXDialogs() {}

    /** 现在有没有任何对话框挡着；有就描述它（UI 线程），没有返回 null。 */
    static @Nullable JSONObject describe(ChromeTabbedActivity activity, @Nullable WebContents wc) {
        ThreadUtils.assertOnUiThread();
        try {
            JSONObject d = describeModal(activity);
            if (d == null) d = describeSelect(wc);
            if (d == null) d = describeDateTime();
            if (d == null) d = describeContextMenu();
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 回答当前对话框（UI 线程）。{@code body}：{@code action:"accept"|"dismiss"}，
     * {@code value} 文本 / 时间，{@code select:[索引...]}，{@code item:"菜单项 id 或文字"}。
     * 返回 null 表示成功；否则是给服务端的错误说明。
     */
    static @Nullable String answer(ChromeTabbedActivity activity, @Nullable WebContents wc,
            JSONObject body) {
        ThreadUtils.assertOnUiThread();
        String action = body.optString("action", "accept");
        boolean dismiss = "dismiss".equals(action) || "cancel".equals(action);

        ModalDialogManager mdm = activity.getModalDialogManager();
        PropertyModel model = mdm == null ? null : currentModal(mdm, new boolean[1]);
        if (model != null) return answerModal(mdm, model, body, dismiss);

        SelectPopup popup = wc == null ? null : SelectPopup.fromWebContents(wc);
        if (popup != null && popup.getShowingItemsForLemurX() != null) {
            return answerSelect(popup, body, dismiss);
        }

        DateTimeChooserAndroid chooser = DateTimeChooserAndroid.getActiveForLemurX();
        if (chooser != null) return answerDateTime(chooser, body, dismiss);

        ContextMenuCoordinator menu = ContextMenuCoordinator.getActiveForLemurX();
        if (menu != null) return answerContextMenu(menu, body, dismiss);

        return "No dialog is open.";
    }

    // ---------------------------------------------------------------- 模态对话框（JS alert/confirm/prompt 等）

    /**
     * 正在显示的模态对话框；没有在显示的就看排队中的（页面的 alert() 已经在等答复，但对话框被挂起
     * 没画出来——例如标签此刻被认为不可交互）。排队的也能回答。
     */
    private static @Nullable PropertyModel currentModal(ModalDialogManager mdm, boolean[] pending) {
        if (mdm.isShowing()) {
            PropertyModel m = mdm.getCurrentDialogForTest();
            if (m != null) return m;
        }
        for (int type : new int[] {ModalDialogType.TAB, ModalDialogType.APP}) {
            List<PropertyModel> queue = mdm.getPendingDialogsForTest(type);
            if (queue != null && !queue.isEmpty()) {
                pending[0] = true;
                return queue.get(0);
            }
        }
        return null;
    }

    private static @Nullable JSONObject describeModal(ChromeTabbedActivity activity) throws Exception {
        ModalDialogManager mdm = activity.getModalDialogManager();
        if (mdm == null) return null;
        boolean[] pending = new boolean[1];
        PropertyModel model = currentModal(mdm, pending);
        if (model == null) return null;
        JSONObject d = new JSONObject();
        d.put("kind", "modal");
        if (pending[0]) {
            d.put("pending", true);
            d.put("suspended", mdm.isSuspended(ModalDialogType.TAB));
        }
        d.put("title", str(get(model, ModalDialogProperties.TITLE)));
        StringBuilder message = new StringBuilder(str(get(model, ModalDialogProperties.MESSAGE_PARAGRAPH_1)));
        Object paragraphs = get(model, ModalDialogProperties.MESSAGE_PARAGRAPHS);
        if (paragraphs instanceof List) {
            for (Object p : (List<?>) paragraphs) {
                if (message.length() > 0) message.append('\n');
                message.append(str(p));
            }
        }
        d.put("message", message.toString());
        JSONArray buttons = new JSONArray();
        String positive = str(get(model, ModalDialogProperties.POSITIVE_BUTTON_TEXT));
        String negative = str(get(model, ModalDialogProperties.NEGATIVE_BUTTON_TEXT));
        if (!positive.isEmpty()) buttons.put(positive);
        if (!negative.isEmpty()) buttons.put(negative);
        d.put("buttons", buttons);
        View custom = (View) get(model, ModalDialogProperties.CUSTOM_VIEW);
        EditText input = findEditText(custom);
        d.put("hasInput", input != null && input.getVisibility() == View.VISIBLE);
        if (input != null && input.getVisibility() == View.VISIBLE) {
            d.put("value", input.getText() == null ? "" : input.getText().toString());
        }
        d.put("hint", "input {type:\"dialog\", action:\"accept\"|\"dismiss\", value?}");
        return d;
    }

    private static @Nullable String answerModal(ModalDialogManager mdm, PropertyModel model,
            JSONObject body, boolean dismiss) {
        if (body.has("value")) {
            View custom = (View) get(model, ModalDialogProperties.CUSTOM_VIEW);
            EditText input = findEditText(custom);
            if (input == null) return "This dialog has no text field.";
            input.setText(body.optString("value", ""));
        }
        ModalDialogProperties.Controller controller = get(model, ModalDialogProperties.CONTROLLER);
        if (controller == null) {
            mdm.dismissDialog(model, dismiss
                    ? DialogDismissalCause.NEGATIVE_BUTTON_CLICKED
                    : DialogDismissalCause.POSITIVE_BUTTON_CLICKED);
            return null;
        }
        // 和用户点按钮同一条路：控制器自己收尾（JS 对话框由此把结果交给页面）。
        controller.onClick(model, dismiss
                ? ModalDialogProperties.ButtonType.NEGATIVE
                : ModalDialogProperties.ButtonType.POSITIVE);
        return null;
    }

    // ---------------------------------------------------------------- <select>

    private static @Nullable JSONObject describeSelect(@Nullable WebContents wc) throws Exception {
        if (wc == null) return null;
        SelectPopup popup = SelectPopup.fromWebContents(wc);
        List<SelectPopupItem> items = popup == null ? null : popup.getShowingItemsForLemurX();
        if (items == null) return null;
        JSONObject d = new JSONObject();
        d.put("kind", "select");
        d.put("multiple", popup.isShowingMultipleForLemurX());
        JSONArray options = new JSONArray();
        for (int i = 0; i < items.size(); i++) {
            SelectPopupItem item = items.get(i);
            JSONObject o = new JSONObject();
            o.put("index", i);
            o.put("label", item.getLabel());
            if (item.getType() == PopupItemType.GROUP) o.put("group", true);
            if (item.getType() == PopupItemType.DISABLED) o.put("disabled", true);
            options.put(o);
        }
        d.put("options", options);
        JSONArray selected = new JSONArray();
        int[] sel = popup.getShowingSelectedForLemurX();
        if (sel != null) for (int i : sel) selected.put(i);
        d.put("selected", selected);
        d.put("hint", "input {type:\"dialog\", select:[index...]} or {type:\"dialog\", value:\"label\"}");
        return d;
    }

    private static @Nullable String answerSelect(SelectPopup popup, JSONObject body,
            boolean dismiss) {
        List<SelectPopupItem> items = popup.getShowingItemsForLemurX();
        if (items == null) return "No select popup is open.";
        if (dismiss) {
            popup.answerForLemurX(null);
            return null;
        }
        int[] indices;
        JSONArray sel = body.optJSONArray("select");
        if (sel != null) {
            indices = new int[sel.length()];
            for (int i = 0; i < sel.length(); i++) {
                indices[i] = sel.optInt(i, -1);
                if (indices[i] < 0 || indices[i] >= items.size()) {
                    return "Option index out of range.";
                }
                if (items.get(indices[i]).getType() != PopupItemType.ENABLED) {
                    return "That option cannot be selected.";
                }
            }
        } else if (body.has("select") && body.opt("select") instanceof Number) {
            indices = new int[] {((Number) body.opt("select")).intValue()};
            if (indices[0] < 0 || indices[0] >= items.size()) return "Option index out of range.";
        } else if (body.has("value")) {
            String want = body.optString("value", "").trim().toLowerCase(Locale.ROOT);
            int found = -1;
            for (int i = 0; i < items.size() && found < 0; i++) {
                if (items.get(i).getType() != PopupItemType.ENABLED) continue;
                if (items.get(i).getLabel().trim().toLowerCase(Locale.ROOT).equals(want)) found = i;
            }
            for (int i = 0; i < items.size() && found < 0; i++) {
                if (items.get(i).getType() != PopupItemType.ENABLED) continue;
                if (items.get(i).getLabel().toLowerCase(Locale.ROOT).contains(want)) found = i;
            }
            if (found < 0) return "No option matches that text.";
            indices = new int[] {found};
        } else {
            return "Give select:[index] or value:\"label\".";
        }
        if (!popup.isShowingMultipleForLemurX() && indices.length > 1) {
            indices = new int[] {indices[0]};
        }
        popup.answerForLemurX(indices);
        return null;
    }

    // ---------------------------------------------------------------- 日期 / 时间

    private static final Pattern TIME = Pattern.compile("^(\\d{1,2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,3}))?)?$");
    private static final Pattern DATE = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$");
    private static final Pattern DATE_TIME =
            Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})[T ](\\d{1,2}):(\\d{2})(?::(\\d{2}))?$");
    private static final Pattern MONTH = Pattern.compile("^(\\d{4})-(\\d{1,2})$");
    private static final Pattern WEEK = Pattern.compile("^(\\d{4})-W(\\d{1,2})$");

    private static @Nullable JSONObject describeDateTime() throws Exception {
        DateTimeChooserAndroid chooser = DateTimeChooserAndroid.getActiveForLemurX();
        if (chooser == null) return null;
        int type = chooser.getDialogTypeForLemurX();
        double[] range = chooser.getRangeForLemurX();
        JSONObject d = new JSONObject();
        d.put("kind", "datetime");
        d.put("inputType", typeName(type));
        d.put("value", format(type, range[0]));
        d.put("min", format(type, range[1]));
        d.put("max", format(type, range[2]));
        d.put("format", typeFormat(type));
        d.put("hint", "input {type:\"dialog\", value:\"" + typeFormat(type) + "\"} or action:\"dismiss\"");
        return d;
    }

    private static @Nullable String answerDateTime(DateTimeChooserAndroid chooser, JSONObject body,
            boolean dismiss) {
        if (dismiss) {
            chooser.cancelForLemurX();
            return null;
        }
        String value = body.optString("value", "").trim();
        if (value.isEmpty()) return "Give value:\"" + typeFormat(chooser.getDialogTypeForLemurX()) + "\".";
        Double parsed = parse(chooser.getDialogTypeForLemurX(), value);
        if (parsed == null) {
            return "Value must look like " + typeFormat(chooser.getDialogTypeForLemurX()) + ".";
        }
        chooser.replaceDateTimeForLemurX(parsed);
        return null;
    }

    private static String typeName(int type) {
        switch (type) {
            case TextInputType.TIME: return "time";
            case TextInputType.DATE: return "date";
            case TextInputType.DATE_TIME:
            case TextInputType.DATE_TIME_LOCAL: return "datetime-local";
            case TextInputType.MONTH: return "month";
            case TextInputType.WEEK: return "week";
            default: return "unknown";
        }
    }

    private static String typeFormat(int type) {
        switch (type) {
            case TextInputType.TIME: return "HH:MM";
            case TextInputType.DATE: return "YYYY-MM-DD";
            case TextInputType.DATE_TIME:
            case TextInputType.DATE_TIME_LOCAL: return "YYYY-MM-DDTHH:MM";
            case TextInputType.MONTH: return "YYYY-MM";
            case TextInputType.WEEK: return "YYYY-Www";
            default: return "";
        }
    }

    /** 对话框单位 → 文本（和 InputDialogContainer.setFieldDateTimeValue 的换算互逆）。 */
    private static String format(int type, double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "";
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        switch (type) {
            case TextInputType.TIME: {
                long ms = (long) v;
                return String.format(Locale.US, "%02d:%02d", TimeUnit.MILLISECONDS.toHours(ms) % 24,
                        TimeUnit.MILLISECONDS.toMinutes(ms) % 60);
            }
            case TextInputType.MONTH: {
                long months = (long) v;
                return String.format(Locale.US, "%04d-%02d", 1970 + Math.floorDiv(months, 12),
                        Math.floorMod(months, 12) + 1);
            }
            case TextInputType.WEEK: {
                if (Math.abs(v) > 8.64e15) return "";
                cal.setTimeInMillis((long) v);
                cal.setFirstDayOfWeek(Calendar.MONDAY);
                cal.setMinimalDaysInFirstWeek(4);
                return String.format(Locale.US, "%04d-W%02d", cal.getWeekYear(),
                        cal.get(Calendar.WEEK_OF_YEAR));
            }
            case TextInputType.DATE_TIME:
            case TextInputType.DATE_TIME_LOCAL: {
                if (Math.abs(v) > 8.64e15) return "";
                cal.setTimeInMillis((long) v);
                return String.format(Locale.US, "%04d-%02d-%02dT%02d:%02d", cal.get(Calendar.YEAR),
                        cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
                        cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
            }
            default: {
                if (Math.abs(v) > 8.64e15) return "";
                cal.setTimeInMillis((long) v);
                return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR),
                        cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
            }
        }
    }

    /** 文本 → 对话框单位；格式不对返回 null。 */
    private static @Nullable Double parse(int type, String text) {
        Matcher m;
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.clear();
        switch (type) {
            case TextInputType.TIME:
                m = TIME.matcher(text);
                if (!m.matches()) return null;
                int h = Integer.parseInt(m.group(1));
                int min = Integer.parseInt(m.group(2));
                int sec = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
                if (h > 23 || min > 59 || sec > 59) return null;
                return (double) (TimeUnit.HOURS.toMillis(h) + TimeUnit.MINUTES.toMillis(min)
                        + TimeUnit.SECONDS.toMillis(sec));
            case TextInputType.MONTH:
                m = MONTH.matcher(text);
                if (!m.matches()) return null;
                int mo = Integer.parseInt(m.group(2));
                if (mo < 1 || mo > 12) return null;
                return (double) ((Integer.parseInt(m.group(1)) - 1970) * 12 + (mo - 1));
            case TextInputType.WEEK:
                m = WEEK.matcher(text);
                if (!m.matches()) return null;
                cal.setFirstDayOfWeek(Calendar.MONDAY);
                cal.setMinimalDaysInFirstWeek(4);
                cal.setWeekDate(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Calendar.MONDAY);
                return (double) cal.getTimeInMillis();
            case TextInputType.DATE_TIME:
            case TextInputType.DATE_TIME_LOCAL:
                m = DATE_TIME.matcher(text);
                if (!m.matches()) return null;
                cal.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1,
                        Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                        Integer.parseInt(m.group(5)), m.group(6) == null ? 0 : Integer.parseInt(m.group(6)));
                return (double) cal.getTimeInMillis();
            default:
                m = DATE.matcher(text);
                if (!m.matches()) return null;
                cal.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1,
                        Integer.parseInt(m.group(3)));
                return (double) cal.getTimeInMillis();
        }
    }

    // ---------------------------------------------------------------- 长按菜单

    private static @Nullable JSONObject describeContextMenu() throws Exception {
        ContextMenuCoordinator menu = ContextMenuCoordinator.getActiveForLemurX();
        if (menu == null) return null;
        JSONObject d = new JSONObject();
        d.put("kind", "contextmenu");
        JSONArray items = new JSONArray();
        for (String[] item : menu.describeItemsForLemurX()) {
            JSONObject o = new JSONObject();
            o.put("id", Integer.parseInt(item[0]));
            o.put("label", item[1]);
            items.put(o);
        }
        d.put("items", items);
        d.put("hint", "input {type:\"dialog\", item:<id or label>} or action:\"dismiss\"");
        return d;
    }

    private static @Nullable String answerContextMenu(ContextMenuCoordinator menu, JSONObject body,
            boolean dismiss) {
        if (dismiss || !body.has("item")) {
            menu.dismiss();
            return null;
        }
        Object item = body.opt("item");
        int id = -1;
        if (item instanceof Number) {
            id = ((Number) item).intValue();
        } else {
            String want = String.valueOf(item).trim().toLowerCase(Locale.ROOT);
            for (String[] entry : menu.describeItemsForLemurX()) {
                if (entry[1].trim().toLowerCase(Locale.ROOT).equals(want)) {
                    id = Integer.parseInt(entry[0]);
                    break;
                }
            }
            if (id < 0) {
                for (String[] entry : menu.describeItemsForLemurX()) {
                    if (entry[1].toLowerCase(Locale.ROOT).contains(want)) {
                        id = Integer.parseInt(entry[0]);
                        break;
                    }
                }
            }
        }
        if (id < 0 || !menu.clickItemForLemurX(id)) return "No menu item matches.";
        return null;
    }

    // ---------------------------------------------------------------- 工具

    private static <T> @Nullable T get(PropertyModel model, PropertyModel.ReadableObjectPropertyKey<T> key) {
        try {
            return model.getAllSetProperties().contains(key) ? model.get(key) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(@Nullable Object o) {
        return o == null ? "" : o.toString();
    }

    private static @Nullable EditText findEditText(@Nullable View root) {
        if (root == null) return null;
        if (root instanceof EditText) return (EditText) root;
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) root;
        for (int i = 0; i < g.getChildCount(); i++) {
            EditText e = findEditText(g.getChildAt(i));
            if (e != null) return e;
        }
        return null;
    }
}
