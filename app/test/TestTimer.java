import com.yypm.assistant.Board;

/** 对局计时判定单测：开局态 / 终局 的两条判据。 */
public class TestTimer {
    static int pass = 0, fail = 0;

    static void ck(String name, boolean got, boolean want) {
        if (got == want) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name + " got=" + got + " want=" + want); }
    }

    public static void main(String[] a) {
        String START = Board.START;
        int[] r;

        // 开局：32 子，双将帅在场
        r = Board.countAndKings(START);
        System.out.println("开局 子=" + r[0] + " 将帅=" + r[1]);
        ck("开局子数=32", r[0] == 32, true);
        ck("开局将帅=2", r[1] == 2, true);
        ck("开局算开局态(>=28且将帅齐)", (r[1] == 2 && r[0] >= 28), true);
        ck("开局不算终局", r[1] < 2, false);

        // 走了一步（车九平八），子数不变
        String after1 = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/1NBAKABNR";
        r = Board.countAndKings(after1);
        System.out.println("走一步 子=" + r[0] + " 将帅=" + r[1]);
        ck("走一步仍是开局态", (r[1] == 2 && r[0] >= 28), true);

        // 中残局：子少，将帅齐
        String mid = "4k4/9/9/9/9/9/9/9/9/4K4";
        r = Board.countAndKings(mid);
        System.out.println("残局 子=" + r[0] + " 将帅=" + r[1]);
        ck("残局不算开局态", (r[1] == 2 && r[0] >= 28), false);
        ck("残局不算终局", r[1] < 2, false);

        // 终局：黑将不在
        String over = "9/9/9/9/9/9/9/9/9/4K4";
        r = Board.countAndKings(over);
        System.out.println("终局 子=" + r[0] + " 将帅=" + r[1]);
        ck("将帅缺一 → 终局", r[1] < 2, true);

        // 22 子（中局，双方将帅在）
        String m22 = "3k5/9/9/p1p1p4/9/9/P1P1P4/9/9/3K5";
        r = Board.countAndKings(m22);
        System.out.println("中局 子=" + r[0] + " 将帅=" + r[1]);
        ck("中局不算开局态", (r[1] == 2 && r[0] >= 28), false);

        System.out.println("通过 " + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
