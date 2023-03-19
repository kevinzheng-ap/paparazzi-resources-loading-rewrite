package app.cash.paparazzi.internal

import com.android.ide.common.rendering.api.ArrayResourceValue
import com.android.ide.common.rendering.api.AttrResourceValue
import com.android.ide.common.rendering.api.AttributeFormat
import com.android.ide.common.rendering.api.PluralsResourceValue
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceValue
import com.android.ide.common.rendering.api.StyleResourceValue
import com.android.resources.ResourceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class PaparazziResourceRepositoryTest {

  @Test
  fun colorsXmlTest() {
    val repository = PaparazziResourceRepository(
      listOf("src/test/resources/values/colors.xml"),
      ResourceNamespace.TODO()
    )
    val map = repository.allResources
    assertThat(map[0].name).isEqualTo("test_color_2")
    assertThat(map[0].resourceValue.value).isEqualTo("#00000000")
    assertThat(map[1].name).isEqualTo("test_color")
    assertThat(map[1].resourceValue.value).isEqualTo("#ffffffff")
  }

  @Test
  fun boolsXmlTest() {
    val repository = PaparazziResourceRepository(
      listOf("src/test/resources/values/bools.xml"),
      ResourceNamespace.TODO()
    )
    val map = repository.allResources
    assertThat(map[0].name).isEqualTo("screen_small")
    assertThat(map[0].resourceValue.value).isEqualTo("true")
    assertThat(map[1].name).isEqualTo("adjust_view_bounds")
    assertThat(map[1].resourceValue.value).isEqualTo("false")
  }

  @Test
  fun dimensXmlTest() {
    val repository = PaparazziResourceRepository(
      listOf("src/test/resources/values/dimens.xml"),
      ResourceNamespace.TODO()
    )
    val map = repository.allResources
    assertThat(map[0].name).isEqualTo("textview_height")
    assertThat(map[0].resourceValue.value).isEqualTo("25dp")
    assertThat(map[1].name).isEqualTo("textview_width")
    assertThat(map[1].resourceValue.value).isEqualTo("150dp")
  }

  @Test
  fun idsXmlTest() {
    val repository = PaparazziResourceRepository(
      listOf("src/test/resources/values/ids.xml"),
      ResourceNamespace.TODO()
    )
    val map = repository.allResources
    assertThat(map[0].name).isEqualTo("dialog_exit")
    assertThat(map[0].type).isEqualTo(ResourceType.ID)
    assertThat(map[1].name).isEqualTo("button_ok")
    assertThat(map[1].type).isEqualTo(ResourceType.ID)
  }

  @Test
  fun integersXmlTest() {
    val repository = PaparazziResourceRepository(
      listOf("src/test/resources/values/integers.xml"),
      ResourceNamespace.TODO()
    )
    val map = repository.allResources
    assertThat(map[0].name).isEqualTo("max_speed")
    assertThat(map[0].resourceValue.value).isEqualTo("75")
    assertThat(map[1].name).isEqualTo("min_speed")
    assertThat(map[1].resourceValue.value).isEqualTo("5")
  }

  @Test
  fun stringsXmlTest() {
    val repository = PaparazziResourceRepository(
      listOf("src/test/resources/values/strings.xml"),
      ResourceNamespace.TODO()
    )
    val map = repository.allResources
    val array = map[0].resourceValue as ArrayResourceValue
    val plurals = map[1].resourceValue as PluralsResourceValue
    val string = map[2].resourceValue

    val firstItemInArray = array.getElement(0)
    val secondItemInArray = array.getElement(1)

    assertThat(array.name).isEqualTo("string_array_name")
    assertThat(plurals.name).isEqualTo("plural_name")
    assertThat(string.name).isEqualTo("string_name")

    assertThat(firstItemInArray).isEqualTo("First Test String")
    assertThat(secondItemInArray).isEqualTo("Second Test String")

    assertThat(plurals.getQuantity(0)).isEqualTo("zero")
    assertThat(plurals.getValue(0)).isEqualTo("Nothing")
    assertThat(plurals.getQuantity(1)).isEqualTo("one")
    assertThat(plurals.getValue(1)).isEqualTo("One String")

    assertThat(string.value).isEqualTo("Test String")
  }

  @Test
  fun styleXmlTest() {
    val repository = PaparazziResourceRepository(
      listOf("src/test/resources/values/style.xml"),
      ResourceNamespace.TODO()
    )
    val map = repository.allResources
    val name = map[0].name
    val value = map[0].resourceValue as StyleResourceValue
    val firstItem = value.definedItems.elementAt(0)
    val secondItem = value.definedItems.elementAt(1)

    assertThat(name).isEqualTo("TestStyle")
    assertThat(firstItem.attrName).isEqualTo("android:scrollbars")
    assertThat(firstItem.value).isEqualTo("horizontal")
    assertThat(secondItem.attrName).isEqualTo("android:marginTop")
    assertThat(secondItem.value).isEqualTo("16dp")
  }

  @Test
  fun attrsXmlTest() {
    val repository = PaparazziResourceRepository(
      listOf("src/test/resources/values/attrs.xml"),
      ResourceNamespace.TODO()
    )
    val map = repository.allResources
    val firstItem = map[0].resourceValue as AttrResourceValue
    val secondItem = map[1].resourceValue as AttrResourceValue
    val styleable = map[2]
    assertThat(styleable.name).isEqualTo("test_styleable")
    assertThat(firstItem.name).isEqualTo("TestAttrInt")
    assertThat(secondItem.name).isEqualTo("TestAttr")
    assertThat(firstItem.formats).isEqualTo(setOf(AttributeFormat.INTEGER))
    assertThat(secondItem.formats).isEqualTo(setOf(AttributeFormat.FLOAT))
  }

  @Test
  fun layoutXmlTest() {
    val repository = PaparazziResourceRepository(
      listOf("src/test/resources/layout/test.xml"),
      ResourceNamespace.TODO()
    )
    val map = repository.allResources
    val firstId = map[0].resourceValue as ResourceValue
    val secondId = map[1].resourceValue as ResourceValue
    assertThat(firstId.name).isEqualTo("test_view")
    assertThat(secondId.name).isEqualTo("test_layout")
  }
}
