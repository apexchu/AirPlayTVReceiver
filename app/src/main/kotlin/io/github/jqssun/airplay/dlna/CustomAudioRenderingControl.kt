package io.github.jqssun.airplay.dlna

import android.content.Context
import android.media.AudioManager
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes
import org.fourthline.cling.model.types.UnsignedIntegerTwoBytes
import org.fourthline.cling.support.model.Channel
import org.fourthline.cling.support.renderingcontrol.AbstractAudioRenderingControl
import kotlin.math.roundToInt

class CustomAudioRenderingControl(
    private val context: Context
) : AbstractAudioRenderingControl() {

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun setVolume(
        instanceId: UnsignedIntegerFourBytes,
        channelName: String,
        desiredVolume: UnsignedIntegerTwoBytes
    ) {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        // desiredVolume is 0..100
        val target = ((desiredVolume.value.toDouble() / 100.0) * maxVolume).roundToInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
    }

    override fun getVolume(
        instanceId: UnsignedIntegerFourBytes,
        channelName: String
    ): UnsignedIntegerTwoBytes {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val percent = if (maxVolume > 0) ((current.toDouble() / maxVolume) * 100.0).roundToInt() else 0
        return UnsignedIntegerTwoBytes(percent.toLong())
    }

    override fun setMute(
        instanceId: UnsignedIntegerFourBytes,
        channelName: String,
        desiredMute: Boolean
    ) {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (desiredMute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            AudioManager.FLAG_SHOW_UI
        )
    }

    override fun getMute(
        instanceId: UnsignedIntegerFourBytes,
        channelName: String
    ): Boolean {
        return audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
    }

    override fun getCurrentChannels(): Array<Channel> {
        return arrayOf(Channel.Master)
    }

    override fun getCurrentInstanceIds(): Array<UnsignedIntegerFourBytes> {
        return arrayOf(UnsignedIntegerFourBytes(0))
    }
}
