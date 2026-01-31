package android.hardware.security.rkp;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * ProtectedData for RemotelyProvisionedComponent.
 * Contains COSE_Encrypt structure for protected data.
 */
public class ProtectedData implements Parcelable {
    /** COSE_Encrypt structure */
    public byte[] protectedData;
    
    public ProtectedData() {}
    
    public ProtectedData(byte[] protectedData) {
        this.protectedData = protectedData;
    }
    
    protected ProtectedData(Parcel in) {
        protectedData = in.createByteArray();
    }
    
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByteArray(protectedData);
    }
    
    @Override
    public int describeContents() {
        return 0;
    }
    
    public static final Creator<ProtectedData> CREATOR = new Creator<ProtectedData>() {
        @Override
        public ProtectedData createFromParcel(Parcel in) {
            return new ProtectedData(in);
        }
        
        @Override
        public ProtectedData[] newArray(int size) {
            return new ProtectedData[size];
        }
    };
}
