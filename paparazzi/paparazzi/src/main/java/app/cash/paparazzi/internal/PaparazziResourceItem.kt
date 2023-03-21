package app.cash.paparazzi.internal

import com.android.SdkConstants.ATTR_FORMAT
import com.android.SdkConstants.ATTR_NAME
import com.android.SdkConstants.ATTR_PARENT
import com.android.SdkConstants.ATTR_QUANTITY
import com.android.SdkConstants.ATTR_VALUE
import com.android.SdkConstants.TAG_ENUM
import com.android.SdkConstants.TAG_FLAG
import com.android.SdkConstants.TOOLS_URI
import com.android.ide.common.rendering.api.ArrayResourceValueImpl
import com.android.ide.common.rendering.api.AttrResourceValue
import com.android.ide.common.rendering.api.AttrResourceValueImpl
import com.android.ide.common.rendering.api.AttributeFormat
import com.android.ide.common.rendering.api.AttributeFormat.ENUM
import com.android.ide.common.rendering.api.AttributeFormat.FLAGS
import com.android.ide.common.rendering.api.DensityBasedResourceValueImpl
import com.android.ide.common.rendering.api.PluralsResourceValueImpl
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.ide.common.rendering.api.ResourceValue
import com.android.ide.common.rendering.api.ResourceValueImpl
import com.android.ide.common.rendering.api.StyleItemResourceValueImpl
import com.android.ide.common.rendering.api.StyleResourceValueImpl
import com.android.ide.common.rendering.api.StyleableResourceValueImpl
import com.android.ide.common.rendering.api.TextResourceValueImpl
import com.android.ide.common.resources.ResourceItem
import com.android.ide.common.resources.SingleNamespaceResourceRepository
import com.android.ide.common.resources.ValueXmlHelper
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.ide.common.util.PathString
import com.android.resources.ResourceType
import com.android.resources.ResourceType.PUBLIC
import com.android.resources.ResourceUrl
import com.android.utils.XmlUtils
import org.w3c.dom.Element
import java.io.File
import java.util.EnumSet

open class PaparazziResourceItem constructor(
  private val file: File,
  private val name: String,
  private val type: ResourceType,
  private val repository: SingleNamespaceResourceRepository,
  private val tag: Element?
) : ResourceItem {
  private val resourceValue: ResourceValue

  private val folderConfiguration: FolderConfiguration =
    FolderConfiguration.getConfigForFolder(file.parentFile.name)

  override fun getConfiguration(): FolderConfiguration {
    return folderConfiguration
  }

  override fun getName(): String {
    return name
  }

  override fun getType(): ResourceType {
    return type
  }

  override fun getNamespace(): ResourceNamespace {
    return repository.namespace
  }

  override fun getLibraryName(): String? {
    return null
  }

  override fun getRepository(): SingleNamespaceResourceRepository {
    return repository
  }

  override fun getReferenceToSelf(): ResourceReference =
    ResourceReference(namespace, type, name)

  override fun getKey(): String {
    val qualifiers = configuration.qualifierString
    return if (qualifiers.isNotEmpty()) {
      (type.getName() + '-') + qualifiers + '/' + name
    } else type.getName() + '/' + name
  }

  init {
    resourceValue = if (tag == null || type == PUBLIC) {
      val density =
        if (type == ResourceType.DRAWABLE || type == ResourceType.MIPMAP) configuration.densityQualifier?.value else null
      val path = file.absolutePath
      if (density != null) {
        DensityBasedResourceValueImpl(namespace, type, name, path, density, null);
      } else {
        ResourceValueImpl(namespace, type, name, path, null);
      }
    } else {
      parseXmlToResourceValueSafe(tag)
    }
  }

  override fun getResourceValue(): ResourceValue {
    return resourceValue
  }

  private fun parseXmlToResourceValueSafe(tag: Element): ResourceValue {
    val value: ResourceValueImpl = when (type) {
      ResourceType.STYLE -> {
        val parent = getAttributeValue(tag, ATTR_PARENT).takeIf { it.isNotBlank() }
        parseStyleValue(tag, StyleResourceValueImpl(namespace, name, parent, null))
      }

      ResourceType.STYLEABLE -> {
        parseDeclareStyleable(
          tag,
          StyleableResourceValueImpl(namespace, name, null, null)
        )
      }

      ResourceType.ATTR -> {
        parseAttrValue(tag, AttrResourceValueImpl(namespace, name, null))
      }
      ResourceType.ARRAY -> parseArrayValue(
        tag,
        ArrayResourceValueImpl(namespace, name, null)
      )
      ResourceType.PLURALS -> parsePluralsValue(
        tag,
        object : PluralsResourceValueImpl(namespace, name, null, null) {
          // Allow the user to specify a specific quantity to use via tools:quantity
          override fun getValue(): String {
            val quantity: String? =
              // TODO: Does getAttributeValue map to getAttributeNS?
              tag.getAttributeNS(
                ATTR_QUANTITY,
                TOOLS_URI
              )
            if (quantity != null) {
              val value = getValue(quantity)
              if (value != null) {
                return value
              }
            }
            return super.getValue()
          }
        })

      ResourceType.STRING ->
        parseTextValue(tag, TextResourceValueImpl(namespace, name, null, null, null))

      else -> parseValue(tag, ResourceValueImpl(namespace, type, name, null))
    }

    value.namespaceResolver = getNamespaceResolver(tag)
    return value
  }

  private fun getAttributeValue(tag: Element, attributeName: String): String {
    return tag.getAttribute(attributeName)
  }

  private fun parseStyleValue(
    tag: Element,
    styleValue: StyleResourceValueImpl
  ): StyleResourceValueImpl {
    for (child in XmlUtils.getSubTags(tag)) {
      val name = getAttributeValue(child, ATTR_NAME)
      if (name.isNotEmpty()) {
        val value =
          ValueXmlHelper.unescapeResourceString(getTextContent(child), true, true)
        val itemValue =
          StyleItemResourceValueImpl(styleValue.namespace, name, value, styleValue.libraryName)
        itemValue.namespaceResolver = getNamespaceResolver(child)
        styleValue.addItem(itemValue)
      }
    }
    return styleValue
  }

  private fun parseDeclareStyleable(
    tag: Element,
    declareStyleable: StyleableResourceValueImpl
  ): StyleableResourceValueImpl {
    for (child in XmlUtils.getSubTags(tag)) {
      val name = getAttributeValue(child, ATTR_NAME)
      if (name.isNotEmpty()) {
        val url = ResourceUrl.parseAttrReference(name)
        if (url != null) {
          val resolvedAttr = url.resolve(namespace, getNamespaceResolver(tag))
          if (resolvedAttr != null) {
            val attr: AttrResourceValue =
              parseAttrValue(child, AttrResourceValueImpl(resolvedAttr, null))
            declareStyleable.addValue(attr)
          }
        }
      }
    }
    return declareStyleable
  }

  private fun parseArrayValue(
    tag: Element,
    arrayValue: ArrayResourceValueImpl
  ): ArrayResourceValueImpl {
    for (child in XmlUtils.getSubTags(tag)) {
      val text =
        ValueXmlHelper.unescapeResourceString(getTextContent(child), true, true)
      arrayValue.addElement(text)
    }
    return arrayValue
  }

  private fun parsePluralsValue(
    tag: Element,
    value: PluralsResourceValueImpl
  ): PluralsResourceValueImpl {
    for (child in XmlUtils.getSubTags(tag)) {
      val quantity: String? = child.getAttribute(ATTR_QUANTITY)
      if (quantity != null) {
        val text =
          ValueXmlHelper.unescapeResourceString(getTextContent(child), true, true)
        value.addPlural(quantity, text)
      }
    }
    return value
  }

  private fun parseTextValue(
    tag: Element,
    value: TextResourceValueImpl
  ): TextResourceValueImpl {
    var text: String? = getTextContent(tag)
    text = ValueXmlHelper.unescapeResourceString(text, true, true)
    value.value = text
    return value
  }

  private fun parseValue(
    tag: Element,
    value: ResourceValueImpl
  ): ResourceValueImpl {
    var text: String? = getTextContent(tag)
    text = ValueXmlHelper.unescapeResourceString(text, true, true)
    value.value = text
    return value
  }

  private fun parseAttrValue(
    attrTag: Element,
    attrValue: AttrResourceValueImpl
  ): AttrResourceValueImpl {
    attrValue.description = getDescription(attrTag)
    val formats: MutableSet<AttributeFormat> = EnumSet.noneOf(AttributeFormat::class.java)
    val formatString = getAttributeValue(attrTag, ATTR_FORMAT)
    formats.addAll(AttributeFormat.parse(formatString))
    val subtags = XmlUtils.getSubTags(attrTag).iterator()
    while (subtags.hasNext()) {
      val child = subtags.next()
      val tagName: String = child.tagName
      if (TAG_ENUM == tagName) {
        formats.add(ENUM)
      } else if (TAG_FLAG == tagName) {
        formats.add(FLAGS)
      }
      val name = getAttributeValue(child, ATTR_NAME)
      var numericValue: Int? = null
      val value = getAttributeValue(child, ATTR_VALUE)
      try {
        // Use Long.decode to deal with hexadecimal values greater than 0x7FFFFFFF.
        numericValue = java.lang.Long.decode(value).toInt()
      } catch (ignored: NumberFormatException) {
      }
      attrValue.addValue(name, numericValue, getDescription(child))
    }
    attrValue.setFormats(formats)
    return attrValue
  }

  private fun getDescription(tag: Element): String? {
    return XmlUtils.getPreviousCommentText(tag)
  }

  /**
   * Returns the text content of a given tag
   */
  open fun getTextContent(tag: Element): String {
    return tag.textContent
  }

  /**
   * Returns a [ResourceNamespace.Resolver] for the specified tag.
   */
  private fun getNamespaceResolver(element: Element): ResourceNamespace.Resolver {
    return object : ResourceNamespace.Resolver {
      override fun uriToPrefix(namespaceUri: String): String? =
        XmlUtils.lookupNamespacePrefix(element, namespaceUri)

      // TODO
      override fun prefixToUri(namespacePrefix: String): String? = null
    }
  }

  override fun getSource(): PathString = PathString(file)

  override fun isFileBased(): Boolean {
    return tag == null
  }
}
