package android.hardware.security.rkp;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;

/**
 * Stub interface for IRemotelyProvisionedComponent HAL.
 * Used for intercepting RKP transactions to achieve STRONG integrity.
 */
public interface IRemotelyProvisionedComponent extends IInterface {
    String DESCRIPTOR = "android.hardware.security.keymint.IRemotelyProvisionedComponent";
    
    int TRANSACTION_getHardwareInfo = IBinder.FIRST_CALL_TRANSACTION + 0;
    int TRANSACTION_generateEcdsaP256KeyPair = IBinder.FIRST_CALL_TRANSACTION + 1;
    int TRANSACTION_generateCertificateRequest = IBinder.FIRST_CALL_TRANSACTION + 2;
    int TRANSACTION_generateCertificateRequestV2 = IBinder.FIRST_CALL_TRANSACTION + 3;
    
    RpcHardwareInfo getHardwareInfo();
    
    MacedPublicKey generateEcdsaP256KeyPair(boolean testMode, byte[] privateKeyHandle);
    
    byte[] generateCertificateRequest(
            boolean testMode,
            MacedPublicKey[] keysToSign,
            byte[] endpointEncryptionCertChain,
            byte[] challenge,
            DeviceInfo deviceInfo,
            ProtectedData protectedData
    );
    
    byte[] generateCertificateRequestV2(MacedPublicKey[] keysToSign, byte[] challenge);
    
    abstract class Stub extends android.os.Binder implements IRemotelyProvisionedComponent {
        
        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }
        
        public static IRemotelyProvisionedComponent asInterface(IBinder obj) {
            if (obj == null) return null;
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IRemotelyProvisionedComponent) {
                return (IRemotelyProvisionedComponent) iin;
            }
            return new Proxy(obj);
        }
        
        @Override
        public IBinder asBinder() {
            return this;
        }
        
        private static class Proxy implements IRemotelyProvisionedComponent {
            private final IBinder mRemote;
            
            Proxy(IBinder remote) {
                mRemote = remote;
            }
            
            @Override
            public IBinder asBinder() {
                return mRemote;
            }
            
            @Override
            public RpcHardwareInfo getHardwareInfo() {
                return null;
            }
            
            @Override
            public MacedPublicKey generateEcdsaP256KeyPair(boolean testMode, byte[] privateKeyHandle) {
                return null;
            }
            
            @Override
            public byte[] generateCertificateRequest(
                    boolean testMode,
                    MacedPublicKey[] keysToSign,
                    byte[] endpointEncryptionCertChain,
                    byte[] challenge,
                    DeviceInfo deviceInfo,
                    ProtectedData protectedData
            ) {
                return null;
            }
            
            @Override
            public byte[] generateCertificateRequestV2(MacedPublicKey[] keysToSign, byte[] challenge) {
                return null;
            }
        }
    }
}
