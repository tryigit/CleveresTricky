package android.os;
import java.io.File;
public abstract class FileObserver {
    public static final int MODIFY = 2;
    public static final int ATTRIB = 4;
    public static final int CLOSE_WRITE = 8;
    public static final int CREATE = 256;
    public static final int DELETE = 512;
    public static final int DELETE_SELF = 1024;
    public static final int MOVE_SELF = 2048;
    public static final int MOVED_FROM = 64;
    public static final int MOVED_TO = 128;

    public FileObserver(String path, int mask) {}
    public FileObserver(File path, int mask) {}
    public abstract void onEvent(int event, String path);
    public void startWatching() {}
    public void stopWatching() {}
}
