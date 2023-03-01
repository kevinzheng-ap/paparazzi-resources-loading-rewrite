package app.cash.paparazzi

import com.android.utils.XmlUtils
import org.junit.Test
import org.w3c.dom.Element
import java.io.File

class XmlUtilsTest {

  // Not work in Unit tests
  @Test
  fun testXml() {
    val file = File("Some file")
    val reader = XmlUtils.getUtfReader(file)
    val xml = reader.readLines()
    System.out.println(xml)
    val document = XmlUtils.createDocument( false)
    document.createAttribute("test")
    System.out.println(document.toString())

    assert(true)
  }
}
