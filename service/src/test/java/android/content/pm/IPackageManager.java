package android.content.pm;
public interface IPackageManager {
    public static abstract class Stub implements android.os.IBinder {
        public static IPackageManager asInterface(android.os.IBinder obj) { return null; }
    }
    String[] getPackagesForUid(int uid) throws android.os.RemoteException;
    PackageInfo getPackageInfo(String packageName, long flags, int userId) throws android.os.RemoteException;
    PackageInfo getPackageInfo(String packageName, int flags, int userId) throws android.os.RemoteException;
}
