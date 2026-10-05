import com.yypm.assistant.Board;

/**
 * 预判（对手应手）单测。
 *
 * 逻辑：引擎 PV 第 1 步 = 我方着法，第 2 步 = 对手最佳应手。
 * 这里验证「拿到 PV 两步后，能把对手应手正确翻成中文」。
 */
public class TestReply {
    static int pass = 0, fail = 0;

    static void ck(String n, String got, String want) {
        boolean ok = want.equals(got);
        System.out.println((ok ? "  OK  " : "  FAIL") + "  " + n + "  got=" + got + " want=" + want);
        if (ok) pass++; else fail++;
    }

    public static void main(String[] a) {
        String S = Board.START;

        // 我方炮二平五(h2e2)，对手马8进7(b9c7)
        String afterMine = Board.applyUci(S, "h2e2");
        ck("我方炮二平五", Board.uciToCn("h2e2", S + " w"), "炮 8-3 平 5-3");
        ck("对手马8进7(黑)", Board.uciToCn("b9c7", afterMine + " b"), "马 2-10 进 3-8");

        // 我方兵七进一，对手卒3进1
        String m2 = Board.applyUci(S, "c3c4");
        ck("我方兵七进一", Board.uciToCn("c3c4", S + " w"), "兵 3-4 进 3-5");
        ck("对手卒3进1(黑)", Board.uciToCn("g6g5", m2 + " b"), "卒 7-7 进 7-6");

        // applyUci 的正确性：走完炮二平五后，e2 上应是红炮
        char[][] b = Board.fromFen(afterMine);
        ck("落点 e2 是红炮", String.valueOf(b[Board.rowOfRank(2)][4]), "C");
        ck("原处 h2 已空", String.valueOf((char) (b[Board.rowOfRank(2)][7] == 0 ? '0' : b[Board.rowOfRank(2)][7])), "0");

        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
