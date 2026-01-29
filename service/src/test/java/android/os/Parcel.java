package android.os;

import java.util.concurrent.atomic.AtomicInteger;

public class Parcel {
    public static final AtomicInteger obtainCount = new AtomicInteger(0);

    public static Parcel obtain() {
        obtainCount.incrementAndGet();
        return new Parcel();
    }

    public static void resetStats() {
        obtainCount.set(0);
    }

    public void recycle() {}

    public int dataSize() {
        return data.size();
    }

    public int dataPosition() {
        return pos;
    }

    public void setDataPosition(int pos) {
        this.pos = pos;
    }

    public void writeNoException() {}
    public void readException() {}

    public <T> T readTypedObject(Parcelable.Creator<T> c) {
        return null;
    }
    public void writeTypedObject(Parcelable val, int parcelableFlags) {}
    public void enforceInterface(String interfaceName) {}

    // Mock helper methods
    public void pushBinder(IBinder binder) {}
    public void pushInt(int val) {}
    public void pushLong(long val) {}

    public IBinder readStrongBinder() { return null; }
    public int readInt() { return 0; }
    public long readLong() { return 0; }

    public void appendFrom(Parcel parcel, int offset, int length) {}
    public void setDataPosition(int pos) {}
    public int dataPosition() { return 0; }
    public void writeStrongBinder(IBinder val) {}
    public void writeInt(int val) {}
    public void writeLong(long val) {}
}
