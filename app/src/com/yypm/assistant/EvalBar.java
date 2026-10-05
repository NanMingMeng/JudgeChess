package com.yypm.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * 形势条（优势条）：对齐天天象棋「显示分析」的评分可视化。
 *
 * 画法：
 *   一条横向长条，左侧红方、右侧黑方。
 *   引擎给的 centipawn 分数换算成一个 0..1 的占比 p，
 *   红色部分宽度 = W * p、黑色部分 = 剩余。
 *   p = 0.5 表示均势（各占一半）；红优则红段变长。
 *
 * 映射用 tanh 而不是线性：
 *   线性映射下 +3 分就爆满，看不出 +0.3 和 +1.5 的区别；
 *   tanh(cp/400) 让 0 附近细腻、大分差迅速饱和，符合棋感。
 *
 * 杀棋（mate）直接给到极端值（3% / 97%），并显示 M 字样。
 *
 * 尺寸：整体 26dp（原先写死 34px，在 3.5 密度屏上只有 9.7dp，又扁又小）。
 *      文字压在条中央，并加黑描边，保证落在红段 / 黑段上都看得清。
 */
public class EvalBar extends View {

    private int scoreCp = 0;
    private boolean hasMate = false;
    private int mate = 0;
    /** 引擎给出的分数是从「当前走子方」视角算的，红方视角要翻一下。 */
    private char sideToMove = 'w';
    private String text = "";
    /** ★ v4.65：是否在条中央画分数文字。悬浮窗里只要条、不要文字 → false。 */
    private boolean showText = true;

    /** 整体高度（dp）。 */
    private static final float TOTAL_DP = 20f;
    /** 条顶部留白（dp）。 */
    private static final float GAP_DP = 3f;

    private static final int C_RED   = 0xFFE53935;
    private static final int C_BLACK = 0xFF37474F;
    private static final int C_BG    = 0xFF101418;

    private final Paint redP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blackP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlineP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    public EvalBar(Context c) { this(c, null); }

    public EvalBar(Context c, AttributeSet a) {
        super(c, a);
        redP.setColor(C_RED);
        redP.setStyle(Paint.Style.FILL);
        blackP.setColor(C_BLACK);
        blackP.setStyle(Paint.Style.FILL);
        bgP.setColor(C_BG);
        bgP.setStyle(Paint.Style.FILL);
        markP.setColor(0xFF000000);
        markP.setStyle(Paint.Style.FILL);

        textP.setColor(0xFFFFFFFF);
        textP.setTextAlign(Paint.Align.CENTER);
        textP.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        // 描边用：在四个方向各偏移一点画黑字，再叠白字
        outlineP.setColor(0xC0000000);
        outlineP.setTextAlign(Paint.Align.CENTER);
        outlineP.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    /**
     * 更新形势。
     *
     * @param cp          centipawn（引擎视角，即「走子方」视角）
     * @param mateScore   杀棋步数；hasMate=false 时忽略
     * @param sideToMove  该分数对应的走子方（'w'=红，'b'=黑）
     */
    public void setEval(int cp, boolean hasMate, int mateScore, char sideToMove) {
        this.scoreCp = cp;
        this.hasMate = hasMate;
        this.mate = mateScore;
        this.sideToMove = sideToMove;
        this.text = scoreLabel();
        postInvalidate();
    }

    /** ★ v4.65：true=条中央画分数文字，false=只画条。 */
    public void setShowText(boolean s) { this.showText = s; postInvalidate(); }

    /** ★ v4.70：条上显示的分析文字（空=回退默认 scoreLabel）。 */
    public void setAnalysis(String s) {
        this.text = (s == null || s.isEmpty()) ? scoreLabel() : s;
        postInvalidate();
    }

    /** 换算成「红方视角」的分数（红优为正）。 */
    private int redViewCp() {
        return sideToMove == 'b' ? -scoreCp : scoreCp;
    }

    public String scoreLabel() {
        if (hasMate) {
            // mate 是从走子方视角的「几步杀」；正数=走子方杀，负数=被杀
            int v = (sideToMove == 'b') ? -mate : mate;
            return (v > 0 ? "红 杀 " : "黑 杀 ") + Math.abs(v);
        }
        double s = redViewCp() / 100.0;
        if (Math.abs(s) < 0.005) return "均势";
        return (s > 0 ? "红 +" : "黑 +") + String.format(java.util.Locale.US, "%.2f", Math.abs(s));
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        if (w <= 0) w = 300;
        float d = getResources().getDisplayMetrics().density;
        setMeasuredDimension(w, (int) (TOTAL_DP * d + 0.5f));
    }

    /** ★ v4.52：是否用「三段渐变」。onDraw 里不能有未捕获异常，所以在这里兜住。 */
    private boolean eval3() {
        try {
            return XqService.spIntOf(getContext(), XqService.K_UI_EVAL3, 1) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    protected void onDraw(Canvas cv) {
        int W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0) return;
        float d = getResources().getDisplayMetrics().density;

        float barTop = GAP_DP * d;
        float barBot = H;
        if (barBot - barTop < 6 * d) barTop = 0;
        float barH = barBot - barTop;
        float rr = barH * 0.5f;

        // 占比
        float p;
        if (hasMate) {
            int v = (sideToMove == 'b') ? -mate : mate;
            p = v > 0 ? 0.97f : 0.03f;
        } else {
            double x = redViewCp() / 400.0;
            p = (float) (0.5 + 0.5 * Math.tanh(x));
            if (p < 0.03f) p = 0.03f;
            if (p > 0.97f) p = 0.97f;
        }

        // 底 → 红段（左）→ 黑段（右）
        r.set(0, barTop, W, barBot);
        cv.drawRoundRect(r, rr, rr, bgP);
        final float bx = W * p;
        if (eval3()) {
            // ★ v4.52：三段渐变 —— 红端（最左）最艳、分界处最中性、黑端（最右）最沉。
            //   优势有多大不只靠谁占得多，颜色深浅本身就读得出来。
            redP.setShader(new LinearGradient(0f, 0f, Math.max(1f, bx), 0f,
                    0xFFF44336, 0xFF7F2C28, Shader.TileMode.CLAMP));
            blackP.setShader(new LinearGradient(bx, 0f, W, 0f,
                    0xFF4E5C64, 0xFF20272B, Shader.TileMode.CLAMP));
        } else {
            redP.setShader(null);
            blackP.setShader(null);
        }
        r.set(0, barTop, bx, barBot);
        cv.drawRoundRect(r, rr, rr, redP);
        r.set(bx, barTop, W, barBot);
        cv.drawRoundRect(r, rr, rr, blackP);

        // 分界刻度 + 均势参考线
        float x = W * p;
        cv.drawRect(x - 1.2f, barTop, x + 1.2f, barBot, markP);
        cv.drawRect(W * 0.5f - 0.6f, barTop, W * 0.5f + 0.6f, barBot, bgP);

        // 分数文字：黑描边 + 白字，压在条上（showText=false 时只画条不画字）
        if (showText) {
            float tsz = barH * 0.60f;
            textP.setTextSize(tsz);
            // ★ v4.70：长文字（如「黑方绝杀 M1　推荐 马2进3」）自动缩字号，不超出条宽
            float tw = textP.measureText(text);
            if (tw > W * 0.92f && tw > 0f) {
                tsz = tsz * (W * 0.92f) / tw;
                textP.setTextSize(tsz);
            }
            outlineP.setTextSize(tsz);
            Paint.FontMetrics fm = textP.getFontMetrics();
            float base = (barTop + barBot) / 2f - (fm.ascent + fm.descent) / 2f;
            float o = Math.max(1.2f, tsz * 0.055f);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    cv.drawText(text, W / 2f + dx * o, base + dy * o, outlineP);
                }
            }
            cv.drawText(text, W / 2f, base, textP);
        }
    }
}
