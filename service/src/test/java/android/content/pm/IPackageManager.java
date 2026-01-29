package android.content.pm;
public interface IPackageManager {
    public static abstract class Stub implements android.os.IBinder {
        public static IPackageManager asInterface(android.os.IBinder obj) { return null; }
    }
    // Add methods used by Config if any, but currently getPackagesForUid is used by getPm() -> used by Config.
    String[] getPackagesForUid(int uid) throws android.os.RemoteException;
}
