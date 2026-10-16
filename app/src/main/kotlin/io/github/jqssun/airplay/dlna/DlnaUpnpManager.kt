package io.github.jqssun.airplay.dlna

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.jqssun.airplay.Prefs
import org.fourthline.cling.UpnpService
import org.fourthline.cling.UpnpServiceImpl
import org.fourthline.cling.android.AndroidRouter
import org.fourthline.cling.android.AndroidUpnpServiceConfiguration
import org.fourthline.cling.binding.annotations.AnnotationLocalServiceBinder
import org.fourthline.cling.model.DefaultServiceManager
import org.fourthline.cling.model.meta.*
import org.fourthline.cling.model.types.ServiceType
import org.fourthline.cling.model.types.UDADeviceType
import org.fourthline.cling.model.types.UDN
import org.fourthline.cling.protocol.ProtocolFactory
import org.fourthline.cling.registry.Registry
import org.fourthline.cling.support.connectionmanager.ConnectionManagerService
import org.fourthline.cling.transport.Router
import org.fourthline.cling.transport.impl.jetty.JettyServletContainer
import java.util.UUID

class DlnaUpnpManager(
    private val context: Context,
    private val callback: CustomAVTransportService.DlnaPlaybackCallback
) {
    private var upnpService: UpnpService? = null
    val isRunning: Boolean
        get() = upnpService != null
    private var localDevice: LocalDevice? = null
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun start(nameOverride: String? = null) {
        if (upnpService != null) {
            nameOverride?.let { updateDeviceName(it) }
            return
        }
        Log.i(TAG, "Starting DLNA UPnP service...")
        resetJettyContainer()
        try {
            val configuration = object : AndroidUpnpServiceConfiguration() {
                override fun getExclusiveServiceTypes(): Array<ServiceType>? {
                    return null
                }
            }
            val service = object : UpnpServiceImpl(configuration) {
                override fun createRouter(protocolFactory: ProtocolFactory, registry: Registry): Router {
                    return AndroidRouter(configuration, protocolFactory, context)
                }
            }
            upnpService = service
            registerMediaRenderer(nameOverride)
            Log.i(TAG, "DLNA UPnP service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DLNA UPnP service", e)
        }
    }

    private inline fun <T> withPermitNetwork(block: () -> T): T {
        val oldPolicy = android.os.StrictMode.getThreadPolicy()
        return try {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder(oldPolicy).permitNetwork().build()
            )
            block()
        } finally {
            android.os.StrictMode.setThreadPolicy(oldPolicy)
        }
    }

    @Synchronized
    fun stop() {
        Log.i(TAG, "Stopping DLNA UPnP service...")
        unregisterMediaRenderer()
        try {
            withPermitNetwork {
                upnpService?.shutdown()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down UPnP service", e)
        }
        upnpService = null
        resetJettyContainer()
        Log.i(TAG, "DLNA UPnP service stopped")
    }

    @Synchronized
    fun updateDeviceName(newName: String) {
        if (localDevice?.details?.friendlyName == newName) return
        unregisterMediaRenderer()
        registerMediaRenderer(newName)
    }

    private fun resetJettyContainer() {
        try {
            JettyServletContainer.INSTANCE.stopIfRunning()
        } catch (e: Throwable) {
            Log.w(TAG, "Error stopping Jetty container", e)
        }
        try {
            val method = JettyServletContainer::class.java.getDeclaredMethod("resetServer")
            method.isAccessible = true
            method.invoke(JettyServletContainer.INSTANCE)
        } catch (e: Throwable) {
            Log.w(TAG, "Error resetting Jetty server", e)
        }
    }

    private fun registerMediaRenderer(nameOverride: String? = null) {
        val service = upnpService ?: return
        try {
            val device = createMediaRendererDevice(nameOverride)
            localDevice = device
            withPermitNetwork {
                service.registry.addDevice(device)
            }
            Log.i(TAG, "Registered DLNA MediaRenderer: ${device.details.friendlyName} (UDN: ${device.identity.udn})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register DLNA MediaRenderer", e)
        }
    }

    private fun unregisterMediaRenderer() {
        localDevice?.let {
            try {
                withPermitNetwork {
                    upnpService?.registry?.removeDevice(it)
                }
                Log.i(TAG, "Unregistered DLNA MediaRenderer: ${it.details.friendlyName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister DLNA MediaRenderer", e)
            }
        }
        localDevice = null
    }

    @Suppress("UNCHECKED_CAST")
    private fun createMediaRendererDevice(nameOverride: String? = null): LocalDevice {
        val friendlyName = nameOverride
            ?: prefs.getString(Prefs.SERVER_NAME, Prefs.DEF_SERVER_NAME)
            ?: Prefs.DEF_SERVER_NAME

        val udnString = prefs.getString("dlna_udn", null) ?: run {
            val newUdn = UUID.randomUUID().toString()
            prefs.edit().putString("dlna_udn", newUdn).apply()
            newUdn
        }
        val udn = UDN.valueOf(udnString)
        val deviceIdentity = DeviceIdentity(udn)
        val deviceType = UDADeviceType("MediaRenderer", 1)

        val deviceDetails = DeviceDetails(
            friendlyName,
            ManufacturerDetails("AirPlayTV", "https://github.com/jqssun/android-airplay-server"),
            ModelDetails("AirPlayTV-DLNA", "Android TV DLNA/UPnP MediaRenderer", "1.0"),
            "1.0",
            ""
        )

        val binder = AnnotationLocalServiceBinder()

        // 1. AVTransport
        val avTransportService: LocalService<CustomAVTransportService> =
            binder.read(CustomAVTransportService::class.java) as LocalService<CustomAVTransportService>
        val avTransportImpl = CustomAVTransportService(callback)
        avTransportService.manager = object : DefaultServiceManager<CustomAVTransportService>(avTransportService, CustomAVTransportService::class.java) {
            override fun createServiceInstance(): CustomAVTransportService = avTransportImpl
        }

        // 2. RenderingControl
        val renderingControlService: LocalService<CustomAudioRenderingControl> =
            binder.read(CustomAudioRenderingControl::class.java) as LocalService<CustomAudioRenderingControl>
        val renderingControlImpl = CustomAudioRenderingControl(context)
        renderingControlService.manager = object : DefaultServiceManager<CustomAudioRenderingControl>(renderingControlService, CustomAudioRenderingControl::class.java) {
            override fun createServiceInstance(): CustomAudioRenderingControl = renderingControlImpl
        }

        // 3. ConnectionManager
        val connectionManagerService: LocalService<ConnectionManagerService> =
            binder.read(ConnectionManagerService::class.java) as LocalService<ConnectionManagerService>
        connectionManagerService.manager = DefaultServiceManager(connectionManagerService, ConnectionManagerService::class.java)

        return LocalDevice(
            deviceIdentity,
            deviceType,
            deviceDetails,
            arrayOf(avTransportService, renderingControlService, connectionManagerService)
        )
    }

    companion object {
        private const val TAG = "DlnaUpnpManager"
    }
}
