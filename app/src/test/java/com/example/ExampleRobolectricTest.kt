package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.HardwareProfileEntity
import com.example.hardware.BeztonCommand
import com.example.hardware.BeztonIrRemote
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
  fun `read app name from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Sona Music", appName)
  }

  @Test
  fun `bezton nec32 encoder produces valid 67-element microsecond frame`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val remote = BeztonIrRemote(context)
    val pattern = remote.encodeNec32Pattern(BeztonCommand.POWER.necHexCode)
    assertEquals(67, pattern.size)
    assertEquals(9000, pattern[0])
    assertEquals(4500, pattern[1])
    assertEquals(562, pattern[66])
  }

  @Test
  fun `hardware profile eq bands parse cleanly`() {
    val usbProfile = HardwareProfileEntity.defaultFor("USB_DAC")
    assertEquals(10, usbProfile.eqBandsList().size)
    assertTrue(usbProfile.bitPerfectEnabled)
  }
}
