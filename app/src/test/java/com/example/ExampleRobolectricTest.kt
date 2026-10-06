package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioConfig
import com.example.audio.AudioDspManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("OpusVoice", appName)
    }

    @Test
    fun testAudioDspManagerPcmProcessing() {
        val dsp = AudioDspManager()
        val samples = ShortArray(AudioConfig.SAMPLES_PER_FRAME) { 0 }

        val silentResult = dsp.processPcmFrame(samples, samples.size)
        assertEquals(-80f, silentResult.dbLevel, 0.1f)
        assertEquals(false, silentResult.isVoiceActive)

        // Loud signal test
        val loudSamples = ShortArray(AudioConfig.SAMPLES_PER_FRAME) { (15000).toShort() }
        val loudResult = dsp.processPcmFrame(loudSamples, loudSamples.size)
        assertTrue(loudResult.dbLevel > -20f)
        assertTrue(loudResult.isVoiceActive)
    }
}
