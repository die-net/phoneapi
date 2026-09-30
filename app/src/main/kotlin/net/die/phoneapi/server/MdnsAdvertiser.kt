package net.die.phoneapi.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/** Advertises the server as a generic `_https._tcp` service so clients can follow IP changes. */
class MdnsAdvertiser(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun advertise(name: String, port: Int, instanceId: String) {
        stop()
        val info =
            NsdServiceInfo().apply {
                serviceName = name
                serviceType = SERVICE_TYPE
                setPort(port)
                setAttribute("id", instanceId)
            }
        val l =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.i(TAG, "mDNS registered ${info.serviceName}")
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS registration failed: $errorCode")
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        listener = l
    }

    @Synchronized
    fun stop() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }

    private companion object {
        const val SERVICE_TYPE = "_https._tcp"
        const val TAG = "PhoneApiMdns"
    }
}
