package io.github.jqssun.airplay.dlna

import org.fourthline.cling.android.AndroidUpnpServiceConfiguration
import org.fourthline.cling.android.AndroidUpnpServiceImpl
import org.fourthline.cling.model.types.ServiceType

class AirPlayUpnpService : AndroidUpnpServiceImpl() {
    override fun createConfiguration(): AndroidUpnpServiceConfiguration {
        return object : AndroidUpnpServiceConfiguration() {
            override fun getExclusiveServiceTypes(): Array<ServiceType>? {
                // As a MediaRenderer endpoint, ignore remote device discovery/advertisements
                // This prevents thread pool exhaustion and descriptor retrieval failures
                return null
            }
        }
    }
}
