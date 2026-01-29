package cleveres.tricky.cleverestech;

import android.util.Log;
import kotlin.jvm.functions.Function0;

public class Logger {
    private static final String TAG = "TrickyStore";
    public static void d(String msg) {
        Log.d(TAG, msg);
    }

    public static void e(String msg) {
        Log.e(TAG, msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
    }

    public static void i(String msg) {
        Log.i(TAG, msg);
    }

    public static void i(Function0<String> msgProvider) {
        if (Log.isLoggable(TAG, Log.INFO)) {
            Log.i(TAG, msgProvider.invoke());
        }
    }

}
