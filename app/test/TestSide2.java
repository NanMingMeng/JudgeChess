import com.yypm.assistant.Board;
import com.yypm.assistant.BoardVision;

/** 方向判定单测：将帅位置优先，残局不能判反。 */
public class TestSide2 {
    static int pass = 0, fail = 0;
    static void ck(String n, Object got, Object want) {
        boolean ok = String.valueOf(got).equals(String.valueOf(want));
        System.out.println((ok ? "  OK  " : "  FAIL") + "  " + n + "  got=" + got + " want=" + want);
        if (ok) pass++; else fail++;
    }
    public static void main(String[] a) {
        // 本次实测出问题的那一帧（红帅在第 9 行 = 红在下）
        String endgame = "2b1ka3/4a4/4b1n2/R5N1p/1P7/9/4P2cP/5A3/5r3/c3Kr3";
        ck("残局-红帅在下 direction", BoardVision.directionOf(Board.fromFen(endgame)), "normal");
        ck("残局-红帅在下 sideOfByKing", BoardVision.sideOfByKing(Board.fromFen(endgame)), "w");

        // 翻转版（黑将在第 9 行）
        String flipped = Board.placeOf(Board.rotate180(endgame, 'w'));
        ck("翻转后 direction", BoardVision.directionOf(Board.fromFen(flipped)), "flipped");
        ck("翻转后 sideOfByKing", BoardVision.sideOfByKing(Board.fromFen(flipped)), "b");

        // 开局
        ck("开局 sideOfByKing", BoardVision.sideOfByKing(Board.fromFen(Board.START)), "w");

        // 中局（红少子）
        String mid = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/R8/P1P1P1P1P/1C5C1/9/1NBAKABNR";
        ck("中局 sideOfByKing", BoardVision.sideOfByKing(Board.fromFen(mid)), "w");

        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
