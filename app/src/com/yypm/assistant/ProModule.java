package com.yypm.assistant;

/**
 * ★ v5.1：付费能力接口（接口层）
 *
 * 作用：把「付费功能是否可用」集中到一处，让主流程只依赖接口、不依赖实现。
 *   - 母本 / 付费版：activated() 由卡密授权状态决定（License 判定）
 *   - 免费版：净化脚本删掉 PAID 块、启用 FREE 块 → activated() 恒为 false
 *
 * 铁律：**母本永不删**。标记只是注释，不影响编译与运行。
 * 详见《审判者免费版净化说明.md》。
 */
public final class ProModule {

    private ProModule() {}

    /** 免费版：付费功能永远不可用。 */
    public static boolean activated() { return false; }

    /** 自动落子：是否允许代替用户点击落子。 */
    public static boolean autoMove() { return activated(); }

    /** 敌方预判：是否显示对手最多 3 个候选着法。 */
    public static boolean predict() { return activated(); }

    /** 分析区：是否在评分条上显示专业分析文字。 */
    public static boolean analyze() { return activated(); }

    /**
     * 多路候选：把付费版的路数折算成免费版可用的路数。
     * 未激活时固定 1 路（只给一步最优）。
     */
    public static int multiPv(int paidPv) { return activated() ? paidPv : 1; }

    /** 未激活提示（免费版）。 */
    public static String lockedHint() { return "（免费版：仅识别 + 一步最优提示）"; }

    /** 卡类型代码（免费版无授权，恒空）。 */
    public static String typeCode() { return ""; }

    /** 云库权限（免费版无）。 */
    public static boolean hasCloud() { return false; }
}
