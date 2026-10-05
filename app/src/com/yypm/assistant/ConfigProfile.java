package com.yypm.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 配置快照（v4.28）——「保存配置 / 恢复配置」的核心。
 *
 * <p>为什么需要它：设置项是**改一项立刻写盘**的，本身就能持久化。但用户真正想要的
 * 是「我现在这整套调好了，存下来，以后随时能回到这套」。这跟「改一项存一项」是两件事：
 * <ul>
 *   <li>调了十项，其中两项试出来不好 —— 没有快照就只能凭记忆一项项改回去；</li>
 *   <li>想拿这套配置去另一台设备 —— 有快照才能整份搬。</li>
 * </ul>
 *
 * <p>格式刻意做得极简（每行 {@code key=value}），因为要满足三个要求：
 * 可读（用户看得懂）、可改（手写也行）、够稳（不认识的行直接跳过，不炸）。
 *
 * <p>这个类是纯逻辑，不碰 Android API，所以能被单元测试完整覆盖。
 */
public class ConfigProfile {

    /** 版本头。以后格式变了，靠它判断并做兼容。 */
    public static final String MAGIC = "YYPM-CONFIG-1";

    /** 整型配置项（这些键在 SharedPreferences 里是 int）。 */
    public static final String[] KEYS_INT = {
            // 棋盘
            "ui_board_side", "ui_board_scale", "ui_board_dx", "ui_board_dy",
            // 布局 / 外观
            "ui_ctrl_card", "ui_bg_on", "ui_bg_alpha", "ui_intro_anim", "ui_btn_style", "ui_tab",
            // v4.52 人物与文字方案与文字优化
            "ui_bg_style", "ui_txt_shadow", "ui_txt_hier", "ui_eval3", "ui_txt_mono", "ui_txt_compact",
            // 构件显隐
            "ui_show_link", "ui_show_one", "ui_show_light",
            "ui_show_close", "ui_show_grip", "ui_show_collapse",
            "ui_show_board", "ui_show_eval", "ui_show_move",
            "ui_show_reply", "ui_show_status",
            // 构件归列
            "ui_col_link", "ui_col_one", "ui_col_light",
            // 对局
            "ui_side", "speed", "ana", "fgmode", "rematch",
    };

    /** 字符串配置项。 */
    public static final String[] KEYS_STR = {
            "ui_bg_img",
            "ui_order_col", "ui_order_btn", "ui_order_right",
    };

    /**
     * 明确**不该**进快照的键（写在这里是为了让下一个人一眼看清边界）：
     * <ul>
     *   <li>{@code dev_*} —— 是硬件探测结果，换设备就该重测，照着搬反而错；</li>
     *   <li>{@code started} —— 是「点过开始游戏没有」的运行态，不属于用户偏好；</li>
     *   <li>{@code px/py/bx/by} —— 悬浮窗位置，跟着屏幕走，不搬。</li>
     * </ul>
     */
    public static boolean isKnown(String key) {
        if (key == null) return false;
        for (String k : KEYS_INT) if (k.equals(key)) return true;
        for (String k : KEYS_STR) if (k.equals(key)) return true;
        return false;
    }

    /** 全部键（排序后，便于测试和展示）。 */
    public static List<String> allKeys() {
        List<String> l = new ArrayList<String>();
        for (String k : KEYS_INT) l.add(k);
        for (String k : KEYS_STR) l.add(k);
        java.util.Collections.sort(l);
        return l;
    }

    /**
     * 编码成文本。键按字典序排列 —— 同样的配置永远产出同样的文本，
     * 这样「有没有变过」可以直接比字符串。
     *
     * @param kv key → value（value 为 null 的项会被跳过）
     */
    public static String encode(Map<String, String> kv) {
        StringBuilder sb = new StringBuilder();
        sb.append(MAGIC).append('\n');
        if (kv != null) {
            for (String k : new TreeMap<String, String>(kv).keySet()) {
                if (!isKnown(k)) continue;
                String v = kv.get(k);
                if (v == null) continue;
                // 值里若混进换行会把格式撑坏，直接丢掉这种脏值（宁可少存一项）
                if (v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) continue;
                sb.append(k).append('=').append(v).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 解码。规则：第一行是版本头（缺失也容忍）；其余按**第一个等号**切分。
     *
     * <p>容错是刻意的 —— 这份文本用户可能手改，也可能从别处粘贴过来。
     * 一行不合法就跳过一个，绝不因此整份作废。
     *
     * @return key → value（只含已知键）
     */
    public static Map<String, String> decode(String text) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (text == null) return out;
        String[] lines = text.split("\n");
        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.trim();
            if (line.length() == 0) continue;
            if (line.startsWith("#")) continue;                 // 注释
            if (line.equals(MAGIC)) continue;                   // 版本头
            int eq = line.indexOf('=');
            if (eq <= 0) continue;                              // 没有 = 或 = 在开头
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if (!isKnown(k)) continue;                          // 不认识的键跳过
            out.put(k, v);
        }
        return out;
    }

    /** 解码 + 类型转换：把整型项转成 Integer，解析失败的跳过。 */
    public static Map<String, Integer> decodeInts(String text) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        Map<String, String> m = decode(text);
        for (String k : KEYS_INT) {
            String v = m.get(k);
            if (v == null) continue;
            try { out.put(k, Integer.valueOf(Integer.parseInt(v))); }
            catch (NumberFormatException ignored) {}            // 脏值跳过
        }
        return out;
    }

    /** 解码 + 只取字符串项。 */
    public static Map<String, String> decodeStrs(String text) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        Map<String, String> m = decode(text);
        for (String k : KEYS_STR) {
            String v = m.get(k);
            if (v != null) out.put(k, v);
        }
        return out;
    }

    /** 这份快照是否像个有效快照（至少含一个已知键）。 */
    public static boolean isValid(String text) {
        return !decode(text).isEmpty();
    }

    /** 快照里有多少项（给界面显示「已保存 33 项」用）。 */
    public static int count(String text) {
        return decode(text).size();
    }
}
