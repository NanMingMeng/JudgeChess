import com.yypm.assistant.Board;

/** 单步闸门 + 子力上限 + 网格校验 单测。 */
public class TestGate {
    static int pass = 0, fail = 0;

    static void ck(String name, boolean got, boolean want) {
        boolean ok = got == want;
        System.out.println((ok ? "  OK  " : "  FAIL") + "  " + name + "  got=" + got + " want=" + want);
        if (ok) pass++; else fail++;
    }

    public static void main(String[] a) {
        String S = Board.START;

        ck("无变化", Board.singleMoveOk(S, S), true);

        // 红兵 c3c4（row6 col2 -> row5 col2）
        String m1 = Board.applyUci(S, "c3c4");
        ck("红兵正常走一步", Board.singleMoveOk(S, m1), true);

        String m2 = Board.applyUci(m1, "g6g5");     // 黑卒 g6g5
        ck("黑卒正常走一步", Board.singleMoveOk(m1, m2), true);

        // 红车吃黑卒：c0c1 之后再到 c4? 用 c0c1 验证
        String m3 = Board.applyUci(m2, "c0c1");     // 红车 c0c1
        ck("红车正常走一步", Board.singleMoveOk(m2, m3), true);

        // 一帧里变了三步 -> 拒绝
        ck("一帧变三步", Board.singleMoveOk(S, m3), false);

        // 红车红炮被认成黑（大小写翻转，本局真实出现过）
        String bug = "3akab2/1r1n5/4b1n2/p1p1p1p1p/9/3R2P2/P1P1P1rcP/3RC1N2/4N4/2BAKAB2";
        String flip = "3akab2/1r1n5/4b1n2/p1p1p1p1p/9/3R2P2/P1P1P1RCP/3RC1N2/4N4/2BAKAB2";
        ck("红车红炮被认成黑", Board.singleMoveOk(bug, flip), false);

        // 凭空多一个帅
        String ghost = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKKBNR";
        ck("凭空多一个帅", Board.singleMoveOk(S, ghost), false);

        // 兵不能变列
        ck("兵变列被拒", Board.singleMoveOk(S,
                "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P2P1P1P1/1C5C1/9/RNBAKABNR"), false);

        // validate：红三车
        String bad = Board.validate("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RRBAKAB1R");
        ck("红三车被 validate 拦下", bad != null, true);

        // validate：子力正常的中局应通过
        String ok = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/R8/P1P1P1P1P/1C5C1/9/1NBAKABNR w";
        String msg = Board.validate(ok);
        ck("正常中局 validate 通过 (" + msg + ")", msg == null, true);

        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
