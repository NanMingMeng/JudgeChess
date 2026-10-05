import com.yypm.assistant.DeviceProfile;

/**
 * 设备自适应单测（纯逻辑，不依赖 Android）。
 *
 * 覆盖四件事：
 *   ① 引擎线程：任何核数/内存组合都不会算出 0 或 >4，且会给系统留核
 *   ② 轮询间隔：识别快的机器保持原样，识别慢的机器自己放宽，且不越界
 *   ③ 帧率：低内存一定更宽松
 *   ④ 分档：边界值、异常值都不崩
 */
public class TestDeviceProfile {
    static int pass = 0, fail = 0;

    static void ck(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  ok   " + name + (detail.isEmpty() ? "" : "  " + detail)); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    public static void main(String[] a) {
        System.out.println("== ① 引擎线程 ==");
        // 本机（8 核，非低内存）应得到 4（上限），而不是 7
        ck("8核普通 → 4（封顶）", DeviceProfile.engineThreads(8, false) == 4,
                "=" + DeviceProfile.engineThreads(8, false));
        ck("8核低内存 → 4（仍封顶）", DeviceProfile.engineThreads(8, true) == 4,
                "=" + DeviceProfile.engineThreads(8, true));
        // ★ 关键：4 核机只能给 3（留 1 个核给系统）
        ck("4核普通 → 3（留给系统）", DeviceProfile.engineThreads(4, false) == 3,
                "=" + DeviceProfile.engineThreads(4, false));
        ck("4核低内存 → 2（留两个）", DeviceProfile.engineThreads(4, true) == 2,
                "=" + DeviceProfile.engineThreads(4, true));
        ck("2核普通 → 1", DeviceProfile.engineThreads(2, false) == 1,
                "=" + DeviceProfile.engineThreads(2, false));
        ck("1核 → 1（不会算出 0）", DeviceProfile.engineThreads(1, false) == 1,
                "=" + DeviceProfile.engineThreads(1, false));
        ck("0核/负数 → 1（不崩）", DeviceProfile.engineThreads(0, false) == 1
                        && DeviceProfile.engineThreads(-5, true) == 1,
                "0→" + DeviceProfile.engineThreads(0, false) + " -5→" + DeviceProfile.engineThreads(-5, true));
        // 任何组合都必须在 [1,4]
        boolean rangeOk = true;
        for (int c = 1; c <= 16; c++)
            for (int lr = 0; lr <= 1; lr++) {
                int t = DeviceProfile.engineThreads(c, lr == 1);
                if (t < 1 || t > 4) rangeOk = false;
                if (c >= 2 && t >= c) rangeOk = false;   // 不许把核吃光
            }
        ck("1~16 核 × {普通,低内存} 全部落在 [1,4] 且不超核数", rangeOk, "");

        System.out.println();
        System.out.println("== ② 置换表 ==");
        ck("低内存更小", DeviceProfile.engineHashMb(true) < DeviceProfile.engineHashMb(false),
                DeviceProfile.engineHashMb(true) + " < " + DeviceProfile.engineHashMb(false));

        System.out.println();
        System.out.println("== ③ 轮询间隔 ==");
        // 还没测出耗时 → 保持原样
        ck("无实测耗时 → 保持 400", DeviceProfile.linkInterval(400, 0, false) == 400,
                "=" + DeviceProfile.linkInterval(400, 0, false));
        ck("无实测耗时 → 保持 700", DeviceProfile.linkInterval(700, 0, false) == 700,
                "=" + DeviceProfile.linkInterval(700, 0, false));
        // 本机识别约 539ms：700 基准下 539*1.6=862 > 700 → 放宽到 862
        int iv = DeviceProfile.linkInterval(700, 539, false);
        ck("本机(539ms, 700基准) → 放宽但不超上限", iv > 700 && iv <= DeviceProfile.LINK_MAX_MS,
                "=" + iv);
        // 快机（识别 150ms）不该被拖慢
        ck("快机(150ms) → 仍保持基准", DeviceProfile.linkInterval(400, 150, false) == 400,
                "=" + DeviceProfile.linkInterval(400, 150, false));
        // 低内存机同等耗时放得更宽
        ck("低内存比普通更宽", DeviceProfile.linkInterval(700, 539, true)
                        >= DeviceProfile.linkInterval(700, 539, false),
                DeviceProfile.linkInterval(700, 539, true) + " >= " + DeviceProfile.linkInterval(700, 539, false));
        // 慢机（识别 1200ms）必须落到上限，而不是无限放大
        ck("慢机(1200ms) → 封顶", DeviceProfile.linkInterval(700, 1200, false) == DeviceProfile.LINK_MAX_MS,
                "=" + DeviceProfile.linkInterval(700, 1200, false));
        // 极慢机也不能超过上限
        ck("极慢机(5000ms) → 仍封顶", DeviceProfile.linkInterval(400, 5000, true) == DeviceProfile.LINK_MAX_MS,
                "=" + DeviceProfile.linkInterval(400, 5000, true));
        // 下限保护
        ck("基准过小 → 抬到下限", DeviceProfile.linkInterval(50, 0, false) == DeviceProfile.LINK_MIN_MS,
                "=" + DeviceProfile.linkInterval(50, 0, false));
        // 单调性：耗时越长间隔越长（不递减）
        boolean mono = true;
        int prev = 0;
        for (long d = 100; d <= 2000; d += 100) {
            int cur = DeviceProfile.linkInterval(400, d, false);
            if (cur < prev) mono = false;
            prev = cur;
        }
        ck("单调不递减（耗时↑ 间隔不缩短）", mono, "");

        System.out.println();
        System.out.println("== ④ 帧率 ==");
        ck("低内存间隔更大（帧率更低）", DeviceProfile.captureFrameGap(true) > DeviceProfile.captureFrameGap(false),
                DeviceProfile.captureFrameGap(true) + " > " + DeviceProfile.captureFrameGap(false));

        System.out.println();
        System.out.println("== ⑤ 分档 ==");
        ck("0 → 标准（不崩）", DeviceProfile.tierOf(0) == 1, "=" + DeviceProfile.tierOf(0));
        ck("负数 → 标准", DeviceProfile.tierOf(-1) == 1, "=" + DeviceProfile.tierOf(-1));
        ck("20万 → 轻量", DeviceProfile.tierOf(200000) == 0, "=" + DeviceProfile.tierOf(200000));
        ck("25万边界 → 标准", DeviceProfile.tierOf(DeviceProfile.NPS_WEAK) == 1,
                "=" + DeviceProfile.tierOf(DeviceProfile.NPS_WEAK));
        ck("本机实测 76万 → 强劲", DeviceProfile.tierOf(760000) == 2, "=" + DeviceProfile.tierOf(760000));
        ck("60万边界 → 强劲", DeviceProfile.tierOf(DeviceProfile.NPS_STRONG) == 2,
                "=" + DeviceProfile.tierOf(DeviceProfile.NPS_STRONG));
        ck("档位名不为空", DeviceProfile.tierName(0).length() > 0
                        && DeviceProfile.tierName(1).length() > 0
                        && DeviceProfile.tierName(2).length() > 0,
                DeviceProfile.tierName(0) + "/" + DeviceProfile.tierName(1) + "/" + DeviceProfile.tierName(2));
        ck("弱机默认关背景板", DeviceProfile.defaultBgOff(0, false), "");
        ck("低内存默认关背景板", DeviceProfile.defaultBgOff(2, true), "");
        ck("强机非低内存不关", !DeviceProfile.defaultBgOff(2, false), "");

        System.out.println();
        System.out.println("== ⑥ 描述串 ==");
        String d = DeviceProfile.describe(8, false, 4, 64, 2);
        ck("日志格式含关键字段", d.contains("核数=8") && d.contains("线程=4") && d.contains("强劲"), d);
        String cn = DeviceProfile.describeCn(8, false, 4, 64, 2);
        ck("界面格式：核数在前", cn.startsWith("8 核"), cn);
        ck("界面格式：含线程/置换表/档位",
                cn.contains("引擎 4 线程") && cn.contains("置换表 64MB") && cn.contains("性能档 强劲"), cn);
        ck("界面格式：普通机不提低内存", !cn.contains("低内存"), cn);
        String cnLow = DeviceProfile.describeCn(4, true, 2, 32, 0);
        ck("界面格式：低内存机明确标出", cnLow.contains("低内存模式"), cnLow);
        ck("界面格式：无换行（能塞进一行）", cn.indexOf('\n') < 0 && cnLow.indexOf('\n') < 0, "");
        ck("界面格式：不含 key=value 的等号", cn.indexOf('=') < 0, cn);

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
