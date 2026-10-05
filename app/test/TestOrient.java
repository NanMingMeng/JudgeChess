import com.yypm.assistant.Board;

/** 朝向判定单测：执黑时（红在屏幕上方）原始 FEN 必须翻转后才合法。 */
public class TestOrient {
    static int pass = 0, fail = 0;

    static void ck(String name, Object got, Object want) {
        boolean ok = (got == null ? want == null : got.equals(want));
        if (ok) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name + " got=" + got + " want=" + want); }
    }

    public static void main(String[] a) {
        // 用户实测那一帧（L1 log 的 LAYOUT），红在屏幕上方 = 执黑
        String raw = "RN1AKABNR/9/1C2B2C1/P1P1P1P1P/9/9/p1p1p1p1p/1c5c1/9/rnbakabnr";

        ck("原始 FEN（红在上）应被判非法", Board.validate(raw), "红仕不在九宫");

        String rot = Board.placeOf(Board.rotate180(raw, 'w'));
        System.out.println("  翻转后 = " + rot);
        ck("翻转后（红在下）应合法", Board.validate(rot), null);

        // 翻转后仍是 16+16 子，将帅各一
        int[] ck1 = Board.countAndKings(rot);
        ck("翻转后子数=32", ck1[0], 32);
        ck("翻转后将帅=2", ck1[1], 2);

        // 翻两次应还原
        ck("翻两次还原", Board.placeOf(Board.rotate180(rot, 'w')), raw);

        // 红在下方（执红）的原始帧本来就应该合法
        ck("执红原始帧合法", Board.validate(rot), null);

        System.out.println("通过 " + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
