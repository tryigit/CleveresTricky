package android.os;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

public class Parcel {
    public static final AtomicInteger obtainCount = new AtomicInteger(0);

    private Queue<Object> data = new LinkedList<>();
    private int dataPosition = 0;

    public static void resetStats() {
        obtainCount.set(0);
    }

    public static Parcel obtain() {
        obtainCount.incrementAndGet();
        return new Parcel();
    }

    public void recycle() { }

    // Test helpers
    public void pushLong(long l) { data.add(l); }
    public void pushInt(int i) { data.add(i); }
    public void pushBinder(IBinder b) { data.add(b); }

    public int readInt() {
        Object o = data.poll();
        return o instanceof Integer ? (Integer)o : 0;
    }
    public long readLong() {
        Object o = data.poll();
        return o instanceof Long ? (Long)o : 0L;
    }
    public IBinder readStrongBinder() {
        Object o = data.poll();
        return o instanceof IBinder ? (IBinder)o : new Binder();
    }

    public void writeInt(int i) {}
    public void writeLong(long l) {}
    public void writeStrongBinder(IBinder b) {}

    public int dataPosition() { return dataPosition; }
    public void setDataPosition(int pos) { dataPosition = pos; }

    public void appendFrom(Parcel other, int start, int length) {}
    public int dataSize() { return 0; }
}
