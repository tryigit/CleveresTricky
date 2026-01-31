package android.hardware.security.rkp;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * RPC Hardware Info for RemotelyProvisionedComponent.
 * Contains information about the RKP implementation.
 */
public class RpcHardwareInfo implements Parcelable {
    public int versionNumber;
    public String rpcAuthorName;
    public int supportedEekCurve;
    public String uniqueId;
    public int supportedNumKeysInCsr;
    
    public RpcHardwareInfo() {}
    
    protected RpcHardwareInfo(Parcel in) {
        versionNumber = in.readInt();
        rpcAuthorName = in.readString();
        supportedEekCurve = in.readInt();
        uniqueId = in.readString();
        supportedNumKeysInCsr = in.readInt();
    }
    
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(versionNumber);
        dest.writeString(rpcAuthorName);
        dest.writeInt(supportedEekCurve);
        dest.writeString(uniqueId);
        dest.writeInt(supportedNumKeysInCsr);
    }
    
    @Override
    public int describeContents() {
        return 0;
    }
    
    public static final Creator<RpcHardwareInfo> CREATOR = new Creator<RpcHardwareInfo>() {
        @Override
        public RpcHardwareInfo createFromParcel(Parcel in) {
            return new RpcHardwareInfo(in);
        }
        
        @Override
        public RpcHardwareInfo[] newArray(int size) {
            return new RpcHardwareInfo[size];
        }
    };
}
