// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.content.Context;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本机人物档案。通用信息可以进对话上下文，敏感信息加密后只在档案库里给用户看。
 * 口令、证件号、卡号不进上下文，也不进日志。
 */
@NullMarked
final class LemurXArchive {
    static final String GENERAL = "general";
    static final String SENSITIVE = "sensitive";

    static final String CONTACT = "contact";
    static final String SOCIAL = "social";
    static final String WORK = "work";
    static final String PREFERENCE = "preference";

    /** 对话里认出来、还没经用户点头的一条。敏感条目的 value 只在保存时加密，卡片上不展开。 */
    static final class Proposal {
        final String section;
        final String kind;
        final String label;
        final String value;
        final boolean redact;

        Proposal(String section, String kind, String label, String value, boolean redact) {
            this.section = SENSITIVE.equals(section) ? SENSITIVE : GENERAL;
            this.kind = kind == null || kind.isEmpty() ? CONTACT : kind;
            this.label = label == null ? "" : label.trim();
            this.value = value == null ? "" : value.trim();
            this.redact = redact || SENSITIVE.equals(this.section);
        }
    }

    /** 已经落在本机的一条。敏感条目的 value 是解开后的明文，调用方不要写进上下文。 */
    static final class Fact {
        final String id;
        final String section;
        final String kind;
        final String label;
        final String value;
        final String source;
        final long updatedAt;

        Fact(String id, String section, String kind, String label, String value, String source,
                long updatedAt) {
            this.id = id;
            this.section = section;
            this.kind = kind;
            this.label = label;
            this.value = value;
            this.source = source;
            this.updatedAt = updatedAt;
        }
    }

    private static final int MAX_FACTS = 200;
    private static final Pattern MARK = Pattern.compile(
            "档案\\|(general|sensitive)\\|(contact|social|work|preference)\\|([^|\\n]{1,40})\\|([^\\n]{1,120})");
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<![0-9])1[3-9][0-9]{9}(?![0-9])");
    private static final Pattern ID_CARD = Pattern.compile("(?<![0-9])[1-9][0-9]{16}[0-9Xx](?![0-9])");
    private static final Pattern CARD = Pattern.compile("(?<![0-9])[0-9]{16,19}(?![0-9])");

    private LemurXArchive() {}

    static List<Fact> facts(Context context, String section) {
        List<Fact> out = new ArrayList<>();
        for (Fact fact : read(context)) {
            if (section.equals(fact.section)) out.add(fact);
        }
        return out;
    }

    static boolean already(Context context, Proposal proposal) {
        if (proposal.label.isEmpty() || proposal.value.isEmpty()) return true;
        for (Fact fact : read(context)) {
            if (!proposal.label.equals(fact.label)) continue;
            if (proposal.value.equalsIgnoreCase(fact.value)) return true;
        }
        return false;
    }

    static void save(Context context, Proposal proposal, String source) {
        if (proposal.label.isEmpty() || proposal.value.isEmpty()) return;
        if (already(context, proposal)) return;
        List<Fact> all = new ArrayList<>(read(context));
        String stored = proposal.value;
        if (SENSITIVE.equals(proposal.section)) {
            String sealed = LemurXVault.sealText(proposal.value);
            if (sealed == null) return;
            stored = "sealed:" + sealed;
        }
        all.add(0, new Fact(
                UUID.randomUUID().toString(),
                proposal.section,
                proposal.kind,
                proposal.label,
                stored,
                source == null ? "chat" : source,
                System.currentTimeMillis()));
        while (all.size() > MAX_FACTS) all.remove(all.size() - 1);
        write(context, all);
    }

    /** 改一条已经记下的事实。敏感内容仍只以密文落盘。找不到时改成新增。 */
    static void update(Context context, String id, Proposal proposal, String source) {
        if (id == null || id.isEmpty() || proposal.label.isEmpty() || proposal.value.isEmpty()) return;
        List<Fact> all = new ArrayList<>(read(context));
        for (int i = 0; i < all.size(); i++) {
            Fact fact = all.get(i);
            if (!id.equals(fact.id)) continue;
            all.set(i, new Fact(
                    fact.id,
                    proposal.section,
                    proposal.kind,
                    proposal.label,
                    proposal.value,
                    source == null || source.isEmpty() ? fact.source : source,
                    System.currentTimeMillis()));
            write(context, all);
            return;
        }
        save(context, proposal, source);
    }

    static void delete(Context context, String id) {
        if (id == null || id.isEmpty()) return;
        List<Fact> all = new ArrayList<>();
        for (Fact fact : read(context)) {
            if (!id.equals(fact.id)) all.add(fact);
        }
        write(context, all);
    }

    /**
     * 给这一轮对话的上下文。只有通用信息和用户允许参考的文件索引。
     * 敏感明文不在这里。
     */
    static String contextValue(Context context) {
        StringBuilder out = new StringBuilder();
        out.append("Person archive stored on this phone. Use it when it helps. Do not invent facts. ");
        out.append("When the user states a durable fact about themselves, end the reply with one or more lines ");
        out.append("the phone will hide and ask them to confirm: ");
        out.append("档案|general|contact|标签|内容  ");
        out.append("档案|general|social|标签|内容  ");
        out.append("档案|general|work|标签|内容  ");
        out.append("档案|general|preference|标签|内容  ");
        out.append("档案|sensitive|contact|标签|内容. ");
        out.append("Passwords, card numbers and national ID numbers must use sensitive lines ");
        out.append("and must not appear in the visible reply.\n");
        List<Fact> general = facts(context, GENERAL);
        if (!general.isEmpty()) {
            out.append("Known facts:\n");
            for (Fact fact : general) {
                out.append("- ").append(fact.kind).append(" · ").append(fact.label)
                        .append("：").append(fact.value).append('\n');
                if (out.length() > 3500) break;
            }
        }
        String files = LemurXFiles.agentIndex(context);
        if (!files.isEmpty()) {
            out.append("Files indexed on this phone. Reference only what is listed:\n");
            out.append(files);
        }
        int sensitive = facts(context, SENSITIVE).size();
        if (sensitive > 0) {
            out.append("\n")
                    .append(sensitive)
                    .append(" sensitive facts stay on this phone and are not included here.");
        }
        String text = out.toString().trim();
        return text.length() > 6000 ? text.substring(0, 6000) : text;
    }

    /** 从用户刚说的话或文件正文里认出高置信的事实。不写入档案。 */
    static List<Proposal> harvest(String text) {
        List<Proposal> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String flat = text.replace('\n', ' ');
        takePhrase(out, flat, "我叫", GENERAL, CONTACT, "姓名", false);
        takePhrase(out, flat, "我的名字是", GENERAL, CONTACT, "姓名", false);
        takePhrase(out, flat, "我住在", GENERAL, SOCIAL, "居住地", false);
        takeWork(out, flat);
        takePhrase(out, flat, "我的职业是", GENERAL, WORK, "职业", false);
        takePhrase(out, flat, "我的公司是", GENERAL, WORK, "公司", false);
        Matcher email = EMAIL.matcher(flat);
        if (email.find()) add(out, new Proposal(GENERAL, CONTACT, "邮箱", email.group(), false));
        Matcher phone = PHONE.matcher(flat);
        if (phone.find()) add(out, new Proposal(GENERAL, CONTACT, "电话", phone.group(), false));
        Matcher wechat = Pattern.compile("微信(?:号)?[:：是]?\\s*([A-Za-z][-_A-Za-z0-9]{4,})").matcher(flat);
        if (wechat.find()) add(out, new Proposal(GENERAL, CONTACT, "微信", wechat.group(1), false));
        Matcher id = ID_CARD.matcher(flat);
        if (id.find()) add(out, new Proposal(SENSITIVE, CONTACT, "证件号", id.group(), true));
        Matcher card = CARD.matcher(flat);
        if (card.find()) {
            String digits = card.group();
            if (!ID_CARD.matcher(digits).matches() && !PHONE.matcher(digits).matches()) {
                add(out, new Proposal(SENSITIVE, CONTACT, "卡号", digits, true));
            }
        }
        Matcher secret = Pattern.compile("密码是\\s*([^\\s，。,；;]{4,64})").matcher(flat);
        if (secret.find()) add(out, new Proposal(SENSITIVE, CONTACT, "密码", secret.group(1), true));
        return out;
    }

    /** 拿掉模型附上的档案行，交给对话去问用户。不完整的一行由调用方先搁住。 */
    static String extractMarkers(String text, List<Proposal> into) {
        if (text == null || text.isEmpty()) return "";
        Matcher matcher = MARK.matcher(text);
        StringBuffer cleaned = new StringBuffer();
        while (matcher.find()) {
            boolean sensitive = SENSITIVE.equals(matcher.group(1));
            add(into, new Proposal(
                    matcher.group(1),
                    matcher.group(2),
                    matcher.group(3),
                    matcher.group(4).trim(),
                    sensitive));
            matcher.appendReplacement(cleaned, "");
        }
        matcher.appendTail(cleaned);
        return cleaned.toString().replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n");
    }

    /** 对话和文件摘录送出本机之前，把证件号、卡号和口令换成占位。通用信息保留。 */
    static String maskSecrets(String text) {
        if (text == null || text.isEmpty()) return "";
        String out = ID_CARD.matcher(text).replaceAll("••••");
        out = CARD.matcher(out).replaceAll("••••");
        out = Pattern.compile("密码是\\s*[^\\s，。,；;]{4,64}").matcher(out).replaceAll("密码是 ••••");
        return out;
    }

    static String maskValues(String text, List<Proposal> proposals) {
        String out = maskSecrets(text);
        if (proposals == null) return out;
        for (Proposal proposal : proposals) {
            if (!proposal.redact || proposal.value.length() < 4) continue;
            out = out.replace(proposal.value, "••••");
        }
        return out;
    }

    static Proposal inSection(Proposal proposal, String section) {
        return new Proposal(section, proposal.kind, proposal.label, proposal.value,
                SENSITIVE.equals(section) || proposal.redact);
    }

    private static void takeWork(List<Proposal> out, String text) {
        Matcher at = Pattern.compile("我在(.{2,24}?)工作").matcher(text);
        if (at.find()) add(out, new Proposal(GENERAL, WORK, "公司", clean(at.group(1)), false));
    }

    private static void takePhrase(
            List<Proposal> out, String text, String prefix, String section, String kind, String label,
            boolean redact) {
        int at = text.indexOf(prefix);
        if (at < 0) return;
        String rest = text.substring(at + prefix.length()).trim();
        if (rest.isEmpty()) return;
        add(out, new Proposal(section, kind, label, clean(rest), redact));
    }

    private static String clean(String raw) {
        String value = raw.trim();
        int cut = value.length();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ("，。,.；;！!？?\n".indexOf(c) >= 0) {
                cut = i;
                break;
            }
        }
        if (cut > 40) cut = 40;
        return value.substring(0, cut).trim();
    }

    private static void add(List<Proposal> out, Proposal proposal) {
        if (proposal.label.isEmpty() || proposal.value.length() < 2) return;
        for (Proposal have : out) {
            if (have.label.equals(proposal.label) && have.value.equalsIgnoreCase(proposal.value)) return;
        }
        out.add(proposal);
    }

    private static List<Fact> read(Context context) {
        List<Fact> out = new ArrayList<>();
        File file = store(context);
        if (!file.isFile()) return out;
        byte[] raw;
        try (FileInputStream in = new FileInputStream(file)) {
            raw = in.readAllBytes();
        } catch (Exception e) {
            return out;
        }
        try {
            JSONArray array = new JSONArray(new String(raw, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.optJSONObject(i);
                if (row == null) continue;
                String label = row.optString("label", "");
                String stored = row.optString("value", "");
                if (label.isEmpty() || stored.isEmpty()) continue;
                String section = row.optString("section", GENERAL);
                String value = stored;
                if (stored.startsWith("sealed:")) {
                    String opened = LemurXVault.openText(stored.substring(7));
                    if (opened == null) continue;
                    value = opened;
                }
                out.add(new Fact(
                        row.optString("id", UUID.randomUUID().toString()),
                        SENSITIVE.equals(section) ? SENSITIVE : GENERAL,
                        row.optString("kind", CONTACT).toLowerCase(Locale.ROOT),
                        label,
                        value,
                        row.optString("source", ""),
                        row.optLong("updatedAt", 0L)));
            }
        } catch (JSONException e) {
            return out;
        }
        return out;
    }

    private static void write(Context context, List<Fact> facts) {
        JSONArray array = new JSONArray();
        for (Fact fact : facts) {
            JSONObject row = new JSONObject();
            try {
                String value = fact.value;
                if (SENSITIVE.equals(fact.section) && !value.startsWith("sealed:")) {
                    String sealed = LemurXVault.sealText(value);
                    if (sealed == null) continue;
                    value = "sealed:" + sealed;
                }
                row.put("id", fact.id);
                row.put("section", fact.section);
                row.put("kind", fact.kind);
                row.put("label", fact.label);
                row.put("value", value);
                row.put("source", fact.source);
                row.put("updatedAt", fact.updatedAt);
                array.put(row);
            } catch (JSONException e) {
                // 键是常量
            }
        }
        File file = store(context);
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(array.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 档案写失败时对话里的确认还在
        }
    }

    private static File store(Context context) {
        return LemurXAccount.localFile(context, "aero-archive", "facts.json");
    }
}
