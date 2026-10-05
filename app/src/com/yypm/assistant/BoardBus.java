package com.yypm.assistant;

/**
 * 识别结果总线：无障碍服务（XqService）把识别到的局面推到这里，
 * 主界面（MainActivity）轮询显示 —— 与鲨鱼象棋"主界面棋盘实时同步"一致。
 */
public class BoardBus {

    private static volatile char[][] board;
    private static volatile float[][] conf;
    private static volatile String fen = "";
    private static volatile String side = "";
    private static volatile String move = "";
    private static volatile String info = "";
    private static volatile long at = 0;

    public static void push(char[][] b, float[][] c, String fenStr, String sideStr) {
        board = b;
        conf = c;
        fen = fenStr == null ? "" : fenStr;
        side = sideStr == null ? "" : sideStr;
        at = System.currentTimeMillis();
    }

    public static void pushMove(String m, String i) {
        move = m == null ? "" : m;
        info = i == null ? "" : i;
    }

    public static char[][] board() { return board; }
    public static float[][] conf() { return conf; }
    public static String fen() { return fen; }
    public static String side() { return side; }
    public static String move() { return move; }
    public static String info() { return info; }
    public static long at() { return at; }
    public static boolean has() { return board != null; }
}
