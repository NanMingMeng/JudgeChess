import com.yypm.assistant.Board;

/** 首行「下一步」记谱单测：必须是标准中文记谱，不能再出现 "兵 3-5 进 3-6" 这种坐标式。 */
public class TestNotation {
    static int pass = 0, fail = 0;

    static void ck(String name, Object got, Object want) {
        boolean ok = (got == null ? want == null : got.equals(want));
        if (ok) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name + " got=" + got + " want=" + want); }
    }

    public static void main(String[] a) {
        // 开局标准局面：兵七进一 = c3c4
        String start = Board.START;
        String n1 = Board.uciToCnStd("c3c4", start + " w");
        System.out.println("开局 兵七进一 -> " + n1);
        ck("开局兵七进一", n1, "兵七进一");

        // 旧函数会输出坐标式（这是要淘汰的）
        String legacy = Board.uciToCn("c3c4", start + " w");
        System.out.println("旧函数输出 -> " + legacy);

        // 用户截图那一局的真实局面（23:29 基线），推荐着法 c4c5
        String fen = "3akabr1/2c6/2c1b1n2/p3p3p/6p2/2P1C4/P3P1P1P/2n1B1N2/N1C5R/2BAKA3";
        String n2 = Board.uciToCnStd("c4c5", Board.placeOf(fen) + " w");
        System.out.println("实测局面 c4c5 -> " + n2);
        ck("实测局面的兵前进", n2, "兵七进一");

        // 关键回归：不能含 "-"（坐标式的特征）
        ck("不含坐标式横杠", n2.contains("-"), false);
        ck("旧函数确实含横杠(说明替换有意义)", legacy.contains("-"), true);

        // 黑方记谱用阿拉伯数字：黑卒 5 进 1 = 从 col4 row3 到 row4
        String n3 = Board.uciToCnStd("e6e5", fen + " b");
        System.out.println("黑卒 e6e5 -> " + n3);

        System.out.println("通过 " + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
