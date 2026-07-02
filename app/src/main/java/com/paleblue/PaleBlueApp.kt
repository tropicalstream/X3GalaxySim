package com.paleblue

/**
 * Application entry: initializes the RayNeo Mercury SDK once, defensively.
 * If the AARs are absent (desktop/emulator build) the app still runs flat.
 */
class PaleBlueApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            // com.ffalcon.mercury.android.sdk
            val clazz = Class.forName("com.ffalcon.mercury.android.sdk.MercurySDK")
            clazz.getMethod("init", android.content.Context::class.java).invoke(null, this)
        }
    }
}
