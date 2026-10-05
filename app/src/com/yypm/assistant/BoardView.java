package com.yypm.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * 实时小棋盘：把识别出来的局面画出来，并对齐鲨鱼象棋悬浮窗的观感。
 *
 * 观感要点（对齐鲨鱼）：
 *   1. 木色圆角棋盘底 + 深棕网格 + 外框 + 九宫斜线 + 「楚河 / 漢界」
 *   2. 棋子画成立体木棋子：投影 + 米色底盘 + 双圈描边 + 红/黑粗体字
 *   3. 我方/敌方走向箭头：加粗、带深色描边（压在棋子上也看得清）
 *      —— 我方绿色 #2BE06B，敌方紫色 #B16CFF
 *
 * 只画，不交互。
 */
public class BoardView extends View {

    private char[][] board;      // 10 行 x 9 列；'\0' 或 '.'/x = 空
    private float[] cents;       // 90 点置信度（可为 null）
    private String sideText = "";
    private int[] myRc;          // 我方着法 {r1,c1,r2,c2}
    private int[] oppRc;         // 敌方着法 {r1,c1,r2,c2}
    private int[][] oppList;     // 敌方候选（最多 3 个，带序号）

    private static final int C_WOOD   = 0xFFF2E0BB;   // 棋盘木底
    private static final int C_FRAME  = 0xFF8A6A3C;   // 外框
    private static final int C_GRID   = 0xFF9C7C4C;   // 网格线
    private static final int C_RIVER  = 0xFFA88A55;   // 楚河汉界
    private static final int C_DISK   = 0xFFFDF6E3;   // 棋子底盘
    private static final int C_RING   = 0xFFC9A063;   // 棋子内圈
    private static final int C_EDGE   = 0xFF8A6A3C;   // 棋子外圈
    private static final int C_MY     = 0xFF2BE06B;   // 我方箭头（绿）
    private static final int C_OPP    = 0xFFB16CFF;   // 敌方箭头（紫）

    private final Paint woodP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frameP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint riverP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint diskP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint redText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blackText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lowMark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sideP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint myLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint oppLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint myHead = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint oppHead = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint myDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint oppDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rankP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rankTxt = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path headPath = new Path();
    private final Path bgPath = new Path();
    private final RectF bgRect = new RectF();

    private static final String[] CN = {
        "", "", "帅", "仕", "相", "马", "车", "炮", "兵",
        "将", "士", "象", "马", "车", "炮", "卒"
    };
    private static final char[] SHORT =
        {'.', 'x', 'K', 'A', 'B', 'N', 'R', 'C', 'P', 'k', 'a', 'b', 'n', 'r', 'c', 'p'};

    public BoardView(Context c) { this(c, null); }

    public BoardView(Context c, AttributeSet a) {
        super(c, a);

        woodP.setColor(C_WOOD);
        woodP.setStyle(Paint.Style.FILL);

        frameP.setColor(C_FRAME);
        frameP.setStyle(Paint.Style.STROKE);
        frameP.setStrokeWidth(2.2f);

        gridP.setColor(C_GRID);
        gridP.setStyle(Paint.Style.STROKE);
        gridP.setStrokeWidth(1.6f);

        riverP.setColor(C_RIVER);
        riverP.setTextAlign(Paint.Align.CENTER);
        riverP.setFakeBoldText(false);

        diskP.setColor(C_DISK);
        diskP.setStyle(Paint.Style.FILL);

        ringP.setColor(C_RING);
        ringP.setStyle(Paint.Style.STROKE);

        edgeP.setColor(C_EDGE);
        edgeP.setStyle(Paint.Style.STROKE);

        shadowP.setColor(0x40000000);
        shadowP.setStyle(Paint.Style.FILL);

        redText.setColor(0xFFD32F2F);
        redText.setTextAlign(Paint.Align.CENTER);
        redText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        blackText.setColor(0xFF1B1B1B);
        blackText.setTextAlign(Paint.Align.CENTER);
        blackText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        lowMark.setColor(0xFFFFB300);
        lowMark.setStyle(Paint.Style.STROKE);
        lowMark.setStrokeWidth(2.5f);

        sideP.setColor(0xFF7FD4FF);
        sideP.setTextSize(28f);
        sideP.setTextAlign(Paint.Align.LEFT);

        // 箭头：先画深色描边再画彩线，保证压在棋子上也清楚
        lineOutline.setColor(0xAA2A1C08);
        lineOutline.setStyle(Paint.Style.STROKE);
        lineOutline.setStrokeCap(Paint.Cap.ROUND);

        headOutline.setColor(0xAA2A1C08);
        headOutline.setStyle(Paint.Style.FILL);

        for (Paint p : new Paint[]{myLine, oppLine}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        myLine.setColor(C_MY);
        oppLine.setColor(C_OPP);
        for (Paint p : new Paint[]{myHead, oppHead}) {
            p.setStyle(Paint.Style.FILL);
        }
        myHead.setColor(C_MY);
        oppHead.setColor(C_OPP);
        for (Paint p : new Paint[]{myDot, oppDot}) p.setStyle(Paint.Style.FILL);
        myDot.setColor(0xCC2BE06B);
        oppDot.setColor(0xCCB16CFF);
        rankP.setStyle(Paint.Style.FILL);
        rankTxt.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        rankTxt.setTextAlign(Paint.Align.CENTER);
    }

    /** 设置「我方着法」和「敌方着法」的起止格（board row/col），用于画箭头。 */
    public void setMoves(int[] my, int[] opp) {
        android.util.Log.i("YYPM", "箭头: 我方=" + rcText(my) + " 敌方=" + rcText(opp));
        this.myRc = (my != null && my.length == 4) ? my : null;
        this.oppRc = (opp != null && opp.length == 4) ? opp : null;
        this.oppList = null;
        postInvalidate();
    }

    /** 我方 1 步 + 敌方最多 3 个候选走向（带 ①②③ 序号）。opps 为空 = 只画我方。 */
    public void setMovesMulti(int[] my, int[][] opps) {
        this.myRc = (my != null && my.length == 4) ? my : null;
        if (opps == null) {
            this.oppList = null;
        } else {
            int n = 0;
            for (int[] o : opps) if (o != null && o.length == 4) n++;
            if (n == 0) {
                this.oppList = null;
            } else {
                int[][] keep = new int[n][];
                int k = 0;
                for (int[] o : opps) if (o != null && o.length == 4) keep[k++] = o;
                this.oppList = keep;
                this.oppRc = keep[0];
            }
        }
        StringBuilder sb = new StringBuilder();
        if (oppList != null) for (int[] o : oppList) sb.append(rcText(o)).append("  ");
        android.util.Log.i("YYPM", "箭头(多): 我方=" + rcText(my) + " 敌方候选=" + (oppList == null ? 0 : oppList.length) + " [" + sb + "]");
        postInvalidate();
    }

    private static String rcText(int[] rc) {
        return rc == null ? "无" : (rc[0] + "," + rc[1] + "->" + rc[2] + "," + rc[3]);
    }

    /**
     * 从 UCI 着法算出 board row/col 形式的起止格。
     * 传 flipped=true 表示当前盘面是翻转的（红在上），需要先转回来。
     */
    public static int[] rcOfUci(String uci, boolean flipped) {
        if (uci == null || uci.length() < 4) return null;
        try {
            int c1 = uci.charAt(0) - 'a', k1 = uci.charAt(1) - '0';
            int c2 = uci.charAt(2) - 'a', k2 = uci.charAt(3) - '0';
            int r1 = 9 - k1, r2 = 9 - k2;
            if (flipped) { r1 = 9 - r1; r2 = 9 - r2; c1 = 8 - c1; c2 = 8 - c2; }
            if (r1 < 0 || r1 > 9 || r2 < 0 || r2 > 9 || c1 < 0 || c1 > 8 || c2 < 0 || c2 > 8) return null;
            return new int[]{r1, c1, r2, c2};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 更新局面（10x9，'\0'/'.'/'x' 为空）。 */
    public void setBoard(char[][] b) {
        this.board = b;
        postInvalidate();
    }

    /** 同时更新置信度（小于 0.6 的点会标黄），以及"我执红/黑"提示。 */
    public void setBoard(char[][] b, float[][] conf, String side) {
        this.board = b;
        this.cents = conf == null ? null : flatten(conf);
        this.sideText = side == null ? "" : side;
        postInvalidate();
    }

    /** 一次性更新局面 + 双方走向，避免两次刷帧闪烁。 */
    public void setBoardAndMoves(char[][] b, float[][] conf, String side, int[] my, int[] opp) {
        this.board = b;
        this.cents = conf == null ? null : flatten(conf);
        this.sideText = side == null ? "" : side;
        this.myRc = (my != null && my.length == 4) ? my : null;
        this.oppRc = (opp != null && opp.length == 4) ? opp : null;
        this.oppList = null;
        postInvalidate();
    }

    private static float[] flatten(float[][] conf) {
        float[] o = new float[90];
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++) o[r * 9 + c] = conf[r][c];
        return o;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int wMode = MeasureSpec.getMode(wSpec), wSize = MeasureSpec.getSize(wSpec);
        int hMode = MeasureSpec.getMode(hSpec), hSize = MeasureSpec.getSize(hSpec);

        // ★ 高度被明确指定时（悬浮面板里由代码按左栏高度定死），以高度为准反推宽度。
        //   旧实现永远按宽度算高度，而面板里宽度是写死的 80dp —— 分析区一展开，
        //   棋盘右边和下边就空出一大块。现在高度说了算，9:10 比例不变，
        //   棋盘自然把左栏那么高的空余区域全部吃掉，面板尺寸一点不变。
        if (hMode == MeasureSpec.EXACTLY && hSize > 0) {
            int note = (sideText == null || sideText.isEmpty()) ? 0 : (int) (hSize * 0.11f);
            int w = (int) Math.round(Math.max(1, hSize - note) * 9f / 10f);
            if (wMode == MeasureSpec.AT_MOST && wSize > 0 && w > wSize) {
                // 宽度不够：退回按宽度定，别把左栏的按钮挤出去
                w = wSize;
                note = (sideText == null || sideText.isEmpty()) ? 0 : (int) (w * 0.11f);
                setMeasuredDimension(w, (int) (w * 10f / 9f) + note);
                return;
            }
            setMeasuredDimension(w, hSize);
            return;
        }

        int w = wSize;
        if (w <= 0) w = 540;
        int note = (sideText == null || sideText.isEmpty()) ? 0 : (int) (w * 0.11f);
        setMeasuredDimension(w, (int) (w * 10f / 9f) + note);
    }

    @Override
    protected void onDraw(Canvas cv) {
        int W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0) return;
        int noteH = (sideText == null || sideText.isEmpty()) ? 0 : (int) (W * 0.11f);
        int boardH = H - noteH;

        // ---- 木色圆角棋盘底 ----
        float rr = Math.min(W, boardH) * 0.05f;
        bgRect.set(0, 0, W, boardH);
        bgPath.reset();
        bgPath.addRoundRect(bgRect, rr, rr, Path.Direction.CW);
        cv.drawPath(bgPath, woodP);

        int save = cv.save();
        cv.clipPath(bgPath);

        float m = Math.min(W, boardH) * 0.085f;      // 边距：保证最外圈棋子不出界
        float gw = (W - 2 * m) / 8f;                 // 列间距
        float gh = (boardH - 2 * m) / 9f;            // 行间距
        float cell = Math.min(gw, gh);
        float rad = cell * 0.44f;
        float x0 = m, y0 = m, x1 = m + 8 * gw, y1 = m + 9 * gh;

        gridP.setStrokeWidth(Math.max(1.3f, cell * 0.044f));
        frameP.setStrokeWidth(Math.max(1.6f, cell * 0.048f));

        // ---- 网格 ----
        for (int r = 0; r < 10; r++) {
            float y = y0 + r * gh;
            cv.drawLine(x0, y, x1, y, gridP);
        }
        for (int c = 0; c < 9; c++) {
            float x = x0 + c * gw;
            if (c == 0 || c == 8) {
                cv.drawLine(x, y0, x, y1, gridP);
            } else {
                cv.drawLine(x, y0, x, y0 + 4 * gh, gridP);
                cv.drawLine(x, y0 + 5 * gh, x, y1, gridP);
            }
        }
        // 外框
        cv.drawRect(x0 - 2f, y0 - 2f, x1 + 2f, y1 + 2f, frameP);

        // ---- 九宫斜线 ----
        cv.drawLine(x0 + 3 * gw, y0, x0 + 5 * gw, y0 + 2 * gh, gridP);
        cv.drawLine(x0 + 5 * gw, y0, x0 + 3 * gw, y0 + 2 * gh, gridP);
        cv.drawLine(x0 + 3 * gw, y0 + 7 * gh, x0 + 5 * gw, y0 + 9 * gh, gridP);
        cv.drawLine(x0 + 5 * gw, y0 + 7 * gh, x0 + 3 * gw, y0 + 9 * gh, gridP);

        // ---- 楚河 漢界（空盘时不画，避免与提示文字重叠）----
        if (board != null) {
            riverP.setTextSize(cell * 0.52f);
            Paint.FontMetrics rfm = riverP.getFontMetrics();
            float rbase = y0 + 4.5f * gh - (rfm.ascent + rfm.descent) / 2f;
            cv.drawText("楚 河", x0 + 2 * gw, rbase, riverP);
            cv.drawText("漢 界", x0 + 6 * gw, rbase, riverP);
        }

        if (board == null) {
            sideP.setColor(0xFF9A7B4A);
            sideP.setTextSize(cell * 0.55f);
            sideP.setTextAlign(Paint.Align.CENTER);
            cv.drawText("等待识别…", W / 2f, y0 + 4.5f * gh, sideP);
            cv.restoreToCount(save);
            return;
        }

        // ---- 棋子 ----
        float tsz = rad * 1.26f;
        redText.setTextSize(tsz);
        blackText.setTextSize(tsz);
        ringP.setStrokeWidth(Math.max(1.2f, rad * 0.13f));
        edgeP.setStrokeWidth(Math.max(1.6f, rad * 0.16f));
        lowMark.setStrokeWidth(Math.max(1.5f, rad * 0.14f));

        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                char p = board[r][c];
                if (p == '\0' || p == '.' || p == 'x') continue;
                int idx = indexOf(p);
                if (idx < 2) continue;
                String s = CN[idx];
                float cx = x0 + c * gw, cy = y0 + r * gh;
                boolean upper = Character.isUpperCase(p);

                // 投影 → 底盘 → 内圈 → 外圈 → 字
                cv.drawCircle(cx, cy + rad * 0.13f, rad, shadowP);
                cv.drawCircle(cx, cy, rad, diskP);
                cv.drawCircle(cx, cy, rad * 0.90f, ringP);
                cv.drawCircle(cx, cy, rad, edgeP);

                Paint tp = upper ? redText : blackText;
                Paint.FontMetrics fm = tp.getFontMetrics();
                cv.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, tp);

                if (cents != null) {
                    float cf = cents[r * 9 + c];
                    if (cf < 0.6f) cv.drawCircle(cx, cy, rad * 1.06f, lowMark);
                }
            }
        }

        // ---- 走向箭头（我方绿 1 条 / 敌方紫 1~3 条，带 ①②③ 序号）----
        drawArrow(cv, myRc, myLine, myHead, myDot, x0, y0, gw, gh, rad, 0);
        if (oppList != null && oppList.length > 0) {
            for (int i = 0; i < oppList.length; i++)
                drawArrow(cv, oppList[i], oppLine, oppHead, oppDot, x0, y0, gw, gh, rad, i + 1);
        } else {
            drawArrow(cv, oppRc, oppLine, oppHead, oppDot, x0, y0, gw, gh, rad, 1);
        }

        cv.restoreToCount(save);

        // ---- 底部提示（主界面用）----
        if (!sideText.isEmpty()) {
            sideP.setColor(0xFF7FD4FF);
            sideP.setTextSize(Math.max(20f, W * 0.075f));
            sideP.setTextAlign(Paint.Align.LEFT);
            cv.drawText(sideText, 10f, H - H * 0.025f, sideP);
        }
    }

    /** 画一条带箭头的走子指示线：起点圆点 + 加粗描边线 + 实心箭头。 */
    /** 画一个走向箭头。rank=0 表示我方；rank>=2 表示敌方第 2/3 候选（更细、更淡）。 */
    private void drawArrow(Canvas cv, int[] rc, Paint line, Paint head, Paint dot,
                           float x0, float y0, float gw, float gh, float rad, int rank) {
        if (rc == null) return;
        try {
            float x1 = x0 + rc[1] * gw, y1 = y0 + rc[0] * gh;
            float x2 = x0 + rc[3] * gw, y2 = y0 + rc[2] * gh;
            float dx = x2 - x1, dy = y2 - y1;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 2f) return;
            float ux = dx / len, uy = dy / len;

            // ★ 让位量必须随线段长度自适应收缩。
            //   旧写法固定让出近一个棋子半径，结果「卒/兵向前拱一格」这种
            //   只有一格长的着法，让位量比整段还长，箭头被吃光、只剩序号徽章
            //   —— 而向前拱卒恰恰是最高频的着法。
            //   现在保证线段至少保留 38% 长度，箭头大小也随之收敛。
            float budget = len * 0.62f;                     // 让位总量上限
            float outOff = Math.min(rad * 0.92f, budget * 0.45f);
            float inOff = Math.min(rad * 1.05f, budget * 0.55f);
            float sx = x1 + ux * outOff, sy = y1 + uy * outOff;
            float ex = x2 - ux * inOff, ey = y2 - uy * inOff;
            float seg = (ex - sx) * ux + (ey - sy) * uy;    // 实际线段长度
            if (seg < 2f) return;                           // 距离太近，不画

            // 序号靠后的候选画细一点、淡一点，三条线才不会糊成一团
            int alpha = (rank <= 1) ? 255 : (rank == 2 ? 210 : 160);
            line.setAlpha(alpha);
            head.setAlpha(alpha);
            dot.setAlpha(alpha);

            float lw = Math.max(4.5f, rad * 0.40f);
            if (rank >= 2) lw *= 0.85f;
            lw = Math.min(lw, Math.max(3.2f, seg * 0.30f));   // 短线也变细
            line.setStrokeWidth(lw);
            lineOutline.setStrokeWidth(lw + Math.max(3f, rad * 0.22f));
            cv.drawLine(sx, sy, ex, ey, lineOutline);
            cv.drawLine(sx, sy, ex, ey, line);

            // 箭头：大小收敛到线段能容纳的范围，保证短线也看得见方向
            float hs = Math.max(9f, rad * 0.92f);
            if (rank >= 2) hs *= 0.86f;
            hs = Math.min(hs, seg * 0.58f);
            float px = -uy, py = ux;
            float bx = ex - ux * hs, by = ey - uy * hs;
            headPath.reset();
            headPath.moveTo(ex + ux * hs * 0.45f, ey + uy * hs * 0.45f);
            headPath.lineTo(bx + px * hs * 0.66f, by + py * hs * 0.66f);
            headPath.lineTo(bx - px * hs * 0.66f, by - py * hs * 0.66f);
            headPath.close();
            float k = Math.max(1.6f, rad * 0.13f);
            cv.drawPath(headPath, head);
            headOutline.setAlpha(alpha);
            headOutline.setStyle(Paint.Style.STROKE);
            headOutline.setStrokeWidth(k);
            cv.drawPath(headPath, headOutline);

            if (rank > 0) {
                // 序号徽章（白底紫圈紫字）：一眼看出哪条是第几候选
                float br = rad * 0.50f;
                rankP.setStyle(Paint.Style.FILL);
                rankP.setColor(0xF2FFFFFF);
                cv.drawCircle(sx, sy, br, rankP);
                rankP.setStyle(Paint.Style.STROKE);
                rankP.setStrokeWidth(Math.max(1.6f, rad * 0.13f));
                rankP.setColor(C_OPP);
                cv.drawCircle(sx, sy, br, rankP);

                rankTxt.setColor(C_OPP);
                rankTxt.setTextSize(br * 1.40f);
                String s = Integer.toString(rank);
                Paint.FontMetrics fm = rankTxt.getFontMetrics();
                cv.drawText(s, sx, sy - (fm.ascent + fm.descent) / 2f, rankTxt);
            } else {
                // 起点标记
                cv.drawCircle(sx, sy, lw * 0.85f, dot);
            }
        } catch (Throwable ignored) {}
    }

    private static int indexOf(char c) {
        for (int i = 0; i < SHORT.length; i++) if (SHORT[i] == c) return i;
        return 0;
    }
}
