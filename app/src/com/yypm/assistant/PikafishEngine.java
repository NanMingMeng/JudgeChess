package com.yypm.assistant;

import android.util.Log;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** 皮卡鱼 UCI 引擎封装（在 Android 上以子进程运行 arm64 原生引擎）。
 *
 *  三个关键设计：
 *   1. 单条常驻读线程 + 队列。旧实现每次 readLine 都新起一个线程，
 *      超时后那个线程仍阻塞在 readLine 上，会把随后到达的引擎输出「偷走」，
 *      导致 analyse 偶发读到空结果。
 *   2. stderr 合并进 stdout 一并排空（redirectErrorStream(true)）。
 *      旧实现 stderr 是独立管道且无人读取，写满 64KB 后引擎会永久阻塞。
 *   3. 引擎死掉能自愈：ensureStarted() 用 start() 时记住的参数重新拉起，
 *      并有最小重启间隔防止热循环。
 */
public class PikafishEngine {

    private static final String TAG = "YYPM";

    public static class Move {
        public String uci = "";
        public String ponder = "";
        public int scoreCp = 0;
        public int scoreMate = 0;
        public int depth = 0;
        public long nodes = 0, nps = 0;
        public boolean hasMate = false;
        public String scoreText() {
            return hasMate ? ("M" + scoreMate) : String.format(Locale.US, "%+.2f", scoreCp / 100.0);
        }
    }

    private Process proc;
    private BufferedReader in;
    private BufferedWriter out;
    private final Object lock = new Object();
    private final Object startLock = new Object();
    private volatile boolean ready = false;
    private volatile boolean starting = false;

    /** 引擎输出的行（由常驻泵线程填充）。 */
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<String>();

    /** 重启所需参数：start() 时记住，引擎意外死亡后可自行拉起。 */
    private volatile String exePath, nnuePath;
    /** ★ v4.28：引擎自报名字（UCI 握手时的 "id name ..."），主界面显示用。 */
    private volatile String engineName = "";
    private volatile int threads = 4, hash = 64;
    private volatile long lastStartAt = 0L;
    private volatile String deathReason = "";
    private volatile int restartCount = 0;

    /** ★ v4.54：把引擎生死事件写进应用自己的文件日志（logcat 跑一会儿就被刷掉）。 */
    public interface LogSink { void log(String s); }
    private static volatile LogSink sink;
    public static void setLogSink(LogSink s) { sink = s; }
    static void flog(String s) {
        LogSink l = sink;
        if (l == null) return;
        try { l.log(s); } catch (Throwable ignored) {}
    }
    /** ★ v4.54：启动代次。旧进程的读线程 EOF 时**只能**标记自己那一代，
     *  否则它晚一步执行就会把刚起好的新引擎标成「已退出」→ 再被重启 → 无休止循环。 */
    private volatile int gen = 0;
    /** ★ v4.54：重启退避，成功启动一次即复位。 */
    private volatile long restartBackoffMs = 3000L;

    public boolean isReady() { return ready && proc != null && proc.isAlive(); }

    /** 引擎最近一次异常原因（空串表示没出过问题）。用于状态行提示。 */
    public String deathReason() { return deathReason; }
    public int restartCount() { return restartCount; }

    /** exePath: app nativeLibraryDir 下的引擎文件；nnuePath: 权重文件完整路径。 */
    public void start(String exePath, String nnuePath, int threads, int hash) throws IOException {
        this.exePath = exePath;
        this.nnuePath = nnuePath;
        this.threads = threads;
        this.hash = hash;
        starting = true;
        try {
            // ★ 先把旧进程彻底清掉：否则重启时会留下孤儿引擎进程（实测两个并存）
            killProc();

            File exe = new File(exePath);
            if (!exe.canExecute()) exe.setExecutable(true);
            ProcessBuilder pb = new ProcessBuilder(exePath);
            pb.directory(exe.getParentFile());
            // ★ 必须合并：stderr 若是独立管道且无人读，写满后引擎会永久卡死
            pb.redirectErrorStream(true);
            proc = pb.start();
            in = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
            out = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), "UTF-8"));

            lines.clear();
            final int myGen = gen;          // ★ v4.54：这一代引擎的编号
            startPump(in, myGen);

            send("uci");
            awaitUciHandshake(40000);
            send("setoption name EvalFile value " + nnuePath);
            send("setoption name Threads value " + threads);
            send("setoption name Hash value " + hash);
            send("isready");
            await("readyok", 40000);
            ready = true;
            deathReason = "";
            restartBackoffMs = 3000L;       // ★ v4.54：起来一次就把退避复位
            flog("引擎已就绪（threads=" + threads + " hash=" + hash + "）");
        } finally {
            starting = false;
        }
    }

    /** 杀掉当前子进程并释放流；不清除重启参数，也不改 deathReason。 */
    private void killProc() {
        gen++;                          // ★ v4.54：作废旧读线程（它 EOF 时不能再改 ready）
        try { if (out != null) { out.write("quit\n"); out.flush(); } } catch (Throwable ignored) {}
        try { if (proc != null) proc.destroy(); } catch (Throwable ignored) {}
        try { if (in != null) in.close(); } catch (Throwable ignored) {}
        try { if (out != null) out.close(); } catch (Throwable ignored) {}
        proc = null; in = null; out = null;
        ready = false;
        lines.clear();
    }

    /** 常驻读线程：把引擎输出灌进队列，进程结束或流关闭时自然退出。 */
    private void startPump(final BufferedReader r, final int myGen) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    String s;
                    while ((s = r.readLine()) != null) lines.offer(s);
                } catch (Throwable ignored) {
                } finally {
                    // 读到 EOF = 引擎已退出，标记不可用（下一次 analyse 会触发重启）。
                    // ★ v4.54：只认自己那一代 —— 旧进程的读线程晚一步 EOF 时，不能再把
                    //   刚起好的新引擎标死（否则重启会被自己拆掉，形成无休止的重启循环）。
                    if (gen != myGen) return;
                    ready = false;
                    if (deathReason.length() == 0) deathReason = "引擎进程已退出";
                    flog("引擎进程已退出（读线程 EOF），下次算招会自动重启");
                }
            }
        });
        t.setDaemon(true);
        t.setName("pikafish-pump");
        t.start();
    }

    /**
     * 保证引擎可用；不可用则用记住的参数重新拉起。
     * 返回 false 表示这次仍然没拉起来（调用方应当稍后重试，而不是卡死）。
     */
    /**
     * 引擎是否可用；不可用则**异步**发起重启，本单位立刻返回 false。
     *
     * ⚠ 绝不能在这里同步等重启完成：start() 要等 uciok/readyok（引擎还要加载
     *    50MB NNUE），把这段放在算招线程里，界面就会长时间停在「算招中…」，
     *    看起来完全卡死。重启交给后台线程，这一轮算招先跳过，下一轮自然用上。
     */
    public boolean ensureStarted() {
        if (isReady()) return true;
        requestRestart();
        return false;
    }

    /** 后台重启引擎，不阻塞调用方。有最小间隔，避免热循环。 */
    private void requestRestart() {
        final String exe = exePath, nnue = nnuePath;
        if (exe == null || nnue == null) return;      // 还没初始化过，无法重启
        synchronized (startLock) {
            if (starting || isReady()) return;        // 已经在启动了
            long now = System.currentTimeMillis();
            // ★ v4.54：退避重启（3s → 6s → 12s → 24s → 30s 封顶）。
            //   原来固定 3s —— 实测内存吃紧时连续 30 多秒刷「恢复中 / 异常」：
            //   每 3 秒重拉一个带 50MB 权重的引擎进程，越抢内存越起不来。
            long wait = restartBackoffMs;
            if (now - lastStartAt < wait) return;
            lastStartAt = now;
            restartBackoffMs = Math.min(restartBackoffMs * 2, 8000L);
            starting = true;
            flog("引擎重启中（第 " + (restartCount + 1) + " 次，间隔 " + (wait / 1000) + "s）");
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Log.i(TAG, "引擎不可用(" + deathReason + ")，后台重启中…");
                    start(exe, nnue, threads, hash);
                    restartCount++;
                    Log.i(TAG, "引擎已重启（第 " + restartCount + " 次）");
                    flog("引擎已重启成功（第 " + restartCount + " 次）");
                } catch (Throwable e) {
                    deathReason = "重启失败:" + e.getClass().getSimpleName();
                    Log.w(TAG, "引擎重启失败", e);
                    flog("引擎重启失败：" + deathReason);
                } finally {
                    starting = false;
                }
            }
        });
        t.setDaemon(true);
        t.setName("engine-restart");
        t.start();
    }


    public void quit() {
        gen++;                          // ★ v4.54：作废旧读线程
        ready = false;
        try { if (out != null) { out.write("quit\n"); out.flush(); } } catch (Exception ignored) {}
        try { if (proc != null) proc.destroy(); } catch (Exception ignored) {}
        try { if (in != null) in.close(); } catch (Exception ignored) {}
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        proc = null;
    }

    /** 引擎不可用时彻底清理，等 ensureStarted() 重新拉起。 */
    private void markDead(String why) {
        if (deathReason.length() == 0 || "引擎进程已退出".equals(deathReason)) deathReason = why;
        ready = false;
        gen++;                          // ★ v4.54：同上，作废旧读线程
        flog("引擎标记不可用：" + why);
        try { if (proc != null) proc.destroy(); } catch (Throwable ignored) {}
        proc = null;
        try { if (in != null) in.close(); } catch (Throwable ignored) {}
        try { if (out != null) out.close(); } catch (Throwable ignored) {}
        lines.clear();
    }

    private void send(String s) throws IOException {
        out.write(s);
        out.write("\n");
        out.flush();
    }

    /** 从队列取一行；超时返回 null。不再新起线程，因此不会偷走后续输出。 */
    private String readLine(long timeoutMs) {
        try {
            return lines.poll(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return null;
        }
    }

    /** ★ v4.28：引擎自报名字（主界面显示）。引擎没起过时为 ""。 */
    public String engineName() { return engineName == null ? "" : engineName; }

    /**
     * ★ v4.28：UCI 握手并顺手把引擎名抓下来。
     *
     * 原来直接用 await("uciok")，那会把 "id name Pikafish 2026-09-25" 这条
     * 白扔掉 —— 而主界面正好要显示它。这里多看一眼就行，代价为零。
     */
    private void awaitUciHandshake(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            String line = readLine(Math.max(100, end - System.currentTimeMillis()));
            if (line == null) break;
            if (line.startsWith("id name ")) {
                String n = line.substring(8).trim();
                if (n.length() > 0) engineName = n;
            }
            if (line.startsWith("uciok")) return;
        }
    }

    private void await(String token, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            String line = readLine(Math.max(100, end - System.currentTimeMillis()));
            if (line == null) break;
            if (line.startsWith(token)) return;
        }
    }

    /**
     * 多路分析：返回前 multipv 个候选着法（按引擎输出顺序）。
     * 不再向外抛 IOException：引擎出问题一律返回空列表并记下原因，
     * 由调用方决定是提示还是稍后重试（绝不能因此把连线停死）。
     */
    public List<Move> analyse(String fenWithSide, int movetimeMs, int multipv) {
        List<Move> result = new ArrayList<Move>();
        if (!ensureStarted()) return result;
        synchronized (lock) {
            try {
                lines.clear();
                try { send("stop"); } catch (Exception ignored) {}
                long tA0 = System.currentTimeMillis();
                // ★ stop 会让引擎吐出上一次搜索的 bestmove；它若晚于 readyok 到达，
                //   就会被下面这一轮当成自己的结果 → 立刻 break、候选为空，
                //   界面表现就是反复「算招中…」却永远不出招。这里先把它排干净。
                drainBestmove(400);
                send("setoption name MultiPV value " + multipv);
                send("isready");
                await("readyok", 8000);
                send("position fen " + fenWithSide);
                send("go movetime " + movetimeMs);

                Map<Integer, Move> latest = new TreeMap<Integer, Move>();
                long end = System.currentTimeMillis() + Math.max(4000, movetimeMs + 2500);
                while (System.currentTimeMillis() < end) {
                    String line = readLine(Math.max(100, end - System.currentTimeMillis()));
                    if (line == null) break;
                    if (line.startsWith("info ") && line.contains(" pv ")) {
                        Integer idx = parseMultiPv(line);
                        Move m = parseInfo(line);
                        if (m.uci != null && m.uci.length() >= 4) latest.put(idx == null ? 1 : idx, m);
                    } else if (line.startsWith("bestmove")) {
                        break;
                    }
                }
                try { send("setoption name MultiPV value 1"); } catch (Exception ignored) {}
                result.addAll(latest.values());
                long dtA = System.currentTimeMillis() - tA0;
                Log.i(TAG, "引擎分析 movetime=" + movetimeMs + " 多路=" + multipv + " 耗时=" + dtA
                        + "ms 候选=" + result.size()
                        + (result.isEmpty() ? " 【无着法】"
                        : (" best=" + result.get(0).uci + " 深度=" + result.get(0).depth
                           + " 分=" + result.get(0).scoreText())));
                if (!result.isEmpty()) deathReason = "";
            } catch (Throwable t) {
                markDead("写引擎失败:" + t.getMessage());
                Log.w(TAG, "引擎通信中断，已标记待重启", t);
            }
        }
        return result;
    }


    /** 排掉队列里残留的 bestmove（stop 引发的那条），避免它被误当成本轮结果。 */
    private void drainBestmove(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            String l = readLine(Math.max(50L, end - System.currentTimeMillis()));
            if (l == null) break;
            if (l.startsWith("bestmove")) break;
        }
    }
    private static Integer parseMultiPv(String line) {
        int i = line.indexOf("multipv ");
        if (i < 0) return null;
        try {
            int j = i + 8;
            int k = j;
            while (k < line.length() && Character.isDigit(line.charAt(k))) k++;
            return Integer.parseInt(line.substring(j, k));
        } catch (Exception e) { return null; }
    }

    private static Move parseInfo(String line) {
        Move m = new Move();
        try {
            int i = line.indexOf(" depth ");
            if (i >= 0) {
                int j = i + 7, k = j;
                while (k < line.length() && Character.isDigit(line.charAt(k))) k++;
                m.depth = Integer.parseInt(line.substring(j, k));
            }
            i = line.indexOf("score cp ");
            if (i >= 0) {
                int j = i + 9, k = j;
                if (k < line.length() && line.charAt(k) == '-') k++;
                while (k < line.length() && Character.isDigit(line.charAt(k))) k++;
                m.scoreCp = Integer.parseInt(line.substring(j, k));
            }
            i = line.indexOf("score mate ");
            if (i >= 0) {
                int j = i + 11, k = j;
                if (k < line.length() && line.charAt(k) == '-') k++;
                while (k < line.length() && Character.isDigit(line.charAt(k))) k++;
                m.scoreMate = Integer.parseInt(line.substring(j, k));
                m.hasMate = true;
            }
            i = line.indexOf("nodes ");
            if (i >= 0) {
                int j = i + 6, k = j;
                while (k < line.length() && Character.isDigit(line.charAt(k))) k++;
                m.nodes = Long.parseLong(line.substring(j, k));
            }
            i = line.indexOf(" nps ");
            if (i >= 0) {
                int j = i + 5, k = j;
                while (k < line.length() && Character.isDigit(line.charAt(k))) k++;
                m.nps = Long.parseLong(line.substring(j, k));
            }
            i = line.indexOf(" pv ");
            if (i >= 0) {
                String pv = line.substring(i + 4).trim();
                String[] parts = pv.split("\\s+");
                if (parts.length > 0) m.uci = parts[0];
                if (parts.length > 1) m.ponder = parts[1];
            }
        } catch (Exception ignored) {}
        return m;
    }
}
