package com.yypm.assistant;

/** 落子核对 Board.moveApplied / Board.at 的单测（用 09-30 那局真实数据构造）。 */
public class TestMoveApplied {
    static int pass = 0, total = 0;

    static void chk(String name, boolean got, boolean want) {
        total++;
        if (got == want) { pass++; System.out.println("  ok   " + name); }
        else System.out.println("  FAIL " + name + " got=" + got + " want=" + want);
    }

    public static void main(String[] a) {
        // 落子前：黑馬(8,2) 红仕(8,4) 红帅(9,5) 红仕(7,5)
        String prev = "2bk1a3/4a4/9/4PP3/3n1N3/1p7/9/5A3/2n1A4/5K3";
        // 仕(8,4)->(9,3) 生效后应有的局面
        String want = "2bk1a3/4a4/9/4PP3/3n1N3/1p7/9/5A3/2n6/3A1K3";

        chk("完全一致 -> 生效", Board.moveApplied(prev, "e1d0", want), true);

        // 真落了，但底行识别漂一行：帅被读成(8,5)
        String drift = "2bk1a3/4a4/9/4PP3/3n1N3/1p7/9/5A3/2n2K3/3A5";
        chk("底行漂一行仍判生效", Board.moveApplied(prev, "e1d0", drift), true);

        // 仕根本没动（起点还是原样的仕）—— 必须判未生效
        String notMoved = "2bk1a3/4a4/9/4PP3/3n1N3/1p7/9/5A3/2n1AK3/9";
        chk("起点没动 -> 未生效", Board.moveApplied(prev, "e1d0", notMoved), false);

        // 识别把起点漂空了 —— 必须判未生效
        chk("起点为空 -> 未生效", Board.moveApplied(want, "e1d0", want), false);

        // 取格
        chk("at(8,4)=A", Board.at(prev, 8, 4) == 'A', true);
        chk("at(9,5)=K", Board.at(prev, 9, 5) == 'K', true);
        chk("at(5,5) 为空", Board.at(prev, 5, 5) == '.', true);

        System.out.println(pass + "/" + total + " 通过");
    }
}
