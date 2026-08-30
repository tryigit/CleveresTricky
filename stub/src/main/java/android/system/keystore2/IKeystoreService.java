package android.system.keystore2;

import android.os.IBinder;

public interface IKeystoreService {
    String DESCRIPTOR = "android.system.keystore2.IKeystoreService";

    IKeystoreSecurityLevel getSecurityLevel(int securityLevel);

    class Stub {
        public static final int TRANSACTION_getSecurityLevel = 1;
        public static final int TRANSACTION_getKeyEntry = 2;

        public static IKeystoreService asInterface(IBinder b) {
            throw new RuntimeException("");
        }
    }
}
