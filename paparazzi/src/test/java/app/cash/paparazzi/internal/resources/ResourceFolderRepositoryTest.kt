package app.cash.paparazzi.internal.resources

import app.cash.paparazzi.internal.resources.base.BasicTextValueResourceItem
import com.android.ide.common.rendering.api.ArrayResourceValue
import com.android.ide.common.rendering.api.AttrResourceValue
import com.android.ide.common.rendering.api.AttributeFormat
import com.android.ide.common.rendering.api.PluralsResourceValue
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.StyleResourceValue
import com.android.ide.common.rendering.api.StyleableResourceValue
import com.android.resources.ResourceType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResourceFolderRepositoryTest {
  private val resFolderRoot = resolveProjectPath("src/test/resources/folders/res")

  @Test
  fun test() {
    val repository = ResourceFolderRepository(
      resFolderRoot.toFile(),
      ResourceNamespace.TODO()
    )

    val map = repository.allResources
    assertThat(map.size).isEqualTo(49)

    // ANIM
    assertThat(map[0].name).isEqualTo("slide_in_from_left")
    assertThat(map[0].type).isEqualTo(ResourceType.ANIM)

    // ANIMATOR
    assertThat(map[1].name).isEqualTo("test_animator")
    assertThat(map[1].type).isEqualTo(ResourceType.ANIMATOR)

    // ARRAY
    assertThat(map[2].name).isEqualTo("string_array_name_donottranslate")
    assertThat(map[2].type).isEqualTo(ResourceType.ARRAY)
    with(map[2].resourceValue as ArrayResourceValue) {
      assertThat(elementCount).isEqualTo(2)
      assertThat(getElement(0)).isEqualTo("First Test String donottranslate")
      assertThat(getElement(1)).isEqualTo("Second Test String donottranslate")
    }
    assertThat(map[3].name).isEqualTo("string_array_name")
    assertThat(map[3].type).isEqualTo(ResourceType.ARRAY)
    with(map[3].resourceValue as ArrayResourceValue) {
      assertThat(elementCount).isEqualTo(2)
      assertThat(getElement(0)).isEqualTo("First Test String")
      assertThat(getElement(1)).isEqualTo("Second Test String")
    }
    assertThat(map[4].name).isEqualTo("string_array_name_translatable_false")
    assertThat(map[4].type).isEqualTo(ResourceType.ARRAY)
    with(map[4].resourceValue as ArrayResourceValue) {
      assertThat(elementCount).isEqualTo(2)
      assertThat(getElement(0)).isEqualTo("First Test String translatable false")
      assertThat(getElement(1)).isEqualTo("Second Test String translatable false")
    }
    assertThat(map[5].name).isEqualTo("string_array_name")
    assertThat(map[5].type).isEqualTo(ResourceType.ARRAY)
    with(map[5].resourceValue as ArrayResourceValue) {
      assertThat(elementCount).isEqualTo(2)
      assertThat(getElement(0)).isEqualTo("[Fîŕšţ Ţéšţ Šţŕîñĝ one two three]")
      assertThat(getElement(1)).isEqualTo("[Šéçöñð Ţéšţ Šţŕîñĝ one two three]")
    }
    assertThat(map[6].name).isEqualTo("string_array_name")
    assertThat(map[6].type).isEqualTo(ResourceType.ARRAY)
    with(map[6].resourceValue as ArrayResourceValue) {
      assertThat(elementCount).isEqualTo(2)
      assertThat(getElement(0)).isEqualTo("${bidiWordStart}First$bidiWordEnd ${bidiWordStart}Test$bidiWordEnd ${bidiWordStart}String$bidiWordEnd")
      assertThat(getElement(1)).isEqualTo("${bidiWordStart}Second$bidiWordEnd ${bidiWordStart}Test$bidiWordEnd ${bidiWordStart}String$bidiWordEnd")
    }

    // ATTR
    assertThat(map[7].name).isEqualTo("TestAttr")
    assertThat(map[7].type).isEqualTo(ResourceType.ATTR)
    assertThat((map[7].resourceValue as AttrResourceValue).formats).isEqualTo(setOf(AttributeFormat.FLOAT))
    assertThat(map[8].name).isEqualTo("TestAttrInt")
    assertThat(map[8].type).isEqualTo(ResourceType.ATTR)
    assertThat((map[8].resourceValue as AttrResourceValue).formats).isEqualTo(setOf(AttributeFormat.INTEGER))

    // BOOL
    assertThat(map[9].name).isEqualTo("screen_small")
    assertThat(map[9].type).isEqualTo(ResourceType.BOOL)
    assertThat(map[9].resourceValue.value).isEqualTo(true.toString())
    assertThat(map[10].name).isEqualTo("adjust_view_bounds")
    assertThat(map[10].type).isEqualTo(ResourceType.BOOL)
    assertThat(map[10].resourceValue.value).isEqualTo(false.toString())

    // COLOR
    assertThat(map[11].name).isEqualTo("test_color")
    assertThat(map[11].type).isEqualTo(ResourceType.COLOR)
    assertThat(map[11].resourceValue.value).isEqualTo("#ffffffff")
    assertThat(map[12].name).isEqualTo("test_color_2")
    assertThat(map[12].type).isEqualTo(ResourceType.COLOR)
    assertThat(map[12].resourceValue.value).isEqualTo("#00000000")
    assertThat(map[13].name).isEqualTo("color_selector")
    assertThat(map[13].type).isEqualTo(ResourceType.COLOR)

    // DIMEN
    assertThat(map[14].name).isEqualTo("textview_height")
    assertThat(map[14].type).isEqualTo(ResourceType.DIMEN)
    assertThat(map[14].resourceValue.value).isEqualTo("25dp")
    assertThat(map[15].name).isEqualTo("textview_width")
    assertThat(map[15].type).isEqualTo(ResourceType.DIMEN)
    assertThat(map[15].resourceValue.value).isEqualTo("150dp")

    // DRAWABLE
    assertThat(map[16].name).isEqualTo("ic_android_black_24dp")
    assertThat(map[16].type).isEqualTo(ResourceType.DRAWABLE)

    // FONT
    assertThat(map[17].name).isEqualTo("aclonica")
    assertThat(map[17].type).isEqualTo(ResourceType.FONT)

    // ID
    assertThat(map[18].name).isEqualTo("test_layout")
    assertThat(map[18].type).isEqualTo(ResourceType.ID)
    assertThat(map[19].name).isEqualTo("test_view")
    assertThat(map[19].type).isEqualTo(ResourceType.ID)
    assertThat(map[20].name).isEqualTo("test_menu_1")
    assertThat(map[20].type).isEqualTo(ResourceType.ID)
    assertThat(map[21].name).isEqualTo("test_menu_2")
    assertThat(map[21].type).isEqualTo(ResourceType.ID)
    assertThat(map[22].name).isEqualTo("button_ok")
    assertThat(map[22].type).isEqualTo(ResourceType.ID)
    assertThat(map[23].name).isEqualTo("dialog_exit")
    assertThat(map[23].type).isEqualTo(ResourceType.ID)

    // INTEGER
    assertThat(map[24].name).isEqualTo("max_speed")
    assertThat(map[24].type).isEqualTo(ResourceType.INTEGER)
    assertThat(map[24].resourceValue.value).isEqualTo("75")
    assertThat(map[25].name).isEqualTo("min_speed")
    assertThat(map[25].type).isEqualTo(ResourceType.INTEGER)
    assertThat(map[25].resourceValue.value).isEqualTo("5")

    // LAYOUT
    assertThat(map[26].name).isEqualTo("test")
    assertThat(map[26].type).isEqualTo(ResourceType.LAYOUT)

    // MENU
    assertThat(map[27].name).isEqualTo("test_menu")
    assertThat(map[27].type).isEqualTo(ResourceType.MENU)

    // MIPMAP
    assertThat(map[28].name).isEqualTo("ic_launcher")
    assertThat(map[28].type).isEqualTo(ResourceType.MIPMAP)

    // PLURALS
    assertThat(map[29].name).isEqualTo("plural_name_donottranslate")
    assertThat(map[29].type).isEqualTo(ResourceType.PLURALS)
    with(map[29].resourceValue as PluralsResourceValue) {
      assertThat(pluralsCount).isEqualTo(2)
      assertThat(getQuantity(0)).isEqualTo("zero")
      assertThat(getValue(0)).isEqualTo("Nothing donottranslate")
      assertThat(getQuantity(1)).isEqualTo("one")
      assertThat(getValue(1)).isEqualTo("One String donottranslate")
    }
    assertThat(map[30].name).isEqualTo("plural_name")
    assertThat(map[30].type).isEqualTo(ResourceType.PLURALS)
    with(map[30].resourceValue as PluralsResourceValue) {
      assertThat(pluralsCount).isEqualTo(2)
      assertThat(getQuantity(0)).isEqualTo("zero")
      assertThat(getValue(0)).isEqualTo("Nothing")
      assertThat(getQuantity(1)).isEqualTo("one")
      assertThat(getValue(1)).isEqualTo("One String")
    }
    assertThat(map[31].name).isEqualTo("plural_name_translatable_false")
    assertThat(map[31].type).isEqualTo(ResourceType.PLURALS)
    with(map[31].resourceValue as PluralsResourceValue) {
      assertThat(pluralsCount).isEqualTo(2)
      assertThat(getQuantity(0)).isEqualTo("zero")
      assertThat(getValue(0)).isEqualTo("Nothing translatable false")
      assertThat(getQuantity(1)).isEqualTo("one")
      assertThat(getValue(1)).isEqualTo("One String translatable false")
    }
    assertThat(map[32].name).isEqualTo("plural_name")
    assertThat(map[32].type).isEqualTo(ResourceType.PLURALS)
    with(map[32].resourceValue as PluralsResourceValue) {
      assertThat(pluralsCount).isEqualTo(2)
      assertThat(getQuantity(0)).isEqualTo("zero")
      assertThat(getValue(0)).isEqualTo("[Ñöţĥîñĝ one two]")
      assertThat(getQuantity(1)).isEqualTo("one")
      assertThat(getValue(1)).isEqualTo("[Öñé Šţŕîñĝ one two]")
    }
    assertThat(map[33].name).isEqualTo("plural_name")
    assertThat(map[33].type).isEqualTo(ResourceType.PLURALS)
    with(map[33].resourceValue as PluralsResourceValue) {
      assertThat(pluralsCount).isEqualTo(2)
      assertThat(getQuantity(0)).isEqualTo("zero")
      assertThat(getValue(0)).isEqualTo("${bidiWordStart}Nothing$bidiWordEnd")
      assertThat(getQuantity(1)).isEqualTo("one")
      assertThat(getValue(1)).isEqualTo("${bidiWordStart}One$bidiWordEnd ${bidiWordStart}String$bidiWordEnd")
    }

    // RAW
    assertThat(map[34].name).isEqualTo("test_json")
    assertThat(map[34].type).isEqualTo(ResourceType.RAW)

    // STRING
    assertThat(map[35].name).isEqualTo("string_name_donottranslate")
    assertThat(map[35].type).isEqualTo(ResourceType.STRING)
    assertThat(map[35].resourceValue.value).isEqualTo("Test String donottranslate")
    assertThat(map[36].name).isEqualTo("string_name")
    assertThat(map[36].type).isEqualTo(ResourceType.STRING)
    assertThat(map[36].resourceValue.value).isEqualTo("Test String")
    assertThat(map[37].name).isEqualTo("string_name_xliff")
    assertThat(map[37].type).isEqualTo(ResourceType.STRING)
    assertThat(map[37].resourceValue.value).isEqualTo("Test String {0} with suffix")
    assertThat((map[37].resourceValue as BasicTextValueResourceItem).rawXmlValue).isEqualTo("Test String <xliff:g id=\"number\" example=\"9\">{0}</xliff:g> with suffix")
    assertThat(map[38].name).isEqualTo("string_name_html")
    assertThat(map[38].type).isEqualTo(ResourceType.STRING)
    assertThat(map[38].resourceValue.value).isEqualTo("<html>Test String <b>with</b> html</html>")
    assertThat((map[38].resourceValue as BasicTextValueResourceItem).rawXmlValue).isEqualTo("<![CDATA[<html>Test String <b>with</b> html</html>]]>")
    assertThat(map[39].name).isEqualTo("string_name_translatable_false")
    assertThat(map[39].type).isEqualTo(ResourceType.STRING)
    assertThat(map[39].resourceValue.value).isEqualTo("Test String translatable false")
    assertThat(map[40].name).isEqualTo("string_name")
    assertThat(map[40].type).isEqualTo(ResourceType.STRING)
    assertThat(map[40].resourceValue.value).isEqualTo("[Ţéšţ Šţŕîñĝ one two]")
    assertThat(map[41].name).isEqualTo("string_name_xliff")
    assertThat(map[41].type).isEqualTo(ResourceType.STRING)
    assertThat(map[41].resourceValue.value).isEqualTo("[Ţéšţ Šţŕîñĝ »{0}« ŵîţĥ šûƒƒîх one two three]")
    assertThat((map[41].resourceValue as BasicTextValueResourceItem).rawXmlValue).isEqualTo("[Ţéšţ Šţŕîñĝ <xliff:g id=\"number\" example=\"9\">»{0}«</xliff:g> ŵîţĥ šûƒƒîх one two three]")
    assertThat(map[42].name).isEqualTo("string_name_html")
    assertThat(map[42].type).isEqualTo(ResourceType.STRING)
    assertThat(map[42].resourceValue.value).isEqualTo("[<html>Ţéšţ Šţŕîñĝ <b>ŵîţĥ</b> ĥţḿļ</html> one two three]")
    assertThat((map[42].resourceValue as BasicTextValueResourceItem).rawXmlValue).isEqualTo("[<![CDATA[<html>Ţéšţ Šţŕîñĝ <b>ŵîţĥ</b> ĥţḿļ</html>]]> one two three]")
    assertThat(map[43].name).isEqualTo("string_name")
    assertThat(map[43].type).isEqualTo(ResourceType.STRING)
    assertThat(map[43].resourceValue.value).isEqualTo("${bidiWordStart}Test$bidiWordEnd ${bidiWordStart}String$bidiWordEnd")
    assertThat(map[44].name).isEqualTo("string_name_xliff")
    assertThat(map[44].type).isEqualTo(ResourceType.STRING)
    assertThat(map[44].resourceValue.value).isEqualTo("${bidiWordStart}Test$bidiWordEnd ${bidiWordStart}String$bidiWordEnd $bidiWordStart{0}$bidiWordEnd ${bidiWordStart}with$bidiWordEnd ${bidiWordStart}suffix$bidiWordEnd")
    assertThat((map[44].resourceValue as BasicTextValueResourceItem).rawXmlValue).isEqualTo("${bidiWordStart}Test$bidiWordEnd ${bidiWordStart}String$bidiWordEnd $bidiWordStart<xliff:g$bidiWordEnd ${bidiWordStart}id=\"number\"$bidiWordEnd ${bidiWordStart}example=\"9\">$bidiWordEnd$bidiWordStart{0}$bidiWordEnd$bidiWordStart</xliff:g>$bidiWordEnd ${bidiWordStart}with$bidiWordEnd ${bidiWordStart}suffix$bidiWordEnd")
    assertThat(map[45].name).isEqualTo("string_name_html")
    assertThat(map[45].type).isEqualTo(ResourceType.STRING)
    assertThat(map[45].resourceValue.value).isEqualTo("$bidiWordStart<html>Test$bidiWordEnd ${bidiWordStart}String$bidiWordEnd $bidiWordStart<b>with</b>$bidiWordEnd ${bidiWordStart}html</html>$bidiWordEnd")
    assertThat((map[45].resourceValue as BasicTextValueResourceItem).rawXmlValue).isEqualTo("$bidiWordStart<![CDATA[<html>Test$bidiWordEnd ${bidiWordStart}String$bidiWordEnd $bidiWordStart<b>with</b>$bidiWordEnd ${bidiWordStart}html</html>]]>$bidiWordEnd")

    // STYLE XML
    assertThat(map[46].name).isEqualTo("TestStyle")
    assertThat(map[46].type).isEqualTo(ResourceType.STYLE)
    with(map[46].resourceValue as StyleResourceValue) {
      assertThat(definedItems.size).isEqualTo(2)
      assertThat(definedItems.elementAt(0).attrName).isEqualTo("android:scrollbars")
      assertThat(definedItems.elementAt(0).value).isEqualTo("horizontal")
      assertThat(definedItems.elementAt(1).attrName).isEqualTo("android:marginTop")
      assertThat(definedItems.elementAt(1).value).isEqualTo("16dp")
    }

    // STYLEABLE
    assertThat(map[47].name).isEqualTo("test_styleable")
    assertThat(map[47].type).isEqualTo(ResourceType.STYLEABLE)
    with(map[47].resourceValue as StyleableResourceValue) {
      assertThat(allAttributes.size).isEqualTo(3)
      assertThat(allAttributes[0].name).isEqualTo("TestAttr")
      assertThat(allAttributes[1].name).isEqualTo("TestAttrInt")
      assertThat(allAttributes[2].name).isEqualTo("TestAttrReference")
    }

    // XML
    assertThat(map[48].name).isEqualTo("test_network_security_config")
    assertThat(map[48].type).isEqualTo(ResourceType.XML)
  }
}
