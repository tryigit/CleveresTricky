package android.hardware.security.rkp;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * DeviceInfo for RemotelyProvisionedComponent.
 * Contains CBOR-encoded device information.
 */
public class DeviceInfo implements Parcelable {
    /** CBOR encoded device info map */
    public byte[] deviceInfo;
    
    public DeviceInfo() {}
    
    public DeviceInfo(byte[] deviceInfo) {
        this.deviceInfo = deviceInfo;
    }
    
    protected DeviceInfo(Parcel in) {
        deviceInfo = in.createByteArray();
    }
    
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByteArray(deviceInfo);
    }
    
    @Override
    public int describeContents() {
        return 0;
    }
    
    public static final Creator<DeviceInfo> CREATOR = new Creator<DeviceInfo>() {
        @Override
        public DeviceInfo createFromParcel(Parcel in) {
            return new DeviceInfo(in);
        }
        
        @Override
        public DeviceInfo[] newArray(int size) {
            return new DeviceInfo[size];
        }
    };
}
