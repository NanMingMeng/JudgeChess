package com.yypm.assistant;

import java.util.Random;

/**
 * 走棋速度档位（纯逻辑，不依赖 Android，便于单测）。
 *
 * 为什么需要：5 分钟快棋一共 300 秒。之前固定「思考 2 秒 + 落子节流 1.5 秒」，
 * 一步端到端 5~6 秒，43 回合必然超时 —— 实测就是这么输的。
 * 现在极速档把一步压到 2 秒出头，快棋能保住时间；慢档留给不限时的局求棋力。
 */
public class Speed {

    /** 每档 = {基准思考ms, MultiPV, 落子节流ms, 落子生效确认窗口ms}
     *
     *  最后一列是「落子后等盘面更新」的窗口：游戏落子动画 + 多帧投票让识别结果
     *  落后点击 2~3 秒，窗口必须盖住它，否则会把「已生效但还没拍进画面」误判成
     *  「点击丢了」。实测 1600/2200ms 就是误判源头 —— 误判会触发重算重下，
     *  把整局节奏打乱。这里统一放宽到 3.2~4.4 秒。 */
    public static final int[][] TABLE = {
            {  250, 1,  350, 3200 },   // 0 极速
            {  700, 1,  600, 3400 },   // 1 快
            { 1500, 2,  900, 3800 },   // 2 正常（默认）
            { 3000, 3, 1500, 4400 },   // 3 慢
    };
    public static final String[] NAME = {"极速", "快", "正常", "慢"};
    public static final int DEFAULT = 1;   // 默认「快」：快棋不至于超时，又不失礼貌

    /** 一步的最短时间（思考 + 节流），防止退化成「一秒好几步」。 */
    public static final int MIN_STEP_MS = 600;

    /** 思考时间的绝对下限。 */
    public static final int MIN_THINK_MS = 120;

    public static int clamp(int level) {
        if (level < 0) return 0;
        if (level >= TABLE.length) return TABLE.length - 1;
        return level;
    }

    /** 循环到下一档：极速 → 快 → 正常 → 慢 → 极速。 */
    public static int next(int level) { return (clamp(level) + 1) % TABLE.length; }

    public static int base(int level) { return TABLE[clamp(level)][0]; }
    public static int multiPv(int level) { return TABLE[clamp(level)][1]; }
    public static int throttle(int level) { return TABLE[clamp(level)][2]; }
    public static int retry(int level) { return TABLE[clamp(level)][3]; }

    /**
     * 本步的思考时间（毫秒）。
     *
     * 真人化两点：
     *   ① 抖动：基准 ×0.72~1.28 —— 固定间隔最容易被看出是机器。
     *   ② 偶尔长考：1/8 概率 ×1.6，模拟人类遇到复杂局面会多想一会儿。
     * 并且保证「思考 + 节流」不低于 MIN_STEP_MS，避免快得不像人。
     */
    public static int thinkTime(int level, Random r) {
        int l = clamp(level);
        int b = base(l);
        double k = 0.72 + r.nextDouble() * 0.56;
        if (r.nextInt(8) == 0) k *= 1.6;
        int t = (int) (b * k);
        if (t < MIN_THINK_MS) t = MIN_THINK_MS;
        int th = throttle(l);
        if (t + th < MIN_STEP_MS) t = MIN_STEP_MS - th;
        if (t < MIN_THINK_MS) t = MIN_THINK_MS;
        return t;
    }

    /**
     * 一步理论最短与最长耗时（思考 + 节流 + 后续落子间隔），用于自检和状态提示。
     * 注意不含识别时间（那部分由帧率决定，通常 1~1.5 秒）。
     */
    public static int[] stepRange(int level) {
        int l = clamp(level);
        int b = base(l), th = throttle(l);
        int min = (int) (b * 0.72);
        if (min < MIN_THINK_MS) min = MIN_THINK_MS;
        if (min + th < MIN_STEP_MS) min = MIN_STEP_MS - th;
        int max = (int) (b * 1.28 * 1.6);
        return new int[]{min + th, max + th};
    }

    /** 人类可读描述，用于状态行。 */
    public static String describe(int level) {
        int l = clamp(level);
        return NAME[l] + "（思考约 "
                + String.format(java.util.Locale.US, "%.1f", TABLE[l][0] / 1000.0)
                + "s，" + TABLE[l][1] + " 路）";
    }
}
