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
        return 100; // Simulated size
    }

    public void writeNoException() {}

    public void readException() {}

    public <T> T readTypedObject(Parcelable.Creator<T> c) {
        return null;
    }

    public void writeTypedObject(Parcelable val, int parcelableFlags) {}

    public void enforceInterface(String interfaceName) {}

    // Mock methods for BinderInterceptorTest
    public void pushInt(int val) {}
    public void pushLong(long val) {}
    public void pushBinder(IBinder val) {}
    public int readInt() { return 0; }
    public long readLong() { return 0; }
    public IBinder readStrongBinder() { return null; }
}
