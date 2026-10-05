package com.yypm.assistant;

import android.content.Context;

/**
 * ★ v5.1：付费能力门面（接口层）
 * ★ v5.9：付费实现独立打包，本类只做转发与降级。
 *
 * 主流程只认这个类。它内部把调用转发给付费实现（若存在）：
 *   - 付费版：实现存在，转发给它
 *   - 免费版：实现不存在，全部返回兜底值
 *
 * 铁律：**母本永不删**。标记只是注释，不影响编译与运行。
 * 详见 README 的代码结构一节。
 */
public final class ProModule {

    private ProModule() {}

    /** 授权操作的回调。 */
    public interface Cb { void done(boolean ok, String msg); }


    // ============================================================ 能力开关

    /** 是否已激活（运行时判定，随卡密状态即时变化）。 */
    public static boolean activated() {
        return false;
    }

    /** 自动落子：是否允许代替用户点击落子。 */
    public static boolean autoMove() {
        return false;
    }

    /** 敌方预判：是否显示对手最多 3 个候选着法。 */
    public static boolean predict() {
        return false;
    }

    /** 分析区：是否在评分条上显示专业分析文字。 */
    public static boolean analyze() {
        return false;
    }

    /** 云库权限（天卡 / 周卡）。 */
    public static boolean hasCloud() {
        return false;
    }

    /** 多路候选：把付费版的路数折算成可用路数（未激活固定 1 路）。 */
    public static int multiPv(int paidPv) {
        return 1;
    }

    /** 未激活提示。 */
    public static String lockedHint() {
        return "（免费版：仅识别 + 一步最优提示）";
    }

    // ============================================================ 授权

    /** 启动时调用。 */
    public static void licenseInit(Context c) {
    }

    /** 向服务端复核。 */
    public static void licenseRefresh(Context c, Cb cb) {
    }

    /** 激活。 */
    public static void licenseActivate(Context c, String key, Cb cb) {
    }

    /** 清空本地授权。 */
    public static void licenseClear(Context c) {
    }

    /** 状态文字。 */
    public static String licenseStatus() {
        return "未激活";
    }

    /** 卡类型可读名。 */
    public static String licenseTypeName() {
        return "—";
    }

    /** 卡类型代码（心跳上报用，PERM / DAY / WEEK）。 */
    public static String typeCode() {
        return "";
    }

    /** 机器码。 */
    public static String licenseMachineCode(Context c) {
        return MachineCode.of(c);
    }

    /** 打码后的卡密。 */
    public static String licenseMaskedKey() {
        return "";
    }

    // ============================================================ 云库

    /** 把本地冷库读进内存。 */
    public static void cloudLoadLocal(Context c) {
    }

    /** 联网增量同步。 */
    public static void cloudSync(Context c) {
    }

    /** 调试钩子。 */
    public static void cloudDemoQuery(Context c) {
    }

    /** 查一个局面；未命中返回 null。 */
    public static PikafishEngine.Move[] cloudLookup(String fen) {
        return null;
    }
}
