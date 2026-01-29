package cleveres.tricky.cleverestech;

import android.util.Log;

public class Logger {
    private static final String TAG = "TrickyStore";

    public interface LogImpl {
        void d(String tag, String msg);
        void e(String tag, String msg);
        void e(String tag, String msg, Throwable t);
        void i(String tag, String msg);
    }

    private static LogImpl impl = new LogImpl() {
        @Override
        public void d(String tag, String msg) {
            Log.d(tag, msg);
        }

        @Override
        public void e(String tag, String msg) {
            Log.e(tag, msg);
        }

        @Override
        public void e(String tag, String msg, Throwable t) {
            Log.e(tag, msg, t);
        }

        @Override
        public void i(String tag, String msg) {
            Log.i(tag, msg);
        }
    };

    public static void setImpl(LogImpl newImpl) {
        impl = newImpl;
    }

    public static void d(String msg) {
        impl.d(TAG, msg);
    }

    public static void e(String msg) {
        impl.e(TAG, msg);
    }

    public static void e(String msg, Throwable t) {
        impl.e(TAG, msg, t);
    }

    public static void i(String msg) {
        impl.i(TAG, msg);
    }

    public static boolean isDebugEnabled() {
        return Log.isLoggable(TAG, Log.DEBUG);
    }

}
