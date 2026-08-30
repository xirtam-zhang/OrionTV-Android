package com.orion.tv.player

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * [DefaultRenderersFactory] that routes all audio through [LoudnessNormalizingAudioProcessor].
 *
 * When normalisation is enabled the [DefaultAudioSink] is built *without* a Context on purpose:
 * that pins its capabilities to [AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES] (stereo 16-bit PCM,
 * no AC-3/E-AC-3 passthrough), so ExoPlayer always decodes multichannel audio itself and hands the
 * processor discrete channels to downmix — instead of shipping 5.1 PCM to the TV for it to fold
 * down badly. When disabled we defer to the stock sink so real surround / passthrough setups are
 * left untouched.
 */
class OrionRenderersFactory(
    context: Context,
    private val audioNormalizationEnabled: Boolean,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        if (!audioNormalizationEnabled) {
            return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
        }
        @Suppress("DEPRECATION") // no-Context Builder is intentional — see class doc
        return DefaultAudioSink.Builder()
            .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(
                arrayOf<AudioProcessor>(LoudnessNormalizingAudioProcessor(enabled = true)),
            )
            .build()
    }
}
