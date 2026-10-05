import com.yypm.assistant.Board;

/** 标准中文着法单测：红用中文数字，黑用阿拉伯数字。 */
public class TestCnStd {
    static int pass = 0, fail = 0;

    static void ck(String n, String got, String want) {
        boolean ok = want.equals(got);
        System.out.println((ok ? "  OK  " : "  FAIL") + "  " + n + "  got=" + got + " want=" + want);
        if (ok) pass++; else fail++;
    }

    public static void main(String[] a) {
        String S = Board.START;

        // 红兵七进一：内部 col 2, row6→row5；红方线号 = 9-col = 9-2 = 7 → 七
        ck("红兵七进一", Board.uciToCnStd("c3c4", S + " w"), "兵七进一");
        // 红炮二平五：h2→e2，红线号 9-7=2 → 二；目标 9-4=5 → 五
        ck("红炮二平五", Board.uciToCnStd("h2e2", S + " w"), "炮二平五");
        // 红马八进七：b0→c2，红线号 9-1=8 → 八；目标 9-2=7 → 七
        ck("红马八进七", Board.uciToCnStd("b0c2", S + " w"), "马八进七");
        // 红车一进一：i0→i1，红线号 9-8=1 → 一；走1格
        ck("红车一进一", Board.uciToCnStd("i0i1", S + " w"), "车一进一");

        // 黑卒3进1：g6→g5，黑线号 col6+1=7 → 7
        ck("黑卒7进1", Board.uciToCnStd("g6g5", S + " b"), "卒7进1");
        // 黑马8进7：b9→c7，黑线号 col1+1=2 → 2；目标 col2+1=3 → 3
        ck("黑马2进3", Board.uciToCnStd("b9c7", S + " b"), "马2进3");
        // 黑车1平2：a9→b9，黑线号 col0+1=1 → 1；目标 2
        ck("黑车1平2", Board.uciToCnStd("a9b9", S + " b"), "车1平2");

        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
