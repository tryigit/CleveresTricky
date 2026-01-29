package android.util;

public class Log {
    public static int d(String tag, String msg) { System.out.println("D/" + tag + ": " + msg); return 0; }
    public static int i(String tag, String msg) { System.out.println("I/" + tag + ": " + msg); return 0; }
    public static int e(String tag, String msg) { System.err.println("E/" + tag + ": " + msg); return 0; }
    public static int e(String tag, String msg, Throwable tr) { System.err.println("E/" + tag + ": " + msg); tr.printStackTrace(); return 0; }
}
