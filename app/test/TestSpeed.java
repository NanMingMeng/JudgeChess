import com.yypm.assistant.Speed;
import java.util.Random;

/**
 * 走棋速度档位单测。
 *
 * 背景：5 分钟快棋 300 秒、43 回合，之前固定「思考 2s + 节流 1.5s」
 * 一步 5~6 秒必然超时。这里验证：
 *   ① 极速档确实够快（能保时间）
 *   ② 慢档确实够慢（求棋力）
 *   ③ 任何档位都不会快到「一秒好几步」
 *   ④ 思考时间有抖动、偶尔长考（像真人，不是固定间隔）
 */
public class TestSpeed {
    static int pass = 0, fail = 0;

    static void ck(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  ok   " + name + (detail.isEmpty() ? "" : "  " + detail)); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    public static void main(String[] a) {
        final int FAST = 0, QUICK = 1, NORMAL = 2, SLOW = 3;

        // ---- ① 档位单调：越快档思考时间越短 ----
        ck("极速 < 快 < 正常 < 慢（思考）",
                Speed.base(FAST) < Speed.base(QUICK)
                        && Speed.base(QUICK) < Speed.base(NORMAL)
                        && Speed.base(NORMAL) < Speed.base(SLOW),
                Speed.base(FAST) + "/" + Speed.base(QUICK) + "/" + Speed.base(NORMAL) + "/" + Speed.base(SLOW));
        ck("节流也单调不减",
                Speed.throttle(FAST) <= Speed.throttle(QUICK)
                        && Speed.throttle(QUICK) <= Speed.throttle(NORMAL)
                        && Speed.throttle(NORMAL) <= Speed.throttle(SLOW),
                Speed.throttle(FAST) + "/" + Speed.throttle(SLOW));
        ck("MultiPV 慢档更多路",
                Speed.multiPv(FAST) <= Speed.multiPv(SLOW),
                Speed.multiPv(FAST) + "→" + Speed.multiPv(SLOW));

        // ---- ② 极速档够快：一步（思考+节流）应远小于旧的 3.5 秒 ----
        int[] fastRange = Speed.stepRange(FAST);
        System.out.println("极速档 一步 " + fastRange[0] + "~" + fastRange[1] + "ms");
        ck("极速档单步 < 1.5 秒（含最坏长考）", fastRange[1] < 1500, fastRange[1] + "ms");
        ck("极速档比旧方案（1500+2000=3500ms）快一倍以上",
                fastRange[1] * 2 < 3500, fastRange[1] + "ms");

        // ---- ③ 任何档位都不能「一秒好几步」 ----
        boolean allAbove = true;
        StringBuilder sb = new StringBuilder();
        for (int lv = 0; lv < Speed.TABLE.length; lv++) {
            int[] r = Speed.stepRange(lv);
            sb.append(Speed.NAME[lv]).append("=").append(r[0]).append("~").append(r[1]).append("  ");
            if (r[0] < Speed.MIN_STEP_MS) allAbove = false;
        }
        ck("所有档位单步 >= " + Speed.MIN_STEP_MS + "ms（不会一秒好几步）", allAbove, sb.toString());

        // ---- ④ 慢档够慢：最长一步应上秒级 ----
        int[] slowRange = Speed.stepRange(SLOW);
        System.out.println("慢档 一步 " + slowRange[0] + "~" + slowRange[1] + "ms");
        ck("慢档最长一步 > 3 秒（够思考）", slowRange[1] > 3000, slowRange[1] + "ms");

        // ---- ⑤ 抖动生效：极速档 200 次采样，不能全是同一个值 ----
        Random r = new Random(12345);
        int lo = Integer.MAX_VALUE, hi = 0;
        java.util.HashSet<Integer> vals = new java.util.HashSet<Integer>();
        for (int i = 0; i < 200; i++) {
            int t = Speed.thinkTime(QUICK, r);
            vals.add(t);
            if (t < lo) lo = t;
            if (t > hi) hi = t;
        }
        System.out.println("快档 thinkTime 采样 " + lo + "~" + hi + "ms，不同取值 " + vals.size() + " 种");
        ck("思考时间有抖动（不是固定值）", vals.size() >= 5, vals.size() + " 种");
        ck("抖动幅度合理（±30% 以上）", (hi - lo) > Speed.base(QUICK) * 0.5, lo + "~" + hi);

        // ---- ⑥ 偶尔长考：采样最大值应超过基准 1.4 倍 ----
        ck("存在偶尔长考", hi > Speed.base(QUICK) * 1.4, "max=" + hi + " base=" + Speed.base(QUICK));

        // ---- ⑦ thinkTime 永远不低于下限 ----
        boolean ok = true;
        for (int lv = 0; lv < Speed.TABLE.length; lv++) {
            for (int i = 0; i < 500; i++) {
                int t = Speed.thinkTime(lv, r);
                if (t < Speed.MIN_THINK_MS) ok = false;
                if (t + Speed.throttle(lv) < Speed.MIN_STEP_MS) ok = false;
            }
        }
        ck("所有档位 2000 次采样都满足下限", ok, "");

        // ---- ⑧ 循环切换 ----
        ck("next: 极速→快", Speed.next(FAST) == QUICK, "");
        ck("next: 快→正常", Speed.next(QUICK) == NORMAL, "");
        ck("next: 正常→慢", Speed.next(NORMAL) == SLOW, "");
        ck("next: 慢→极速（回环）", Speed.next(SLOW) == FAST, "");

        // ---- ⑨ clamp 边界 ----
        ck("clamp(-5)=0", Speed.clamp(-5) == 0, "");
        ck("clamp(99)=最慢档", Speed.clamp(99) == SLOW, "");
        ck("clamp(默认) 合法", Speed.clamp(Speed.DEFAULT) == Speed.DEFAULT, "");
        // 越界档位不应抛异常
        int t = Speed.thinkTime(99, r);
        ck("越界档位 thinkTime 不抛异常", t >= Speed.MIN_THINK_MS, "t=" + t);

        // ---- ⑩ 描述文本可读 ----
        String d = Speed.describe(QUICK);
        System.out.println("描述: " + d);
        ck("describe 含档位名", d.contains(Speed.NAME[QUICK]), d);
        // ---- ⑪ 落子生效确认窗口必须盖住「游戏动画 + 投票」的 2~3 秒 ----
        //  这条是实战教训：窗口 1.6/2.2 秒时，识别还没追上就判「点击丢了」，
        //  于是重算重下，把整局走成随机着法。窗口不得低于 3000ms。
        boolean allWide = true;
        for (int i = 0; i < Speed.TABLE.length; i++) {
            if (Speed.retry(i) < 3000) allWide = false;
            System.out.println("  档" + i + " " + Speed.NAME[i] + " 确认窗口=" + Speed.retry(i) + "ms");
        }
        ck("所有档位落子确认窗口 >= 3000ms", allWide, "");
        ck("慢档窗口 >= 4000ms", Speed.retry(SLOW) >= 4000, "v=" + Speed.retry(SLOW));

        System.out.println("通过 " + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
