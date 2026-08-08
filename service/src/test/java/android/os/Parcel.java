package android.os;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

public class Parcel {
    public static final AtomicInteger obtainCount = new AtomicInteger(0);

    private Queue<Object> queue = new LinkedList<>();
    private int lastDeclaredSize;

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
        long value = (o instanceof Long) ? (Long) o : 0L;
        lastDeclaredSize = value >= 0 && value <= Integer.MAX_VALUE ? (int) value : 0;
        return value;
    }
    public String readString() {
        Object o = queue.poll();
        return (o instanceof String) ? (String) o : null;
    }
    public void writeString(String val) { queue.add(val); }

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
    public int dataAvail() { return queue.isEmpty() ? lastDeclaredSize : Integer.MAX_VALUE; }
    public void appendFrom(Parcel parcel, int offset, int length) {}
}
