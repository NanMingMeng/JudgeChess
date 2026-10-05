import com.yypm.assistant.ConfigProfile;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置快照单测。重点在**容错**：这份文本用户可能手改，一行坏了不能整份作废。
 */
public class TestConfigProfile {
    static int pass = 0, fail = 0;

    static void ck(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  ok   " + name + (detail.isEmpty() ? "" : "  " + detail)); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    public static void main(String[] a) {
        System.out.println("== ① 编解码往返 ==");
        Map<String, String> kv = new LinkedHashMap<String, String>();
        kv.put("ui_board_scale", "200");
        kv.put("ui_bg_alpha", "40");
        kv.put("ui_bg_img", "/data/x y/图.jpg");
        kv.put("ui_order_col", "btn,eval,move,reply,status,ana");
        String s = ConfigProfile.encode(kv);
        ck("含版本头", s.startsWith(ConfigProfile.MAGIC), s.split("\n")[0]);
        Map<String, String> back = ConfigProfile.decode(s);
        ck("往返一致", "200".equals(back.get("ui_board_scale"))
                        && "40".equals(back.get("ui_bg_alpha"))
                        && "/data/x y/图.jpg".equals(back.get("ui_bg_img"))
                        && "btn,eval,move,reply,status,ana".equals(back.get("ui_order_col")),
                "项数=" + back.size());
        ck("整型项转成 Integer", Integer.valueOf(200).equals(ConfigProfile.decodeInts(s).get("ui_board_scale")),
                "=" + ConfigProfile.decodeInts(s).get("ui_board_scale"));
        ck("字符串项单独取出", ConfigProfile.decodeStrs(s).size() == 2,
                "=" + ConfigProfile.decodeStrs(s).size());

        System.out.println();
        System.out.println("== ② 稳定性（同样的配置产出同样的文本）==");
        String s2 = ConfigProfile.encode(kv);
        ck("两次编码完全相同（按键排序）", s.equals(s2), "");
        Map<String, String> rev = new LinkedHashMap<String, String>();
        for (String k : new java.util.ArrayList<String>(kv.keySet())) rev.put(k, kv.get(k));
        ck("插入顺序不同也产出同样文本",
                ConfigProfile.encode(rev).equals(ConfigProfile.encode(kv)), "");

        System.out.println();
        System.out.println("== ③ 容错（用户手改过的文本）==");
        String messy = ConfigProfile.MAGIC + "\n"
                + "\n"
                + "# 这是注释\n"
                + "ui_board_scale=150\n"
                + "不认识的键=1\n"
                + "坏行没有等号\n"
                + "=值在前面\n"
                + "ui_bg_alpha=abc\n"          // 整型项写了非数字
                + "ui_ctrl_card=1\n";
        Map<String, String> m = ConfigProfile.decode(messy);
        ck("认识的两项都读到了", "150".equals(m.get("ui_board_scale")) && "1".equals(m.get("ui_ctrl_card")),
                "项数=" + m.size());
        ck("不认识的键被丢掉", !m.containsKey("不认识的键"), "");
        ck("坏行不致命（整份没作废）", m.size() >= 2, "项数=" + m.size());
        ck("非数字的整型项被跳过", !ConfigProfile.decodeInts(messy).containsKey("ui_bg_alpha"),
                "=" + ConfigProfile.decodeInts(messy).get("ui_bg_alpha"));
        ck("同一份里合法的整型项仍然拿到", Integer.valueOf(150).equals(
                        ConfigProfile.decodeInts(messy).get("ui_board_scale")),
                "=" + ConfigProfile.decodeInts(messy).get("ui_board_scale"));

        System.out.println();
        System.out.println("== ④ 异常输入不崩 ==");
        ck("null → 空", ConfigProfile.decode(null).isEmpty(), "");
        ck("空串 → 空", ConfigProfile.decode("").isEmpty(), "");
        ck("只有版本头 → 空", ConfigProfile.decode(ConfigProfile.MAGIC).isEmpty(), "");
        ck("null 编码 → 只有头", ConfigProfile.encode(null).trim().equals(ConfigProfile.MAGIC), "");
        ck("空 map → 只有头", ConfigProfile.encode(new LinkedHashMap<String, String>())
                .trim().equals(ConfigProfile.MAGIC), "");
        ck("带换行的脏值被丢弃",
                ConfigProfile.decode(ConfigProfile.encode(
                        java.util.Collections.singletonMap("ui_bg_img", "a\nb"))).isEmpty(), "");

        System.out.println();
        System.out.println("== ⑤ 边界：该存 / 不该存 ==");
        ck("dev_tier 不进快照", !ConfigProfile.isKnown("dev_tier"), "");
        ck("dev_desc 不进快照", !ConfigProfile.isKnown("dev_desc"), "");
        ck("dev_lowram 不进快照", !ConfigProfile.isKnown("dev_lowram"), "");
        ck("started 不进快照（运行态）", !ConfigProfile.isKnown("started"), "");
        ck("px/py/bx/by 不进快照（屏幕相关）", !ConfigProfile.isKnown("px")
                        && !ConfigProfile.isKnown("py") && !ConfigProfile.isKnown("bx")
                        && !ConfigProfile.isKnown("by"), "");
        ck("ui_board_scale 进快照", ConfigProfile.isKnown("ui_board_scale"), "");
        ck("speed 进快照", ConfigProfile.isKnown("speed"), "");
        ck("null 键不算已知", !ConfigProfile.isKnown(null), "");
        Map<String, String> withDev = new LinkedHashMap<String, String>();
        withDev.put("dev_tier", "2");
        withDev.put("ui_board_scale", "150");
        String enc = ConfigProfile.encode(withDev);
        ck("编码时 dev_* 被过滤掉", !enc.contains("dev_tier") && enc.contains("ui_board_scale"), "");

        System.out.println();
        System.out.println("== ⑥ 有效性 / 计数 ==");
        ck("有效快照", ConfigProfile.isValid(s), "");
        ck("无效快照（全是垃圾行）", !ConfigProfile.isValid("没用的东西\n还是没用的"), "");
        ck("null 无效", !ConfigProfile.isValid(null), "");
        ck("计数正确", ConfigProfile.count(s) == 4, "=" + ConfigProfile.count(s));
        ck("键表非空且无重复", ConfigProfile.allKeys().size()
                        == ConfigProfile.KEYS_INT.length + ConfigProfile.KEYS_STR.length,
                "总数=" + ConfigProfile.allKeys().size());

        System.out.println();
        System.out.println("== ⑦ 全键往返（一个不漏）==");
        Map<String, String> all = new LinkedHashMap<String, String>();
        for (String k : ConfigProfile.KEYS_INT) all.put(k, "7");
        for (String k : ConfigProfile.KEYS_STR) all.put(k, "v");
        String ae = ConfigProfile.encode(all);
        ck("全部整型项都能还原", ConfigProfile.decodeInts(ae).size() == ConfigProfile.KEYS_INT.length,
                ConfigProfile.decodeInts(ae).size() + "/" + ConfigProfile.KEYS_INT.length);
        ck("全部字符串项都能还原", ConfigProfile.decodeStrs(ae).size() == ConfigProfile.KEYS_STR.length,
                ConfigProfile.decodeStrs(ae).size() + "/" + ConfigProfile.KEYS_STR.length);

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
