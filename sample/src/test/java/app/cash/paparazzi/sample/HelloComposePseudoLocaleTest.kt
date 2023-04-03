package app.cash.paparazzi.sample

import androidx.compose.ui.res.stringResource
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.sample.R.string
import org.junit.Rule
import org.junit.Test

class HelloComposePseudoLocaleTest {
  @get:Rule
  val paparazzi = Paparazzi(
    deviceConfig = DeviceConfig.NEXUS_5.copy(pseudoLocalEnabled = true)
  )

  @Test
  fun hello() {
    paparazzi.snapshot { HelloPaparazzi(stringResource(id = string.hello)) }
  }

  @Test
  fun helloNotTranslatable() {
    paparazzi.snapshot { HelloPaparazzi(stringResource(id = string.hello_not_translatable)) }
  }
}
