package com.yypm.assistant;

import java.util.Locale;

/**
 * 设备能力自适应（v4.27）。
 *
 * <p>为什么要有这个类：这份 APK 原来只在一台旗舰机上跑过（8 核，引擎线程写死 4）。
 * 换到弱机会出三类问题：
 * <ul>
 *   <li>① 引擎线程写死 4 → 4 核机被占满，每步 0.7 秒里整台手机是僵的；</li>
 *   <li>② 识别轮询写死 400/700ms → 弱机单帧识别就要 1 秒以上，永远追不上，
 *       表现为「连线卡顿、延迟越来越大、节拍乱掉」；</li>
 *   <li>③ 内存峰值 345MB → 4GB 机容易被系统后台杀掉（悬浮窗突然消失）。</li>
 * </ul>
 *
 * <p>这个类只做纯计算、不碰 Android API，所以能被单元测试直接覆盖；
 * 真正「读硬件参数」的部分在 XqService 里做，读完把数值传进来。
 */
public class DeviceProfile {

    // ==================== 引擎线程 ====================

    /** 线程数上限。再多收益递减（象棋搜索的并行效率本来就低），而且更容易发热。 */
    public static final int ENGINE_THREADS_MAX = 4;

    /**
     * 引擎线程数 = min(4, 核数 − 保留核)。
     *
     * <p>为什么要减：搜索时所有线程都会跑满。若把核数用光，系统 UI、截屏、
     * 以及我们自己的 ONNX 识别都会抢不到 CPU —— 在那 0.7 秒里整台手机是僵的。
     * 低内存机多留一个核：它本来就在内存压力下，更容易被调度器惩罚。
     */
    public static int engineThreads(int cores, boolean lowRam) {
        if (cores < 1) cores = 1;
        int reserve = lowRam ? 2 : 1;
        int t = cores - reserve;
        if (t > ENGINE_THREADS_MAX) t = ENGINE_THREADS_MAX;
        if (t < 1) t = 1;
        return t;
    }

    /** 引擎置换表（MB）。低内存机减半 —— 省下来的是实打实的常驻内存。 */
    public static int engineHashMb(boolean lowRam) {
        return lowRam ? 32 : 64;
    }

    // ==================== 识别轮询 ====================

    public static final int LINK_MIN_MS = 300;
    public static final int LINK_MAX_MS = 1600;

    /** 普通机的余量系数：间隔 = 单帧耗时 × 1.6，即跑完一帧还能歇约 40% 的时间。 */
    public static final double LINK_SLACK = 1.6;
    /** 低内存机再放宽一些，别让 CPU 一直满载（发热和掉帧都从这来）。 */
    public static final double LINK_SLACK_LOWRAM = 2.2;

    /**
     * 自适应轮询间隔。
     *
     * <p>老代码是固定的 400ms（等对手）/ 700ms（算招/落子）。
     * 现在改成「不小于实测单帧识别耗时 × 余量系数」，并且有上下限：
     * 识别快的机器结果仍是原来的 400/700；识别慢的机器会自己放宽，
     * 避免出现「还没识别完就又去截一帧」的追尾。
     *
     * @param baseMs       这一阶段想要的间隔（400 / 700）
     * @param lastDetectMs 最近一次单帧识别的实测耗时；≤0 表示还没测出来
     * @param lowRam       是否低内存设备
     */
    public static int linkInterval(int baseMs, long lastDetectMs, boolean lowRam) {
        int v = baseMs;
        if (lastDetectMs > 0) {
            double f = lowRam ? LINK_SLACK_LOWRAM : LINK_SLACK;
            int need = (int) Math.round(lastDetectMs * f);
            if (need > v) v = need;
        }
        if (v < LINK_MIN_MS) v = LINK_MIN_MS;
        if (v > LINK_MAX_MS) v = LINK_MAX_MS;
        return v;
    }

    // ==================== 屏幕捕获帧率 ====================

    /**
     * 取帧最小间隔（ms）。
     * 120ms ≈ 8fps，本来就远超识别所需（识别每 400~700ms 才要一帧），
     * 多出来的帧只会在「14MB 位图转换」上白烧 CPU —— 这是发热的主因之一。
     * 低内存机放宽到 200ms（5fps），进一步降低压力。
     */
    public static long captureFrameGap(boolean lowRam) {
        return lowRam ? 200L : 120L;
    }

    // ==================== 性能分档 ====================

    /** 按 700ms 搜索实测的 nps 划三档。 */
    public static final long NPS_WEAK = 250000L;
    public static final long NPS_STRONG = 600000L;

    /** @return 0=轻量 1=标准 2=强劲 */
    public static int tierOf(long nps) {
        if (nps <= 0) return 1;              // 没测出来 → 按标准处理，最保险
        if (nps < NPS_WEAK) return 0;
        if (nps >= NPS_STRONG) return 2;
        return 1;
    }

    public static String tierName(int tier) {
        switch (tier) {
            case 0:  return "轻量";
            case 2:  return "强劲";
            default: return "标准";
        }
    }

    /**
     * 弱机是否默认关掉背景板。
     *
     * <p>背景板要多解一张 1272px 宽的图、每帧多合成一层 —— 在弱机上是纯负担。
     * 注意：这个「默认」只在用户**从未设置过**时生效（调用方负责判断），
     * 用户明确打开过就必须尊重他的选择。
     */
    public static boolean defaultBgOff(int tier, boolean lowRam) {
        return lowRam || tier == 0;
    }

    /** 人话描述，给设置页与日志用。 */
    public static String describe(int cores, boolean lowRam, int threads, int hashMb, int tier) {
        return String.format(Locale.US,
                "核数=%d  低内存=%s  引擎线程=%d  置换表=%dMB  性能档=%s",
                cores, lowRam ? "是" : "否", threads, hashMb, tierName(tier));
    }

    /**
     * ★ v4.28：给界面看的中文短描述。
     *
     * 跟 describe() 的区别：那个是给日志的（key=value，便于 grep），
     * 这个是给人看的（短、有分隔符、能塞进主界面一行）。
     */
    public static String describeCn(int cores, boolean lowRam, int threads, int hashMb, int tier) {
        StringBuilder sb = new StringBuilder();
        sb.append(cores).append(" 核");
        sb.append(" · 引擎 ").append(threads).append(" 线程");
        sb.append(" · 置换表 ").append(hashMb).append("MB");
        sb.append(" · 性能档 ").append(tierName(tier));
        if (lowRam) sb.append(" · 低内存模式");
        return sb.toString();
    }
}
