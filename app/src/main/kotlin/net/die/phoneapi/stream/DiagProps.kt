package net.die.phoneapi.stream

import android.util.Log

/** Diagnostics switches, set with `adb shell setprop debug.phoneapi.<name> <value>`. */
internal object DiagProps {
    /** `debug.phoneapi.relay=0` mirrors straight onto the encoder, as v0.2.3 did. */
    fun relayEnabled(): Boolean = prop("debug.phoneapi.relay") != "0"

    private fun prop(name: String): String =
        try {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, name, "") as String
        } catch (e: ReflectiveOperationException) {
            Log.w("PhoneApiStream", "diag: could not read $name", e)
            ""
        }
}
