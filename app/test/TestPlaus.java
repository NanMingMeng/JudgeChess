import com.yypm.assistant.Board;

/**
 * 首帧兜底判据单测。
 *
 * 注意契约已变：不再用棋型（有没有车马炮）当判据 —— 实测一局残局里黑方真的没有车，
 * 用棋型会把合法局面拒掉，表现成「悬着不走」。现在只拦最离谱的「一方几乎没子」，
 * 挡垃圾帧靠的是 XqService 里「连续两帧认到同一局面」的稳定性判据。
 */
public class TestPlaus {
    public static void main(String[] a) {
        int pass = 0, fail = 0;
        String[][] cases = {
            {"开局", "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR", null},
            {"中局", "rnbakabnr/9/1c5c1/p1p1p1p1p/9/R8/P1P1P1P1P/1C5C1/9/1NBAKABNR", null},
            // 残局挑战真实出现过：黑方没有车，但这是合法局面，必须放行
            {"残局-黑无车(合法)", "3akab2/1r1n5/4b1n2/p1p1p1p1p/9/3R2P2/P1P1P1rcP/3RC1N2/4N4/2BAKAB2", null},
            {"黑方只剩1子", "4k4/9/9/9/9/9/9/4C4/4A4/4KABNR", "有"},
            {"双方各剩1子", "4k4/9/9/9/9/9/9/9/4A4/4K4", "有"},
        };
        for (String[] c : cases) {
            String got = Board.plausibleProblem(c[1]);
            boolean ok = (c[2] == null) ? (got == null) : (got != null);
            System.out.println((ok ? "  OK  " : "  FAIL") + "  " + c[0] + "  -> " + got);
            if (ok) pass++; else fail++;
        }
        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
