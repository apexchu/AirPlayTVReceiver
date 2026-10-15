package io.github.jqssun.airplay.dlna

import org.fourthline.cling.model.ModelUtil
import org.fourthline.cling.model.types.ErrorCode
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes
import org.fourthline.cling.support.avtransport.AVTransportException
import org.fourthline.cling.support.avtransport.AbstractAVTransportService
import org.fourthline.cling.support.model.*

class CustomAVTransportService(
    private val callback: DlnaPlaybackCallback
) : AbstractAVTransportService() {

    interface DlnaPlaybackCallback {
        fun onSetUri(uri: String, title: String?)
        fun onPlay()
        fun onPause()
        fun onStop()
        fun onSeek(positionMs: Long)
        fun onHeartbeat()
        fun getPositionInfo(): PositionInfo
        fun getMediaInfo(): MediaInfo
        fun getTransportInfo(): TransportInfo
    }

    override fun setAVTransportURI(
        instanceId: UnsignedIntegerFourBytes,
        currentURI: String,
        currentURIMetaData: String?
    ) {
        callback.onHeartbeat()
        val title = extractTitleFromMetadata(currentURIMetaData)
        callback.onSetUri(currentURI, title)
    }

    override fun setNextAVTransportURI(
        instanceId: UnsignedIntegerFourBytes,
        nextURI: String,
        nextURIMetaData: String?
    ) {
        // Optional for simple single stream playback
    }

    override fun setPlayMode(instanceId: UnsignedIntegerFourBytes, newPlayMode: String) {
        // NORMAL mode default
    }

    override fun setRecordQualityMode(instanceId: UnsignedIntegerFourBytes, newRecordQualityMode: String) {
        // Not supported
    }

    override fun record(instanceId: UnsignedIntegerFourBytes) {
        // Not supported on MediaRenderer
    }

    override fun play(instanceId: UnsignedIntegerFourBytes, speed: String?) {
        callback.onHeartbeat()
        callback.onPlay()
    }

    override fun pause(instanceId: UnsignedIntegerFourBytes) {
        callback.onHeartbeat()
        callback.onPause()
    }

    override fun stop(instanceId: UnsignedIntegerFourBytes) {
        callback.onHeartbeat()
        callback.onStop()
    }

    override fun seek(instanceId: UnsignedIntegerFourBytes, unit: String, target: String) {
        callback.onHeartbeat()
        when (unit) {
            "REL_TIME", "ABS_TIME" -> {
                val seconds = ModelUtil.fromTimeString(target)
                callback.onSeek(seconds * 1000L)
            }
            "TRACK_NR" -> {
                // track seek not applicable
            }
            else -> throw AVTransportException(ErrorCode.ACTION_FAILED, "Unsupported seek unit: $unit")
        }
    }

    override fun next(instanceId: UnsignedIntegerFourBytes) {
        callback.onHeartbeat()
    }

    override fun previous(instanceId: UnsignedIntegerFourBytes) {
        callback.onHeartbeat()
    }

    override fun getMediaInfo(instanceId: UnsignedIntegerFourBytes): MediaInfo {
        callback.onHeartbeat()
        return callback.getMediaInfo()
    }

    override fun getTransportInfo(instanceId: UnsignedIntegerFourBytes): TransportInfo {
        callback.onHeartbeat()
        return callback.getTransportInfo()
    }

    override fun getPositionInfo(instanceId: UnsignedIntegerFourBytes): PositionInfo {
        callback.onHeartbeat()
        return callback.getPositionInfo()
    }

    override fun getDeviceCapabilities(instanceId: UnsignedIntegerFourBytes): DeviceCapabilities {
        return DeviceCapabilities(arrayOf(StorageMedium.NETWORK))
    }

    override fun getTransportSettings(instanceId: UnsignedIntegerFourBytes): TransportSettings {
        return TransportSettings(PlayMode.NORMAL)
    }

    override fun getCurrentTransportActions(instanceId: UnsignedIntegerFourBytes): Array<TransportAction> {
        return arrayOf(
            TransportAction.Play,
            TransportAction.Stop,
            TransportAction.Pause,
            TransportAction.Seek
        )
    }

    override fun getCurrentInstanceIds(): Array<UnsignedIntegerFourBytes> {
        return arrayOf(UnsignedIntegerFourBytes(0))
    }

    private fun extractTitleFromMetadata(metadata: String?): String? {
        if (metadata.isNullOrBlank()) return null
        // Extract <dc:title>...</dc:title>
        val match = Regex("<dc:title>(.*?)</dc:title>", RegexOption.IGNORE_CASE).find(metadata)
        return match?.groupValues?.get(1)?.trim()
    }
}
