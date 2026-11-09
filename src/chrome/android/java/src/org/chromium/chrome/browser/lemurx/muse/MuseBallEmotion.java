// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.content.Context;

import org.chromium.build.annotations.NullMarked;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * grok-ball 的 32 种表情契约（MIT，https://github.com/tycoding/grok-ball）。
 *
 * <p>ID 分段：00–09 生命周期，10–29 情绪反应，30–49 代理工作状态。视觉参数是原生近似，
 * 给 Canvas 表情球用：开合、注视、环带、zzz、进场甩带 / 撒花。
 */
@NullMarked
final class MuseBallEmotion {
    static final String SLEEP = "00";
    static final String IDLE = "02";
    static final String THINKING = "30";
    static final String RECEIVING = "31";
    static final String BUSY = "32";
    static final String DONE = "33";
    static final String ERROR = "34";
    static final String WAITING = "35";
    static final String REPLYING = "39";
    static final String SEARCHING = "40";

    static final MuseBallEmotion[] ALL = {
            life("00", "睡眠", "Sleeping")
                    .open(0.08f)
                    .look(0f, 0.35f)
                    .noGaze()
                    .zzz()
                    .color(0xFFE8E4DC)
                    .breathe(2.9f),
            life("01", "唤醒", "Waking").open(0.7f).look(0f, -0.15f).breathe(1.8f),
            life("02", "待机放空", "Idle").open(1f).amp(0.72f, 0.18f, 4.8f, 4.1f).antics(),
            life("03", "好奇", "Curious").open(1.12f).look(0f, -0.2f).amp(0.28f, 0.1f, 2.8f, 2.8f).tilt(4f),
            life("04", "发呆", "Spacing Out")
                    .open(0.62f)
                    .look(-0.25f, 0.2f)
                    .uneven(1f, 0.92f, 0.12f, -0.08f)
                    .tilt(-3f)
                    .antics(),
            life("05", "加载苏醒", "Booting").open(1f).altBlink().pulse(),
            life("06", "休眠", "Dormant")
                    .open(0.4f)
                    .look(0f, 0.28f)
                    .noGaze()
                    .color(0xFFEBE8E1)
                    .breathe(3.4f),
            life("07", "抖动唤醒", "Shake Awake").open(0.85f).shake().breathe(1.2f),
            feel("10", "开心", "Happy")
                    .open(0.48f)
                    .look(0f, 0.12f)
                    .amp(0.1f, 0.35f, 3f, 1.4f)
                    .color(0xFFF6EFE4)
                    .antics()
                    .breathe(1.4f),
            feel("11", "疑惑", "Puzzled")
                    .open(1f)
                    .uneven(1.12f, 0.88f, -0.18f, 0.14f)
                    .tilt(-8f)
                    .amp(0.12f, 0.08f, 3.2f, 3.2f),
            feel("12", "失落", "Down")
                    .open(0.55f)
                    .look(0f, 0.42f)
                    .color(0xFFEDEAE3)
                    .tilt(-4f)
                    .breathe(2.8f),
            feel("13", "惊讶", "Surprised").open(1.28f).look(0f, -0.22f).breathe(2.6f),
            feel("14", "害羞", "Shy")
                    .open(0.82f)
                    .look(0.55f, 0.28f)
                    .color(0xFFF4D3D0)
                    .tilt(6f)
                    .amp(0.18f, 0.08f, 2.6f, 2.6f),
            feel("15", "疲惫", "Tired").open(0.55f).look(0f, 0.32f).color(0xFFEFECE5).tilt(-3f),
            feel("16", "专注", "Focused").open(0.92f).converge().breathe(3.2f),
            feel("17", "慌张", "Panicked").open(1.2f).jitter().amp(0.55f, 0.4f, 0.9f, 0.7f),
            feel("18", "无奈", "Resigned")
                    .open(0.9f)
                    .look(0.48f, -0.55f)
                    .tilt(10f)
                    .amp(0.08f, 0.12f, 3f, 3f),
            feel("19", "满意", "Satisfied").open(0.7f).amp(0.05f, 0.42f, 1.05f, 1.05f).antics(),
            feel("20", "困惑", "Confused")
                    .open(1f)
                    .uneven(1.16f, 0.8f, -0.16f, 0.2f)
                    .tilt(-5f),
            feel("21", "生气", "Angry").open(1.15f).look(0f, 0.08f).color(0xFFE4574A).brow(1f).jitter(),
            agent("30", "思考中", "Thinking")
                    .open(0.88f)
                    .look(0f, -0.72f)
                    .amp(0.55f, 0.08f, 2.6f, 2.6f)
                    .orbit()
                    .tilt(-3f),
            agent("31", "接收任务", "Receiving").open(1.08f).look(0f, -0.12f),
            agent("32", "处理中忙碌", "Busy").open(1f).amp(0.45f, 0.28f, 1.2f, 0.9f).breathe(1.05f),
            agent("33", "任务完成", "Done").open(0.42f).look(0f, 0.16f).ribbons().confetti(),
            agent("34", "出错", "Error")
                    .open(1.18f)
                    .uneven(1f, 1f, -0.12f, 0.16f)
                    .color(0xFFE25B5B)
                    .brow(1f)
                    .tilt(-6f),
            agent("35", "等待输入", "Listening").open(0.95f).amp(0.08f, 0.48f, 2.2f, 2.2f),
            agent("36", "联网加载", "Loading").open(1f).altBlink().amp(0.18f, 0.05f, 2.4f, 2.4f),
            agent("37", "复述回忆", "Recalling").open(0.9f).look(0.18f, -0.7f).amp(0.08f, 0.16f, 4f, 4f),
            agent("38", "拒绝/受限", "Refusing").open(0.6f).look(0f, 0.22f).shake().color(0xFFEFE8E4),
            agent("39", "输出回复", "Replying").open(1f).look(0f, -0.12f).pulse().breathe(0.68f),
            agent("40", "检索资料", "Searching").open(1.05f).scan().orbit().amp(0.82f, 0.22f, 0.7f, 0.95f),
            agent("41", "停止终止", "Powering Off").open(0.35f).look(0f, 0.22f).noGaze().color(0xFFEBE8E2),
    };

    private static final Map<String, MuseBallEmotion> BY_ID = new HashMap<>();

    static {
        for (MuseBallEmotion e : ALL) BY_ID.put(e.id, e);
    }

    final String id;
    final String nameZh;
    final String nameEn;
    final String group;
    float openness = 1f;
    float restX;
    float restY;
    float ampX;
    float ampY;
    float periodX = 4f;
    float periodY = 4.1f;
    float leftScale = 1f;
    float rightScale = 1f;
    float leftY;
    float rightY;
    float tilt;
    float brow;
    int color;
    boolean gaze = true;
    boolean orbit;
    boolean zzz;
    boolean ribbons;
    boolean confetti;
    boolean jitter;
    boolean pulse;
    boolean scan;
    boolean altBlink;
    boolean shake;
    boolean antics;
    boolean converge;
    float breathe = 2.35f;

    private MuseBallEmotion(String id, String nameZh, String nameEn, String group) {
        this.id = id;
        this.nameZh = nameZh;
        this.nameEn = nameEn;
        this.group = group;
    }

    static MuseBallEmotion of(String id) {
        MuseBallEmotion found = BY_ID.get(id);
        return found != null ? found : ALL[2];
    }

    String label(Context context) {
        return zh(context) ? nameZh : nameEn;
    }

    static String groupLabel(Context context, String group) {
        boolean chinese = zh(context);
        switch (group) {
            case "life":
                return chinese ? "生命周期" : "Lifecycle";
            case "emotion":
                return chinese ? "情绪反应" : "Emotions";
            default:
                return chinese ? "代理工作状态" : "Agent";
        }
    }

    static boolean zh(Context context) {
        return Locale.getDefault().getLanguage().startsWith("zh");
    }

    private static MuseBallEmotion life(String id, String zh, String en) {
        return new MuseBallEmotion(id, zh, en, "life");
    }

    private static MuseBallEmotion feel(String id, String zh, String en) {
        return new MuseBallEmotion(id, zh, en, "emotion");
    }

    private static MuseBallEmotion agent(String id, String zh, String en) {
        return new MuseBallEmotion(id, zh, en, "agent");
    }

    private MuseBallEmotion open(float v) {
        openness = v;
        return this;
    }

    private MuseBallEmotion look(float x, float y) {
        restX = x;
        restY = y;
        return this;
    }

    private MuseBallEmotion amp(float x, float y, float px, float py) {
        ampX = x;
        ampY = y;
        periodX = px;
        periodY = py;
        return this;
    }

    private MuseBallEmotion uneven(float ls, float rs, float ly, float ry) {
        leftScale = ls;
        rightScale = rs;
        leftY = ly;
        rightY = ry;
        return this;
    }

    private MuseBallEmotion tilt(float deg) {
        tilt = deg;
        return this;
    }

    private MuseBallEmotion brow(float v) {
        brow = v;
        return this;
    }

    private MuseBallEmotion color(int c) {
        color = c;
        return this;
    }

    private MuseBallEmotion breathe(float seconds) {
        breathe = seconds;
        return this;
    }

    private MuseBallEmotion noGaze() {
        gaze = false;
        return this;
    }

    private MuseBallEmotion orbit() {
        orbit = true;
        return this;
    }

    private MuseBallEmotion zzz() {
        zzz = true;
        return this;
    }

    private MuseBallEmotion ribbons() {
        ribbons = true;
        return this;
    }

    private MuseBallEmotion confetti() {
        confetti = true;
        return this;
    }

    private MuseBallEmotion jitter() {
        jitter = true;
        return this;
    }

    private MuseBallEmotion pulse() {
        pulse = true;
        return this;
    }

    private MuseBallEmotion scan() {
        scan = true;
        return this;
    }

    private MuseBallEmotion altBlink() {
        altBlink = true;
        return this;
    }

    private MuseBallEmotion shake() {
        shake = true;
        return this;
    }

    private MuseBallEmotion antics() {
        antics = true;
        return this;
    }

    private MuseBallEmotion converge() {
        converge = true;
        return this;
    }
}
