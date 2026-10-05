package com.yypm.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import java.nio.ByteBuffer;

/**
 * 屏幕捕获（MediaProjection + VirtualDisplay + ImageReader）。
 *
 * ★ V2.0 关键修复：每帧 14MB 的「分配风暴」
 *
 *   旧实现每收到一帧就 Bitmap.createBitmap(1272×2800) 新建一张 14MB 位图，
 *   换帧时把上一张 recycle 掉；而取帧方 XqService 拿到的那张**从来不回收**。
 *   VirtualDisplay 是「屏幕一变就送一帧」，动画/转场时能到 60~120fps：
 *
 *       14MB × 60fps ≈ 840MB/s 的位图垃圾
 *
 *   后果不是慢慢变卡，而是**突发性的**：
 *     · GC 疯狂回收，主线程被 stop-the-world 暂停数秒
 *     · 「截图回调 4 秒超时」本身也跑在主线程上 → 连超时都触发不了
 *     · 于是 busy 卡死十几秒（日志「上一帧卡死，已强制恢复」）
 *     · 随后分配失败/OOM → grab() 返回 null → 界面「连线: 截图失败」
 *
 *   实测对局中途一切正常、**偏偏在一局打完开新一局时炸**，就是因为那个转场
 *   动画把帧率顶到最高。日志取证：帧龄只有 7ms（帧在飞速到达），
 *   但内容 88.8% 落在亮度 224~255、抽样点全是 #F7F7F7 —— 系统占位帧。
 *
 *   现在改成：帧缓冲**复用**（不再每帧新建），并且**限流到最多 8fps**
 *   （识别本来每 400~700ms 才要一帧，多出来的帧纯属浪费）。
 *   分配量从 ~840MB/s 降到 ~20MB/s。
 *
 * 使用流程：
 *   1) MainActivity / PermissionActivity 调 CaptureService.captureIntent() 请求授权
 *   2) 拿到 resultCode + data 后 startForegroundService 拉起本服务
 *   3) 本服务先 startForeground(type=mediaProjection)，再 getMediaProjection
 *      —— 顺序不能反：Android 14+ 要求先成为对应类型的前台服务，否则抛异常
 *   4) ImageReader 持续回调，保存最新一帧；需要时调 CaptureService.grab() 取副本
 */
public class CaptureService extends Service {

    private static final String TAG = "YYPM";
    public static final String ACTION_START = "com.yypm.assistant.CAPTURE_START";
    public static final String ACTION_STOP  = "com.yypm.assistant.CAPTURE_STOP";
    public static final String ACTION_REBUILD = "com.yypm.assistant.CAPTURE_REBUILD";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";

    private static final String CH_ID = "yypm_capture";
    private static final int NOTI_ID = 1001;

    /** 取帧上限：识别每 400~700ms 才要一帧，给到 8fps 已经远超所需。
     *  多出来的帧只会在拷贝上白烧 CPU 和内存。
     *  ★ v4.27：改成可配置 —— 低内存设备放宽到 200ms（5fps），进一步降低位图转换压力。 */
    private static final long MIN_FRAME_GAP_MS = 120L;
    private static volatile long sFrameGap = MIN_FRAME_GAP_MS;

    /** ★ v4.27：由 XqService 按设备能力设置取帧间隔（低内存设备自动放宽）。 */
    public static void setFrameGap(long ms) {
        if (ms < 60L) ms = 60L;
        if (ms > 1000L) ms = 1000L;
        sFrameGap = ms;
    }

    private static volatile Bitmap sLatest;
    private static volatile long sLatestAt = 0;
    private static volatile boolean sRunning = false;
    /** ★ v4.56：MediaProjection 是否仍然存活（onStop / release 会置 false）。
     *  用来区分「投影真的停了」和「投影还在、只是画面被别的窗口挡住」——
     *  前者只能重新授权，后者只需等；两种情况都**绝不能**去重建捕获。 */
    private static volatile boolean sAlive = false;
    private static volatile int sW = 0, sH = 0;
    private static volatile String sLastStop = "";
    private static volatile long sFrameCount = 0;   // 累计成功换帧次数（诊断用）

    /** 保护 sLatest 的「读—拷贝」和「写」不交叉。
     *
     *  崩溃根因：grab() 先读 sLatest、判 isRecycled()==false，随后 ImageReader 线程
     *  换帧并 recycle 掉那张图，grab() 再调 b.copy() 就作用在已回收的 Bitmap 上。
     *  这不是 Java 异常，是 native abort（libhwui Bitmap_copy 里 __android_log_assert），
     *  catch (Throwable) 拦不住，整个无障碍服务进程直接死 —— 表现就是「悬浮窗突然没了」。
     */
    private static final Object latestLock = new Object();

    /** sLatest 的可复用绘制目标。sLatest 只在尺寸变化时重建，其余帧直接 blit 进去。 */
    private static Canvas sCanvas;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler bg;
    private int w, h, dpi;

    /** 读帧线程的可复用缓冲：整行（含行末填充）那张，尺寸 w+pad × h。 */
    private Bitmap frameBuf;
    private long lastConvertAt = 0;

    // ---------------- 对外接口 ----------------

    public static boolean isRunning() { return sRunning; }

    /** ★ v4.56：投屏对象是否还活着。画面空白时用它区分「真停了」和「被遮挡」。 */
    public static boolean isAlive() { return sAlive; }
    public static int frameW() { return sW; }
    public static int frameH() { return sH; }
    public static long frameCount() { return sFrameCount; }

    /** 最近一次捕获停止的原因（写进文件日志，事后定位「捕获为什么掉」）。 */
    public static String lastStopReason() { return sLastStop; }

    /**
     * 建「整屏捕获」授权意图。
     *
     * ★ 为什么必须显式锁定整屏（本机实测的血泪教训）：
     *   Android 14+ 的授权弹窗里带一个「单个应用 / 整个屏幕」选择器，默认停在「单个应用」。
     *   用户按默认选完某个应用之后，只要那个应用退到后台，系统就只喂 #F7F7F7 占位帧
     *   （实测占 87%），随后自动停掉投影 —— 这正是「棋盘突然整片空白、捕获中途失效」
     *   的根因，也是后面一连串「捕获掉了 → 重新弹授权」的起点。
     *   用 createConfigForDefaultDisplay() 直接锁定整屏，弹窗里就不再有选择器，
     *   也不会因为切应用而变成占位帧。
     */
    public static Intent captureIntent(Context ctx) {
        MediaProjectionManager mgr = (MediaProjectionManager)
                ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                return mgr.createScreenCaptureIntent(
                        MediaProjectionConfig.createConfigForDefaultDisplay());
            } catch (Throwable t) {
                Log.w(TAG, "CAP 整屏授权意图失败，退回系统默认", t);
            }
        }
        return mgr.createScreenCaptureIntent();
    }

    /** 与 XqService 共用同一个日志文件，出问题时一次就能看全。 */
    static void flog(Context ctx, String s) {
        try {
            java.io.File d = ctx.getExternalFilesDir("logs");
            if (d == null) d = ctx.getFilesDir();
            if (d != null && !d.exists()) d.mkdirs();
            java.io.File f = new java.io.File(d, "app.log");
            if (f.length() > 1024 * 1024) f.delete();
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f, true);
            fo.write((new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS",
                    java.util.Locale.US).format(new java.util.Date())
                    + "  捕获  " + s + "\n").getBytes("UTF-8"));
            fo.close();
        } catch (Throwable ignored) {}
    }

    /** 最新一帧的副本；没有则 null。整个过程持锁，绝不让换帧线程把图从脚下回收掉。 */
    public static Bitmap grab() {
        synchronized (latestLock) {
            Bitmap b = sLatest;
            if (b == null || b.isRecycled()) return null;
            try {
                return b.copy(Bitmap.Config.ARGB_8888, false);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** 距上一帧的毫秒数；-1 表示还没有帧。 */
    public static long frameAge() {
        return sLatestAt == 0 ? -1 : System.currentTimeMillis() - sLatestAt;
    }

    /** 从 Activity 拿到的授权结果启动捕获。 */
    public static void startWith(Context ctx, int resultCode, Intent data) {
        Intent i = new Intent(ctx, CaptureService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_CODE, resultCode);
        i.putExtra(EXTRA_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    /** 就地重建捕获管线（不动授权）：投影还在出帧但内容一直是占位帧时用。
     *  不需要用户重新点「链」，也不会弹新的授权框。 */
    public static void rebuild(Context ctx) {
        try {
            Intent i = new Intent(ctx, CaptureService.class);
            i.setAction(ACTION_REBUILD);
            ctx.startService(i);
        } catch (Throwable ignored) {}
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, CaptureService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("yypm-capture");
        thread.start();
        bg = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_REBUILD.equals(action)) {
            rebuildPipeline();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            release("用户关闭捕获");
            stopSelf();
            return START_NOT_STICKY;
        }

        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (data == null || code == 0) {
            Log.w(TAG, "CAP 缺少授权数据");
            flog(this, "启动失败：缺少授权数据");
            stopSelf();
            return START_NOT_STICKY;
        }

        // ① 必须先成为 mediaProjection 类型的前台服务（Android 14+ 硬性要求）
        startForegroundCompat();
        // ② 再拿 MediaProjection
        try {
            MediaProjectionManager mgr =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mgr.getMediaProjection(code, data);
            if (projection == null) {
                Log.e(TAG, "CAP getMediaProjection 返回 null");
                flog(this, "启动失败：getMediaProjection 返回 null");
                stopSelf();
                return START_NOT_STICKY;
            }
        } catch (Throwable t) {
            Log.e(TAG, "CAP getMediaProjection 失败", t);
            flog(this, "启动失败：getMediaProjection 抛异常 " + t);
            stopSelf();
            return START_NOT_STICKY;
        }

        // ③ 注册回调（Android 14+ 要求在 createVirtualDisplay 之前注册）
        try {
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    Log.i(TAG, "CAP 用户/系统停止了投屏");
                    sLastStop = "系统/用户停止了投屏";
                    sAlive = false;             // ★ v4.56：投影停了
                    flog(CaptureService.this, "投屏被系统或用户停止");
                    release(null);
                }
                @Override public void onCapturedContentResize(int nw, int nh) {
                    Log.i(TAG, "CAP 捕获区域变化 " + nw + "x" + nh);
                    resizeDisplay(nw, nh);
                }
                @Override public void onCapturedContentVisibilityChanged(boolean visible) {
                    Log.i(TAG, "CAP 捕获内容可见=" + visible);
                    if (!visible)
                        flog(CaptureService.this, "捕获内容不可见：系统只会给占位帧，棋盘会整片空白");
                }
            }, bg);
        } catch (Throwable t) {
            Log.w(TAG, "CAP registerCallback 失败", t);
        }

        // ④ 建 VirtualDisplay + ImageReader
        try {
            startCapture();
            sAlive = true;                  // ★ v4.56：投屏建起来了
        } catch (Throwable t) {
            Log.e(TAG, "CAP startCapture 失败", t);
            flog(this, "启动失败：startCapture 抛异常 " + t);
            release(null);
            stopSelf();
            return START_NOT_STICKY;
        }

        Log.i(TAG, "CAP 已启动 " + w + "x" + h + " dpi=" + dpi);
        sLastStop = "";
        flog(this, "已启动捕获 " + w + "x" + h + " dpi=" + dpi
                + (Build.VERSION.SDK_INT >= 34 ? "（整屏）" : "")
                + " 限流=" + sFrameGap + "ms");
        return START_NOT_STICKY;
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "屏幕捕获", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CH_ID);
        else b = new Notification.Builder(this);
        b.setContentTitle("YYPM 棋盘助手")
         .setContentText("正在读取屏幕（连线模式）")
         .setSmallIcon(android.R.drawable.ic_menu_view)
         .setOngoing(true);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTI_ID, n);
        }
    }

    private void startCapture() {
        DisplayMetrics dm = new DisplayMetrics();
        Display d = ((DisplayManager) getSystemService(DISPLAY_SERVICE))
                .getDisplay(Display.DEFAULT_DISPLAY);
        d.getRealMetrics(dm);
        w = dm.widthPixels;
        h = dm.heightPixels;
        dpi = dm.densityDpi;
        sW = w; sH = h;

        makeReader();
        display = projection.createVirtualDisplay("yypm-capture", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, bg);
    }

    /** 建 ImageReader（换尺寸时要重建，所以单独抽出来）。 */
    private void makeReader() {
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            public void onImageAvailable(ImageReader r) {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img == null) return;

                    // ★ 限流：识别每 400~700ms 才要一帧，60fps 全量转换纯属浪费。
                    //   超出的帧直接丢（finally 里会 close，不会占住缓冲）。
                    long now = System.currentTimeMillis();
                    if (now - lastConvertAt < sFrameGap) return;
                    lastConvertAt = now;

                    if (!fillFrame(img)) return;

                    // ★ 不再每帧新建位图：把复用缓冲 blit 进 sLatest。
                    //   与 grab() 的拷贝互斥，否则就是 native 崩溃。
                    synchronized (latestLock) {
                        if (sLatest == null || sLatest.isRecycled()
                                || sLatest.getWidth() != w || sLatest.getHeight() != h) {
                            Bitmap old = sLatest;
                            sLatest = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                            sCanvas = new Canvas(sLatest);
                            if (old != null && !old.isRecycled()) {
                                try { old.recycle(); } catch (Throwable ignored) {}
                            }
                        }
                        sCanvas.drawBitmap(frameBuf, 0, 0, null);
                        sLatestAt = System.currentTimeMillis();
                        sRunning = true;
                        sFrameCount++;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "CAP 取帧失败", t);
                } finally {
                    if (img != null) {
                        try { img.close(); } catch (Throwable ignored) {}
                    }
                }
            }
        }, bg);
    }

    /** 把一帧 Image 拷进复用缓冲 frameBuf（只在尺寸变化时重建，不再每帧分配）。 */
    private boolean fillFrame(Image img) {
        try {
            Image.Plane[] planes = img.getPlanes();
            if (planes == null || planes.length == 0) return false;
            ByteBuffer buf = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * w;
            int bw = w + rowPadding / pixelStride;
            if (bw < w) bw = w;

            if (frameBuf == null || frameBuf.isRecycled()
                    || frameBuf.getWidth() != bw || frameBuf.getHeight() != h) {
                Bitmap old = frameBuf;
                frameBuf = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888);
                if (old != null && !old.isRecycled()) {
                    try { old.recycle(); } catch (Throwable ignored) {}
                }
            }
            buf.rewind();
            frameBuf.copyPixelsFromBuffer(buf);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "CAP fillFrame 失败", t);
            return false;
        }
    }

    /**
     * 捕获尺寸变了（旋转屏幕 / 系统改变了捕获区域）：重建 ImageReader 与 VirtualDisplay。
     * 不重建的话底层缓冲区尺寸和 ImageReader 对不上，会一路吐错位或空白的帧。
     */
    private void resizeDisplay(final int nw, final int nh) {
        if (nw <= 0 || nh <= 0) return;
        if (nw == w && nh == h) return;      // 尺寸没变就别折腾（旧版这里会误报「重建」）
        flog(this, "捕获区域变化 " + nw + "x" + nh + "，重建 VirtualDisplay");
        bg.post(new Runnable() {
            public void run() {
                try {
                    // ★ v4.56：这里同样不能再 createVirtualDisplay（同一个 MediaProjection
                    //   只能建一次 VirtualDisplay）—— 改成只换 Surface，投屏不会被打断。
                    if (display == null || projection == null || !sAlive) {
                        flog(CaptureService.this, "捕获区域变化 " + nw + "x" + nh + "：投屏已停，跳过");
                        return;
                    }
                    if (reattach(nw, nh)) {
                        flog(CaptureService.this, "已按新尺寸重接捕获 " + nw + "x" + nh);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "CAP resizeDisplay 失败", t);
                    flog(CaptureService.this, "重接捕获失败: " + t);
                }
            }
        });
    }

    /** 丢掉复用缓冲（尺寸变化 / 释放时调用）。 */
    private void dropBuffers() {
        Bitmap fb = frameBuf;
        frameBuf = null;
        if (fb != null && !fb.isRecycled()) {
            try { fb.recycle(); } catch (Throwable ignored) {}
        }
    }

    /** 拆掉再重建 VirtualDisplay + ImageReader，复用同一个 MediaProjection。 */
    /**
     * ★ v4.56：就地重接 Surface —— 保留 VirtualDisplay，只换一块新的 ImageReader Surface。
     *
     * 为什么不再用 createVirtualDisplay：
     *   Android 明确禁止对**同一个 MediaProjection** 调第二次 createVirtualDisplay。
     *   系统原话（实测抛的 SecurityException）：
     *     "Don't re-use the resultData to retrieve the same projection instance, and
     *      don't use a token that has timed out. Don't take multiple captures by
     *      invoking MediaProjection#createVirtualDisplay multiple times on the same instance."
     *   旧代码的 rebuild / resize 两条路都这么干 —— 不但必然失败，
     *   还会把一个**本来还活着**的投屏直接弄死，逼用户重新授权。
     *   （实测：只因为画面被别的窗口挡了 25 帧就调它，投屏当场停掉。）
     */
    private boolean reattach(int nw, int nh) {
        if (display == null) return false;
        try {
            try { if (reader != null) reader.close(); } catch (Throwable ignored) {}
            reader = null;
            w = nw; h = nh; sW = nw; sH = nh;
            dropBuffers();
            synchronized (latestLock) {
                Bitmap old = sLatest;
                sLatest = null;
                sCanvas = null;
                sLatestAt = 0;
                if (old != null && !old.isRecycled()) {
                    try { old.recycle(); } catch (Throwable ignored) {}
                }
            }
            lastConvertAt = 0;
            makeReader();
            display.resize(nw, nh, dpi);
            display.setSurface(reader.getSurface());
            return true;
        } catch (Throwable t) {
            flog(this, "重接 Surface 失败: " + t);
            return false;
        }
    }

    /** 就地重接（不动授权、不重建 VirtualDisplay）。投屏已停时什么也不做 —— 那只能重新授权。 */
    private void rebuildPipeline() {
        bg.post(new Runnable() {
            public void run() {
                try {
                    if (display == null || projection == null || !sAlive) {
                        flog(CaptureService.this, "就地重建跳过：投屏已停，需要重新授权");
                        return;
                    }
                    if (reattach(w, h)) {
                        flog(CaptureService.this, "已就地重接捕获 " + w + "x" + h);
                    }
                } catch (Throwable t) {
                    flog(CaptureService.this, "就地重建失败: " + t);
                }
            }
        });
    }

    private void release(String why) {
        if (why != null) {
            sLastStop = why;
            flog(this, "停止捕获：" + why);
        }
        sRunning = false;
        sAlive = false;                 // ★ v4.56：投影没了
        try { if (display != null) display.release(); } catch (Throwable ignored) {}
        display = null;
        try { if (reader != null) reader.close(); } catch (Throwable ignored) {}
        reader = null;
        try { if (projection != null) projection.stop(); } catch (Throwable ignored) {}
        projection = null;
        dropBuffers();
        Bitmap b;
        // 与 grab() 的拷贝互斥：换帧线程正在拷 sLatest 时，这里不能把它回收掉
        synchronized (latestLock) {
            b = sLatest;
            sLatest = null;
            sCanvas = null;
            sLatestAt = 0;
        }
        if (b != null && !b.isRecycled()) { try { b.recycle(); } catch (Throwable ignored) {} }
    }

    @Override
    public void onDestroy() {
        release(null);
        try { if (thread != null) thread.quitSafely(); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
