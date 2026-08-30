package com.orion.tv.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Fixes the "bass is deafening, dialogue is a whisper" problem on movie/TV sources that ship a 5.1
 * track. The app otherwise hands multichannel PCM straight to the TV, which folds it down to stereo
 * badly: the centre channel (i.e. dialogue) ends up buried and the LFE (.1) channel is mixed in at
 * full level. YouTube is unaffected only because it is plain stereo — there is no downmix step.
 *
 * This processor runs on the ExoPlayer audio thread and does two things, in order:
 *
 *  1. **Dialogue-forward downmix** (>2ch -> stereo): the centre channel is mixed in at full level,
 *     surrounds are attenuated, and the LFE channel is dropped entirely — TV speakers can't
 *     reproduce sub-bass anyway, and folding it in is exactly what makes explosions/music boom over
 *     speech.
 *  2. **Loudness normalisation**: a streaming auto-gain plus soft limiter that pulls quiet passages
 *     up towards a target level and reins loud transients in, so different videos (and quiet vs.
 *     loud scenes within one) play back at a consistent volume. Deliberately gentle — slow to boost
 *     (~0.6s), quick to duck (~10ms) — to avoid audible pumping.
 *
 * Inactive (bytes pass straight through) when disabled in Settings, or for anything that isn't
 * 16-bit PCM with 1–8 channels. 16-bit is guaranteed here because [OrionRenderersFactory] leaves
 * float output off, so the sink's ToInt16 processor always runs first.
 */
class LoudnessNormalizingAudioProcessor(
    private val enabled: Boolean,
) : BaseAudioProcessor() {

    private var inChannels = 0
    private var outChannels = 0

    // Loudness-normaliser state / coefficients (recomputed per configuration in onFlush()).
    private var msEnv = 0.0        // running mean-square of the downmixed signal
    private var gain = 1f          // current smoothed gain applied to samples
    private var msCoef = 0.0
    private var attackCoef = 0f
    private var releaseCoef = 0f

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (!enabled ||
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            inputAudioFormat.channelCount !in 1..MAX_SUPPORTED_CHANNELS
        ) {
            return AudioProcessor.AudioFormat.NOT_SET // inactive: leave the stream alone
        }
        val outCount = inputAudioFormat.channelCount.coerceAtMost(2)
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, outCount, C.ENCODING_PCM_16BIT)
    }

    override fun onFlush() {
        inChannels = inputAudioFormat.channelCount
        outChannels = outputAudioFormat.channelCount
        val sr = inputAudioFormat.sampleRate.toDouble()
        msCoef = 1.0 - exp(-1.0 / (sr * RMS_WINDOW_SEC))
        attackCoef = exp(-1.0 / (sr * ATTACK_SEC)).toFloat()
        releaseCoef = exp(-1.0 / (sr * RELEASE_SEC)).toFloat()
        msEnv = 0.0
        gain = 1f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val limit = inputBuffer.limit()
        val frameCount = (limit - inputBuffer.position()) / (BYTES_PER_SAMPLE * inChannels)
        if (frameCount == 0) return

        val output = replaceOutputBuffer(frameCount * outChannels * BYTES_PER_SAMPLE)
        val ch = inChannels
        val samples = FloatArray(ch)
        val multichannel = ch > 2
        val premix = if (multichannel) MULTICHANNEL_PREMIX else 1f

        repeat(frameCount) {
            for (c in 0 until ch) samples[c] = inputBuffer.getShort().toFloat() / PCM_FULL_SCALE

            val l: Float
            val r: Float
            if (multichannel) {
                // Canonical Android interleave order: FL, FR, FC, LFE, BL, BR, SL, SR.
                val centre = samples[2] * CENTRE_MIX
                var left: Float
                var right: Float
                when (ch) {
                    3 -> { left = samples[0] + centre; right = samples[1] + centre }
                    4 -> { // quad: FL, FR, BL, BR
                        left = samples[0] + samples[2] * SURROUND_MIX
                        right = samples[1] + samples[3] * SURROUND_MIX
                    }
                    5 -> { // FL, FR, FC, BL, BR
                        left = samples[0] + centre + samples[3] * SURROUND_MIX
                        right = samples[1] + centre + samples[4] * SURROUND_MIX
                    }
                    6 -> { // 5.1
                        left = samples[0] + centre + samples[4] * SURROUND_MIX
                        right = samples[1] + centre + samples[5] * SURROUND_MIX
                    }
                    7 -> { // 6.1: FL, FR, FC, LFE, BC, SL, SR
                        left = samples[0] + centre + (samples[4] * 0.7f + samples[5]) * SURROUND_MIX
                        right = samples[1] + centre + (samples[4] * 0.7f + samples[6]) * SURROUND_MIX
                    }
                    else -> { // 7.1: FL, FR, FC, LFE, BL, BR, SL, SR
                        left = samples[0] + centre + (samples[4] + samples[6]) * SURROUND_MIX
                        right = samples[1] + centre + (samples[5] + samples[7]) * SURROUND_MIX
                    }
                }
                l = left * premix
                r = right * premix
            } else if (ch == 2) {
                l = samples[0]; r = samples[1]
            } else {
                l = samples[0]; r = samples[0]
            }

            // --- loudness normalisation ---
            val mono = 0.5f * (l + r)
            msEnv += (mono.toDouble() * mono - msEnv) * msCoef
            val rms = sqrt(msEnv).toFloat()
            val desired = if (rms < GATE_RMS) {
                gain // freeze the gain over silence instead of winding it up to the ceiling
            } else {
                (TARGET_RMS / max(rms, NOISE_FLOOR)).coerceIn(MIN_GAIN, MAX_GAIN)
            }
            val coef = if (desired < gain) attackCoef else releaseCoef
            gain = desired + (gain - desired) * coef

            if (outChannels == 1) {
                output.putShort(toPcm16(mono * gain))
            } else {
                output.putShort(toPcm16(l * gain))
                output.putShort(toPcm16(r * gain))
            }
        }

        inputBuffer.position(limit)
        output.flip()
    }

    private fun toPcm16(value: Float): Short {
        val scaled = softClip(value) * PCM_MAX_POSITIVE
        return when {
            scaled >= PCM_MAX_POSITIVE -> Short.MAX_VALUE
            scaled <= PCM_MIN_NEGATIVE -> Short.MIN_VALUE
            else -> scaled.roundToInt().toShort()
        }
    }

    /** Linear below the ceiling, tanh knee above it — cheap, and only exercised on peaks. */
    private fun softClip(x: Float): Float {
        val ceil = LIMIT_CEILING
        val headroom = 1f - ceil
        return when {
            x > ceil -> ceil + headroom * tanh(((x - ceil) / headroom).toDouble()).toFloat()
            x < -ceil -> -ceil - headroom * tanh(((-x - ceil) / headroom).toDouble()).toFloat()
            else -> x
        }
    }

    private companion object {
        const val MAX_SUPPORTED_CHANNELS = 8
        const val BYTES_PER_SAMPLE = 2
        const val PCM_FULL_SCALE = 32768f
        const val PCM_MAX_POSITIVE = 32767f
        const val PCM_MIN_NEGATIVE = -32768f

        // Downmix coefficients. Tune here if dialogue is still low / bass still hot.
        const val CENTRE_MIX = 1.0f          // dialogue at full level (cinema downmix uses ~0.71)
        const val SURROUND_MIX = 0.6f
        const val MULTICHANNEL_PREMIX = 0.5f // headroom before the normaliser; limiter mops up peaks

        // Loudness normaliser.
        const val TARGET_RMS = 0.10f         // ~ -20 dBFS RMS
        const val NOISE_FLOOR = 0.001f
        const val GATE_RMS = 0.003f          // below this, hold the gain (don't amplify near-silence)
        const val MIN_GAIN = 0.30f           // ~ -10 dB
        const val MAX_GAIN = 4.0f            // ~ +12 dB
        const val RMS_WINDOW_SEC = 0.3
        const val ATTACK_SEC = 0.010         // quick to duck loud passages
        const val RELEASE_SEC = 0.6          // slow to lift quiet ones
        const val LIMIT_CEILING = 0.98f
    }
}
