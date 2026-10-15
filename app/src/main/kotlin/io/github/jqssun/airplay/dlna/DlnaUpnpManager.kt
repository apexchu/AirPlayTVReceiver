package io.github.jqssun.airplay.dlna

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import io.github.jqssun.airplay.Prefs
import org.fourthline.cling.android.AndroidUpnpService
import org.fourthline.cling.android.AndroidUpnpServiceImpl
import org.fourthline.cling.binding.annotations.AnnotationLocalServiceBinder
import org.fourthline.cling.model.DefaultServiceManager
import org.fourthline.cling.model.meta.*
import org.fourthline.cling.model.types.UDADeviceType
import org.fourthline.cling.model.types.UDN
import org.fourthline.cling.support.connectionmanager.ConnectionManagerService
import java.util.UUID

class DlnaUpnpManager(
    private val context: Context,
    private val callback: CustomAVTransportService.DlnaPlaybackCallback
) {
    private var upnpService: AndroidUpnpService? = null
    private var localDevice: LocalDevice? = null
    private var isBound = false
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "AndroidUpnpService connected")
            upnpService = service as? AndroidUpnpService
            registerMediaRenderer()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "AndroidUpnpService disconnected")
            upnpService = null
        }
    }

    fun start() {
        if (isBound) return
        val intent = Intent(context, AndroidUpnpServiceImpl::class.java)
        isBound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        Log.i(TAG, "Binding to AndroidUpnpServiceImpl, bound=$isBound")
    }

    fun updateDeviceName(newName: String) {
        if (localDevice?.details?.friendlyName == newName) return
        unregisterMediaRenderer()
        registerMediaRenderer(newName)
    }

    private fun registerMediaRenderer(nameOverride: String? = null) {
        val service = upnpService ?: return
        try {
            val device = createMediaRendererDevice(nameOverride)
            localDevice = device
            service.registry.addDevice(device)
            Log.i(TAG, "Registered DLNA MediaRenderer: ${device.details.friendlyName} (UDN: ${device.identity.udn})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register DLNA MediaRenderer", e)
        }
    }

    private fun unregisterMediaRenderer() {
        localDevice?.let {
            try {
                upnpService?.registry?.removeDevice(it)
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

    fun stop() {
        unregisterMediaRenderer()
        if (isBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding AndroidUpnpServiceImpl", e)
            }
            isBound = false
        }
        upnpService = null
    }

    companion object {
        private const val TAG = "DlnaUpnpManager"
    }
}
