package app.cash.paparazzi.sample

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

class HelloComposePseudoLocaleTest {
  @get:Rule
  val paparazzi = Paparazzi(
    deviceConfig = DeviceConfig.NEXUS_5.copy(pseudoLocalEnabled = true)
  )

  @Test
  fun compose() {
    paparazzi.snapshot { HelloPaparazzi() }
  }
}
