package com.yypm.assistant;
import java.util.*;
/** 象棋棋盘：FEN / 坐标 / 着法转换与合法性校验。 */
public class Board {
    public static final String COLS = "abcdefghi";
    public static final int N_COL = 9, N_ROW = 10;
    public static final String START =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR";
    private static final Map<Character, String> NAME = new HashMap<Character, String>();
    static {
        String[] k = {"K","A","B","N","R","C","P","k","a","b","n","r","c","p"};
        String[] v = {"帅","仕","相","马","车","炮","兵","将","士","象","马","车","炮","卒"};
        for (int i = 0; i < k.length; i++) NAME.put(k[i].charAt(0), v[i]);
    }
    /** FEN -> 10x9 棋盘，board[row][col]，row0 = 黑方底线（屏幕上方）。空位 '\0'。 */
    public static char[][] fromFen(String fen) {
        char[][] b = new char[N_ROW][N_COL];
        String placement = fen.trim().split("\\s+")[0];
        String[] rows = placement.split("/");
        for (int r = 0; r < N_ROW && r < rows.length; r++) {
            int c = 0;
            for (char ch : rows[r].toCharArray()) {
                if (ch >= '1' && ch <= '9') {
                    c += ch - '0';
                } else {
                    if (c < N_COL) b[r][c] = ch;
                    c++;
                }
            }
        }
        return b;
    }
    public static String toFen(char[][] b, char side) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < N_ROW; r++) {
            int empty = 0;
            for (int c = 0; c < N_COL; c++) {
                char p = b[r][c];
                if (p == '\0') {
                    empty++;
                } else {
                    if (empty > 0) { sb.append(empty); empty = 0; }
                    sb.append(p);
                }
            }
            if (empty > 0) sb.append(empty);
            if (r != N_ROW - 1) sb.append('/');
        }
        return sb.toString() + " " + side;
    }
    /** 整体旋转 180°（换视角），棋子颜色不变。 */
    public static String rotate180(String fen, char side) {
        char[][] b = fromFen(fen);
        char[][] n = new char[N_ROW][N_COL];
        for (int r = 0; r < N_ROW; r++)
            for (int c = 0; c < N_COL; c++)
                n[N_ROW - 1 - r][N_COL - 1 - c] = b[r][c];
        return toFen(n, side);
    }
    /** 是否为合法（可交给引擎的）局面。 */
    public static String validate(String fen) {
        if (fen == null || fen.trim().isEmpty()) return "FEN 为空";
        String[] rows = fen.trim().split("\\s+")[0].split("/");
        if (rows.length != N_ROW) return "行数=" + rows.length;
        char[][] b = fromFen(fen);
        int K = 0, k = 0;
        Map<Character, Integer> cnt = new HashMap<Character, Integer>();
        for (int r = 0; r < N_ROW; r++) {
            for (int c = 0; c < N_COL; c++) {
                char p = b[r][c];
                if (p == '\0') continue;
                cnt.put(p, (cnt.containsKey(p) ? cnt.get(p) : 0) + 1);
                if (p == 'K') {
                    K++;
                    if (r < 7 || r > 9 || c < 3 || c > 5) return "红帅不在九宫";
                } else if (p == 'k') {
                    k++;
                    if (r > 2 || c < 3 || c > 5) return "黑将不在九宫";
                } else if (p == 'P') {
                    if (r > 6) return "红兵越界";
                } else if (p == 'p') {
                    if (r < 3) return "黑卒越界";
                } else if (p == 'A') {
                    if (r < 7 || r > 9 || c < 3 || c > 5) return "红仕不在九宫";
                } else if (p == 'a') {
                    if (r > 2 || c < 3 || c > 5) return "黑士不在九宫";
                } else if (p == 'B') {
                    if (!(r == 5 || r == 7 || r == 9) || c % 2 != 0) return "红相位置非法";
                } else if (p == 'b') {
                    if (!(r == 0 || r == 2 || r == 4) || c % 2 != 0) return "黑象位置非法";
                }
            }
        }
        if (K != 1) return "红帅数=" + K;
        // 子力数量上限：识别把红车认成黑车、凭空多子时，靠这一关拦住
        String over = countOver(cnt);
        if (over != null) return over;
        if (k != 1) return "黑将数=" + k;
        int[] kr = find(b, 'K'), kg = find(b, 'k');
        if (kr[1] == kg[1]) {
            boolean blocked = false;
            for (int r = Math.min(kr[0], kg[0]) + 1; r < Math.max(kr[0], kg[0]); r++)
                if (b[r][kr[1]] != '\0') blocked = true;
            if (!blocked) return "将帅照面";
        }
        return null;
    }
    /**
     * 「这像不像一局真实的象棋」——只在建立第一帧基线时用。
     *
     * 为什么需要：validate 只能挡掉结构非法的局面（将不在九宫、兵越界…），
     * 挡不住「把游戏大厅/聊天界面认成棋盘」产生的糊状局面。
     * 实测大厅被认成 CC2kab2/2R1a4/... 后，validate 是通过的，直接变成了基线。
     * 真实对局里双方车、炮、马几乎总在（残局也很少三样全没），用这个兜底最稳。
     */
    /**
     * 「这像不像一局棋」——只做最宽松的兜底。
     *
     * 注意：不能在这里判「有没有车马炮」。实测一局残局挑战里黑方真的没有车，
     * 用棋型当判据会把合法局面拒掉，表现成「悬着不走」。
     * 真正挡垃圾帧靠的是「连续两帧认到同一局面」的稳定性判据（见 XqService）。
     * 这里只挡最离谱的：一方几乎没子。
     */
    public static String plausibleProblem(String fen) {
        char[][] b = fromFen(fen);
        int[] cnt = new int[2];
        for (int r = 0; r < N_ROW; r++) {
            for (int c = 0; c < N_COL; c++) {
                char p = b[r][c];
                if (p == 0) continue;
                cnt[Character.isUpperCase(p) ? 0 : 1]++;
            }
        }
        if (cnt[0] < 2) return "红方子太少(" + cnt[0] + ")";
        if (cnt[1] < 2) return "黑方子太少(" + cnt[1] + ")";
        if (cnt[0] + cnt[1] < 6) return "双方子太少(" + (cnt[0] + cnt[1]) + ")";
        return null;
    }
    /** 每种子力数量上限（红：帥1仕2相2馬2車2炮2兵5；黑同理）。 */
    private static final String UP_PIECE = "KABNRCP", LOW_PIECE = "kabnrcp";
    private static final int[] LIM_PIECE = {1, 2, 2, 2, 2, 2, 5};

    /** ★ v4.59：某种子的数量上限（识别纠错要用）。未知子返回 99。 */
    public static int limitOf(char p) {
        int i = UP_PIECE.indexOf(p);
        if (i < 0) i = LOW_PIECE.indexOf(p);
        return i < 0 ? 99 : LIM_PIECE[i];
    }

    /**
     * ★ v4.59：哪个子力超了上限（返回该子字符；都没超返回 0）。
     *
     * 用于「多子自动纠正」：知道是「黑卒」超了，才能只针对这一格去还原。
     */
    public static char overPiece(String fen) {
        char[][] b = fromFen(fen);
        int[] c = new int[128];
        for (int r = 0; r < N_ROW; r++)
            for (int cc = 0; cc < N_COL; cc++) {
                char p = b[r][cc];
                if (p != 0 && p < 128) c[p]++;
            }
        for (int i = 0; i < UP_PIECE.length(); i++) {
            if (c[UP_PIECE.charAt(i)] > LIM_PIECE[i]) return UP_PIECE.charAt(i);
            if (c[LOW_PIECE.charAt(i)] > LIM_PIECE[i]) return LOW_PIECE.charAt(i);
        }
        return 0;
    }

    /** ★ v4.59：多子纠错的结果。 */
    public static final class Fix {
        public String board;      // 修好的盘面（纯棋盘部分）
        public String note;       // 说明改了什么
    }

    /**
     * ★ v4.59：「多子」自动纠错（纯逻辑，与界面无关，便于离线验证）。
     *
     * 为什么必须有：识别把**同一格恒定读错**时，多帧投票压不掉（票数一致），
     * 整帧被判「不可靠」丢掉 → 一次都不落子，**重启连线也没用**。
     * 实测 12:41:17~12:45 整整 2.5 分钟、107 次「黑方 p 多子(6)」，整局瘫痪。
     *
     * 象棋不可能凭空多子，所以「多子」必定是误读。做法：
     *   把「多出来的那种子、在基线里本来不是这种子」的格子逐一还原成基线值，
     *   组合结果必须同时满足
     *     ① validate() 通过（不再有别的违规）
     *     ② 与基线只差「一步」或「两步」合法棋（explainChange）
     *   才采纳；否则返回 null —— **宁可继续丢帧，也绝不硬改盘面**
     *   （盲删有风险：对手刚走过来的那一格若也被误读，删错就把真子抹掉了）。
     *
     * @param base 上一帧确认过的盘面（红线基线）
     * @param cur  本帧识别出的盘面
     */
    private static Fix repairCore(String base, String cur) {
        if (base == null || base.isEmpty() || cur == null || cur.isEmpty()) return null;
        try {
            char pc = overPiece(cur);
            if (pc == 0) return null;
            char[][] a = fromFen(base), b = fromFen(cur);
            int nCur = 0, lim = limitOf(pc);
            for (int r = 0; r < N_ROW; r++)
                for (int c = 0; c < N_COL; c++)
                    if (b[r][c] == pc) nCur++;
            final int need = nCur - lim;
            if (need <= 0 || need > 2) return null;      // 只收拾 1~2 个多子，再多不敢动

            // 候选格 = 本帧读成 pc、而基线里不是 pc 的格子（= 凭空冒出来的那颗）
            java.util.List<int[]> cand = new java.util.ArrayList<int[]>();
            for (int r = 0; r < N_ROW; r++)
                for (int c = 0; c < N_COL; c++)
                    if (b[r][c] == pc && a[r][c] != pc) cand.add(new int[]{r, c});
            if (cand.size() < need) return null;

            // ★ v4.60：候选格该填什么？只试「还原成基线的值」是不够的 ——
            //   实测 13:45:25 我方卒3平4 之后，落点被读成「黑车」，
            //   基线里那一格是空的、但「p」在基线里少了一个 ——
            //   真相是「那格是我刚走过去的卒、只是类型读错了」。
            //   所以还要试「基线里有、本帧少了的子种」（deficit）。
            int[] ca = new int[128], cb = new int[128];
            for (int r = 0; r < N_ROW; r++)
                for (int c = 0; c < N_COL; c++) {
                    char q = a[r][c]; if (q != 0 && q < 128) ca[q]++;
                    char w = b[r][c]; if (w != 0 && w < 128) cb[w]++;
                }
            char[] vals = new char[20];
            int nv = 0;
            vals[nv++] = 0;                                  // 空的
            for (int i = 0; i < UP_PIECE.length() && nv < 18; i++) {
                char u = UP_PIECE.charAt(i), l = LOW_PIECE.charAt(i);
                if (ca[u] > cb[u] && u != pc && nv < 18) vals[nv++] = u;
                if (ca[l] > cb[l] && l != pc && nv < 18) vals[nv++] = l;
            }
            char[] vv = new char[nv];
            int m = 0;
            for (int i = 0; i < nv; i++) {
                boolean dup = false;
                for (int j = 0; j < m; j++) if (vv[j] == vals[i]) { dup = true; break; }
                if (!dup) vv[m++] = vals[i];
            }

            java.util.List<String> tries = new java.util.ArrayList<String>();
            for (int i = 0; i < cand.size() && tries.size() < 48; i++) {
                int[] p1 = cand.get(i);
                if (need == 1) {
                    for (int k = 0; k < m && tries.size() < 48; k++)
                        tries.add(withCells(b, p1, vv[k], null, (char) 0));
                } else {
                    for (int j = i + 1; j < cand.size() && tries.size() < 48; j++) {
                        int[] p2 = cand.get(j);
                        tries.add(withCells(b, p1, (char) 0, p2, (char) 0));
                        for (int k = 0; k < m && tries.size() < 48; k++) {
                            if (vv[k] == 0) continue;
                            tries.add(withCells(b, p1, vv[k], p2, (char) 0));
                            tries.add(withCells(b, p1, (char) 0, p2, vv[k]));
                        }
                    }
                }
            }
            for (int i = 0; i < tries.size(); i++) {
                String t = tries.get(i);
                String p = placeOf(t);
                if (validate(p) != null) continue;
                if (base.equals(p)) {
                    Fix f = new Fix();
                    f.board = p;
                    f.note = "还原 " + need + " 格，结果与基线一致";
                    return f;
                }
                int kind = explainChange(base, p);
                if (kind == EXPL_ONE || kind == EXPL_TWO) {
                    Fix f = new Fix();
                    f.board = p;
                    f.note = "改 " + need + " 格（候选" + tries.size() + "），与基线差"
                            + (kind == EXPL_ONE ? "一步" : "两步");
                    return f;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
    /** v4.60：优先「上一手裁定的局面」。任何一处不满足就退回普通纠错，绝不硬改。 */
    public static Fix repairExtra(String base, String cur) { return repairCore(base, cur); }

    public static Fix repairExtra(String base, String cur, String prefer) {
        Fix f = repairCore(base, cur);
        if (f != null && f.board != null && f.board.equals(base)) return f;
        if (prefer == null || prefer.isEmpty()) return f;
        try {
            char[][] c = fromFen(cur);
            if (c == null) return f;
            String pf = placeOf(prefer), curN = placeOf(cur);
            if (pf == null || pf.isEmpty() || validate(pf) != null) return f;
            int k = explainChange(base, pf);
            if (k != EXPL_ONE && k != EXPL_TWO) return f;
            int d = diffCells(pf, curN);
            if (d == 0 || d > 3) return f;
            Fix g = new Fix();
            g.board = pf;
            g.note = "按上一手裁定的局面（与基线差" + (k == EXPL_ONE ? "一步" : "两步") + "，与识别差 " + d + " 格）";
            return g;
        } catch (Throwable t) { return f; }
    }

    /** 两盘面差异格数；解析失败返回 99。 */
    public static int diffCells(String x, String y) {
        char[][] a = fromFen(x), b = fromFen(y);
        if (a == null || b == null) return 99;
        int d = 0;
        for (int r = 0; r < N_ROW; r++) for (int c = 0; c < N_COL; c++) if (a[r][c] != b[r][c]) d++;
        return d;
    }

    /** 把 b 的 c1 格改成 v1、c2 格改成 v2（c2 可为 null），返回新盘面的 FEN。 */
    private static String withCells(char[][] b, int[] c1, char v1, int[] c2, char v2) {
        char[][] t = new char[N_ROW][N_COL];
        for (int r = 0; r < N_ROW; r++) System.arraycopy(b[r], 0, t[r], 0, N_COL);
        t[c1[0]][c1[1]] = v1;
        if (c2 != null) t[c2[0]][c2[1]] = v2;
        return toFen(t, 'w');
    }

    private static String countOver(Map<Character, Integer> cnt) {
        for (int i = 0; i < UP_PIECE.length(); i++) {
            Integer a = cnt.get(UP_PIECE.charAt(i));
            if (a != null && a > LIM_PIECE[i]) return "红方 " + UP_PIECE.charAt(i) + " 多子(" + a + ")";
            Integer b = cnt.get(LOW_PIECE.charAt(i));
            if (b != null && b > LIM_PIECE[i]) return "黑方 " + LOW_PIECE.charAt(i) + " 多子(" + b + ")";
        }
        return null;
    }
    private static int[] find(char[][] b, char p) {
        for (int r = 0; r < N_ROW; r++)
            for (int c = 0; c < N_COL; c++)
                if (b[r][c] == p) return new int[]{r, c};
        return new int[]{-1, -1};
    }
    /**
     * 比较两个局面，判断「谁刚走了一步」。
     * 用于连线模式自动识别轮次：不需要读 FEN 的走子方字段，
     * 直接看两帧画面之间是哪一方的棋子动了。
     *
     * @return 'w' 红方走了，'b' 黑方走了，0 判断不出
     */
    public static char whoMoved(char[][] prev, char[][] cur) {
        if (prev == null || cur == null) return 0;
        int redA = 0, redB = 0, blackA = 0, blackB = 0;
        int redDiff = 0, blackDiff = 0;
        for (int r = 0; r < N_ROW; r++) {
            for (int c = 0; c < N_COL; c++) {
                char p = prev[r][c], q = cur[r][c];
                boolean pu = p != 0 && Character.isUpperCase(p);
                boolean pl = p != 0 && Character.isLowerCase(p);
                boolean qu = q != 0 && Character.isUpperCase(q);
                boolean ql = q != 0 && Character.isLowerCase(q);
                if (pu) redA++;
                if (pl) blackA++;
                if (qu) redB++;
                if (ql) blackB++;
                if (p != q) {
                    if (pu || qu) redDiff++;
                    if (pl || ql) blackDiff++;
                }
            }
        }
        int dRed = redB - redA, dBlack = blackB - blackA;
        // 吃子情形：被吃的颜色减少
        if (dBlack < 0 && dRed >= 0) return 'w';   // 黑子少了 → 红吃的 → 红走
        if (dRed < 0 && dBlack >= 0) return 'b';   // 红子少了 → 黑吃的 → 黑走
        // 无吃子：看哪一方位置变了
        if (redDiff > 0 && blackDiff == 0) return 'w';
        if (blackDiff > 0 && redDiff == 0) return 'b';
        if (dBlack < 0) return 'w';
        if (dRed < 0) return 'b';
        return 0;
    }
    /** 从 FEN 取棋盘部分（不含走子方等字段）。 */
    /** 统计棋子总数与将帅个数（75='K'红帅, 107='k'黑将），用于判断开局态与终局。 */
    public static int[] countAndKings(String placement) {
        char[][] b = fromFen(placement);
        int n = 0, k = 0;
        for (int r = 0; r < N_ROW; r++) {
            for (int c = 0; c < N_COL; c++) {
                char p = b[r][c];
                if (p == 0) continue;
                n++;
                if (p == 75 || p == 107) k++;
            }
        }
        return new int[]{n, k};
    }

    public static String placeOf(String fen) {
        if (fen == null) return "";
        String s = fen.trim();
        int sp = s.indexOf(' ');
        return sp > 0 ? s.substring(0, sp) : s;
    }
    /** 在局面上执行一步 UCI 着法，返回执行后的局面（FEN 前半）。用于核对落子到底有没有生效。 */
    public static String applyUci(String placement, String uci) {
        if (placement == null || uci == null || uci.length() < 4) return placement;
        try {
            char[][] b = fromFen(placement);
            int c1 = COLS.indexOf(uci.charAt(0));
            int r1 = rowOfRank(uci.charAt(1) - '0');
            int c2 = COLS.indexOf(uci.charAt(2));
            int r2 = rowOfRank(uci.charAt(3) - '0');
            if (c1 < 0 || c2 < 0 || r1 < 0 || r1 > 9 || r2 < 0 || r2 > 9) return placement;
            b[r2][c2] = b[r1][c1];
            b[r1][c1] = 0;
            return placeOf(toFen(b, 'w'));
        } catch (Throwable t) {
            return placement;
        }
    }
    /**
     * cur 是否恰好是 prev 走一步（含吃子）的结果。
     * 真实对局两帧之间最多变一步。识别把红车认成黑车、或凭空吃掉两个马时，
     * 差分就不止一步 —— 这种情况宁可用上一帧局面，也不能把错局面喂给引擎。
     */
    public static boolean singleMoveOk(String prev, String cur) {
        if (prev == null || cur == null) return true;
        if (prev.equals(cur)) return true;
        char[][] a = fromFen(prev), b = fromFen(cur);
        int[] rr = new int[4], cc = new int[4];
        char[] pa = new char[4], pb = new char[4];
        int n = 0;
        for (int r = 0; r < N_ROW; r++) {
            for (int c = 0; c < N_COL; c++) {
                if (a[r][c] == b[r][c]) continue;
                if (n >= 4) return false;
                rr[n] = r; cc[n] = c; pa[n] = a[r][c]; pb[n] = b[r][c];
                n++;
            }
        }
        if (n == 0) return true;
        if (n != 2) return false;      // 一格变 / 三格以上变，都不是一步棋
        int src = -1, dst = -1;
        for (int i = 0; i < 2; i++) {
            if (pb[i] == 0) src = i; else dst = i;
        }
        if (src < 0 || dst < 0) return false;
        if (pa[src] == 0) return false;
        if (pb[dst] != pa[src]) return false;   // 落点上的子必须就是走过来的那个子
        char mv = pa[src];
        if ((mv == 'P' || mv == 'p') && cc[src] != cc[dst]) {
            // ★ v4.60：兵/卒**过河后可以横走**，原来一律 return false。
            //   后果有两个（都已实测到）：
            //     ① 对手的卒横移被判成「识别抖动」，要等 3 帧才被采纳；
            //     ② 「多子纠错」永远采纳不了「落点被读成别的子」这种修正 ——
            //        实测 13:45:25 卒3平4 之后，落点被读成黑车 → 卡死 90 秒。
            //   按真实规则放开：红兵过河 = row<=4，黑卒过河 = row>=5，且只许走一列。
            //   （棋盘内部 row0 = 黑方底线；红兵初始 row6、黑卒初始 row3。）
            boolean crossed = (mv == 'P') ? (rr[src] <= 4) : (rr[src] >= 5);
            if (!crossed) return false;
            if (Math.abs(cc[src] - cc[dst]) != 1) return false;
        }
        return true;
    }
    /** UCI 着法 -> 中文描述（近似）。 */
    /** UCI rank(0=红方底线) ↔ 内部 row(0=黑方底线) 互换。
     *  坑：两者上下颠倒。把 rank 直接当 row 用，会把红兵认成黑卒，
     *      更会把落子点算到对面 —— 点了等于没点，表现为「不会自动走子」。 */
    /** 取某格棋子；空返回 '.'。 */
    public static char at(String placement, int row, int col) {
        try { char c = fromFen(placement)[row][col]; return (c == 0) ? '.' : c; } catch (Throwable t) { return '.'; }
    }

    /**
     * 落子核对：now 是否等于「prev 走完 uci」。
     *
     * 完全一致 → true；否则退一步，只核对我方那一步的起点 / 终点两格。
     * 底行（帅仕那排）识别偶尔漂一行，不该因此把已经落下的子判成没落，
     * 更不该因此把整条连线停掉。
     */
    public static boolean moveApplied(String prev, String uci, String now) {
        if (prev == null || uci == null || now == null) return false;
        try {
            String want = applyUci(prev, uci);
            if (want != null && want.equals(now)) return true;
            if (uci.length() < 4) return false;
            int c1 = COLS.indexOf(uci.charAt(0)), c2 = COLS.indexOf(uci.charAt(2));
            int r1 = rowOfRank(uci.charAt(1) - '0'), r2 = rowOfRank(uci.charAt(3) - '0');
            if (c1 < 0 || c2 < 0) return false;
            char[][] a = fromFen(prev), b = fromFen(now);
            char moving = a[r1][c1];
            if (moving == 0 || moving == '.' || moving == 'x') return false;
            if (b[r1][c1] == moving) return false;   // 起点还是原样 -> 没动
            if (b[r2][c2] != moving) return false;   // 终点没站着我的子 -> 没落到
            return true;
        } catch (Throwable t) { return false; }
    }

    // ---------------- 变化解释（把「拒绝」改成「修复/忽略」）----------------

    // ---------------- 轮次推导（纯函数，单测可覆盖）----------------

    public static final int T_INIT = 0, T_MY_TURN = 1, T_MOVING = 2, T_WAIT = 3;

    /**
     * 「局面变了」之后，现在轮到谁。
     *
     * ⚠ 关键：mover 判不出来（=0，通常是漏拍了两手）时，**绝不能复用上一次的
     *    linkMover**。残留值会让状态永久停在 WAIT，表现成
     *    「对方明明走子了，却一直显示等对手走棋」，只能靠重新连线才恢复。
     *    这里改成状态机前向推断：
     *      · 还在等对手（WAIT）时局面变了 → 我的落子只在 MOVING 里处理，
     *        所以这必然是「对手走完了」→ 轮到我
     *      · 初始（INIT）→ 按我执哪边定（红先走）
     *      · 待出招（MY_TURN）→ 保持
     */
    public static int nextTurn(int prevState, char mover, char mySide, boolean atStart) {
        // 等对手期间，任何被采纳的盘面变化 ⇒ 只可能是对手走完了。
        // 我自己的落子只在 MOVING 分支处理，WAIT 里绝不会有我的子动。
        // （这里若还去看 mover，一旦 whoMoved 判反就会永久卡在「等对手走棋」。）
        if (prevState == T_WAIT) return T_MY_TURN;
        // 待出招时盘面变化：我的子动了 = 我那步被延迟识别到了 → 轮到对手
        if (prevState == T_MY_TURN) {
            if (mover == 0) return T_MY_TURN;          // 判不出，保守停住（下一步会核对落子）
            return (mover == mySide) ? T_WAIT : T_MY_TURN;
        }
        if (prevState == T_INIT) {
            if (mover != 0) return (mover == mySide) ? T_WAIT : T_MY_TURN;
            // 首帧且判不出谁走的：
            //   标准开局 → 红先走，执黑就该等
            //   盘面已动过（中途接入）→ 按「轮到我」处理
            // 后者是必须的：执黑中途连线时若判成「等对手」，红方在等我走、我在等红方走，
            // 双方都不动 → 永久卡死在「等对手走棋」。宁可先当作轮到我，猜错也只是
            // 一次无效点击，接上红方下一手就会自愈。
            if (atStart) return (mySide == 'w') ? T_MY_TURN : T_WAIT;
            return T_MY_TURN;
        }
        return prevState;
    }

    public static final int EXPL_NOISE = 0;   // 识别抖动：保持基线，忽略本帧
    public static final int EXPL_ONE = 1;     // 一步合法棋
    public static final int EXPL_TWO = 2;     // 两步合法棋（轮询漏了一拍）
    public static final int EXPL_UNKNOWN = 3; // 无法解释

    /** 取两盘面的差异格（最多 8 个）。 */
    private static int[][] diffCells(char[][] a, char[][] b) {
        int[][] d = new int[6][2];
        int n = 0;
        for (int r = 0; r < N_ROW; r++)
            for (int c = 0; c < N_COL; c++)
                if (a[r][c] != b[r][c]) {
                    if (n >= 6) return null;
                    d[n][0] = r; d[n][1] = c; n++;
                }
        int[][] o = new int[n][2];
        for (int i = 0; i < n; i++) { o[i][0] = d[i][0]; o[i][1] = d[i][1]; }
        return o;
    }

    /** 把 src 格的子搬到 dst 格，返回新局面。 */
    private static String applyMoveCells(String placement, int[] src, int[] dst) {
        try {
            char[][] b = fromFen(placement);
            char mv = b[src[0]][src[1]];
            if (mv == 0) return null;
            b[src[0]][src[1]] = 0;
            b[dst[0]][dst[1]] = mv;
            return placeOf(toFen(b, 'w'));
        } catch (Throwable t) { return null; }
    }

    /** cur 是否是 prev 走两步的结果（轮询漏了一拍时会出现）。 */
    public static boolean twoMovesOk(String prev, String cur) {
        if (prev == null || cur == null) return false;
        try {
            char[][] a = fromFen(prev), b = fromFen(cur);
            int[][] d = diffCells(a, b);
            if (d == null) return false;
            if (d.length != 4) return false;
            int[][] combos = {{0, 1, 2, 3}, {0, 2, 1, 3}, {0, 3, 1, 2}};
            for (int i = 0; i < combos.length; i++) {
                int[] q = combos[i];
                String m1 = applyMoveCells(prev, d[q[0]], d[q[1]]);
                if (m1 != null && singleMoveOk(prev, m1) && singleMoveOk(m1, cur)) return true;
                String m2 = applyMoveCells(prev, d[q[2]], d[q[3]]);
                if (m2 != null && singleMoveOk(prev, m2) && singleMoveOk(m2, cur)) return true;
            }
            return false;
        } catch (Throwable t) { return false; }
    }

    /**
     * 解释 cur 相对 prev 的变化。真实对局一步最多动 2 格（起点变空 + 终点落子）。
     *   1 格变 -> 棋子凭空多/少，必然是识别抖动
     *   3 格变 -> 一步棋 + 一格噪声
     *   2 格变但子种/颜色不符、兵卒横移、象士出界 -> 识别抖动
     *   4 格变且能拆成两步合法棋 -> 轮询漏了一拍
     * 只有明确解释不通的（>=5 格、或将帅凭空多出）才交给外层丢帧。
     */
    public static int explainChange(String prev, String cur) {
        if (prev == null || cur == null) return EXPL_UNKNOWN;
        if (prev.equals(cur)) return EXPL_ONE;
        try {
            char[][] a = fromFen(prev), b = fromFen(cur);
            int[][] dc = diffCells(a, b);
            if (dc == null) return EXPL_UNKNOWN;
            int n = dc.length;
            if (n == 0) return EXPL_ONE;
            if (n == 1) return EXPL_NOISE;
            if (n == 2) return singleMoveOk(prev, cur) ? EXPL_ONE : EXPL_NOISE;
            if (n == 3) return EXPL_NOISE;
            if (n == 4) return twoMovesOk(prev, cur) ? EXPL_TWO : EXPL_UNKNOWN;
            return EXPL_UNKNOWN;
        } catch (Throwable t) { return EXPL_UNKNOWN; }
    }

    public static int rowOfRank(int rank) { return N_ROW - 1 - rank; }
    private static final char[] NUM_CN = {'一','二','三','四','五','六','七','八','九'};

    /**
     * 标准中文着法（红用中文数字、黑用阿拉伯数字），例如「兵七进一」「卒3进1」「车一平二」。
     *
     * 记谱规则：
     *   · 红方线号从红方右手边起数 1..9，屏幕上看就是 col 8 → 一、col 0 → 九
     *   · 黑方线号从黑方右手边起数 1..9，屏幕上看就是 col 0 → 1、col 8 → 9
     *   · 车/炮/兵/帅/将 走直线：进/退后面跟「走了几格」
     *   · 马/相/象/仕/士 走斜线：进/退后面跟「目标线号」
     *   · 同一线有两个同种子时用「前/后」区分
     */
    public static String uciToCnStd(String uci, String fen) {
        if (uci == null || uci.length() < 4) return uci;
        char[][] b = fromFen(fen);
        try {
            int c1 = COLS.indexOf(uci.charAt(0)), r1 = uci.charAt(1) - '0';
            int c2 = COLS.indexOf(uci.charAt(2)), r2 = uci.charAt(3) - '0';
            int R1 = rowOfRank(r1), R2 = rowOfRank(r2);
            if (c1 < 0 || c2 < 0 || R1 < 0 || R2 < 0) return uci;
            char p = b[R1][c1];
            if (p == 0) return uci;
            boolean red = Character.isUpperCase(p);
            char low = Character.toLowerCase(p);
            String nm = NAME.containsKey(p) ? NAME.get(p) : String.valueOf(p);

            // 起始线号（按该方视角）
            int fileFrom = red ? (9 - c1) : (c1 + 1);
            int fileTo = red ? (9 - c2) : (c2 + 1);
            String sFrom = num(fileFrom, red);
            String sTo = num(fileTo, red);

            // 前/后：同一线上有两个同种子
            int sameFile = 0;
            for (int r = 0; r < N_ROW; r++) {
                if (r != R1 && b[r][c1] == p) sameFile++;
            }
            String prefix = "";
            if (sameFile == 1) {
                // 找出另一个同类子的行号，判断自己在前还是后
                int other = -1;
                for (int r = 0; r < N_ROW; r++) {
                    if (r != R1 && b[r][c1] == p) { other = r; break; }
                }
                boolean iAmFront = red ? (R1 < other) : (R1 > other);   // 越靠对方 = 前
                prefix = iAmFront ? "前" : "后";
            }

            String act;
            if (R2 == R1) {
                act = "平";
                return prefix + nm + sFrom + act + sTo;
            }
            boolean forward = red ? (R2 < R1) : (R2 > R1);
            String verb = forward ? "进" : "退";
            // 斜行子（马/相/象/仕/士）用目标线号，直线子用格数
            boolean diagonal = (low == 'n' || low == 'b' || low == 'a');
            String numPart = diagonal ? sTo : num(Math.abs(r2 - r1), red);
            return prefix + nm + sFrom + verb + numPart;
        } catch (Throwable t) {
            return uci;
        }
    }

    private static String num(int n, boolean red) {
        if (n < 1) n = 1;
        if (n > 9) n = 9;
        return red ? String.valueOf(NUM_CN[n - 1]) : String.valueOf(n);
    }
    public static int rankOfRow(int row) { return N_ROW - 1 - row; }
    public static String uciToCn(String uci, String fen) {
        if (uci == null || uci.length() < 4) return uci;
        char[][] b = fromFen(fen);
        try {
            int c1 = COLS.indexOf(uci.charAt(0)), r1 = uci.charAt(1) - '0';
            int c2 = COLS.indexOf(uci.charAt(2)), r2 = uci.charAt(3) - '0';
            int R1 = rowOfRank(r1), R2 = rowOfRank(r2);
            char p = b[R1][c1];
            String nm = NAME.containsKey(p) ? NAME.get(p) : String.valueOf(p);
            boolean up = Character.isUpperCase(p);
            String act = (up ? (R2 < R1) : (R2 > R1)) ? "进"
                       : ((up ? (R2 > R1) : (R2 < R1)) ? "退" : "平");
            return nm + " " + (c1 + 1) + "-" + (r1 + 1) + " " + act + " " + (c2 + 1) + "-" + (r2 + 1);
        } catch (Exception e) {
            return uci;
        }
    }
}
