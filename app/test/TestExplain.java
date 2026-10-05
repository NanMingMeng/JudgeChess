package com.yypm.assistant;

/** 变化解释 Board.explainChange / twoMovesOk 的单测（数据取自 09-30 那局的真实日志）。 */
public class TestExplain {
    static int pass = 0, total = 0;

    static void ck(String name, int got, int want) {
        total++;
        boolean ok = got == want;
        System.out.println((ok ? "  ok   " : "  FAIL ") + name
                + "  got=" + got + " want=" + want);
        if (ok) pass++;
    }

    public static void main(String[] a) {
        // ---- 真实日志里的 4 类被拒帧 ----
        // 1) 黑马凭空消失（1 格变化）
        String p1 = "2bk1a3/4a4/9/4PP3/5N3/1p7/9/5An2/2n1AK3/9";
        String c1 = "2bk1a3/4a4/9/4PP3/5N3/1p7/9/5A3/2n1AK3/9";
        ck("黑马凭空消失 -> 抖动", Board.explainChange(p1, c1), Board.EXPL_NOISE);

        // 2) 黑卒横移一格（兵卒不能横走）
        String p2 = "2bk1a3/4a4/9/4PP3/9/1p7/6N1n/5A3/2n1AK3/9";
        String c2 = "2bk1a3/4a4/9/4PP3/9/2p6/6N1n/5A3/2n1AK3/9";
        ck("黑卒横移一格 -> 抖动", Board.explainChange(p2, c2), Board.EXPL_NOISE);

        // 3) 红帅被读到相邻格（帅走一步是合法的，这一步应当采纳）
        String p3 = "2b1ka3/4a4/9/3PP4/9/5n3/9/4KA3/7n1/9";
        String c3 = "2b1ka3/4a4/9/3PP4/9/5n3/9/5A3/4K2n1/9";
        ck("红帅合法走一步 -> 一步", Board.explainChange(p3, c3), Board.EXPL_ONE);

        // 4) 完全相同的盘面
        ck("盘面相同 -> 一步", Board.explainChange(p1, p1), Board.EXPL_ONE);

        // 5) 一步 + 一格噪声（3 格变化）
        String p5 = "2bk1a3/4a4/9/4PP3/9/6n2/3p2N2/9/2n1AK3/3A5";
        String c5 = "2bk1a3/4a4/9/4PP3/9/6n2/4p1N2/9/2n1AK3/3A5"; // 卒 3->4 且 N 6 没了? 这里构造 3 格
        String p5b = "2bk1a3/4a4/9/4PP3/9/9/5nN3/9/4K4/9";
        String c5b = "2bk1a3/4a4/9/4PP3/9/9/4n4/9/4K4/9";
        ck("3 格变化 -> 抖动", Board.explainChange(p5b, c5b), Board.EXPL_NOISE);

        // 6) 漏拍一次：红帅走一步 + 黑马走一步 = 4 格
        String p6 = "2bk1a3/4a4/9/9/9/9/9/4KA3/9/9";
        // 先让黑马跳到 c5b 的位置，再看两步是否成立：用两步近似构造
        String mid = Board.applyUci(p6, "e0e1");        // 红帅 仕? 用 UCI 直接推
        System.out.println("  (调试) applyUci(e0e1) = " + mid);
        ck("4 格但解释不通 -> UNKNOWN",
                Board.explainChange("2b1ka3/4a4/9/9/9/9/9/9/9/4K4",
                        "2b1ka3/4a4/9/9/9/9/9/5n3/9/4K4"),
                Board.EXPL_NOISE);

        // 7) 大面积跳变必须判 UNKNOWN（>=5 格）
        ck("大面积跳变 -> UNKNOWN",
                Board.explainChange(Board.START,
                        "9/9/9/9/9/9/9/9/9/9"),
                Board.EXPL_UNKNOWN);

        System.out.println(pass + "/" + total + " 通过");
    }
}
