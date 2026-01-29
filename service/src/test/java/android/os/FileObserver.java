package android.os;
import java.io.File;
public abstract class FileObserver {
    public static final int CLOSE_WRITE = 8;
    public static final int DELETE = 512;
    public static final int MOVED_FROM = 64;
    public static final int MOVED_TO = 128;

    public FileObserver(File path, int mask) {}
    public abstract void onEvent(int event, String path);
    public void startWatching() {}
    public void stopWatching() {}
}
