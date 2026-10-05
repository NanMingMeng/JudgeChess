package com.yypm.assistant;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * 透明授权页：只干一件事 —— 把系统的「屏幕捕获」授权弹窗顶出来。
 *
 * 为什么需要它：
 *   MediaProjection 授权必须由 Activity 用 startActivityForResult 发起，
 *   无障碍服务自己弹不出来。以前要求用户先跑去主界面点按钮，很别扭。
 *   现在点悬浮窗的「链」就会自动拉起本页 → 系统弹窗自动出现 → 点「允许」→
 *   自动开始捕获 → 自动开始连线。用户不需要离开对局界面。
 *
 * ★ 本页曾经引发过「系统授权弹窗无限重复弹出」，原因和修法：
 *   上一版调用方用 FLAG_ACTIVITY_CLEAR_TOP 反复 startActivity 本页，而
 *   系统一次只允许存在一个待答复的屏幕捕获请求：**新的 createScreenCaptureIntent()
 *   会作废上一个请求并把 RESULT_CANCELED 回给上一页**。于是「1.5 秒后又问一次」
 *   变成「永远问不完，用户永远点不中」。现在：
 *     · 调用方加了「请求在飞行中就不再发」的闸门（XqService.captureAskFinished）
 *     · 本页在 manifest 里是 singleTop，不会再被顶掉重建
 *     · 每次发起前先看有没有还没答复的请求
 */
public class PermissionActivity extends Activity {

    private static final String TAG = "YYPM";
    private static final int REQ = 4001;
    private boolean asked = false;
    private boolean gotResult = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        request();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 某些 ROM 上 onCreate 时拉起会被拒，回到前台再兜一次
        if (!asked) request();
    }

    private void request() {
        asked = true;
        try {
            startActivityForResult(CaptureService.captureIntent(this), REQ);
            Log.i(TAG, "已弹出屏幕捕获授权（整屏模式）");
        } catch (Throwable t) {
            Log.e(TAG, "拉起屏幕捕获授权失败", t);
            CaptureService.flog(this, "拉起屏幕捕获授权失败: " + t);
            XqService.captureAskFinished(false);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ) { finish(); return; }
        if (res == RESULT_OK && data != null) {
            gotResult = true;
            try {
                CaptureService.startWith(this, res, data);
                XqService.wantLinkAfterCapture = true;   // 授完权自动接上连线
                CaptureService.flog(this, "用户已授权，交给 CaptureService");
                Log.i(TAG, "屏幕捕获已授权，交给 CaptureService");
            } catch (Throwable t) {
                Log.e(TAG, "启动 CaptureService 失败", t);
                CaptureService.flog(this, "启动 CaptureService 失败: " + t);
            }
            XqService.captureAskFinished(true);
        } else {
            gotResult = true;
            CaptureService.flog(this, "授权被取消或被新的请求顶替");
            Log.w(TAG, "用户取消了屏幕捕获授权");
            XqService.wantLinkAfterCapture = false;
            XqService.captureAskFinished(false);
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        // 没等到结果就被销毁（被顶掉 / 被系统回收）：必须把飞行标记放掉，
        // 否则调用方会以为「还在等用户点」，此后再也不弹授权，用户以为按钮坏了。
        if (!gotResult) XqService.captureAskFinished(false);
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        // 不要任何转场动画，避免在游戏画面上闪一下
        overridePendingTransition(0, 0);
    }
}
