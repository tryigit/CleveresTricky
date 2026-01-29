package cleveres.tricky.cleverestech

import android.os.Binder
import android.os.Parcel
import cleveres.tricky.cleverestech.Config

class PropertyHiderService : Binder() {
    companion object {
        // Must match the C++ side: IBinder.FIRST_CALL_TRANSACTION + 0
        const val GET_SPOOFED_PROPERTY_TRANSACTION_CODE = FIRST_CALL_TRANSACTION + 0
        // Must match the C++ side
        const val INTERFACE_TOKEN = "android.os.IPropertyServiceHider"
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == GET_SPOOFED_PROPERTY_TRANSACTION_CODE) {
            data.enforceInterface(INTERFACE_TOKEN) // Verify the token
            val propertyName = data.readString()
            reply?.writeNoException() // Important: write no exception before writing result

            if (propertyName != null) {
                // Use getBuildVar as it holds the loaded properties from spoof_build_vars
                val spoofedValue = Config.getBuildVar(propertyName)
                Logger.d("PropertyHiderService: Received request for '$propertyName', spoofed to '$spoofedValue'")
                reply?.writeString(spoofedValue) // writeString can handle null
            } else {
                Logger.d("PropertyHiderService: Received request with null property name")
                reply?.writeString(null) // Property name was null
            }
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }
}
