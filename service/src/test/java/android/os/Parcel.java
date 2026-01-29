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

    // Stub methods for BinderInterceptorTest compilation
    public void pushBinder(IBinder b) {}
    public void pushInt(int i) {}
    public void pushLong(long l) {}

    // Methods called by BinderInterceptor but not in original stub
    public IBinder readStrongBinder() { return null; }
    public int readInt() { return 0; }
    public long readLong() { return 0; }
    public void appendFrom(Parcel parcel, int offset, int length) {}
    public void setDataPosition(int pos) {}
    public int dataPosition() { return 0; }
    public void writeInt(int i) {}
    public void writeLong(long l) {}
    public void writeStrongBinder(IBinder b) {}
}
