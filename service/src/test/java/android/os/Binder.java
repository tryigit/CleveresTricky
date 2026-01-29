package android.os;

public class Binder implements IBinder {
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        return false;
    }

    public void attachInterface(IInterface owner, String descriptor) {}
    public String getInterfaceDescriptor() { return null; }
    public boolean pingBinder() { return true; }
    public boolean isBinderAlive() { return true; }
    public IInterface queryLocalInterface(String descriptor) { return null; }
    public void dump(java.io.FileDescriptor fd, String[] args) {}
    public void dumpAsync(java.io.FileDescriptor fd, String[] args) {}
    public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        return onTransact(code, data, reply, flags);
    }
    public void linkToDeath(DeathRecipient recipient, int flags) {}
    public boolean unlinkToDeath(DeathRecipient recipient, int flags) { return true; }
}
