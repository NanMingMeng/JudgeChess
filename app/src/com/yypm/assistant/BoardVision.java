package com.yypm.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.Log;

import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * 端侧棋盘识别（ONNX）。
 *  1) board_pose.onnx       找棋盘 4 个外角（SimCC 输出：simcc_x / simcc_y，各 [1,4,512]）
 *  2) 透视拉正成 450x500
 *  3) board_classifier.onnx 认 90 个交叉点（16 类，输出 [1,90,16]）
 */
public class BoardVision {

    private static final String TAG = "YYPM";

    private static final float[] MEAN = {123.675f, 116.28f, 103.53f};
    private static final float[] STD = {58.395f, 57.12f, 57.375f};

    private static final int BOARD_W = 450, BOARD_H = 500, PAD = 50;
    // 网格吸附：默认关闭。角点正确时，均匀网格本身就是精确的；
    // 开启会在棋子边缘有强对比时吸到错误位置（实测吸成 dy=40.89 vs 正确 44.44）。
    // 留作角点轻微偏差时的可选修正手段。
    private static final boolean USE_SNAP = false;
    private static final int N_V = 9, N_H = 10;

    public static final String LABELS = "point,other,red_king,red_advisor,red_bishop,red_knight,red_rook,red_cannon,red_pawn,black_king,black_advisor,black_bishop,black_knight,black_rook,black_cannon,black_pawn";
    public static final char[] SHORT = {'.','x','K','A','B','N','R','C','P','k','a','b','n','r','c','p'};

    public static class Result {
        public boolean ok;
        public String message = "";
        public char[][] chars;
        public float[][] conf;
        public float[] corners;          // 8 个 float: x1,y1,...,x4,y4
        public float[][] points;         // 10x9 屏幕坐标
        public String fen = "";
        public String direction;
        public boolean gridOk = true;    // 格点是否合理（不合理=识别不可信）
        public float gridScore = 0f;    // 网格线周期强度：判断「这是不是棋盘」
        public int minVote = 1;         // 每格众数的最小票数（判断这一帧是否可信）
        public float minConf;
        public String method = "";
    }

    private OrtEnvironment env;
    private OrtSession poseSess, clsSess;
    private final Object lock = new Object();
    private volatile boolean loaded = false;
    private static int snapLog = 0;
    private static int diagLog = 0;
    private static int cornerLog = 0;
    private static final int VOTE_K = 3;                        // 投票窗口
    private final char[][][] voteHist = new char[N_H][N_V][VOTE_K];
    private int votePos = 0;
    private static int voteLog = 0;
    /** 最近一次拉正的棋盘图（模型真正看到的东西），供诊断存盘。 */
    private static volatile Bitmap lastWarp;
    public static Bitmap warpOf() { return lastWarp; }

    public boolean isLoaded() { return loaded; }

    /** 测试钩子：只跑几何定位（不加载 ONNX 模型），用于离线验证。 */
    public float[] gridProbe(Bitmap b) { return findGridByLines(b); }
    /**
     * 把识别结果规范到「红在下方」的标准朝向，再做合法性校验。
     *
     * ⚠ 执黑时屏幕是红在上（上下颠倒），原始 FEN 直接喂 Board.validate 会把
     *    完全合法的开局判成「红仕不在九宫 / 红相位置非法」，于是几何定位每帧都被
     *    否掉、退化到姿态模型乱猜，表现成「第二把开局就识别不到任何东西」。
     *    校验的是「这局面合不合法」，跟屏幕怎么摆毫无关系，必须先归一化再判。
     */
    private static String normFen(char[][] ch, String fen) {
        try {
            if ("flipped".equals(directionOf(ch))) return Board.rotate180(fen, 'w');
        } catch (Throwable ignored) {}
        return fen;
    }


    /**
     * 多帧时间投票：每格取最近 VOTE_K 帧的众数。
     *
     * 为什么必须做：分类器是单帧独立的，偶发误判（凭空多出一个仕、帅跑到九宫外）
     * 会直接变成错盘面，喂给引擎就会下出在真实盘面上等于送子的棋 —— 这正是
     * 「打不过人机」的根因。真实对局每帧都一样，噪声帧会被历史投票压掉。
     * 代价是认到一个新着法要等 2 帧（约 1.4 秒），这个延迟可以接受。
     */
    private void applyVote(Result r) {
        int minVote = Integer.MAX_VALUE;
        boolean corrected = false;
        for (int rr = 0; rr < N_H; rr++) {
            for (int cc = 0; cc < N_V; cc++) {
                char cur = r.chars[rr][cc];
                voteHist[rr][cc][votePos] = cur;
                int bestN = 0;
                char bestC = cur;
                for (int i = 0; i < VOTE_K; i++) {
                    char v = voteHist[rr][cc][i];
                    if (v == 0) continue;
                    int n = 0;
                    for (int j = 0; j < VOTE_K; j++)
                        if (voteHist[rr][cc][j] == v) n++;
                    // 票数多者胜；并列时优先当前帧
                    if (n > bestN || (n == bestN && v == cur)) { bestN = n; bestC = v; }
                }
                if (bestC != cur) {
                    corrected = true;
                    r.conf[rr][cc] *= 0.7f;        // 被历史纠正过，置信度打折
                }
                r.chars[rr][cc] = bestC;
                if (bestN < minVote) minVote = bestN;
            }
        }
        votePos = (votePos + 1) % VOTE_K;
        // 投票改了 chars，FEN 和方向必须跟着重算
        r.fen = fenOf(r.chars);
        r.direction = directionOf(r.chars);
        r.minConf = 1f;
        for (int rr = 0; rr < N_H; rr++)
            for (int cc = 0; cc < N_V; cc++)
                if (r.conf[rr][cc] < r.minConf) r.minConf = r.conf[rr][cc];
        r.minVote = (minVote == Integer.MAX_VALUE) ? 1 : minVote;
        if (corrected && ++voteLog % 10 == 1)
            Log.i(TAG, "投票纠正了单帧误判，本帧最小票数=" + r.minVote);
    }

    /** 清空投票历史（重新锁定时调用）。 */
    private void resetVote() {
        for (int rr = 0; rr < N_H; rr++)
            for (int cc = 0; cc < N_V; cc++)
                for (int k = 0; k < VOTE_K; k++)
                    voteHist[rr][cc][k] = 0;
        votePos = 0;
    }

    /** 解锁棋盘位置（下一帧重新检测）。连续多帧对不上时由调用方调用。 */
    /** 解锁棋盘位置（下一帧重新检测）。连续多帧对不上时由调用方调用。
     *  ★ 故意不清 prevCorners：它是「棋盘一局之内不会移动」这条先验的载体，
     *    几何法正是靠它把「整体错整数个格距」的相位纠正回来。 */
    public void resetLock() {
        resetVote();
        synchronized (lock) { lastGoodCorners = null; }
    }

    public void load(Context ctx, String posePath, String clsPath) throws Exception {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions o1 = new OrtSession.SessionOptions();
        o1.setIntraOpNumThreads(Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() / 2)));
        poseSess = env.createSession(posePath, o1);
        OrtSession.SessionOptions o2 = new OrtSession.SessionOptions();
        o2.setIntraOpNumThreads(Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() / 2)));
        clsSess = env.createSession(clsPath, o2);
        loaded = true;
    }


    /**
     * 主入口：整屏位图 -> 识别结果。
     *
     * ★ 关键改动：棋盘在一局棋里根本不会动，所以角点一律锁定复用。
     *   以前每帧都重跑姿态模型，角点一抖，识别采样和落子点位就一起崩 ——
     *   表现为「漏子」「点飞」「主界面棋盘跳一下」。鲨鱼象棋之所以稳，
     *   就是因为它的棋盘位置是锁住的。
     *   只有锁定角点真的失效（校验不过）时才重新检测一次。
     */
    /**
     * 主入口：整屏位图 -> 识别结果。
     *
     * ★ 关键改动：棋盘在一局棋里根本不会动，所以角点一律锁定复用。
     *   以前每帧都重跑姿态模型，角点一抖，识别采样和落子点位就一起崩 ——
     *   表现为「漏子」「点飞」「主界面棋盘跳一下」。鲨鱼象棋之所以稳，
     *   就是因为它的棋盘位置是锁住的。
     *   只有锁定角点真的失效（校验不过）时才重新检测一次。
     *
     * ★ v4.58：失锁后的重定位大改，解决「丢一次锁就再也回不来」——
     *   实测（12:16~12:24）丢锁后每帧 7~9 秒、连续 8 分钟一帧都没认出来：
     *     ① 先用**上次锁定的角点**就地复核一次（1 次分类，几百毫秒）；
     *     ② 几何法路径不变（它便宜）；
     *     ③ 最后才是姿态模型扫候选 —— 但有先验时只扫**上次棋盘附近**的一小撮框，
     *        且有「够好就停」和 2.5 秒总预算，不再无脑跑满 72 个候选。
     *   同时把每一帧的失败原因记进 lastWhy()，交给调用方写进 app.log
     *   （以前只写 logcat，几分钟就被冲掉，事后查不下去）。
     */
    public Result detect(Bitmap frame) {
        int W = frame.getWidth(), H = frame.getHeight();
        lastWhy = "";
        synchronized (lock) {
            // ---- 快速路径：用锁定的角点直接认 ----
            if (lastGoodCorners != null) {
                try {
                    Result r = classify(frame, lastGoodCorners);
                    if (r != null && gridSane(r, W, H)) {
                        applyVote(r);
                        r.corners = lastGoodCorners;
                        r.gridOk = true;
                        prevCorners = lastGoodCorners;
                        return r;
                    }
                } catch (Exception ignored) {}
                Log.w(TAG, "锁定角点失效，重新检测棋盘");
                lastWhy = "锁定角点失效";
                lastGoodCorners = null;
            }

            // ---- ★ v4.58：用「上次锁定过的角点」就地复核一次 ----
            //   棋盘一局之内不会动，所以绝大多数「失锁」只是某一帧被挡住 / 抖动了一下，
            //   角点本身还是对的。一次分类（几百毫秒）就能确认，
            //   完全没必要掉进全屏扫描（3~8 秒）。
            if (prevCorners != null) {
                try {
                    Result r = classify(frame, prevCorners);
                    if (r != null && gridSane(r, W, H)) {
                        float cv = coverage(frame, r);
                        if (cv < 0f || cv >= COV_MIN) {
                            resetVote();
                            applyVote(r);
                            r.corners = prevCorners;
                            r.gridOk = true;
                            lastGoodCorners = prevCorners;
                            Log.i(TAG, String.format(java.util.Locale.US,
                                    "原角点复核通过，直接复用（覆盖=%.2f）", cv));
                            return r;
                        }
                        lastWhy = String.format(java.util.Locale.US, "原角点覆盖不足 %.2f", cv);
                    } else {
                        lastWhy = "原角点复核不过";
                    }
                } catch (Throwable ignored) {}
            }

            // ---- 第二级：几何法（找浅色矩形 + 拟合网格线）----
            //
            // ★ V3.0 根治「相位整体错一格」
            //   网格线是等距的，所以相位搜索分不清 y0 与 y0+一个格距 ——
            //   两者都恰好落在线上，评分几乎一样。本机实测出现过整整错一行的锁定：
            //       正确 tl=(89,799)  →  错误 tl=(89,667)    （差 132px = 恰好一个 dy）
            //   错行之后采样点全落在两格之间：识别全废；而每帧重新检测又会算出
            //   同一个错相位 —— 于是永远「坐标与画面不符」，只能反复重新识别，
            //   却永远起不来。日志里就是这个样子。
            //
            //   两手一起上（缺一不可）：
            //     ① 先验吸附：棋盘一局之内不会动。把候选按上次锁定的锚点重新锚一次，
            //        作为第二候选 —— 专门捞「整体错整数个格距」这一种。
            //     ② 覆盖度闸门：候选必须通过「识别说有子的格子，映射回屏幕真的
            //        坐着亮块」才允许锁定。过不了就本帧不锁（宁可不认，绝不锁错的）。
            //   只做①治不了「首次进游戏还没有先验」；只做②会陷入
            //   「每帧都算出同一个错相位、又被否掉」的空转。所以必须都有。
            try {
                float[] gc = findGridByLines(frame);
                if (gc != null) {
                    float[] anchored = anchorToPrev(gc);
                    float[] pick = null;
                    Result pickR = null;
                    float pickCov = -1f;
                    int tried = 0;
                    for (int i = 0; i < 2; i++) {
                        float[] c = (i == 0) ? gc : anchored;
                        if (c == null) continue;
                        String geo = geometryOk(c, W, H);
                        if (geo != null) {
                            Log.w(TAG, "几何法角点被几何校验拒: " + geo);
                            lastWhy = "几何法被拒:" + geo;
                            continue;
                        }
                        tried++;
                        Result r;
                        try { r = classify(frame, c); } catch (Throwable t) { r = null; }
                        if (r == null || !gridSane(r, W, H)) continue;
                        float cov = coverage(frame, r);
                        if (cov < 0f) cov = 1f;           // 样本太少 → 无法判定，视为通过
                        if (pickR == null || cov > pickCov) { pickR = r; pick = c; pickCov = cov; }
                        if (cov >= COV_MIN) break;        // 够了就不再试另一个，省一次识别
                    }
                    if (pickR != null && pickCov >= COV_MIN
                            && Board.validate(normFen(pickR.chars, pickR.fen)) == null) {
                        resetVote();
                        applyVote(pickR);
                        pickR.corners = pick;
                        pickR.gridOk = true;
                        lastGoodCorners = pick;
                        prevCorners = pick;
                        Log.i(TAG, String.format(java.util.Locale.US,
                                "几何法命中并锁定（候选%d 覆盖=%.2f）: %s",
                                tried, pickCov, pickR.fen));
                        return pickR;
                    }
                    if (pickR != null) {
                        String why = Board.validate(normFen(pickR.chars, pickR.fen));
                        Log.w(TAG, String.format(java.util.Locale.US,
                                "几何法网格不合格（候选%d 最高覆盖=%.2f 需>=%.2f）%s，本帧不锁定",
                                tried, pickCov, COV_MIN, why == null ? "" : (" 认字=" + why)));
                        lastWhy = String.format(java.util.Locale.US,
                                "几何法覆盖不足 %.2f", pickCov);
                    } else if (lastWhy.length() == 0) {
                        lastWhy = "几何法无可用候选";
                    }
                } else if (lastWhy.length() == 0) {
                    lastWhy = "几何法未找到网格";
                }
            } catch (Throwable t) {
                Log.w(TAG, "几何法异常", t);
                lastWhy = "几何法异常:" + t.getClass().getSimpleName();
            }

            // ---- 慢速路径：跑姿态模型找角点 ----
            //   ★ v4.58：加三道闸门，把单帧成本从 3~8 秒压到 1 秒级 ——
            //     ① 有先验（prevCorners）时只扫「上次棋盘附近」的一小撮框；
            //     ② 「够好就停」：姿态模型有把握 + 网格合理就不再扫剩下的；
            //     ③ 总预算 2.5 秒，超了就收工（结果照旧走合法性/覆盖度闸门）。
            //   原来这里没有任何提前退出：72 个候选每个都跑一次姿态模型（256²），
            //   通过几何校验的还要再跑一次分类（280×315）—— 这就是那 8 秒。
            Result best = null;
            float bestScore = -1e9f;
            // 连续几次「附近」都没找到 → 退回全屏扫（棋盘真的换了位置，比如新开一局）
            final boolean near = (prevCorners != null) && nearFailStreak < 3;
            int[][] cands = near ? candidatesNear(prevCorners, W, H) : candidates(W, H);
            long tStart = System.currentTimeMillis();
            StringBuilder dbg = new StringBuilder();
            int nOk = 0, nGeo = 0, nCls = 0;
            boolean budgetOut = false;
            for (int[] bb : cands) {
                try {
                    if (System.currentTimeMillis() - tStart > SEARCH_BUDGET_MS) {
                        budgetOut = true;
                        if (dbg.length() < 900) dbg.append("超预算;");
                        break;
                    }
                    float[] corners = findCorners(frame, bb);
                    if (corners == null) { nCls++; if (dbg.length() < 900) dbg.append("C").append(";"); continue; }
                    String geo = geometryOk(corners, W, H);
                    if (geo != null) { nGeo++; if (dbg.length() < 900) dbg.append(geo).append(";"); continue; }
                    float score = cornerScore;
                    Result r = classify(frame, corners);
                    if (r == null) { nCls++; if (dbg.length() < 900) dbg.append("K").append(";"); continue; }
                    nOk++;
                    if (score > bestScore) { bestScore = score; r.corners = corners; best = r; }
                    // ★ 够好就停：有把握 + 网格合理，不必再扫剩下的候选
                    if (score >= GOOD_ENOUGH && gridSane(r, W, H)) {
                        if (dbg.length() < 900) dbg.append("命中;");
                        break;
                    }
                } catch (Exception e) {
                    nCls++;
                    if (dbg.length() < 900) dbg.append(e.getClass().getSimpleName()).append(";");
                }
            }
            if (++diagLog % 10 == 1 || best == null) {
                Log.i(TAG, "检测: 候选=" + cands.length + " 附近=" + near + " 几何过=" + nOk
                        + " 几何拒=" + nGeo + " 识别失败=" + nCls
                        + (budgetOut ? " 超预算" : "")
                        + (best != null ? " 最高分=" + String.format(java.util.Locale.US, "%.2f", bestScore) : " 无结果")
                        + " 耗时=" + (System.currentTimeMillis() - tStart) + "ms | " + dbg);
            }
            if (best == null) {
                nearFailStreak++;
                Result r = new Result();
                r.ok = false;
                r.message = "未找到棋盘";
                lastWhy = "扫完无候选(" + cands.length + "框 几何过" + nOk
                        + " 识别失败" + nCls + (budgetOut ? " 超预算" : "")
                        + " " + (System.currentTimeMillis() - tStart) + "ms)";
                return r;
            }
            boolean sane = gridSane(best, W, H);
            if (!sane) {
                float hh = Math.abs(best.points[N_H - 1][1] - best.points[0][1]);
                float ww = Math.abs(best.points[0][(N_V - 1) * 2] - best.points[0][0]);
                Log.w(TAG, String.format(java.util.Locale.US,
                        "格点校验不过 boardH=%.0f(需>%.0f) boardW=%.0f(需>%.0f)",
                        hh, H * 0.30f, ww, W * 0.30f));
                lastWhy = String.format(java.util.Locale.US,
                        "格点异常 boardH=%.0f boardW=%.0f(需>%.0f/%.0f)", hh, ww,
                        H * 0.30f, W * 0.30f);
            }
            // 先投票、再判合法性：投票会纠正单帧误判，判的应当是纠正后的局面。
            resetVote();          // 换了网格，旧的逐格历史不再有意义
            applyVote(best);      // 本帧作为历史的第一票
            String bad = Board.validate(normFen(best.chars, best.fen));
            boolean legal = (bad == null);
            float pcov = coverage(frame, best);
            boolean covOk = (pcov < 0f) || (pcov >= COV_MIN);
            if (sane && legal && covOk) {
                lastGoodCorners = best.corners;   // 锁定，后续帧直接复用
                prevCorners = best.corners;
                best.gridOk = true;
                lastWhy = "";
                nearFailStreak = 0;
            } else {
                nearFailStreak++;
                if (!covOk) Log.w(TAG, String.format(java.util.Locale.US,
                        "姿态模型网格覆盖不足（%.2f 需>=%.2f），不锁定", pcov, COV_MIN));
                best.gridOk = false;
                if (!legal) lastWhy = "局面不合法:" + bad;
                else if (!covOk) lastWhy = String.format(java.util.Locale.US,
                        "覆盖不足 %.2f(需>=%.2f)", pcov, COV_MIN);
            }
            return best;
        }
    }

    /**
     * 把候选网格按「上次锁定的锚点」重新锚一次。
     *
     * 网格线等距，相位搜索分不清 y0 与 y0+一个格距；而棋盘一局之内不会移动，
     * 所以「上次锁定过的那一行」是极强的先验。只在
     *   · 间距一致（同一个棋盘尺度）
     *   · 锚点偏移接近整数个格距（这才是「整体错行/错列」的特征）
     * 时才给出吸附候选；否则说明棋盘真的换了位置，不做干预、返回 null。
     */
    private float[] anchorToPrev(float[] gc) {
        if (prevCorners == null || gc == null) return null;
        try {
            float dx = (gc[2] - gc[0]) / 8f, dy = (gc[5] - gc[1]) / 9f;
            float pdx = (prevCorners[2] - prevCorners[0]) / 8f;
            float pdy = (prevCorners[5] - prevCorners[1]) / 9f;
            if (dx <= 2 || dy <= 2 || pdx <= 2 || pdy <= 2) return null;
            float r1 = dy / Math.max(dx, 1e-6f), r2 = pdy / Math.max(pdx, 1e-6f);
            if (Math.abs(r1 - r2) > 0.06f) return null;
            if (Math.abs(dx - pdx) > Math.max(2f, pdx * 0.08f)) return null;
            if (Math.abs(dy - pdy) > Math.max(2f, pdy * 0.08f)) return null;
            float kx = (prevCorners[0] - gc[0]) / dx;
            float ky = (prevCorners[1] - gc[1]) / dy;
            int ex = Math.round(kx), ey = Math.round(ky);
            if (ex == 0 && ey == 0) return null;                       // 本来就一样
            if (Math.abs(kx - ex) > 0.22f || Math.abs(ky - ey) > 0.22f) return null;
            if (Math.abs(ex) > 2 || Math.abs(ey) > 2) return null;
            float x0 = prevCorners[0], y0 = prevCorners[1];
            return new float[]{
                    x0, y0,
                    x0 + 8 * dx, y0,
                    x0, y0 + 9 * dy,
                    x0 + 8 * dx, y0 + 9 * dy};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 覆盖率：识别判定「有子」的格子，映射回原图后真的坐着亮块（棋子木色）的比例。
     *
     * 为什么它是相位歧义的判据：格点整体错一格时，所有采样点都落在两格之间，
     * 覆盖率会从 0.9x 直接掉到 0.2x —— 这是网格线相位看不出来的信息。
     * 返回 -1 表示样本太少、无法判定（调用方应视为「不拦」）。
     */
    private static float coverage(Bitmap frame, Result r) {
        if (frame == null || r == null || r.points == null || r.chars == null) return -1f;
        try {
            float dy = Math.abs(r.points[1][1] - r.points[0][1]);
            float radius = Math.max(12f, dy * 0.30f);
            int checked = 0, ok = 0;
            for (int rr = 0; rr < r.chars.length; rr++) {
                for (int cc = 0; cc < r.chars[rr].length; cc++) {
                    char pc = r.chars[rr][cc];
                    if (pc == 0 || pc == '.' || pc == 'x') continue;
                    if (rr >= r.points.length) continue;
                    if (cc * 2 + 1 >= r.points[rr].length) continue;
                    float f = brightFill(frame, r.points[rr][cc * 2], r.points[rr][cc * 2 + 1], radius);
                    if (f < 0) continue;
                    checked++;
                    if (f >= 0.45f) ok++;
                }
            }
            if (checked < 6) return -1f;
            return ok / (float) checked;
        } catch (Throwable t) {
            return -1f;
        }
    }

    /** 以 (x,y) 为中心、边长约 2r 的方块里「亮像素」占比；无法取样返回 -1。 */
    private static float brightFill(Bitmap bmp, float x, float y, float r) {
        if (bmp == null || bmp.isRecycled()) return -1f;
        int W = bmp.getWidth(), H = bmp.getHeight();
        int n = Math.max(3, Math.round(r * 2));
        int x0 = Math.round(x) - n / 2, y0 = Math.round(y) - n / 2;
        if (x0 < 0) x0 = 0;
        if (y0 < 0) y0 = 0;
        if (x0 + n > W) n = W - x0;
        if (y0 + n > H) n = H - y0;
        if (n <= 2) return -1f;
        int[] buf = new int[n * n];
        try { bmp.getPixels(buf, 0, n, x0, y0, n, n); } catch (Throwable t) { return -1f; }
        int hit = 0;
        for (int i = 0; i < buf.length; i++) {
            int c = buf[i];
            int rr = (c >> 16) & 0xFF, gg = (c >> 8) & 0xFF, bb = c & 0xFF;
            if (((rr * 77 + gg * 151 + bb * 28) >> 8) >= 175) hit++;
        }
        return hit / (float) buf.length;
    }

    // ================= 几何法定位（主通道） =================

    private static final int GEO_W = 640;      // 几何检测的缩放宽度

    /**
     * 直接找棋盘：光区定位 + 自相关定间距 + 相位搜索。
     *
     * 离线在真实对局截图上验证：
     *   竖线 96 232 367 503 638 774 909 1045 1180（9 条，间距 135.5）
     *   横线 824 957 1089 1222 1354 1487 1619 1752 1884 2017（10 条，间距 132.5）
     * 与实测边缘强峰完全吻合。
     *
     * 三个要点：
     *   ① 光区判定必须平滑：不然一条深色网格线就把「连续段」切断（旧版就断在这）
     *   ② 间距用自相关估，不依赖光区起止的准确性
     *   ③ 相位在光区内搜，取 9 条竖线 / 10 条横线强度之和最大的位置
     *
     * 返回 4 角（原图屏幕坐标）：tl, tr, bl, br；失败返回 null。
     */
    private float[] findGridByLines(Bitmap frame) {
        int W = frame.getWidth(), H = frame.getHeight();
        float scale = GEO_W / (float) W;
        int sw = GEO_W, sh = Math.max(1, Math.round(H * scale));
        Bitmap small = Bitmap.createScaledBitmap(frame, sw, sh, true);
        int[] px = new int[sw * sh];
        small.getPixels(px, 0, sw, 0, 0, sw, sh);
        if (small != frame) small.recycle();

        int[] g = new int[sw * sh];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            g[i] = (((c >> 16) & 0xFF) * 77 + ((c >> 8) & 0xFF) * 151 + (c & 0xFF) * 28) >> 8;
        }

        int LUM = 140;
        int sm = Math.max(3, sw / 160);            // 平滑半径

        // ① 行：浅色像素数的连续段（先平滑，跨过深色网格线）
        int[] rowHit = new int[sh];
        for (int y = 0; y < sh; y++) {
            int n = 0;
            for (int x = 0; x < sw; x++) if (g[y * sw + x] >= LUM) n++;
            rowHit[y] = n;
        }
        int[] rowS = smooth1d(rowHit, sm);
        int[] yr = longestRun(rowS, (int) (sw * 0.40f));
        if (yr == null || yr[1] - yr[0] < sh * 0.18f) return null;
        int yA = yr[0], yB = yr[1];

        // ② 列：在上面的行范围内再找连续段
        int[] colHit = new int[sw];
        for (int x = 0; x < sw; x++) {
            int n = 0;
            for (int y = yA; y < yB; y++) if (g[y * sw + x] >= LUM) n++;
            colHit[x] = n;
        }
        int[] colS = smooth1d(colHit, sm);
        int[] xr = longestRun(colS, (int) ((yB - yA) * 0.40f));
        if (xr == null || xr[1] - xr[0] < sw * 0.18f) return null;
        int xA = xr[0], xB = xr[1];

        // ③ 边缘投影
        int[] vp = edgeProfile(g, sw, sh, xA, xB, yA, yB, true);
        int[] hp = edgeProfile(g, sw, sh, xA, xB, yA, yB, false);

        // ④ 自相关定间距（9 条竖线=8 段，10 条横线=9 段）
        float dx = pitchOf(vp, xA, xB, N_V);
        float dy = pitchOf(hp, yA, yB, N_H);
        if (dx <= 2 || dy <= 2) return null;

        // ⑤ 相位搜索
        float x0 = phaseOf(vp, xA, xB, N_V, dx);
        float y0 = phaseOf(hp, yA, yB, N_H, dy);
        if (x0 <= 0 || y0 <= 0) return null;

        // ⑥ 合理性：棋盘尺寸与比例
        float bw = 8 * dx, bh = 9 * dy;
        if (bw < sw * 0.30f || bh < sh * 0.30f) return null;
        float ratio = dy / Math.max(dx, 1e-6f);
        if (ratio < 0.62f || ratio > 1.6f) return null;

        float inv = 1f / scale;
        if (++snapLog % 10 == 1) {
            Log.i(TAG, String.format(java.util.Locale.US,
                    "几何 ok x0=%.1f dx=%.2f y0=%.1f dy=%.2f 光区 x[%d,%d] y[%d,%d] (缩略 %dx%d)",
                    x0 * inv, dx * inv, y0 * inv, dy * inv, xA, xB, yA, yB, sw, sh));
        }
        return new float[]{
                x0 * inv, y0 * inv,
                (x0 + 8 * dx) * inv, y0 * inv,
                x0 * inv, (y0 + 9 * dy) * inv,
                (x0 + 8 * dx) * inv, (y0 + 9 * dy) * inv
        };
    }

    /** 一维最大值平滑：跨过深色网格线，避免把连续段切断。 */
    private static int[] smooth1d(int[] v, int r) {
        int[] o = new int[v.length];
        for (int i = 0; i < v.length; i++) {
            int m = 0;
            for (int d = -r; d <= r; d++) {
                int j = i + d;
                if (j >= 0 && j < v.length && v[j] > m) m = v[j];
            }
            o[i] = m;
        }
        return o;
    }

    /** 在 [0, v.length) 里找满足 v[i] >= need 的最长连续段。 */
    private static int[] longestRun(int[] v, int need) {
        int bestS = -1, bestE = -1, s = -1;
        for (int i = 0; i < v.length; i++) {
            if (v[i] >= need) {
                if (s < 0) s = i;
            } else {
                if (s >= 0 && i - s > bestE - bestS) { bestS = s; bestE = i; }
                s = -1;
            }
        }
        if (s >= 0 && v.length - s > bestE - bestS) { bestS = s; bestE = v.length; }
        if (bestS < 0) return null;
        return new int[]{bestS, bestE};
    }

    /** 区域内边缘强度投影（竖线看横向梯度，横线看纵向梯度）。 */
    private static int[] edgeProfile(int[] g, int sw, int sh,
                                     int xA, int xB, int yA, int yB, boolean vertical) {
        int[] p = new int[vertical ? sw : sh];
        if (vertical) {
            for (int x = Math.max(1, xA); x < Math.min(sw - 1, xB); x++) {
                int s = 0;
                for (int y = yA; y < yB; y++)
                    s += Math.abs(g[y * sw + x + 1] - g[y * sw + x - 1]);
                p[x] = s;
            }
        } else {
            for (int y = Math.max(1, yA); y < Math.min(sh - 1, yB); y++) {
                int s = 0;
                for (int x = xA; x < xB; x++)
                    s += Math.abs(g[(y + 1) * sw + x] - g[(y - 1) * sw + x]);
                p[y] = s;
            }
        }
        return p;
    }

    /** 自相关估间距：约束在 跨度/(n-1) 的 0.62~1.55 倍之间。 */
    private static float pitchOf(int[] p, int lo, int hi, int n) {
        int span = hi - lo;
        if (span <= 0) return 0;
        float expect = span / (float) (n - 1);
        int pLo = Math.max(8, (int) (expect * 0.62f));
        int pHi = Math.max(pLo + 2, (int) (expect * 1.55f));
        float best = 0;
        double bv = -1;
        for (float pp = pLo; pp <= pHi; pp += 0.5f) {
            int step = Math.round(pp);
            if (step < 6) continue;
            double v = 0;
            int c = 0;
            for (int i = lo + step; i < hi && i < p.length; i++) {
                v += (double) p[i] * p[i - step];
                c++;
            }
            v /= Math.max(1, c);
            if (v > bv) { bv = v; best = pp; }
        }
        return best;
    }

    /** 相位搜索：让 n 条等距线的强度之和最大的起点。 */
    private static float phaseOf(int[] p, int lo, int hi, int n, float pitch) {
        if (pitch <= 0) return -1;
        float bestS = -1;
        double bestV = -1;
        int s0 = Math.max(1, Math.round(lo - pitch * 0.95f));
        int s1 = Math.round(hi - pitch * (n - 1) + pitch * 0.6f);
        for (float s = s0; s <= s1; s += 0.5f) {
            double v = 0;
            boolean ok = true;
            for (int i = 0; i < n; i++) {
                int idx = Math.round(s + pitch * i);
                if (idx < 1 || idx >= p.length - 1) { ok = false; break; }
                v += p[idx];
            }
            if (!ok) continue;
            if (v > bestV) { bestV = v; bestS = s; }
        }
        return bestS;
    }
    private static int[][] candidates(int W, int H) {
        java.util.List<int[]> out = new java.util.ArrayList<int[]>();
        float[] scales = {0.98f, 0.92f, 0.86f, 0.80f, 0.72f, 0.64f, 0.56f, 0.48f};
        float[] cyF = {0.50f, 0.44f, 0.56f, 0.38f, 0.62f};
        float[] cxF = {0.50f, 0.44f, 0.56f};
        int mn = Math.min(W, H);
        for (float s : scales) {
            int side = (int) (mn * s);
            for (float cyf : cyF) {
                for (float cxf : cxF) {
                    // 大框只试中心（大框本来就能盖住大部分区域），小框多试偏移
                    if (s > 0.90f && (cyf != 0.50f || cxf != 0.50f)) continue;
                    if (s > 0.80f && cxf != 0.50f) continue;
                    int cx = (int) (W * cxf), cy = (int) (H * cyf);
                    int x1 = Math.max(0, cx - side / 2), y1 = Math.max(0, cy - side / 2);
                    int x2 = Math.min(W, x1 + side), y2 = Math.min(H, y1 + side);
                    if (x2 - x1 < 200 || y2 - y1 < 200) continue;
                    out.add(new int[]{x1, y1, x2, y2});
                }
            }
        }
        return out.toArray(new int[0][]);
    }

    /**
     * ★ v4.58：以「上次锁定过的角点」为中心，只生成一小撮候选框。
     *
     * 棋盘在一局之内不会移动，所以丢锁后的重定位根本不需要 72 个全屏候选 ——
     * 围着上次的位置、几个尺度试一遍就够。配合「够好就停」和总预算，
     * 单帧成本从 3~8 秒降到 1 秒级；而抖音那种非棋盘画面也只会做几次
     * 便宜的尝试，不会再把 CPU 烧满。
     *
     * 与 candidates() 保持一致：**必须是正方形框**，
     * 因为 findCorners 会把框裁成正方形再缩到 256×256 喂姿态模型。
     */
    private static int[][] candidatesNear(float[] corners, int W, int H) {
        java.util.List<int[]> out = new java.util.ArrayList<int[]>();
        if (corners == null || corners.length < 8) return candidates(W, H);
        float x1 = Math.min(Math.min(corners[0], corners[2]), Math.min(corners[4], corners[6]));
        float x2 = Math.max(Math.max(corners[0], corners[2]), Math.max(corners[4], corners[6]));
        float y1 = Math.min(Math.min(corners[1], corners[3]), Math.min(corners[5], corners[7]));
        float y2 = Math.max(Math.max(corners[1], corners[3]), Math.max(corners[5], corners[7]));
        float bw = Math.max(120f, x2 - x1), bh = Math.max(120f, y2 - y1);
        float cx = (x1 + x2) / 2f, cy = (y1 + y2) / 2f;
        float base = Math.max(bw, bh);
        float[] scales = {1.0f, 1.2f, 0.85f, 1.45f, 0.7f};
        float[] dxs = {0f, -0.5f, 0.5f};
        for (float s : scales) {
            int side = (int) (base * s);
            for (float dxf : dxs) {
                int px = (int) (cx + dxf * bw);
                int ax1 = Math.max(0, px - side / 2), ay1 = Math.max(0, (int) cy - side / 2);
                int ax2 = Math.min(W, ax1 + side), ay2 = Math.min(H, ay1 + side);
                if (ax2 - ax1 < 200 || ay2 - ay1 < 200) continue;
                out.add(new int[]{ax1, ay1, ax2, ay2});
            }
        }
        if (out.isEmpty()) return candidates(W, H);
        return out.toArray(new int[0][]);
    }

    private float cornerScore = 0f;
    private float[] lastGoodCorners;   // 上一帧通过的角点，用于抖动回退

    /**
     * ★ v4.58：最近一次 detect() 的失败原因（成功时为空串）。
     *
     * 以前这些原因只写 logcat（`Log.w`），而本机 logcat 只能留几分钟，
     * 出事之后完全查不下去 —— 12:16~12:24 那次「识别不稳刷了 8 分钟」
     * 就是因为丢帧原因一个字都没留下来，只能靠猜。
     * 现在把它交给 XqService 一起写进 app.log（文件日志能留存）。
     */
    private volatile String lastWhy = "";
    public String lastWhy() { return lastWhy; }

    /** ★ v4.58：连续几次「附近搜索」没结果，就退回全屏扫描（比如开了一局新棋、
     *  棋盘换了位置）。这样「局部优先」既能省 CPU，又不会把自己锁死。 */
    private int nearFailStreak = 0;

    /** ★ v4.58：姿态模型候选「够好就停」的分数线（姿态模型自评分）。 */
    private static final float GOOD_ENOUGH = 0.72f;
    /** ★ v4.58：一次全屏候选扫描的总预算。原来是无穷大 → 实测单帧 3~8 秒。 */
    private static final long SEARCH_BUDGET_MS = 2500L;

    /** ★ V3.0 覆盖率闸门：识别判定有子的格子，映射回屏幕真的有亮块的最低比例。 */
    private static final float COV_MIN = 0.70f;

    /** ★ V3.0 上一次成功锁定的角点。
     *  resetLock() 故意不清它 —— 它是「棋盘一局之内不会移动」这条先验的载体，
     *  几何法正是靠它把「整体错整数个格距」的相位纠正回来。 */
    private float[] prevCorners;

    /** 在候选框内找 4 个角，返回屏幕坐标 [x1,y1,x2,y2,x3,y3,x4,y4]。 */
    private float[] findCorners(Bitmap frame, int[] bb) throws Exception {
        float x1 = bb[0], y1 = bb[1], x2 = bb[2], y2 = bb[3];
        float w = (x2 - x1) * 1.25f, h = (y2 - y1) * 1.25f;
        float cx = (x1 + x2) / 2f, cy = (y1 + y2) / 2f;
        int S = 256;

        Bitmap crop = cropScale(frame, cx - w / 2f, cy - h / 2f, w, h, S, S);
        float[] in = bitmapToNCHW(crop, S, S);

        String inputName = poseSess.getInputNames().iterator().next();
        OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(in), new long[]{1, 3, S, S});
        OrtSession.Result res = poseSess.run(java.util.Collections.singletonMap(inputName, t));

        // SimCC：simcc_x / simcc_y 各为 [1, 4, 512]，每行是该角点的坐标分布
        float[][] sx = to2D(res.get(0));
        float[][] sy = to2D(res.get(1));
        t.close();
        res.close();
        if (sx == null || sy == null) return null;

        int n = Math.min(sx.length, 4);
        int ow = sx[0].length;
        float[] pts = new float[n * 2];
        float scoreSum = 0;
        for (int i = 0; i < n; i++) {
            int ix = argmax(sx[i]);
            int iy = argmax(sy[i]);
            float u = ix * (S / (float) ow);
            float v = iy * (S / (float) ow);
            pts[i * 2] = cx + (u / S - 0.5f) * w;
            pts[i * 2 + 1] = cy + (v / S - 0.5f) * h;
            scoreSum += Math.max(sx[i][ix], sy[i][iy]);
        }
        cornerScore = scoreSum / Math.max(1, n);
        return pts;
    }

    /** 四角排序 + 形状校验。返回 null 表示通过。 */
    private static String geometryOk(float[] c, int W, int H) {
        float[][] p = new float[4][2];
        for (int i = 0; i < 4; i++) { p[i][0] = c[i * 2]; p[i][1] = c[i * 2 + 1]; }
        java.util.Arrays.sort(p, new java.util.Comparator<float[]>() {
            public int compare(float[] a, float[] b) { return Float.compare(a[1], b[1]); }
        });
        float[] top = p[0][0] < p[1][0] ? p[0] : p[1];
        float[] top2 = p[0][0] < p[1][0] ? p[1] : p[0];
        float[] bot = p[2][0] < p[3][0] ? p[2] : p[3];
        float[] bot2 = p[2][0] < p[3][0] ? p[3] : p[2];
        float wTop = dist(top, top2), wBot = dist(bot, bot2);
        float hL = dist(top, bot), hR = dist(top2, bot2);
        if (Math.min(wTop, wBot) < 110 || Math.min(hL, hR) < 110) return "棋盘太小";
        float w = (wTop + wBot) / 2, h = (hL + hR) / 2;
        float ratio = w / Math.max(h, 1e-6f);
        if (ratio < 0.4f || ratio > 2.2f) return "宽高比异常";
        // 角点不应跑到画面外太远
        float minX = Math.min(Math.min(top[0], top2[0]), Math.min(bot[0], bot2[0]));
        float maxX = Math.max(Math.max(top[0], top2[0]), Math.max(bot[0], bot2[0]));
        float minY = Math.min(Math.min(top[1], top2[1]), Math.min(bot[1], bot2[1]));
        float maxY = Math.max(Math.max(top[1], top2[1]), Math.max(bot[1], bot2[1]));
        if (maxX < 0 || minX > W || maxY < 0 || minY > H) return "角点在画面外";
        return null;
    }

    /**
     * 格点合理性：行距/列距不能太小、比例不能太离谱、棋盘得占屏幕足够大。
     * 这一关拦住的是「角点乱跑」——它同时会毁掉识别（漏子）和落子点位（点飞）。
     */
    /**
     * 格点合理性。这一关是「漏子」和「点飞」的总闸门。
     *
     * 光看平均值不够 —— 本局实测出现过「上半盘正常、下半盘被压扁」的情形：
     * 平均行距看着还行，但中腹几行挤成 36px，识别就把子认错了地方。
     * 所以这里同时要求：
     *   ① 行距/列距不能太小
     *   ② 行距列距本身要均匀（最大/最小 <= 1.35）
     *   ③ 长宽比接近 9:10
     *   ④ 棋盘得占屏幕足够大
     */
    private static boolean gridSane(Result r, int W, int H) {
        if (r == null || r.points == null) return false;

        float[] rs = new float[N_H - 1];
        float[] cs = new float[N_V - 1];
        for (int i = 0; i < N_H - 1; i++)
            rs[i] = Math.abs(r.points[i + 1][1] - r.points[i][1]);
        for (int i = 0; i < N_V - 1; i++)
            cs[i] = Math.abs(r.points[0][(i + 1) * 2] - r.points[0][i * 2]);

        float rMin = Float.MAX_VALUE, rMax = 0, rSum = 0;
        for (float v : rs) { if (v < rMin) rMin = v; if (v > rMax) rMax = v; rSum += v; }
        float cMin = Float.MAX_VALUE, cMax = 0, cSum = 0;
        for (float v : cs) { if (v < cMin) cMin = v; if (v > cMax) cMax = v; cSum += v; }
        float rAvg = rSum / rs.length, cAvg = cSum / cs.length;

        if (rAvg < 40f || cAvg < 40f) return false;
        if (rMin <= 1f || cMin <= 1f) return false;
        if (rMax / rMin > 1.35f || cMax / cMin > 1.35f) return false;   // 网格被压扁/拉伸

        float ratio = rAvg / Math.max(cAvg, 1e-6f);
        if (ratio < 0.7f || ratio > 1.45f) return false;

        float bh = Math.abs(r.points[N_H - 1][1] - r.points[0][1]);
        float bw = Math.abs(r.points[0][(N_V - 1) * 2] - r.points[0][0]);
        if (bh < H * 0.30f || bw < W * 0.30f) return false;
        return true;
    }

    private static float dist(float[] a, float[] b) {
        float dx = a[0] - b[0], dy = a[1] - b[1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** 拉正棋盘 -> 认 90 点。 */
    private Result classify(Bitmap frame, float[] corners) throws Exception {
        float[][] p = orderCorners(corners);
        Bitmap board = warp(frame, p[0], p[1], p[2], p[3]);
        lastWarp = board;
        if (board == null) return null;
        float[] in = bitmapToNCHW(board, 280, 315);

        String inputName = clsSess.getInputNames().iterator().next();
        OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(in), new long[]{1, 3, 315, 280});
        OrtSession.Result res = clsSess.run(java.util.Collections.singletonMap(inputName, t));
        float[][][] out = to3D(res.get(0));
        t.close();
        res.close();
        if (out == null) return null;

        // ★ 关键修复：分类输出形状是 [1, 90, 16]。
        //   必须先去 batch 维再按点位索引。原来写成 out[i]（i 是点位 0..89），
        //   而 out.length 只有 1，于是只算了第 0 个点、其余全是空字符，
        //   FEN 直接作废（表现为 minConf 偏低、direction=null）。
        float[][] probs = pointsOf(out);
        if (probs == null) {
            Log.w(TAG, "分类输出形状异常: " + shape3(out));
            return null;
        }

        Result r = new Result();
        r.ok = true;
        r.chars = new char[N_H][N_V];
        r.conf = new float[N_H][N_V];
        r.points = new float[N_H][N_V * 2];
        r.minConf = 1f;

        for (int i = 0; i < N_H * N_V && i < probs.length; i++) {
            int rr = i / N_V, cc = i % N_V;
            int best = argmax(probs[i]);
            r.chars[rr][cc] = SHORT[best];
            float cf = probs[i][best];
            r.conf[rr][cc] = cf;
            if (cf < r.minConf) r.minConf = cf;
        }

        // 屏幕坐标：把标准棋盘点用逆透视映射回去。
        // 先用拉正图上的网格线把格点吸准（见 snapAxis），再映射回屏幕。
        float[] inv = invertBoardMap(p[0], p[1], p[2], p[3]);
        float gridX = PAD, gridW = (BOARD_W - 2f * PAD) / (N_V - 1f);
        float gridY = PAD, gridH = (BOARD_H - 2f * PAD) / (N_H - 1f);
        try {
            int bw = board.getWidth(), bh = board.getHeight();
            int[] gp = grayOf(board);
            int[] vprof = new int[bw];
            int[] hprof = new int[bh];
            for (int x = 1; x < bw - 1; x++) {
                int s = 0;
                for (int y = PAD; y < bh - PAD; y++)
                    s += Math.abs(gp[y * bw + x + 1] - gp[y * bw + x - 1]);
                vprof[x] = s;
            }
            for (int y = 1; y < bh - 1; y++) {
                int s = 0;
                for (int x = PAD; x < bw - PAD; x++)
                    s += Math.abs(gp[(y + 1) * bw + x] - gp[(y - 1) * bw + x]);
                hprof[y] = s;
            }
            float[] vx = snapAxis(vprof, N_V, gridX, gridW);
            float[] hy = snapAxis(hprof, N_H, gridY, gridH);
            if (vx != null) r.gridScore = vx[2];
            if (USE_SNAP && vx != null) { gridX = vx[0]; gridW = vx[1]; }
            if (USE_SNAP && hy != null) { gridY = hy[0]; gridH = hy[1]; }
            if (++snapLog % 20 == 1)
                Log.i(TAG, String.format(java.util.Locale.US,
                        "网格 x0=%.1f dx=%.2f y0=%.1f dy=%.2f 周期=%.2f", gridX, gridW, gridY, gridH, r.gridScore));
        } catch (Throwable ignored) {}
        for (int rr = 0; rr < N_H; rr++) {
            for (int cc = 0; cc < N_V; cc++) {
                float bx = gridX + gridW * cc;
                float by = gridY + gridH * rr;
                float[] sp = applyH(inv, bx, by);
                r.points[rr][cc * 2] = sp[0];
                r.points[rr][cc * 2 + 1] = sp[1];
            }
        }

        r.fen = fenOf(r.chars);
        r.direction = directionOf(r.chars);
        r.method = "ONNX 识别（最小把握 " + String.format(java.util.Locale.US, "%.2f", r.minConf) + "）";
        return r;
    }

    /** 把分类模型输出统一成 [90,16]（实测形状为 [1][90][16]）。 */
    /** 灰度图（网格线检测用）。 */
    private static int[] grayOf(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int[] g = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            g[i] = (((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF)) / 3;
        }
        return g;
    }

    /**
     * 单轴网格吸附：在边缘投影里找最像「等距 n 条线」的相位与间距。
     *
     * 为什么必须做：ONNX 给的角点有几像素误差，映射到棋盘上就是「半个格子」的错位。
     * 半格错位会让同一个棋子同时落进相邻两个采样点 —— 实际表现就是凭空多子
     * （本局出现过「红方 A 多子(3)」「红车认成黑车」）。
     * 棋盘线是真实存在的强边缘，用它把格点吸准，比任何模型输出都稳。
     */
    private static float[] snapAxis(int[] prof, int n, float ns, float nst) {
        int L = prof.length;
        float bestS = ns, bestT = nst;
        double bestScore = -1;
        float tLo = nst * 0.92f, tHi = nst * 1.08f;
        for (float t = tLo; t <= tHi; t += 0.25f) {
            float half = t / 2f;
            for (float s = ns - half; s <= ns + half; s += 1f) {
                if (s < 2) continue;
                if (s + t * (n - 1) > L - 3) continue;
                double sc = 0;
                boolean ok = true;
                for (int i = 0; i < n; i++) {
                    int idx = Math.round(s + t * i);
                    if (idx < 1 || idx >= L - 1) { ok = false; break; }
                    sc += prof[idx];
                }
                if (!ok) continue;
                if (sc > bestScore) { bestScore = sc; bestS = s; bestT = t; }
            }
        }
        if (bestScore <= 0) return null;
        if (bestS < 2) return null;
        if (Math.abs(bestS - ns) > nst * 0.12f) return null;   // 偏太多 → 判定吸附失败，保留原值
        // 周期性强度：棋盘线的边缘投影应显著高于平均。随机区域（比如游戏大厅）没有这个结构。
        double base = 0;
        int skip = Math.max(1, (int) bestT);
        int cnt = 0;
        for (int i = 1; i < L - 1; i += skip) { base += prof[i]; cnt++; }
        base = cnt > 0 ? base / cnt : 1;
        float ratio = base > 0 ? (float) (bestScore / n / base) : 0f;
        return new float[]{bestS, bestT, ratio};
    }

    private static float[][] pointsOf(float[][][] out) {
        if (out == null || out.length == 0 || out[0] == null) return null;
        if (out[0].length == N_H * N_V) return out[0];
        return null;
    }
    /** 把 [1,90,16] 或 [90,16] 统一成 [90,16]。 */

    private static String shape3(float[][][] o) {
        if (o == null) return "null";
        return "[" + o.length + "][" + (o.length > 0 ? o[0].length : 0)
                + "][" + (o.length > 0 && o[0] != null && o[0].length > 0 ? o[0][0].length : 0) + "]";
    }

    private static float[][] orderCorners(float[] corners) {
        float[][] p = new float[4][2];
        for (int i = 0; i < 4; i++) { p[i][0] = corners[i * 2]; p[i][1] = corners[i * 2 + 1]; }
        java.util.Arrays.sort(p, new java.util.Comparator<float[]>() {
            public int compare(float[] a, float[] b) { return Float.compare(a[1], b[1]); }
        });
        float[][] out = new float[4][];
        out[0] = p[0][0] < p[1][0] ? p[0] : p[1];   // 左上
        out[1] = p[0][0] < p[1][0] ? p[1] : p[0];   // 右上
        out[2] = p[2][0] < p[3][0] ? p[2] : p[3];   // 左下
        out[3] = p[2][0] < p[3][0] ? p[3] : p[2];   // 右下
        return out;
    }

    // ---------- 图像工具 ----------
    private static Bitmap cropScale(Bitmap src, float x, float y, float w, float h, int tw, int th) {
        Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.drawColor(0xFF000000);
        Matrix m = new Matrix();
        m.postScale(tw / w, th / h);
        m.postTranslate(-x * tw / w, -y * th / h);
        Paint p = new Paint();
        p.setFilterBitmap(true);
        cv.drawBitmap(src, m, p);
        return out;
    }

    private static Bitmap warp(Bitmap src, float[] tl, float[] tr, float[] bl, float[] br) {
        Bitmap out = Bitmap.createBitmap(BOARD_W, BOARD_H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.drawColor(0xFF000000);
        Matrix m = new Matrix();
        float[] s = {tl[0], tl[1], tr[0], tr[1], bl[0], bl[1], br[0], br[1]};
        float[] d = {PAD, PAD, BOARD_W - PAD, PAD, PAD, BOARD_H - PAD, BOARD_W - PAD, BOARD_H - PAD};
        if (!m.setPolyToPoly(s, 0, d, 0, 4)) return null;
        Paint p = new Paint();
        p.setFilterBitmap(true);
        cv.drawBitmap(src, m, p);
        return out;
    }

    /** 棋盘(拉正图)坐标 -> 屏幕坐标 的 3x3 齐次矩阵的逆。 */
    private static float[] invertBoardMap(float[] tl, float[] tr, float[] bl, float[] br) {
        float[] src = {tl[0], tl[1], tr[0], tr[1], bl[0], bl[1], br[0], br[1]};
        float[] dst = {PAD, PAD, BOARD_W - PAD, PAD, PAD, BOARD_H - PAD, BOARD_W - PAD, BOARD_H - PAD};
        float[] H = solveH(dst, src);   // board -> screen
        return H;
    }

    /** 由 4 组对应点解 3x3 单应矩阵（dst -> src）。 */
    private static float[] solveH(float[] from, float[] to) {
        double[][] A = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = from[i * 2], y = from[i * 2 + 1];
            double u = to[i * 2], v = to[i * 2 + 1];
            A[i * 2][0] = x; A[i * 2][1] = y; A[i * 2][2] = 1;
            A[i * 2][6] = -u * x; A[i * 2][7] = -u * y; A[i * 2][8] = u;
            A[i * 2 + 1][3] = x; A[i * 2 + 1][4] = y; A[i * 2 + 1][5] = 1;
            A[i * 2 + 1][6] = -v * x; A[i * 2 + 1][7] = -v * y; A[i * 2 + 1][8] = v;
        }
        // 高斯消元求 h（h22 = 1）
        int n = 8;
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++)
                if (Math.abs(A[r][col]) > Math.abs(A[piv][col])) piv = r;
            double[] tmp = A[col]; A[col] = A[piv]; A[piv] = tmp;
            if (Math.abs(A[col][col]) < 1e-12) continue;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = A[r][col] / A[col][col];
                for (int c = col; c <= n; c++) A[r][c] -= f * A[col][c];
            }
        }
        float[] h = new float[9];
        for (int i = 0; i < n; i++) {
            h[i] = (float) (Math.abs(A[i][i]) < 1e-12 ? 0 : A[i][8] / A[i][i]);
        }
        h[8] = 1f;
        return h;
    }

    private static float[] applyH(float[] H, float x, float y) {
        float d = H[6] * x + H[7] * y + H[8];
        if (Math.abs(d) < 1e-9f) d = 1e-9f;
        return new float[]{(H[0] * x + H[1] * y + H[2]) / d, (H[3] * x + H[4] * y + H[5]) / d};
    }

    private static float[] bitmapToNCHW(Bitmap bmp, int w, int h) {
        if (bmp.getWidth() != w || bmp.getHeight() != h)
            bmp = Bitmap.createScaledBitmap(bmp, w, h, true);
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        float[] out = new float[3 * w * h];
        int plane = w * h;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            float r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, bl = p & 0xFF;
            out[i] = (r - MEAN[0]) / STD[0];
            out[plane + i] = (g - MEAN[1]) / STD[1];
            out[2 * plane + i] = (bl - MEAN[2]) / STD[2];
        }
        return out;
    }

    private static int argmax(float[] a) {
        int bi = 0;
        for (int i = 1; i < a.length; i++) if (a[i] > a[bi]) bi = i;
        return bi;
    }

    // ---------- ONNX 输出取值 ----------
    private static float[][] to2D(ai.onnxruntime.OnnxValue v) throws Exception {
        Object o = ((OnnxTensor) v).getValue();
        if (o instanceof float[][]) return (float[][]) o;
        if (o instanceof float[][][]) return ((float[][][]) o)[0];
        if (o instanceof float[][][][]) return ((float[][][][]) o)[0][0];
        return null;
    }

    private static float[][][] to3D(ai.onnxruntime.OnnxValue v) throws Exception {
        Object o = ((OnnxTensor) v).getValue();
        if (o instanceof float[][][]) return (float[][][]) o;
        if (o instanceof float[][][][]) return ((float[][][][]) o)[0];
        return null;
    }

    // ---------- 结果工具 ----------
    public static String fenOf(char[][] ch) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < N_H; r++) {
            int empty = 0;
            for (int c = 0; c < N_V; c++) {
                char x = ch[r][c];
                if (x == '.' || x == 'x') empty++;
                else { if (empty > 0) { sb.append(empty); empty = 0; } sb.append(x); }
            }
            if (empty > 0) sb.append(empty);
            if (r != N_H - 1) sb.append('/');
        }
        return sb.toString();
    }

    /**
     * 棋盘方向（= 我在哪一边）。
     * 以将帅所在九宫为准：红帅只能在 7-9 行，黑将只能在 0-2 行。
     * 这比按子力加权可靠得多 —— 残局里子力加权会判反（实测红剩 7 子、黑 11 子时判成了黑在下）。
     */
    public static String directionOf(char[][] ch) {
        int kr = -1, kg = -1;
        for (int r = 0; r < N_H; r++) {
            for (int c = 0; c < N_V; c++) {
                if (ch[r][c] == 'K') kr = r;
                else if (ch[r][c] == 'k') kg = r;
            }
        }
        // ① 两个将帅都在：按谁在下方
        if (kr >= 0 && kg >= 0) return kr > kg ? "normal" : "flipped";
        // ② 只认出一个：看它在不在自己的九宫
        if (kr >= 7) return "normal";       // 红帅在下方九宫 → 红在下
        if (kr >= 0 && kr <= 2) return "flipped";  // 红帅在上方九宫 → 红在上（我被翻转）
        if (kg >= 7) return "flipped";      // 黑将在下方九宫 → 黑在下
        if (kg >= 0 && kg <= 2) return "normal";
        return null;
    }

    /**
     * 判断「我方」（玩家坐在哪一边）。
     * 原理：把每个棋子的所在行当成权重（行号越大 = 越靠近屏幕底部），
     * 红黑各累加。谁的总权重更大，谁就在屏幕下方，也就是玩家这边。
     * 比单看将的位置稳得多：残局时双方棋子变少也不会翻车。
     *
     * @return 'w' = 玩家执红，'b' = 玩家执黑，0 = 判不出来
     */
    public static char sideOf(char[][] ch, float[][] conf, float minConf) {
        double rw = 0, bw = 0;
        int rn = 0, bn = 0;
        for (int r = 0; r < N_H; r++) {
            for (int c = 0; c < N_V; c++) {
                char x = ch[r][c];
                if (x == '.' || x == 'x') continue;
                if (conf != null && conf[r][c] < minConf) continue;
                double wgt = r + 0.5;
                if (Character.isUpperCase(x)) { rw += wgt; rn++; }
                else { bw += wgt; bn++; }
            }
        }
        if (rn == 0 || bn == 0) return 0;          // 一方被吃光，判不了
        if (Math.abs(rw - bw) < 4.0) return 0;     // 太接近，不敢下结论
        return rw > bw ? 'w' : 'b';
    }

    /** 退化方案：红帅在下半盘就是玩家执红。 */
    public static char sideOfByKing(char[][] ch) {
        // 将帅位置是铁证：红帅必在 7-9 行（己方九宫、屏幕下方），黑将必在 0-2 行。
        // 残局里按子力加权（sideOf）会判反（实测红剩 7 子、黑 11 子时判成我执黑），
        // 所以这里以将帅为准，只有将一个都找不到才返回 0。
        String d = directionOf(ch);
        if ("normal".equals(d)) return 'w';
        if ("flipped".equals(d)) return 'b';
        return 0;
    }

    public static String layoutOf(char[][] ch) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < N_H; r++) {
            for (int c = 0; c < N_V; c++) sb.append(ch[r][c] == '.' ? '·' : ch[r][c]);
            sb.append('\n');
        }
        return sb.toString();
    }
}
