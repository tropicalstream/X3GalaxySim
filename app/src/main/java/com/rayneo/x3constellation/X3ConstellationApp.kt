package com.rayneo.x3constellation

import android.app.Application
import com.ffalcon.mercury.android.sdk.MercurySDK

class X3ConstellationApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MercurySDK.init(this)
    }
}
