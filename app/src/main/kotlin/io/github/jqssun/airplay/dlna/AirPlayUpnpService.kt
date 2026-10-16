package io.github.jqssun.airplay.dlna

import org.fourthline.cling.android.AndroidUpnpServiceConfiguration
import org.fourthline.cling.android.AndroidUpnpServiceImpl
import org.fourthline.cling.transport.impl.StreamClientConfigurationImpl
import org.fourthline.cling.transport.impl.StreamClientImpl
import org.fourthline.cling.transport.spi.StreamClient

class AirPlayUpnpService : AndroidUpnpServiceImpl() {
    override fun createConfiguration(): AndroidUpnpServiceConfiguration {
        return object : AndroidUpnpServiceConfiguration() {
            override fun createStreamClient(): StreamClient<*> {
                return StreamClientImpl(
                    StreamClientConfigurationImpl(syncProtocolExecutorService)
                )
            }
        }
    }
}
