import com.yypm.assistant.Board;

/**
 * 轮次推导单测。
 *
 * 核心回归：
 *  ① 处于「等对手」时，任何被采纳的盘面变化都必须回到「轮到我」
 *     （否则 whoMoved 判反就永久卡在「等对手走棋」）
 *  ② 首帧、判不出谁走的、且盘面已动过（中途接入）→ 必须判成「轮到我」
 *     否则执黑中途连线会死锁：红方等我走、我等红方走
 */
public class TestTurn {
    static int pass = 0, fail = 0;

    static void ck(String name, int got, int want) {
        if (got == want) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name + " got=" + got + " want=" + want); }
    }

    public static void main(String[] a) {
        final int INIT = Board.T_INIT, MY = Board.T_MY_TURN, MOV = Board.T_MOVING, WAIT = Board.T_WAIT;
        final boolean START = true, MID = false;

        // ① 等对手期间盘面变了 → 必须轮到我（三种 mover 都要成立）
        ck("等对手 + 对手走 → 轮到我", Board.nextTurn(WAIT, 'b', 'w', MID), MY);
        ck("等对手 + 我走的(判反) → 轮到我", Board.nextTurn(WAIT, 'w', 'w', MID), MY);
        ck("等对手 + 漏拍判不出 → 轮到我", Board.nextTurn(WAIT, (char) 0, 'w', MID), MY);
        ck("等对手 + 判反(执黑) → 轮到我", Board.nextTurn(WAIT, 'b', 'b', MID), MY);

        // ② 首帧接入：中途局面（盘面已动过）→ 轮到我
        ck("★中途接入 + 执黑 + 判不出 → 轮到我", Board.nextTurn(INIT, (char) 0, 'b', MID), MY);
        ck("★中途接入 + 执红 + 判不出 → 轮到我", Board.nextTurn(INIT, (char) 0, 'w', MID), MY);

        // ③ 首帧接入：标准开局 → 红先走
        ck("标准开局 + 执红 → 轮到我", Board.nextTurn(INIT, (char) 0, 'w', START), MY);
        ck("标准开局 + 执黑 → 等对手", Board.nextTurn(INIT, (char) 0, 'b', START), WAIT);

        // ④ 首帧但能判出 mover：按 mover 走
        ck("首帧 + 对手刚走过(执黑) → 轮到我", Board.nextTurn(INIT, 'w', 'b', MID), MY);
        ck("首帧 + 我刚走过(执黑) → 等对手", Board.nextTurn(INIT, 'b', 'b', MID), WAIT);

        // ⑤ 待出招
        ck("待出招 + 我走的 → 等对手", Board.nextTurn(MY, 'w', 'w', MID), WAIT);
        ck("待出招 + 对手走的 → 仍轮到我", Board.nextTurn(MY, 'b', 'w', MID), MY);
        ck("待出招 + 判不出 → 保守留在我这", Board.nextTurn(MY, (char) 0, 'w', MID), MY);

        // ⑥ 常量与 XqService 的 ST_* 数值一致
        ck("T_INIT=0", INIT, 0);
        ck("T_MY_TURN=1", MY, 1);
        ck("T_MOVING=2", MOV, 2);
        ck("T_WAIT=3", WAIT, 3);

        // ⑦ 开局常量确实是 standard 那个串
        ck("START 串正确",
                Board.START.equals("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR")
                        ? 1 : 0, 1);

        System.out.println("通过 " + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
