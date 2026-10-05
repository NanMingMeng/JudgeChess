package com.yypm.assistant;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 设置 / 个性化（v4.7）。
 *
 * 为什么单独开一页而不是塞进主界面：
 *   主界面已经有标题 + 状态横幅 + 两个大按钮 + 四张权限卡 + 实时看盘 + FEN，
 *   再内嵌一大坨设置会滚很久。独立页还能拿到完整高度去放预览。
 *
 * ★ 改一项就立刻生效：写 SharedPreferences 之后调 XqService.applyPersonalization()，
 *   它会重建悬浮窗 —— 不用重启 App，回到棋盘上抬头就是新的样子。
 *
 * ★ 配置读写走的是 XqService 的静态方法（spPutInt / spStr / uiInt），
 *   所以**无障碍服务没开的时候这一页也能用**（appCtx 兜底）。
 */
public class SettingsActivity extends Activity {

    private static final int REQ_PICK = 7001;

    private LinearLayout root;
    /** 每个分组里的「选项按钮」，改选时统一刷新选中态。 */
    private java.util.List<Button> boardSideBtns = new java.util.ArrayList<>();
    private java.util.List<Button> cardBtns = new java.util.ArrayList<>();
    private java.util.List<Button> bgBtns = new java.util.ArrayList<>();
    private java.util.List<Button> animBtns = new java.util.ArrayList<>();
    /** v4.8：对局选项（从悬浮窗挪来的四项）。 */
    private java.util.List<Button> sideBtns = new java.util.ArrayList<>();
    private java.util.List<Button> spBtns = new java.util.ArrayList<>();
    private java.util.List<Button> anaBtns = new java.util.ArrayList<>();
    private java.util.List<Button> fgBtns = new java.util.ArrayList<>();
    private TextView tvAlphaVal, tvBgImg;
    /** ★ v4.78：界面背景主题的宿主根视图（= onCreate 的 outer）。 */
    private View themeHost;
    /** ★ v4.78：界面背景主题的选项按钮。 */
    private final java.util.List<Button> themeBtns = new java.util.ArrayList<>();
    /** v4.28：配置快照的「上次保存」状态行。 */
    private TextView tvCfgStatus;
    private SeekBar sbAlpha;
    /** ★ v4.33：顶部留白 —— 高度撑到「真悬浮窗下沿」，真面板就浮在这块上面。 */
    private View spacerTop;
    private int insetTop = 0;
    private boolean spacerRetry = false;
    /** ★ v4.46：顶部留白的**冻结值**。
     *  进入本页时算一次，之后整页布局不再变 —— 否则拖动「悬浮窗上下位置」时
     *  留白跟着变，整个设置页会像一张图一样上下滑动（用户反馈）。 */
    private int spacerPx = -1;
    /** v4.42：用户截屏回调（API 34+）。必须留着引用，否则会被回收掉。 */
    private android.app.Activity.ScreenCaptureCallback shotCb;
    /** ★ v4.33：一级页（入口列表，像系统设置那样一项进一页）。 */
    private LinearLayout homeBox;
    /** ★ v4.36：二级页当前正在填的卡片（两个 miniHead 之间自动成一组）。 */
    private LinearLayout curCard;
    /** ★ v4.33：标题 —— 一级页显示「设置」，子页显示「设置 · 棋盘」。 */
    private TextView tvTitle;
    /** v4.13：分组锚点（点上面的分类 chip 就滚到这里）。 */
    private final java.util.LinkedHashMap<String, View> anchors =
            new java.util.LinkedHashMap<String, View>();
    /** 设置项所在的 ScrollView。 */
    private ScrollView svRef;
    /** v4.18：当前分类（0~3）。切列会 recreate 整页，靠它回到原来那一组。 */
    private int curTab = 0;
    /** v4.15：四个分组容器 —— 一次只显示一个（点上面的分类按钮切换）。 */
    private final java.util.LinkedHashMap<String, LinearLayout> groups =
            new java.util.LinkedHashMap<String, LinearLayout>();
    /** 当前正在往里加控件的分组容器。 */
    private LinearLayout curGroup;
    /** 分类按钮（选中态高亮）。 */
    private Button[] tabBtns;

    /** 启动动画选项（id 与 XqService 约定一致；扫光不做，因为要逐帧裁剪，代价大收益小）。 */
    private static final String[] ANIM_NAME = {"呼吸对焦", "化形", "拉幕", "推入", "关闭"};
    private static final int[] ANIM_ID = {0, 1, 2, 3, 5};
    /** v4.15：四个分类（tab 按钮的文字 = 分组容器的键）。 */
    private static final String[] GROUP_KEYS = {"棋盘", "背景", "动画", "对局"};
    /** ★ v4.33：当前在第几级 —— 0 = 一级入口列表，1 = 某个子页（recreate 后要回到原处）。 */
    private static final String K_LEVEL = "ui_level";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        XqService.appCtx = getApplicationContext();   // 服务没起来时也能读写配置
        // 调试钩子：外部目录放一个 demo_ana 文件 → 把「分析区」设为展开。
        // 用途：本机 shell 的点击到不了 App（会被前台抢焦点），没法点按钮验证预览，
        // 就靠这个文件把状态摆好再截图核对（和 files/selftest / demo_intro / demo_blink 同套路）。
        try {
            java.io.File ext = getExternalFilesDir(null);
            if (ext != null && new File(ext, "demo_ana").exists())
                XqService.spPutInt(XqService.K_ANA, 1);
            // demo_order：把左栏顺序整个倒过来，用来验证「重排不会丢构件」。
            if (ext != null && new File(ext, "demo_order").exists()) {
                XqService.spPutStr(XqService.K_UI_ORDER_COL, "ana,status,reply,move,eval,btn");
                XqService.spPutStr(XqService.K_UI_ORDER_BTN, "light,one,link");
                XqService.spPutStr(XqService.K_UI_ORDER_RIGHT, "collapse,grip,close");
            }
        } catch (Throwable ignored) {}

        final int pad = dp(16);
        final int baseTop = dp(20);
        final int baseBottom = dp(40);

        // ★ v4.7：整页 = [固定区：标题 + 悬浮窗预览] + [可滚动的设置项]。
        //   用户点名要的：预览必须**像真悬浮窗一样钉在屏幕最上方** ——
        //   不然往下翻到「背景板 / 启动动画」时预览已经滚出屏幕，
        //   一边调一边看不见，就等于没有预览。
        //   所以预览**不放进 ScrollView**，而是跟标题一起固定在顶部。
        final LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        UiTheme.apply(outer, UiTheme.current(this));
        themeHost = outer;
        setContentView(outer);

        // ★ v4.33：这里不再放「预览面板」。顶上留出的这块空白，正好就是
        //   真悬浮窗落下来的位置 —— 打开本页时服务会把真面板亮出来盖在上面，
        //   所以下面改的每一项，看到的就是棋盘上那块真身。
        spacerTop = new View(this);
        outer.addView(spacerTop, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0));

        final LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(pad, baseTop, pad, 0);
        outer.addView(head);

        final ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, baseBottom);
        sv.addView(root);
        outer.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        svRef = sv;
        // ★ v4.33：一级页容器。放在所有子页前面 —— 一次只显示它一个。
        homeBox = new LinearLayout(this);
        homeBox.setOrientation(LinearLayout.VERTICAL);
        homeBox.setPadding(pad, 0, pad, 0);
        root.addView(homeBox);

        for (int gi = 0; gi < GROUP_KEYS.length; gi++) {
            LinearLayout g = new LinearLayout(this);
            g.setOrientation(LinearLayout.VERTICAL);
            g.setPadding(pad, 0, pad, 0);
            g.setVisibility(View.GONE);      // 稍后由 showGroup(curTab) 统一打开
            root.addView(g);
            groups.put(GROUP_KEYS[gi], g);
        }
        curGroup = groups.get(GROUP_KEYS[0]);
        // v4.18：恢复上次停留的分类（切「按钮排/右栏」会重建整页，不恢复就跳回第一组）
        curTab = XqService.spIntOf(this, XqService.K_UI_TAB, 0);
        if (curTab < 0 || curTab >= GROUP_KEYS.length) curTab = 0;
        // ---- 全屏独立页：状态栏/挖孔（本机 141px）不能压住标题和返回键 ----
        try {
            getWindow().setStatusBarColor(0xFF0B1014);
            getWindow().setNavigationBarColor(0xFF0B1014);
        } catch (Throwable ignored) {}
        outer.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets ins) {
                int top = 0, bot = 0;
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        android.graphics.Insets si = ins.getInsets(
                                android.view.WindowInsets.Type.systemBars()
                                        | android.view.WindowInsets.Type.displayCutout());
                        top = si.top;
                        bot = si.bottom;
                    }
                } catch (Throwable ignored) {}
                head.setPadding(pad, baseTop + top, pad, 0);
                root.setPadding(0, 0, 0, baseBottom + bot);
                insetTop = top;
                layoutSpacer();
                return ins;
            }
        });

        // ---------- 顶部：标题 + 返回 ----------
        LinearLayout hd = new LinearLayout(this);
        hd.setOrientation(LinearLayout.HORIZONTAL);
        hd.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(0xFF7ED321);
        back.setPadding(0, 0, dp(12), 0);
        back.setOnClickListener(new View.OnClickListener() {
            // ★ v4.33：子页里按返回 = 回一级列表；已经在一级页才退出本页
            public void onClick(View v) { goBack(); }
        });
        hd.addView(back);
        tvTitle = new TextView(this);
        tvTitle.setText("设置");
        tvTitle.setTextSize(19);
        tvTitle.setTextColor(0xFFF2F6FA);
        tvTitle.getPaint().setFakeBoldText(true);
        hd.addView(tvTitle);
        head.addView(hd);

        // 调试钩子：files/demo_board（内容「缩放 / 左右 / 上下」，各占一行）→ 启动就摆好棋盘参数。
        // 用途同 demo_tab / demo_ana：本机 shell 的点击会被前台抢焦点、拖不了滑杆，
        // 就靠它把参数摆出来，再截图 / 读日志核对。
        try {
            java.io.File bf = new File(getExternalFilesDir(null), "demo_board");
            if (bf.exists()) {
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(bf)));
                String l1 = br.readLine();
                String l2 = br.readLine();
                String l3 = br.readLine();
                br.close();
                if (l1 != null && l1.trim().length() > 0)
                    XqService.spPutInt(XqService.K_UI_BOARD_SCALE, Integer.parseInt(l1.trim()));
                if (l2 != null && l2.trim().length() > 0)
                    XqService.spPutInt(XqService.K_UI_BOARD_DX, Integer.parseInt(l2.trim()));
                if (l3 != null && l3.trim().length() > 0)
                    XqService.spPutInt(XqService.K_UI_BOARD_DY, Integer.parseInt(l3.trim()));
            }
        } catch (Throwable ignored) {}

        // ★ v4.33：一级页 = 入口列表（棋盘 / 背景 / 动画 / 对局 / 配置快照），
        //   点哪一项就进哪一页 —— 和手机系统设置一样的层级。
        closeCard();   // v4.36：二级页卡片收尾，之后才开始排一级页
        buildHomeRows(homeBox);
        // v4.51：「按钮风格（圆润 / 方角）」整块删除 —— 用户要求去掉这个功能。
        //       样式固定为圆润（原来默认方角）。
        // 调试钩子：外部目录放 demo_tab（内容 0~3）→ 启动就切到那一组。
        // 用途同 demo_ana / demo_order：本机 shell 的点击会被前台抢焦点，点不了按钮，
        // 就靠这个文件把状态摆好，再用 uiautomator dump 核对。
        try {
            java.io.File tf = new File(getExternalFilesDir(null), "demo_tab");
            if (tf.exists()) {
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(tf)));
                String ln = br.readLine();
                br.close();
                if (ln != null) {
                    int gi2 = Integer.parseInt(ln.trim());
                    if (gi2 >= 0 && gi2 < GROUP_KEYS.length) showGroup(gi2);
                }
            }
        } catch (Throwable ignored) {}

        TextView hint = new TextView(this);
        hint.setText("屏幕上方那块就是真悬浮窗本身，改一项它立刻跟着变。");
        hint.setTextSize(11);
        hint.setTextColor(0xFF7E8B98);
        hint.setPadding(0, dp(8), 0, dp(6));
        head.addView(hint);

        // ---------- 1. 布局 ----------
        closeCard();                            // v4.38：先把上一页的卡收进上一页
        curGroup = groups.get(GROUP_KEYS[0]);   // 棋盘
        miniHead("悬浮窗");                      // v4.39：本页拆成两张卡，别一整页一张
        // ★ v4.32：悬浮窗上下位置（用户点名：只要能上下移动）。
        //   面板尺寸由 panel_fixed_h 写死，这里只挪位置，不改大小。
        // ★ v4.54：上限与「拖动面板」用同一个值（面板底边贴屏幕底），
        //   否则拖到 240dp 以下的位置，隐藏再恢复会被钳回来。
        sliderRow("悬浮窗上下位置", XqService.K_UI_PANEL_DY, 0, 0,
                XqService.panelDyMax(this), "dp", false);
        note("0 = 贴屏幕最顶端（盖住状态栏），往右拉整块往下挪。只挪位置，不改悬浮窗大小。");
        boardSideBtns.clear();
        miniHead("棋盘");
        row("棋盘位置",
                new String[]{"左侧", "右侧"},
                new int[]{0, 1},
                XqService.uiInt(XqService.K_UI_BOARD_SIDE, 0),
                boardSideBtns,
                new OnPick() {
                    public void onPick(int v) {
                        XqService.spPutInt(XqService.K_UI_BOARD_SIDE, v);
                        apply();
                    }
                });
        note("棋盘在左：方块压的是头发，人像的脸能露出来；在右就是 v4.6 以前的样子。");

        // ★ v4.21：棋盘大小 / 位置微调
        //   原来棋盘尺寸是「按左栏高度自动撑满」，用户看不惯也只能忍着；
        //   现在给一个缩放比 + 上下左右微调，改完预览里立刻能看到。
        sliderRow("棋盘大小", XqService.K_UI_BOARD_SCALE, XqService.K_UI_BOARD_SCALE_DEF,
                50, 200, "%", false);
        note("100% = 原来那样（棋盘高度自动对齐左栏）。想让棋盘更大就往右拉 ——"
                + "拉到 200% 时棋盘能长到和悬浮窗差不多高，而悬浮窗本身大小不变。"
                + "放大的代价是左边文字区会变窄，所以不会百分百填满。");

        sliderRow("棋盘左右微调", XqService.K_UI_BOARD_DX, 0, -60, 60, "dp", true);
        sliderRow("棋盘上下微调", XqService.K_UI_BOARD_DY, 0, -60, 60, "dp", true);
        note("微调只是把棋盘挪个位置，不改面板大小 —— 所以挪开的地方会留空。"
                + "想让棋盘居中，一般是「调小 + 往下挪一点」。");

        cardBtns.clear();
        row("控制栏底",
                new String[]{"不加", "加卡底"},
                new int[]{0, 1},
                XqService.uiInt(XqService.K_UI_CTRL_CARD, 0),
                cardBtns,
                new OnPick() {
                    public void onPick(int v) {
                        XqService.spPutInt(XqService.K_UI_CTRL_CARD, v);
                        apply();
                    }
                });
        note("实测：控制栏本来就没有底色，只有按钮不透明 —— 所以「不加」时人像反而更透。");

        // ---------- 2. 对局选项（v4.9：悬浮窗上的每一块都在这里管）----------
        // 为什么合成一块：以前「对局选项」和「悬浮窗按键」分成两节，
        // 想关掉某个按钮得跑到下面另找一节。现在每个构件一行，
        // 它的功能设定和显示开关就在同一行上，不用来回翻。
        closeCard();                            // v4.38：先把上一页的卡收进上一页
        curGroup = groups.get(GROUP_KEYS[3]);   // 对局
        note("悬浮窗上的每一块都在这里：右边的开关打开就显示，关掉就隐藏。"
                + "隐藏的构件保留占位、只是看不见，所以其余部分的位置不会跑。");

        miniHead("棋盘与信息");
        note("「↑ 上移 / ↓ 下移」调它在悬浮窗上的位置；右边的开关管它出不出现。");

        moveRow("按钮排（链 / 识 / 自 整排位置）",
                XqService.K_UI_ORDER_COL, XqService.ORDER_COL_DEF, "btn");
        visRow("小棋盘", XqService.K_UI_SHOW_BOARD);
        visMoveRow("分析条（优势条）",
                XqService.K_UI_ORDER_COL, XqService.ORDER_COL_DEF, "eval",
                XqService.K_UI_SHOW_EVAL);
        visMoveRow("本局计时",
                XqService.K_UI_ORDER_COL, XqService.ORDER_COL_DEF, "move",
                XqService.K_UI_SHOW_MOVE);
        visMoveRow("状态行",
                XqService.K_UI_ORDER_COL, XqService.ORDER_COL_DEF, "status",
                XqService.K_UI_SHOW_STATUS);
        note("小棋盘的位置用「棋盘大小 / 左右微调 / 上下微调」来摆；"
                + "悬浮窗本身的大小是固定的，改任何设置都不会变。");

        miniHead("构件自由排版（避开人物眼睛）");
        note("每个构件都能独立左右/上下挪动，把信息挪到不挡背景人物眼睛的位置。");
        sliderRow("按钮排 左右", XqService.K_UI_BTN_DX, 0, -60, 60, "dp", true);
        sliderRow("按钮排 上下", XqService.K_UI_BTN_DY, 0, -60, 60, "dp", true);
        sliderRow("分析条 左右", XqService.K_UI_EVAL_DX, 0, -60, 60, "dp", true);
        sliderRow("分析条 上下", XqService.K_UI_EVAL_DY, 0, -60, 60, "dp", true);
        sliderRow("计时 左右", XqService.K_UI_MOVE_DX, 0, -60, 60, "dp", true);
        sliderRow("计时 上下", XqService.K_UI_MOVE_DY, 0, -60, 60, "dp", true);
        sliderRow("状态 左右", XqService.K_UI_STATUS_DX, 0, -60, 60, "dp", true);
        sliderRow("状态 上下", XqService.K_UI_STATUS_DY, 0, -60, 60, "dp", true);
        note("负值向左/向上，正值向右/向下。挪开的地方会留空 —— 这是「微调」不是「改布局」。");

        closeCard();
        headTo(curGroup, "按键");
        note("每个按键一张卡：「↑↓」调它在悬浮窗上的位置，右边的开关管它出不出现。");

        unitCard();

        // v4.50：右栏 = 链 / 识 / 自 / ✕ / ⠿（用户要求把前三个搬过去）。
        //        「—」（折叠）已删除 —— 功能由音量减键代替。
        panelBtnRow("链（连线 / 开捕获）", "link", XqService.K_UI_SHOW_LINK);
        unitCard();
        panelBtnRow("识（识别一步给建议）", "one", XqService.K_UI_SHOW_ONE);
        unitCard();
        panelBtnRow("自（自动走子开关，长按切速度）", "light", XqService.K_UI_SHOW_LIGHT);
        unitCard();
        visMoveRow("✕（关闭面板）",
                XqService.K_UI_ORDER_RIGHT, XqService.ORDER_RIGHT_DEF, "close",
                XqService.K_UI_SHOW_CLOSE);
        unitCard();
        visMoveRow("⠿（拖动把手）",
                XqService.K_UI_ORDER_RIGHT, XqService.ORDER_RIGHT_DEF, "grip",
                XqService.K_UI_SHOW_GRIP);
        unitCard();
        panelBtnRow("连开（绝杀后自动再来一局）", "lk", XqService.K_UI_SHOW_LK);
        unitCard();
        note("这五项都在右侧竖列里、从上到下排：「↑↓」调它们的上下位置。"
                + "「—」（折叠成小球）已取消 —— 用音量减键收起更省事。");
        note("注意：关掉「链」之后，悬浮窗上就没有开屏幕捕获的入口了 —— 要回主界面点「重新授权」。"
                + "关掉「⠿」就不能拖动面板。");
        miniHead("棋力与判定");
        sideBtns.clear();
        row("执子",
                new String[]{"自动", "执红", "执黑"},
                new int[]{0, 1, 2},
                XqService.getSidePref(this),
                sideBtns,
                new OnPick() {
                    public void onPick(int v) {
                        XqService.putEnginePref(SettingsActivity.this, XqService.K_UI_SIDE, v);
                    }
                });
        note("自动识别偶尔判反时，在这里直接指定，最稳。（原来悬浮窗上的「红」按钮。）");

        spBtns.clear();
        row("走棋速度",
                Speed.NAME,
                new int[]{0, 1, 2, 3},
                XqService.getSpeedPref(this),
                spBtns,
                new OnPick() {
                    public void onPick(int v) {
                        XqService.putEnginePref(SettingsActivity.this, XqService.K_SPEED, v);
                    }
                });
        note("对局中想临时改速度：「自」长按仍可循环切换。");

        fgBtns.clear();
        row("前台校验",
                new String[]{"严格", "宽松", "关闭"},
                new int[]{0, 1, 2},
                XqService.getFgModePref(this),
                fgBtns,
                new OnPick() {
                    public void onPick(int v) {
                        XqService.putEnginePref(SettingsActivity.this, XqService.K_FGMODE, v);
                    }
                });
        note("严格=只认象棋类 App；宽松=除本应用和系统界面外都识别；关闭=任何界面都识别。"
                + "（原来藏在「长按 识」里。）");

        // ★ v4.27：设备能力自检结果（只读展示）。
        //   为什么不给手动调：这些参数是「按硬件测得」的，让用户手调只会把弱机调得更糟。
        //   要重测就删一次配置（pm clear）。
        TextView devTv = new TextView(this);
        devTv.setTextSize(11);
        devTv.setTextColor(0xFFC6D2DC);
        devTv.setPadding(0, dp(14), 0, 0);
        String devDesc = XqService.spStrOf(this, XqService.K_DEV_DESC, "");
        if (devDesc == null || devDesc.length() == 0)
            devDesc = "（尚未检测 —— 打开一次悬浮窗后自动完成）";
        devTv.setText("本机能力自检\n" + devDesc);
        addV(devTv);
        note("首次启动会自动测一次并据此调参：引擎线程按核数留出系统开销（4 核机用 3 线程，"
                + "不再把核心占满）；识别慢的机器自动放宽轮询间隔，避免「还没识别完又去截一帧」；"
                + "低内存机默认关掉背景板、并把取帧频率从 8fps 降到 5fps。"
                + "「恢复默认」不会动这一组参数。");

        // ---------- 3a. 界面背景（App 界面主题） ----------
        closeCard();
        curGroup = groups.get(GROUP_KEYS[1]);   // 背景
        miniHead("界面背景");
        buildThemeSection();
        miniHead("卡片正面与背面");
        buildHoloCardSection();

        // ---------- 3. 背景板（人物封面） ----------
        closeCard();                            // v4.38：先把上一页的卡收进上一页
        curGroup = groups.get(GROUP_KEYS[1]);   // 背景
        miniHead("背景板");                      // v4.39：本页拆成一张卡
        bgBtns.clear();
        row("启用",
                new String[]{"开", "关"},
                new int[]{1, 0},
                XqService.uiInt(XqService.K_UI_BG_ON, 1),
                bgBtns,
                new OnPick() {
                    public void onPick(int v) {
                        XqService.spPutInt(XqService.K_UI_BG_ON, v);
                        apply();
                    }
                });

        tvBgImg = new TextView(this);
        tvBgImg.setTextSize(11);
        tvBgImg.setTextColor(0xFFA8B4C0);
        tvBgImg.setPadding(0, dp(8), 0, dp(4));
        addV(tvBgImg);
        updateBgImgLabel();

        LinearLayout imgRow = new LinearLayout(this);
        imgRow.setOrientation(LinearLayout.HORIZONTAL);
        Button bDefault = ghost("用内置图片");
        bDefault.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                XqService.spPutStr(XqService.K_UI_BG_IMG, "");
                updateBgImgLabel();
                apply();
            }
        });
        Button bPick = primary("从相册选一张");
        bPick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickImage(); }
        });
        imgRow.addView(bDefault, lpRow(1));
        imgRow.addView(bPick, lpRow(1));
        addV(imgRow);
        note("选中的图会拷进 App 私有目录，之后删掉相册原图也不影响。");

        LinearLayout alphaHead = new LinearLayout(this);
        alphaHead.setOrientation(LinearLayout.HORIZONTAL);
        alphaHead.setGravity(Gravity.CENTER_VERTICAL);
        alphaHead.setPadding(0, dp(10), 0, 0);
        TextView al = new TextView(this);
        al.setText("不透明度");
        al.setTextSize(12);
        al.setTextColor(0xFFC6D2DC);
        alphaHead.addView(al);
        tvAlphaVal = new TextView(this);
        tvAlphaVal.setTextSize(12);
        tvAlphaVal.setTextColor(0xFF7ED321);
        tvAlphaVal.getPaint().setFakeBoldText(true);
        tvAlphaVal.setPadding(dp(8), 0, 0, 0);
        alphaHead.addView(tvAlphaVal);
        addV(alphaHead);

        sbAlpha = new SeekBar(this);
        sbAlpha.setMax(100);   // v4.52：55 → 100，遮罩与亮度解耦后人物可以拉亮
        sbAlpha.setProgress(XqService.uiInt(XqService.K_UI_BG_ALPHA, XqService.K_UI_BG_ALPHA_DEF));
        showAlpha();
        sbAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                showAlpha();
                // 拖着就能看到真悬浮窗里的人像变浓变淡（只改 alpha，不重建窗口）
                if (fromUser) XqService.liveCoverAlpha(p / 100f);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {
                XqService.spPutInt(XqService.K_UI_BG_ALPHA, s.getProgress());
                apply();
            }
        });
        addV(sbAlpha);
        note("0 = 完全不要背景板。选好下面「人物与文字」的方案之后，"
                + "这里就只是「人物有多亮」，建议直接拉到 80 以上。");

        // ★ v4.52：人物与文字方案 —— 解耦「人物亮度」和「遮罩强度」。
        //   选方案时会自动把上面的不透明度带到推荐值，不用手动配。
        miniHead("人物与文字");
        final java.util.List<Button> styleBtns = new java.util.ArrayList<>();
        row("方案",
                new String[]{"现状", "提亮", "局部", "软底", "轮廓光", "综合"},
                new int[]{0, 1, 2, 3, 4, 5},
                XqService.uiInt(XqService.K_UI_BG_STYLE, XqService.K_UI_BG_STYLE_DEF),
                styleBtns,
                new OnPick() {
                    public void onPick(int v) {
                        XqService.spPutInt(XqService.K_UI_BG_STYLE, v);
                        // 各方案配套的推荐不透明度：方案自己会把遮罩处理好，
                        // 所以人物可以放心拉亮。
                        int[] rec = {40, 85, 90, 95, 92, 96};
                        int a = (v >= 0 && v < rec.length) ? rec[v] : 40;
                        XqService.spPutInt(XqService.K_UI_BG_ALPHA, a);
                        if (sbAlpha != null) sbAlpha.setProgress(a);
                        showAlpha();
                        apply();
                    }
                });
        note("现状 = 老样子；提亮 = 只加文字投影并放开上限；局部 = 遮罩只压文字栏、右半人物全亮；"
                + "软底 = 遮罩更实但边界更柔；轮廓光 = 局部 + 人物加饱和对比 + 四周暗角；"
                + "综合 = 软底 + 加饱和对比 + 暗角。后两个建议配合 90 以上的不透明度。");

        visRow("文字投影（人物能提亮的前提）", XqService.K_UI_TXT_SHADOW);
        visRow("主次分明（着法提白、状态行降灰）", XqService.K_UI_TXT_HIER);
        visRow("数字等宽（计时 / 评分不左右抖）", XqService.K_UI_TXT_MONO);
        visRow("计时并入着法行（关掉则单独一行）", XqService.K_UI_TXT_COMPACT);
        visRow("势条三段渐变", XqService.K_UI_EVAL3);


        // ---------- 4. 启动动画 ----------
        closeCard();                            // v4.38：先把上一页的卡收进上一页
        curGroup = groups.get(GROUP_KEYS[2]);   // 动画
        miniHead("入场动画");                    // v4.39：本页拆成一张卡
        animBtns.clear();
        int cur = XqService.uiInt(XqService.K_UI_INTRO, 0);
        int curIdx = 0;
        for (int i = 0; i < ANIM_ID.length; i++) if (ANIM_ID[i] == cur) curIdx = i;
        row("入场方式",
                ANIM_NAME,
                ANIM_ID,
                ANIM_ID[curIdx],
                animBtns,
                new OnPick() {
                    public void onPick(int v) {
                        XqService.spPutInt(XqService.K_UI_INTRO, v);
                        // ★ v4.51：选入场方式时**故意播一遍**给用户看 ——
                        //   平时设置页里不播动画（避免一直闪），但选这个的时候不播就没法挑。
                        XqService.previewIntro();
                    }
                });
        note("前四种都是「先只出现人物 → 停一下 → 人物淡成背景板、内容淡入」。");

        // ---------- 保存 / 恢复配置 ----------
        // 为什么单独一节：上面的开关都是「改一项立刻存一项」，本身能持久化。
        // 但用户真正要的是「这整套调好了，存下来，以后随时能回到这套」——
        // 调了十项、其中两项试出来不好，没有快照就只能凭记忆一项项改回去。
        // 标题自己建（不走 miniHead）—— 这个区块要挂在 root 上、对所有分类都可见，
        // 跟「恢复默认」按钮一样，不用切到某个 tab 才能找到。
        TextView cfgHead = new TextView(this);
        cfgHead.setText("配置快照");
        cfgHead.setTextSize(11);
        cfgHead.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        cfgHead.setTextColor(0xFF7ED321);
        // v4.36：一级页统一用小标题 + 卡片。原来的绿色大标题换成 headTo 的小标题，
        // 下面的状态行 / 两个按钮 / 说明一起装进一张卡（和权限播报卡同宽）。
        headTo(homeBox, "配置快照");
        final LinearLayout cfgCard = new LinearLayout(this);
        cfgCard.setOrientation(LinearLayout.VERTICAL);
        cfgCard.setBackground(cardBg());
        cfgCard.setPadding(dp(14), dp(12), dp(14), dp(13));

        tvCfgStatus = new TextView(this);
        tvCfgStatus.setTextSize(11);
        tvCfgStatus.setTextColor(0xFFC6D2DC);
        tvCfgStatus.setPadding(0, dp(2), 0, dp(4));
        cfgCard.addView(tvCfgStatus);
        showCfgStatus();

        LinearLayout cfgRow = new LinearLayout(this);
        cfgRow.setOrientation(LinearLayout.HORIZONTAL);
        Button bSave = ghost("保存当前配置");
        bSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int n = XqService.saveProfile();
                showCfgStatus();
                Toast.makeText(SettingsActivity.this,
                        n > 0 ? ("已保存 " + n + " 项设置") : "保存失败",
                        Toast.LENGTH_SHORT).show();
            }
        });
        Button bRestore = ghost("恢复上次保存");
        bRestore.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int n = XqService.restoreProfile();
                if (n <= 0) {
                    Toast.makeText(SettingsActivity.this, "还没有保存过配置", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(SettingsActivity.this,
                        "已恢复 " + n + " 项设置", Toast.LENGTH_SHORT).show();
                // 恢复的是整页的配置，重建一次最省事也最不容易残留
                apply();
                recreate();
            }
        });
        cfgRow.addView(bSave, lpRow(1));
        cfgRow.addView(bRestore, lpRow(1));
        LinearLayout.LayoutParams cfglp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cfglp.leftMargin = 0;
        cfglp.rightMargin = 0;
        cfgCard.addView(cfgRow, cfglp);

        TextView cfgNote = new TextView(this);
        cfgNote.setText("「保存当前配置」把上面所有设置（棋盘位置与大小、背景板、动画、"
                + "每个构件的显隐 / 顺序 / 归列、执子、速度、分析区、前台校验）整份存下来；"
                + "以后调乱了点「恢复上次保存」就回到那一套。");
        cfgNote.setTextSize(10);
        cfgNote.setTextColor(0xFF6B7A87);
        cfgNote.setPadding(0, dp(5), 0, 0);
        cfgCard.addView(cfgNote);
        cardWrap(homeBox, cfgCard, 0);

        // ---------- 恢复默认 ----------
        Button reset = ghost("恢复默认（棋盘 100% 在左 / 背景板 40% / 呼吸对焦 / 执子自动）");
        reset.setPadding(dp(8), dp(12), dp(8), dp(12));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(22);
        rlp.leftMargin = 0;
        rlp.rightMargin = 0;
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { resetAll(); }
        });
        // v4.36：整张卡可点，文字用警示色 —— 比一个灰按钮更像「危险操作」那一栏
        headTo(homeBox, "其他");
        TextView rt = new TextView(this);
        rt.setText("恢复默认（棋盘 100% 在左 / 背景板 40% / 呼吸对焦 / 执子自动）");
        rt.setTextSize(12);
        rt.setTextColor(0xFFE06C6C);
        rt.setGravity(Gravity.CENTER);
        rt.setPadding(dp(6), dp(13), dp(6), dp(13));
        rt.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { resetAll(); }
        });
        LinearLayout resetCard = new LinearLayout(this);
        resetCard.setOrientation(LinearLayout.VERTICAL);
        resetCard.setBackground(cardBg());
        resetCard.addView(rt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cardWrap(homeBox, resetCard, 0);

        closeCard();   // v4.36：把最后一张卡收进页面
        // v4.33：装配完成 —— 上次停在子页就回那一页，否则停在一级列表
        if (XqService.spIntOf(this, K_LEVEL, 0) == 1) showGroup(curTab); else goHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        XqService.appCtx = getApplicationContext();
        // ★ v4.33：本页打开期间把**真悬浮窗**亮出来 —— 这一页调的就是它本人，不再有预览副本。
        XqService.notifySettingsOpen(true);
        refreshSpacer();       // ★ v4.46：本页只在进入时量一次留白
        // ★ v4.42：用户按「音量减 + 电源」截屏时别把悬浮窗收掉（API 34+）
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34 && shotCb == null) {
                shotCb = new android.app.Activity.ScreenCaptureCallback() {
                    public void onScreenCaptured() { XqService.onUserScreenshot(); }
                };
                registerScreenCaptureCallback(getMainExecutor(), shotCb);
            }
        } catch (Throwable ignored) {}
        // 面板是异步建起来的：起来之后再量一次，留白才对得上它的下沿
        if (spacerTop != null) {
            spacerTop.postDelayed(new Runnable() { public void run() { refreshSpacerIfEmpty(); } }, 400L);
            spacerTop.postDelayed(new Runnable() { public void run() { refreshSpacerIfEmpty(); } }, 1200L);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开本页：悬浮窗交回给原来的「本应用界面在前台」判定
        XqService.notifySettingsOpen(false);
    }

    /** 系统返回键：子页回一级列表，一级列表才退出本页。 */
    @Override
    public void onBackPressed() {
        if (!atHome()) { goHome(); return; }
        super.onBackPressed();
    }

    // ============================================================
    //  小工具
    // ============================================================

    /** v4.15：往「当前分组容器」加控件 —— 四大分类各自独立，一次只显示一个。 */
    private void addV(View v) {
        if (curCard != null) curCard.addView(v);
        else if (curGroup != null) curGroup.addView(v);
    }

    private void addV(View v, android.view.ViewGroup.LayoutParams lp) {
        if (curCard != null) curCard.addView(v, lp);
        else if (curGroup != null) curGroup.addView(v, lp);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    interface OnPick { void onPick(int v); }

    /** 一行选项按钮（互斥）。 */
    /**
     * ★ v4.44：一行「标签 + 分段控件」。取代原来「标签占一行、下面两个等宽大按钮」。
     *
     * 为什么改：原来那种排版在整屏宽的卡片里像「一排按钮」，不像设置项；
     * 而且二选一占掉整整一行，一屏能看到的项很少。
     * 选项 ≥ 4 个时自动退化成「标签一行 + 分段一行」—— 挤在一行会互相压。
     */
    private void row(String label, String[] names, final int[] vals, int cur,
                     final java.util.List<Button> out, final OnPick cb) {
        rowIn((curCard != null) ? curCard : curGroup, label, names, vals, cur, out, cb);
    }

    /** v4.44：同上的显式宿主版本（一级页要用它往指定卡片里塞）。 */
    private void rowIn(android.view.ViewGroup host, String label, String[] names,
                       final int[] vals, int cur, final java.util.List<Button> out,
                       final OnPick cb) {
        if (host == null) return;
        boolean wide = names.length >= 4;

        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(7), 0, dp(7));

        TextView t = null;
        if (label != null && label.length() > 0) {
            t = new TextView(this);
            t.setText(label);
            t.setTextSize(13);
            t.setTextColor(0xFFE3EBF3);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (!wide) r.addView(t, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(segBg());
        int sp = dp(2);
        seg.setPadding(sp, sp, sp, sp);
        for (int i = 0; i < names.length; i++) {
            final int v = vals[i];
            Button b = new Button(this);
            b.setText(names[i]);
            b.setTextSize(12);
            b.setAllCaps(false);
            if (wide) b.setPadding(0, dp(5), 0, dp(5));
            else b.setPadding(dp(11), dp(4), dp(11), dp(4));
            b.setMinimumWidth(0);
            b.setMinimumHeight(0);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    for (Button o : out) segStyle(o, false);
                    segStyle((Button) x, true);
                    cb.onPick(v);
                }
            });
            segStyle(b, v == cur);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    wide ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, wide ? 1f : 0f);
            if (i > 0) lp.leftMargin = dp(2);
            seg.addView(b, lp);
            out.add(b);
        }

        if (wide) {
            // 选项多：标签单独一行，分段占满下一行
            if (t != null) host.addView(t);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(3);
            host.addView(seg, slp);
        } else {
            r.addView(seg);
            host.addView(r);
        }
    }

    /**
     * 按钮样式（v4.17 起支持两种风格，用户自己选）。
     *
     * 方角 = 原来那样直接 setBackgroundColor；圆润 = 用 GradientDrawable 给一个大圆角，
     * 半径给到 22dp —— 对 44dp 高的按钮来说已经超过一半，渲染出来就是**胶囊**，
     * 和 MIUI / HyperOS 的按钮观感一致。
     */
    private void style(Button b, boolean on) {
        // v4.36：未选中从 0xFF151C22 提到 0xFF1E2831 —— 卡片底（0xFF161D25）
        // 上原来的色几乎看不见，看着像「没有按钮」。
        int bg = on ? 0xFF7ED321 : 0xFF1E2831;
        if (roundedStyle()) {
            android.graphics.drawable.GradientDrawable g =
                    new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            g.setColor(bg);
            g.setCornerRadius(dp(22));
            b.setBackground(g);
        } else {
            b.setBackgroundColor(bg);
        }
        b.setTextColor(on ? 0xFF0B1014 : 0xFFC6D2DC);
        b.getPaint().setFakeBoldText(on);
    }
    /** ★ v4.44：分段控件的底板（一个圆角胶囊，和卡片同色系但更深一档）。 */
    private android.graphics.drawable.Drawable segBg() {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(0xFF1B232B);
        g.setCornerRadius(dp(9));
        return g;
    }

    /** ★ v4.44：分段控件里的一段。选中 = 品牌绿 + 深字；未选中 = 透明 + 灰字。
     *  比原来「两个等宽大按钮」安静得多 —— 那看着像一排按钮，不像设置项。 */
    private void segStyle(Button b, boolean on) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(on ? 0xFF7ED321 : 0x00000000);
        g.setCornerRadius(dp(7));
        b.setBackground(g);
        b.setTextColor(on ? 0xFF0B1014 : 0xFF93A0AD);
        b.getPaint().setFakeBoldText(on);
    }

    /** ★ v4.44：开关。轨道选中 = 品牌绿、未选中 = 深灰；滑块白。
     *  只交给调用方去挂监听 —— 建视图时不能顺带触发回调。 */
    private android.widget.Switch newSwitch(boolean on) {
        final android.widget.Switch sw = new android.widget.Switch(this);
        sw.setShowText(false);
        sw.setChecked(on);
        try {
            sw.setTrackTintList(new android.content.res.ColorStateList(
                    new int[][]{ new int[]{android.R.attr.state_checked}, new int[]{} },
                    new int[]{ 0xFF7ED321, 0xFF2B3742 }));
            sw.setThumbTintList(new android.content.res.ColorStateList(
                    new int[][]{ new int[]{} }, new int[]{ 0xFFFFFFFF }));
        } catch (Throwable ignored) {}
        return sw;
    }

    /** ★ v4.44：一行「标签 + 开关」。 */
    private LinearLayout switchLine(String label) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(6), 0, dp(6));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setTextColor(0xFFE3EBF3);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    /**
     * v4.18：显示 / 隐藏一个构件（用户要求：隐藏让位，再显示排到末尾空位）。
     *
     * 隐藏 → 把它的键从顺序串里删掉（不再占顺序），面板那边同时 GONE 让出占位；
     * 显示 → 把键追加到顺序串末尾 —— 下次 applyOrder 就把它放在最后，
     *        也就是「空出来的那个位置」。
     */
    private void setItemVisible(String visKey, String orderKey, String orderDef,
                                String itemKey, boolean show) {
        XqService.spPutInt(visKey, show ? 1 : 0);
        if (orderKey != null && itemKey != null) {
            String cur = XqService.spStrOf(this, orderKey, orderDef);
            XqService.spPutStr(orderKey, show
                    ? XqService.orderAppend(cur, orderDef, itemKey)
                    : XqService.orderRemove(cur, itemKey));
        }
        apply();
    }

    /** ★ v4.51：按钮风格固定圆润 —— 「圆润 / 方角」的选择已删除。 */
    private boolean roundedStyle() {
        return true;
    }

    /** v4.9：分组内的小标题（比 section 轻一档，用来把一大节拆成几块）。 */
    private void miniHead(String s) {
        // ★ v4.36：收上一张卡 → 写小标题 → 开新卡。这样二级页的选项、滑杆、
        //   说明会自动成组落进卡片，和一级页同一套观感，不用逐个方法去改。
        closeCard();
        if (curGroup != null) headTo(curGroup, s);
        openCard();
    }
    /**
     * v4.15：四个分类按钮（钉在预览下方）。
     *
     * 用户明确要求：这四处**只显示这四个按钮**，下面的设置项默认一个都不显示，
     * 点哪个才显示哪一组。所以按钮不再是「滚动导航」，而是**切换分组显隐**。
     */
    /**
     * ★ v4.33：一级页的入口列表。
     *
     * 用户点名要的层级：像手机系统设置那样 —— 一级页只列条目，点「棋盘」才进棋盘那一页。
     * 所以这里不再是一排选项卡，而是几个可点的行 + 右侧指示箭头。
     */
    /**
     * ★ v4.42：一级页 —— 「分类」整组做成一张卡。
     *
     * 设计参考：iOS 设置 / HyperOS 的 grouped inset 列表 —— 一张圆角卡里放多行，
     * 行与行之间用发丝线分隔，每行左侧一个**专属颜色**的图标（不是四个一样的绿块）。
     * 这样一眼能区分四个分类，视觉密度也比「一行一张卡」高得多。
     */
    private void buildHomeRows(LinearLayout parent) {
        headTo(parent, "分类");
        LinearLayout card = newCard();
        for (int i = 0; i < GROUP_KEYS.length; i++) {
            final int idx = i;
            homeRow(card, GROUP_KEYS[i], groupDesc(i), iconChar(i), accentOf(i),
                    new Runnable() { public void run() { showGroup(idx); } });
            if (i < GROUP_KEYS.length - 1) card.addView(hairline());
        }
        cardWrap(parent, card, 0);
    }


    /** v4.42：一张空卡 —— 近黑 + 20dp 圆角 + 发丝描边（和权限播报卡同宽同圆角）。 */
    private LinearLayout newCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(cardBg());
        int p = dp(3);
        c.setPadding(p, p, p, p);
        return c;
    }

    /** 卡内分隔线：左边缩进到文字起始处（iOS 列表就是这么缩的）。 */
    private View hairline() {
        View v = new View(this);
        v.setBackgroundColor(0xFF1E262E);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.leftMargin = dp(52);
        v.setLayoutParams(lp);
        return v;
    }

    /** v4.42：四个分类各自的强调色（借鉴 iOS 设置：每行图标一个颜色）。 */
    private int accentOf(int i) {
        switch (i) {
            case 0: return 0xFF7ED321;   // 棋盘 · 品牌绿
            case 1: return 0xFF4A9EFF;   // 背景 · 蓝
            case 2: return 0xFFB07CFF;   // 动画 · 紫
            default: return 0xFFFFA23A;  // 对局 · 橙
        }
    }

    /** v4.42：淡色容器 —— 强调色压到很低的透明度叠在卡底上。
     *  比「实心饱和色块」安静得多（Material 3 的 tonal 做法），
     *  四个图标并存时不会互相抢戏。 */
    private android.graphics.drawable.Drawable toneBg(int accent) {
        int r = (accent >> 16) & 0xFF, g = (accent >> 8) & 0xFF, b = accent & 0xFF;
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setColor(android.graphics.Color.argb(44, r, g, b));
        d.setCornerRadius(dp(8));
        return d;
    }

    /** v4.36：直接加到指定容器的小标题（一级页用；miniHead 只会加到 curGroup）。 */
    private void headTo(LinearLayout parent, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(11);
        t.setTextColor(0xFF8FA0AE);
        t.getPaint().setFakeBoldText(true);
        t.setPadding(dp(2), dp(24), 0, dp(8));
        // v4.43：卡片整屏宽之后，标签直接加到页面里就行（左对齐自然和卡片对齐）。
        parent.addView(t);
    }

    /** 一级页每一行下面的小字（一眼知道那页里有什么）。 */
    private String groupDesc(int i) {
        switch (i) {
            case 0: return "棋盘大小与位置、悬浮窗上下位置";
            case 1: return "背景板开关、浓度、换图片";
            case 2: return "入场动画方式";
            default: return "构件显隐与顺序、分析区、执子速度";
        }
    }

    /** ★ v4.33：一级页的一行（标题 + 副标题 + 右侧箭头），点进对应子页。 */
    /**
     * ★ v4.36：一级页的一张卡 —— 图标 + 标题 + 副标题 + 右侧箭头，整张可点。
     *
     * 尺寸对齐「权限播报卡」：280dp 宽、20dp 圆角、近黑底 + 1dp 发丝描边。
     * 结构参考 Material 3 的 list item：leading icon 容器 → 文字列 → trailing chevron。
     */
    /** ★ v4.42：一级页的一行 —— 图标 + 标题 + 副标题 + 右侧箭头，整行可点。
     *  行高比原来「一行一张卡」矮约 20%，四行能一屏看完。 */
    private void homeRow(LinearLayout card, String title, String desc, String glyph,
                         int accent, final Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(11), dp(12), dp(11));
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onClick.run(); }
        });

        TextView ic = new TextView(this);
        ic.setText(glyph);
        ic.setTextSize(14);
        ic.setTextColor(accent);
        ic.getPaint().setFakeBoldText(true);
        ic.setGravity(Gravity.CENTER);
        ic.setIncludeFontPadding(false);
        ic.setBackground(toneBg(accent));
        row.addView(ic, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(0xFFEDF3F8);
        t.getPaint().setFakeBoldText(true);
        col.addView(t);
        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(11);
        d.setTextColor(0xFF7C8894);
        d.setSingleLine(true);
        d.setEllipsize(android.text.TextUtils.TruncateAt.END);
        d.setPadding(0, dp(2), 0, 0);
        col.addView(d);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(12);
        row.addView(col, clp);

        TextView chev = new TextView(this);
        chev.setText("\u203a");
        chev.setTextSize(18);
        chev.setTextColor(0xFF4E5A66);
        row.addView(chev);

        card.addView(row);
    }

    /**
     * ★ v4.36：二级页也用「小标题 → 一张卡」来排，和一级页同一套观感。
     *
     * 做法：每个 miniHead 先把上一张卡收进页面，再开新标题 + 新卡；
     * 之后所有 addV 的控件都落进这张卡里，直到下一个 miniHead。
     * 这样二级页的选项、滑杆、说明自动成组，不用逐个方法去改。
     */
    private void closeCard() {
        if (curCard != null && curGroup != null) {
            cardWrap(curGroup, curCard, 0);
        }
        curCard = null;
    }
    /** ★ v4.46：开一张「一项一张」的卡。
     *  用户要求：按键类的设置，每一个按键对应的调整控件为一个单位、住在自己卡片里。 */
    private void unitCard() {
        if (curGroup == null) return;
        closeCard();
        openCard();
    }

    private void openCard() {
        closeCard();
        if (curGroup == null) return;
        curCard = new LinearLayout(this);
        curCard.setOrientation(LinearLayout.VERTICAL);
        curCard.setBackground(cardBg());
        curCard.setPadding(dp(12), dp(6), dp(12), dp(10));
    }
    /** ★ v4.36：把一张卡按 280dp 宽、水平居中放进容器（和权限播报卡同宽）。 */
    private void cardWrap(LinearLayout parent, View card, int topDp) {
        // ★ v4.43：整屏宽（页面本身左右各 16dp padding）。
        //   原来是固定 280dp 居中 —— 那是「权限播报卡」的宽度，用在设置页里
        //   一行内容全挤在中间一小条里，看着像被圈住的一栏（用户反馈）。
        //   现在每张卡都占满可用宽度，高度不变，一张一张分得清清楚楚。
        FrameLayout wrap = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        wrap.addView(card, lp);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(topDp);
        parent.addView(wrap, wlp);
    }

    /** 卡片底：近黑 + 20dp 圆角 + 发丝描边（和权限播报卡同款）。 */
    private android.graphics.drawable.Drawable cardBg() {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        // v4.43：底色和描边各提一档 —— 整屏宽之后，卡与卡之间的分界要看得出来
        g.setColor(0xFF171F28);
        g.setCornerRadius(dp(20));
        g.setStroke(dp(1), 0xFF2A3641);
        return g;
    }

    /** 卡片上的图标块：品牌绿圆角方 + 深色字（和提示卡的图标块同一套观感）。 */
    private android.graphics.drawable.Drawable iconBg() {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(0xFF7ED321);
        g.setCornerRadius(dp(9));
        return g;
    }

    /** 四张卡各用一个字：取分类名的首字，一眼对得上。 */
    private String iconChar(int i) {
        switch (i) {
            case 0: return "\u68cb";
            case 1: return "\u80cc";
            case 2: return "\u52a8";
            default: return "\u5bf9";
        }
    }

    /** ★ v4.32：分类选项卡的样式 —— 选中填绿，未选中不填色、只有描边 + 灰字。
     *  比普通按钮轻一档，一眼能看出「这是一排选项卡」。 */
    private void tabStyle(Button b, boolean on) {
        if (on) { style(b, true); return; }
        if (roundedStyle()) {
            android.graphics.drawable.GradientDrawable g =
                    new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            g.setColor(0x00000000);
            g.setCornerRadius(dp(22));
            g.setStroke(dp(1), 0xFF2A343D);
            b.setBackground(g);
        } else {
            b.setBackgroundColor(0x00000000);
        }
        b.setTextColor(0xFF9AA7B3);
        b.getPaint().setFakeBoldText(false);
    }

    /**
     * v4.17：按钮风格切换（圆润 / 方角）。
     *
     * 放在固定区、四个分类按钮的正下方：它管的就是**这个页面的按钮长相**，
     * 一边切一边马上能看到效果，不用钻到某个分类里找。
     */
    /** v4.36：按钮风格 —— 包进卡片，和上面几张分类卡同一套观感。 */
    /** v4.44：按钮风格 —— 一级页的「外观」卡，排版和二级页那些二选一字段一致。 */
    /** ★ v4.51：「按钮风格」整块删除（用户要求）。样式固定为圆润。 */

    /** 切到第 idx 组：只显示它，其余全部隐藏；顺手把滚动条拉回顶部。 */
    /** 切到第 idx 个子页：只显示它，其余全部隐藏；顺手把滚动条拉回顶部。 */
    private void showGroup(int idx) {
        if (idx < 0 || idx >= GROUP_KEYS.length) idx = 0;
        curTab = idx;
        XqService.spPutInt(XqService.K_UI_TAB, idx);   // 记住：recreate 后回到这一页
        XqService.spPutInt(K_LEVEL, 1);
        if (homeBox != null) homeBox.setVisibility(View.GONE);
        int i = 0;
        for (java.util.Map.Entry<String, LinearLayout> e : groups.entrySet()) {
            e.getValue().setVisibility(i == idx ? View.VISIBLE : View.GONE);
            i++;
        }
        if (tvTitle != null) tvTitle.setText("设置 · " + GROUP_KEYS[idx]);
        if (svRef != null) svRef.scrollTo(0, 0);
    }

    /** ★ v4.33：回到一级入口列表。 */
    private void goHome() {
        XqService.spPutInt(K_LEVEL, 0);
        for (java.util.Map.Entry<String, LinearLayout> e : groups.entrySet())
            e.getValue().setVisibility(View.GONE);
        if (homeBox != null) homeBox.setVisibility(View.VISIBLE);
        if (tvTitle != null) tvTitle.setText("设置");
        if (svRef != null) svRef.scrollTo(0, 0);
    }

    /** 现在是不是停在一级页。 */
    private boolean atHome() {
        return XqService.spIntOf(this, K_LEVEL, 0) == 0;
    }

    /** 返回上一级：子页 → 一级页；已经在一级页 → 退出本页。 */
    private void goBack() {
        if (atHome()) { finish(); return; }
        goHome();
    }

    /** 顶上空出来的高度（= 真悬浮窗下沿 - 本页内容起点）。
     *  注意不能直接用 insets 的 top：本页窗口已经被系统让出状态栏，
     *  内容起点本身就在状态栏下沿，insets 里读到的 top 是 0。
     *  所以改用「本页实际在屏幕上的位置」来换算，换机型也不会错。 */
    /**
     * 重新量一次「顶上空出来的高度」（= 真悬浮窗下沿 - 本页内容起点），并冻存。
     *
     * ★ v4.46：只在「进入本页」时量一次。之后一律用冻存值（layoutSpacer），
     * 拖动「悬浮窗上下位置」不再改动页面布局 —— 用户要的是「只有悬浮窗动」。
     *
     * 注意不能直接用 insets 的 top：本页窗口已经被系统让出状态栏，
     * 内容起点本身就在状态栏下沿，insets 里读到的 top 是 0。
     * 所以改用「本页实际在屏幕上的位置」来换算，换机型也不会错。
     */
    private void refreshSpacer() {
        if (spacerTop == null) return;
        View hostV = (spacerTop.getParent() instanceof View) ? (View) spacerTop.getParent() : null;
        int topOnScreen = 0;
        if (hostV != null) {
            int[] loc = new int[2];
            hostV.getLocationOnScreen(loc);
            topOnScreen = loc[1];
        }
        if (topOnScreen == 0 && !spacerRetry) {
            // 还没上屏（量之前 getLocationOnScreen 返回 0）：等一帧再算一次
            spacerRetry = true;
            spacerTop.postDelayed(new Runnable() { public void run() { refreshSpacer(); } }, 80L);
            return;
        }
        int need = XqService.panelBottomPx(this) - topOnScreen + dp(6);
        if (need < 0) need = 0;
        spacerPx = need;
        layoutSpacer();
    }

    /** 等面板起来后补量一次（只在还没量到时）。 */
    private void refreshSpacerIfEmpty() {
        if (spacerPx <= 0) refreshSpacer();
    }

    /** 把冻存的留白值套上去。整页布局从此不再随任何设置滑动。 */
    private void layoutSpacer() {
        if (spacerTop == null || spacerPx < 0) return;
        ViewGroup.LayoutParams lp = spacerTop.getLayoutParams();
        if (lp != null && lp.height != spacerPx) {
            lp.height = spacerPx;
            spacerTop.setLayoutParams(lp);
        }
    }

    private void section(String name) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(13);
        t.setTextColor(0xFF7ED321);
        t.getPaint().setFakeBoldText(true);
        t.setPadding(0, dp(24), 0, 0);
        addV(t);
        anchors.put(name, t);       // 记下位置，chip 要滚过来
        View div = new View(this);
        div.setBackgroundColor(0xFF1E2830);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(6);
        addV(div, lp);
    }

    private void note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(11);
        t.setTextColor(0xFF7C8894);
        t.setPadding(0, dp(5), 0, 0);
        addV(t);
    }

    private Button primary(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(12);
        b.setAllCaps(false);
        style(b, true);
        return b;
    }

    private Button ghost(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(12);
        b.setAllCaps(false);
        style(b, false);
        return b;
    }

    private LinearLayout.LayoutParams lpRow(int weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.rightMargin = dp(1);
        return lp;
    }

    /**
     * ★ v4.21：一行滑杆（标签 + 当前值 + SeekBar）。
     *
     * 拖动过程中：写配置 → 重建预览（预览在页面顶部，能立刻看到效果）。
     * 松手之后：调 apply() → 真悬浮窗也按新配置重建一次。
     * 中间值用 min 偏移（SeekBar 的 progress 不能为负），所以取值范围是 [min,max]。
     */
    private void sliderRow(String label, final String key, int def, final int min, final int max,
                           final String unit, final boolean signed) {
        final TextView val = new TextView(this);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(10), 0, 0);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(13);
        l.setTextColor(0xFFE3EBF3);
        head.addView(l);
        val.setTextSize(12);
        val.setTextColor(0xFF7ED321);
        val.getPaint().setFakeBoldText(true);
        val.setPadding(dp(8), 0, 0, 0);
        head.addView(val);
        addV(head);

        final SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        int cur = XqService.uiInt(key, def);
        if (cur < min) cur = min;
        if (cur > max) cur = max;
        sb.setProgress(cur - min);
        val.setText(sliderText(cur, unit, signed));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = p + min;
                val.setText(sliderText(v, unit, signed));
                if (!fromUser) return;
                XqService.spPutInt(key, v);
                // ★ v4.33：拖着就动**真悬浮窗** —— 只改尺寸/位置、不重建窗口，所以跟手。
                if (XqService.K_UI_PANEL_DY.equals(key)) {
                    // ★ v4.46：只挪真悬浮窗，**绝不**动本页布局 ——
                    //   原来这里会重算顶部留白，整个设置页跟着一起上下滑。
                    XqService.applyPanelDy();
                } else {
                    XqService.liveApplyBoard();
                }
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) {
                XqService.spPutInt(key, s.getProgress() + min);
                apply();
            }
        });
        addV(sb);
    }

    private String sliderText(int v, String unit, boolean signed) {
        return (signed && v > 0 ? "+" : "") + v + unit;
    }

    /** v4.28：刷新「上次保存」那行。 */
    private void showCfgStatus() {
        if (tvCfgStatus == null) return;
        long at = XqService.profileSavedAt();
        int n = XqService.profileCount();
        if (at <= 0 || n <= 0) {
            tvCfgStatus.setText("还没有保存过配置 —— 调好之后点一下「保存当前配置」。");
            return;
        }
        String when;
        try {
            when = new java.text.SimpleDateFormat("MM-dd HH:mm",
                    java.util.Locale.getDefault()).format(new java.util.Date(at));
        } catch (Throwable t) { when = ""; }
        tvCfgStatus.setText("上次保存：" + when + "（共 " + n + " 项）");
    }

    private void showAlpha() {
        if (tvAlphaVal != null && sbAlpha != null)
            tvAlphaVal.setText(sbAlpha.getProgress() + "%");
    }

    private void updateBgImgLabel() {
        if (tvBgImg == null) return;
        String p = XqService.spStr(XqService.K_UI_BG_IMG, "");
        boolean custom = (p != null && !p.isEmpty() && new File(p).exists());
        tvBgImg.setText("当前背景图：" + (custom ? "你选的照片" : "内置图片"));
    }

    /** v4.9：一个「显示 / 隐藏」的行（用于悬浮窗构件开关）。 */
    /** ★ v4.44：一个「显示 / 隐藏」的行 —— 换成开关（右侧对齐）。
     *  开关是设置页里最不该用「两个按钮」表达的控件。 */
    private void visRow(String label, final String key) {
        LinearLayout r = switchLine(label);
        final android.widget.Switch sw = newSwitch(XqService.uiInt(key, 1) == 1);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                int want = v ? 1 : 0;
                if (XqService.uiInt(key, 1) == want) return;
                XqService.spPutInt(key, want);
                apply();
            }
        });
        r.addView(sw);
        addV(r);
    }

    // ============================================================
    //  v4.11：构件「占位自己移动」
    //
    //  用户点名要的：悬浮窗上每个按钮 / 每一块的**位置**都能自己调。
    //  三组顺序各自独立存盘：
    //    · 左栏上下顺序（按钮排 / 势 / 着法 / 预判 / 状态 / 分析区）
    //    · 按钮排内左右顺序（链 / 识 / ⚡）
    //    · 右栏上下顺序（✕ / ⠿ / —）
    //  配合已有的「棋盘左 / 右」和「面板整体拖动」，位置就都归用户了。
    // ============================================================

    /** 行头：名称（占满一行左侧）+ 右侧一对紧凑 ↑↓ 箭头。 */
    /**
     * ★ v4.44：构件行的「第一行」—— 名称 + ↑↓ 箭头。
     *
     * 和旧 headRow 的区别：**返回这一行、但不入页**，由调用方决定后面再挂什么
     * （开关 / 分段）。旧版本固定返回一个空的第二行，开关就没法和名称同一行了。
     */
    private LinearLayout orderHead(String label, String orderKey, String orderDef,
                                   String itemKey) {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, dp(6), 0, dp(6));

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setTextColor(0xFFE3EBF3);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        addArrow(top, orderKey, orderDef, itemKey, -1, true);
        addArrow(top, orderKey, orderDef, itemKey, +1, false);
        return top;
    }

    /** ★ v4.44：把开关挂到「名称 + ↑↓」那一行的末尾。 */
    private void addSwitch(LinearLayout line, android.widget.Switch sw) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(2);
        line.addView(sw, lp);
    }

    /** ★ v4.44：一行「↑↓ + 分段控件」（分段右对齐，和上一行的名称同一张卡）。 */
    private void addSegment(LinearLayout line, String[] names, int cur,
                            final Runnable[] acts) {

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(segBg());
        int sp = dp(2);
        seg.setPadding(sp, sp, sp, sp);
        final java.util.List<Button> out = new java.util.ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(names[i]);
            b.setTextSize(12);
            b.setAllCaps(false);
            b.setPadding(dp(13), dp(4), dp(13), dp(4));
            b.setMinimumWidth(0);
            b.setMinimumHeight(0);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    for (Button o : out) segStyle(o, false);
                    segStyle((Button) x, true);
                    if (acts != null && idx < acts.length && acts[idx] != null) acts[idx].run();
                }
            });
            segStyle(b, i == cur);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(2);
            seg.addView(b, lp);
            out.add(b);
        }
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        line.addView(seg, slp);
    }

    /** 紧凑箭头按钮：只有 "↑" "↓" 两个字符，宽 46dp / 高 34dp（好点，又不挤）。 */
    private void addArrow(LinearLayout r, final String orderKey, final String orderDef,
                          final String itemKey, final int dir, boolean first) {
        Button b = new Button(this);
        b.setText(dir < 0 ? "\u2191" : "\u2193");
        b.setTextSize(15);
        b.setAllCaps(false);
        style(b, false);
        b.setPadding(0, 0, 0, 0);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String cur = XqService.spStrOf(SettingsActivity.this, orderKey, orderDef);
                XqService.spPutStr(orderKey, XqService.moveInOrder(cur, orderDef, itemKey, dir));
                apply();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(34));
        if (!first) lp.leftMargin = dp(6);
        r.addView(b, lp);
    }
    /** ★ v4.46：跨列按钮专用的箭头 —— 顺序表**在点击那一刻**才决定。
     *
     * 为什么：原来 panelBtnRow 的箭头在构建时就绑死了某一列的顺序表，
     * 所以用户一切换「按钮排 / 右栏」就只能 recreate() 整页重来 ——
     * 后果是刚滑到的位置被刷回页面顶部（用户反馈）。
     * 现在改成每次点击现读当前列，切换列就不用重建整页了。
     */
    private void addArrowDyn(LinearLayout r, final String colKey, final int colDef,
                             final String itemKey, final int dir, boolean first) {
        Button b = new Button(this);
        b.setText(dir < 0 ? "\u2191" : "\u2193");
        b.setTextSize(15);
        b.setAllCaps(false);
        style(b, false);
        b.setPadding(0, 0, 0, 0);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean inRight = XqService.spIntOf(SettingsActivity.this, colKey, colDef) == 1;
                String key = inRight ? XqService.K_UI_ORDER_RIGHT : XqService.K_UI_ORDER_BTN;
                String def = inRight ? XqService.ORDER_RIGHT_DEF : XqService.ORDER_BTN_DEF;
                String cur = XqService.spStrOf(SettingsActivity.this, key, def);
                XqService.spPutStr(key, XqService.moveInOrder(cur, def, itemKey, dir));
                apply();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(34));
        if (!first) lp.leftMargin = dp(6);
        r.addView(b, lp);
    }

    /** 第二行：一对「二选一」按钮，各占一半宽。 */
    private void pickerRow(LinearLayout bot, String aText, String bText,
                           boolean aOn, final Runnable onA, final Runnable onB) {
        final Button ba = new Button(this);
        final Button bb = new Button(this);
        ba.setText(aText);
        bb.setText(bText);
        ba.setTextSize(12);
        bb.setTextSize(12);
        ba.setAllCaps(false);
        bb.setAllCaps(false);
        ba.setPadding(0, dp(8), 0, dp(8));
        bb.setPadding(0, dp(8), 0, dp(8));
        style(ba, aOn);
        style(bb, !aOn);
        ba.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                style(ba, true);
                style(bb, false);
                onA.run();
            }
        });
        bb.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                style(ba, false);
                style(bb, true);
                onB.run();
            }
        });
        bot.addView(ba, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        l2.leftMargin = dp(6);
        bot.addView(bb, l2);
        addV(bot);
    }

    /**
     * 构件行：第一行「名称 + ↑↓」、第二行「显示 / 隐藏」。
     *
     * v4.13 从「4 个按钮挤一行」改成两行 —— 一行 4 个按钮每个只有 ~80dp 宽、
     * 文字要压到 11sp 才放得下；拆开之后字号提到 13/12sp，44dp 触控也够了。
     */
    /**
     * 构件行：名称 + ↑↓ + 开关（同一行）。
     *
     * ★ v4.44：原来是「第一行名称+↑↓、第二行显示/隐藏两个大按钮」占两行；
     *   现在开关直接跟在箭头后面，一行放下。选项一屏能看全。
     */
    private void visMoveRow(String label, final String orderKey, final String orderDef,
                            final String itemKey, final String visKey) {
        LinearLayout top = orderHead(label, orderKey, orderDef, itemKey);
        final android.widget.Switch sw = newSwitch(XqService.uiInt(visKey, 1) == 1);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                setItemVisible(visKey, orderKey, orderDef, itemKey, v);
            }
        });
        addSwitch(top, sw);
        addV(top);
    }

    /** 只有「↑↓」的行（构件本身没有显隐开关时用）。 */
    /** 只有「↑↓」的行（构件本身没有显隐开关时用）。 */
    private void moveRow(String label, String orderKey, String orderDef, String itemKey) {
        addV(orderHead(label, orderKey, orderDef, itemKey));
    }

    /**
     * v4.17：「链 / 识 / 自」三项专用行 —— 它们可以**跨列搬**。
     *
     * 第一行：名称 + ↑↓（顺序作用于它**当前所在的那一列**）
     * 第二行：显示 / 隐藏 ｜ 按钮排 / 右栏
     *
     * 为什么单独写一个而不复用 visMoveRow：普通构件固定在某一列，
     * 这三个的列是可变的，↑↓ 要用哪张顺序表得看当前列。
     */
    /**
     * v4.17：「链 / 识 / 自」三项专用行 —— 它们可以**跨列搬**。
     *
     * 第一行：名称 + ↑↓（顺序作用于它**当前所在的那一列**）+ 显隐开关
     * 第二行：它待在「按钮排」还是「右栏」（分段控件）
     *
     * ★ v4.44：开关挪到第一行、跨列改成右侧分段 —— 从「四个等宽大按钮两行」
     *   压缩成「一行半」，和页面里其他字段同一套排版。
     */
    /**
     * v4.17：「链 / 识 / 自」三项专用行 —— 它们可以**跨列搬**。
     *
     * 第一行：名称 + ↑↓（顺序作用于它**当前所在的那一列**）+ 显隐开关
     * 第二行：它待在「按钮排」还是「右栏」（分段控件）
     *
     * ★ v4.44：开关挪到第一行、跨列改成右侧分段 —— 从「四个等宽大按钮两行」
     *   压缩成「一行半」，和页面里其他字段同一套排版。
     * ★ v4.46：切换列不再 recreate() 整页（那会把刚滑到的位置刷回页面顶部），
     *   改成开关与箭头都在点击时现读当前列 + apply()。
     */
    /**
     * v4.17：「链 / 识 / 自」三项的行 —— 名称 + ↑↓ + 显隐开关。
     *
     * ★ v4.44：开关挪到名称那一行，一行放下。
     * ★ v4.49：**取消「按钮排 / 右栏」的选择** —— 这三项一律留在按钮排。
     *   原因：右栏是竖排 wrap_content，塞进第四、第五个圆钮就会超出面板固定高度
     *   （最下面那个被裁掉，用户截图里「自」只露了一半），
     *   而且和 ✕ / ⠿ / — 混排后几个钮的圆心对不齐。
     *   所以这里不再给分段控件，↑↓ 直接作用于按钮排的顺序。
     */
    /**
     * 右栏五个圆钮（链 / 识 / 自 / ✕ / ⠿）的通用行：名称 + ↑↓ + 显隐开关。
     *
     * ★ v4.44：开关挪到名称那一行，一行放下。
     * ★ v4.49：取消「按钮排 / 右栏」选择。
     * ★ v4.50：链 / 识 / 自 也搬进右栏 —— 所以 ↑↓ 现在作用于
     *   K_UI_ORDER_RIGHT（右侧竖列的上下顺序），和 ✕ / ⠿ 是同一张表。
     */
    private void panelBtnRow(String label, final String itemKey, final String visKey) {
        LinearLayout top = orderHead(label, XqService.K_UI_ORDER_RIGHT,
                XqService.ORDER_RIGHT_DEF, itemKey);
        final android.widget.Switch sw = newSwitch(XqService.spIntOf(this, visKey, 1) == 1);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                setItemVisible(visKey, XqService.K_UI_ORDER_RIGHT,
                        XqService.ORDER_RIGHT_DEF, itemKey, v);
            }
        });
        addSwitch(top, sw);
        addV(top);
    }

    /** 分析区专用：「↑↓」+「折叠 / 展开」（它的「显隐」就是折叠与否，键是 K_ANA）。 */
    /** 分析区专用：「↑↓」+「折叠 / 展开」分段（它的「显隐」就是折叠与否，键是 K_ANA）。 */
    private void anaRow(String label, final String orderKey, final String orderDef,
                        final String itemKey) {
        LinearLayout top = orderHead(label, orderKey, orderDef, itemKey);
        boolean on = XqService.getAnaPref(this);
        addSegment(top, new String[]{"折叠", "展开"}, on ? 1 : 0,
                new Runnable[]{
                        new Runnable() {
                            public void run() {
                                XqService.putEnginePref(SettingsActivity.this,
                                        XqService.K_ANA, 0);
                                apply();
                            }
                        },
                        new Runnable() {
                            public void run() {
                                XqService.putEnginePref(SettingsActivity.this,
                                        XqService.K_ANA, 1);
                                apply();
                            }
                        }
                });
        addV(top);
    }

    /** ★ v4.78：界面背景主题（只作用于 App 界面，与悬浮窗无关）。 */
    /** ★ v4.90：卡片正面图（自定义背景 + 人脸位置自适应避让）。 */
    private static final int REQ_HOLO = 7002;

    private void buildHoloCardSection() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        Button pick = primary("选卡片正面图");
        pick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickHoloImage(); }
        });
        Button def = ghost("恢复默认");
        def.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                MainActivity.clearHoloCardImage(SettingsActivity.this);
                Toast.makeText(SettingsActivity.this, "已恢复内置卡面", Toast.LENGTH_SHORT).show();
            }
        });
        r.addView(pick, lpRow(1));
        r.addView(def, lpRow(1));
        addV(r);
        Button info = ghost("设置联系方式 / QQ群");
        info.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askHoloInfo(); }
        });
        addV(info);
        note("选图后会自动检测图中人脸位置，主界面的文字与按钮会自动避开人脸排布"
                + "（真实人脸照片识别最准）。双击卡片翻面，背面显示联系方式与 QQ 群，"
                + "点「设置联系方式 / QQ群」填写，保存后回到主界面即可看到。");
    }

    private void pickHoloImage() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(i, REQ_HOLO);
        } catch (Throwable t) {
            try {
                Intent i2 = new Intent(Intent.ACTION_PICK);
                i2.setType("image/*");
                startActivityForResult(i2, REQ_HOLO);
            } catch (Throwable t2) {
                Toast.makeText(this, "打不开相册：" + t2.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void askHoloInfo() {
        final android.widget.EditText etC = new android.widget.EditText(this);
        etC.setHint("联系方式（如 QQ 号 / 微信）");
        etC.setText(XqService.spStr(MainActivity.K_HOLO_CONTACT, ""));
        final android.widget.EditText etQ = new android.widget.EditText(this);
        etQ.setHint("QQ 群号");
        etQ.setText(XqService.spStr(MainActivity.K_HOLO_QQ, ""));   // 留空 = 用出厂默认群号
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        box.setPadding(p, dp(8), p, 0);
        box.addView(etC);
        box.addView(etQ);
        new android.app.AlertDialog.Builder(this)
                .setTitle("卡片背面信息")
                .setView(box)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        XqService.spPutStr(MainActivity.K_HOLO_CONTACT, etC.getText().toString().trim());
                        XqService.spPutStr(MainActivity.K_HOLO_QQ, etQ.getText().toString().trim());
                        MainActivity.bumpHoloVer();
                        Toast.makeText(SettingsActivity.this,
                                "已保存，回到主界面双击卡片翻面查看", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void buildThemeSection() {
        themeBtns.clear();
        final int cur = UiTheme.current(this);
        final String[] shortNames = {"鎏金", "纸感", "扫光", "线稿", "融合", "白相"};
        row("主题", shortNames, new int[]{0, 1, 2, 3, 4, 5}, cur, themeBtns,
                new OnPick() {
                    public void onPick(int v) {
                        UiTheme.set(SettingsActivity.this, v);
                        // 只刷新 App 界面背景，不触碰悬浮窗里的任何元素
                        if (themeHost != null) UiTheme.apply(themeHost, v);
                    }
                });
        note("App 界面（设置页 / 主界面）的背景主题，与悬浮窗无关：改它不会动悬浮窗里的任何元素。"
                + "鎏金 = 暗夜金发星光；纸感 = 暗化纸纹；扫光 = 加斜向流光；线稿 = 淡线稿档案；融合 = 人物氛围底；白相 = mello 整卡铺满（浅色底）。");
    }

    private void apply() {
        // 真悬浮窗按新配置重建一次（重建时的入场动画就是设置里选的那一种），
        // 再把顶部留白对准它的下沿。
        XqService.applyPersonalization();
    }

    private void resetAll() {
        XqService.spPutInt(XqService.K_UI_BOARD_SIDE, 0);
        XqService.spPutInt(XqService.K_UI_BOARD_SCALE, XqService.K_UI_BOARD_SCALE_DEF);
        XqService.spPutInt(XqService.K_UI_BOARD_DX, 0);
        XqService.spPutInt(XqService.K_UI_BOARD_DY, 0);
        XqService.spPutInt(XqService.K_UI_PANEL_DY, 0);
        XqService.spPutInt(XqService.K_UI_CTRL_CARD, 0);
        XqService.spPutInt(XqService.K_UI_BG_ON, 1);
        XqService.spPutInt(XqService.K_UI_BG_ALPHA, XqService.K_UI_BG_ALPHA_DEF);
        XqService.spPutInt(XqService.K_UI_INTRO, 0);
        XqService.spPutStr(XqService.K_UI_BG_IMG, "");
        // v4.8 对局选项也一起回默认
        XqService.spPutInt(XqService.K_UI_SIDE, 0);
        XqService.spPutInt(XqService.K_SPEED, Speed.DEFAULT);
        XqService.spPutInt(XqService.K_ANA, 0);
        XqService.spPutInt(XqService.K_FGMODE, 1);
        XqService.applyEngineOptions();
        Toast.makeText(this, "已恢复默认", Toast.LENGTH_SHORT).show();
        recreate();
        apply();
    }

    // ============================================================
    //  选图
    // ============================================================

    private void pickImage() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(i, REQ_PICK);
        } catch (Throwable t) {
            try {
                Intent i2 = new Intent(Intent.ACTION_PICK);
                i2.setType("image/*");
                startActivityForResult(i2, REQ_PICK);
            } catch (Throwable t2) {
                Toast.makeText(this, "打不开相册：" + t2.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_HOLO) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                MainActivity.setHoloCardImage(SettingsActivity.this, data.getData());
                Toast.makeText(SettingsActivity.this, "卡片正面图已更新", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (req != REQ_PICK) return;
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        // 拷进 App 私有目录：这样不依赖相册的 URI 授权，用户删原图也不影响
        File dst = new File(getFilesDir(), "bg_cover.jpg");
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) throw new Exception("打不开这张图");
            out = new FileOutputStream(dst);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            XqService.spPutStr(XqService.K_UI_BG_IMG, dst.getAbsolutePath());
            updateBgImgLabel();
            apply();
            Toast.makeText(this, "背景图已换成你选的这张", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "换图失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
        }
    }
}
