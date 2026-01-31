package android.hardware.security.rkp;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * MacedPublicKey for RemotelyProvisionedComponent.
 * Contains a COSE_Mac0 structure wrapping a public key.
 */
public class MacedPublicKey implements Parcelable {
    /** COSE_Mac0 encoded public key */
    public byte[] macedKey;
    
    public MacedPublicKey() {}
    
    public MacedPublicKey(byte[] macedKey) {
        this.macedKey = macedKey;
    }
    
    protected MacedPublicKey(Parcel in) {
        macedKey = in.createByteArray();
    }
    
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByteArray(macedKey);
    }
    
    @Override
    public int describeContents() {
        return 0;
    }
    
    public static final Creator<MacedPublicKey> CREATOR = new Creator<MacedPublicKey>() {
        @Override
        public MacedPublicKey createFromParcel(Parcel in) {
            return new MacedPublicKey(in);
        }
        
        @Override
        public MacedPublicKey[] newArray(int size) {
            return new MacedPublicKey[size];
        }
    };
}
