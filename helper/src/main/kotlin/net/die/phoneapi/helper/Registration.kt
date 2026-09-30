package net.die.phoneapi.helper

/** Strings for the content-provider handshake. The app's provider accepts the same names. */
object Registration {
    const val METHOD: String = "register"
    const val EXTRA_BINDER: String = "binder"
    const val EXTRA_APP_UID: String = "appUid"
    const val AUTHORITY_SUFFIX: String = ".helper"
}
