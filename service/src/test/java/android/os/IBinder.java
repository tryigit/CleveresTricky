package android.os;

public interface IBinder {
    public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException;

    public interface DeathRecipient {
        public void binderDied();
    }
}
