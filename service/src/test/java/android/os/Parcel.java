package android.os;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

public class Parcel {
    public static final AtomicInteger obtainCount = new AtomicInteger(0);

    private Queue<Object> queue = new LinkedList<>();

    public static void resetStats() {
        obtainCount.set(0);
    }

    public static Parcel obtain() {
        obtainCount.incrementAndGet();
        return new Parcel();
    }

    public void recycle() {
        queue.clear();
    }

    public int dataSize() {
        return 100;
    }

    // Write methods
    public void pushBinder(IBinder binder) { queue.add(binder); }
    public void pushInt(int val) { queue.add(val); }
    public void pushLong(long val) { queue.add(val); }
    public void writeInt(int val) { queue.add(val); }
    public void writeLong(long val) { queue.add(val); }
    public void writeStrongBinder(IBinder val) { queue.add(val); }

    // Read methods
    public IBinder readStrongBinder() {
        Object o = queue.poll();
        return (o instanceof IBinder) ? (IBinder) o : new Binder();
    }
    public int readInt() {
        Object o = queue.poll();
        return (o instanceof Integer) ? (Integer) o : 0;
    }
    public long readLong() {
        Object o = queue.poll();
        return (o instanceof Long) ? (Long) o : 0L;
    }

    // Other stubs
    public void writeNoException() {}
    public void readException() {}
    public <T> T readTypedObject(Parcelable.Creator<T> c) { return null; }
    public void writeTypedObject(Parcelable val, int parcelableFlags) {}
    public void enforceInterface(String interfaceName) {}
    public byte[] createByteArray() { return new byte[0]; }
    public void readByteArray(byte[] val) {}
    public void setDataPosition(int pos) {}
    public int dataPosition() { return 0; }
    public void appendFrom(Parcel parcel, int offset, int length) {}
}
