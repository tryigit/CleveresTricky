package android.os;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class Parcel {
    public static final AtomicInteger obtainCount = new AtomicInteger(0);
    private int mDataPosition = 0;
    private List<Object> mObjects = new ArrayList<>();
    private int mReadPosition = 0;

    public static Parcel obtain() {
        obtainCount.incrementAndGet();
        return new Parcel();
    }

    public static void resetStats() {
        obtainCount.set(0);
    }

    public void recycle() {
        mObjects.clear();
        mReadPosition = 0;
    }

    public int dataSize() {
        return items.size() * 4; // Mock size
    }

    public void writeNoException() {}

    public void readException() {}

    public <T> T readTypedObject(Parcelable.Creator<T> c) {
        return null;
    }

    public void writeTypedObject(Parcelable val, int parcelableFlags) {}

    public void enforceInterface(String interfaceName) {}

    // Mock methods for test
    public void pushBinder(IBinder binder) { mObjects.add(binder); }
    public void pushInt(int i) { mObjects.add(i); }
    public void pushLong(long l) { mObjects.add(l); }

    public void writeInt(int i) { mObjects.add(i); }
    public void writeLong(long l) { mObjects.add(l); }
    public void writeStrongBinder(IBinder val) { mObjects.add(val); }

    public IBinder readStrongBinder() {
        if (mReadPosition < mObjects.size()) {
             Object o = mObjects.get(mReadPosition);
             if (o instanceof IBinder) {
                 mReadPosition++;
                 return (IBinder) o;
             }
        }
        // Advance anyway to keep sync? Or return default.
        // If the test pushes everything correctly, we should find IBinder.
        // If not found, return dummy.
        // But if we return dummy without advancing, we desync.
        // Let's assume we should advance only if we find something or assume it's the next one.
        // But types are mixed.
        // Let's just return item at mReadPosition and cast if possible? No.
        // The tests seem to push in exact order.
        if (mReadPosition < mObjects.size()) {
             Object o = mObjects.get(mReadPosition);
             // If we expect Binder but find something else, maybe we should skip it?
             // Or maybe just return dummy and NOT advance?
             // But the code reads sequentially.
             mReadPosition++; // Assume it was intended to be read
             if (o instanceof IBinder) return (IBinder) o;
        }
        return new Binder();
    }

    public int readInt() {
        if (mReadPosition < mObjects.size()) {
             Object o = mObjects.get(mReadPosition++);
             if (o instanceof Integer) return (Integer) o;
        }
        return 0;
    }

    public long readLong() {
        if (mReadPosition < mObjects.size()) {
             Object o = mObjects.get(mReadPosition++);
             if (o instanceof Long) return (Long) o;
        }
        return 0;
    }

    public void appendFrom(Parcel other, int offset, int length) {}
    public void setDataPosition(int pos) { mDataPosition = pos; }
    public int dataPosition() { return mDataPosition; }

    public byte[] createByteArray() { return new byte[0]; }
    public <T> T[] createTypedArray(Parcelable.Creator<T> c) {
        return (T[]) java.lang.reflect.Array.newInstance(Object.class, 0);
    }
}
