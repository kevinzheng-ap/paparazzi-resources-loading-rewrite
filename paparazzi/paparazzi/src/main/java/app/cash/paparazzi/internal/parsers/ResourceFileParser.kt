package app.cash.paparazzi.internal.parsers

import app.cash.paparazzi.internal.PaparazziResourceItem
import app.cash.paparazzi.internal.PaparazziResourceRepository
import app.cash.paparazzi.internal.pseudolocale.PaparazziPseudoResourceItem
import com.android.SdkConstants
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.ValueResourceNameValidator
import com.android.resources.FolderTypeRelationship
import com.android.resources.ResourceFolderType
import com.android.resources.ResourceType
import com.android.resources.ResourceType.ATTR
import com.android.resources.ResourceType.ID
import com.android.utils.SdkUtils
import com.android.utils.XmlUtils
import com.android.utils.forEach
import org.jetbrains.annotations.Contract
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File

private const val DO_NOT_TRANSLATE = "donottranslate"

/**
 * Parse the resource file and commit result to repository
 */
internal fun parse(resourceFile: String, repository: PaparazziResourceRepository) {
  val file = File(resourceFile)
  val folderType = getFolderType(file)
  if (folderType == ResourceFolderType.VALUES) {
    parseValueFileAsResourceItem(repository, file)
  } else {
    if (FolderTypeRelationship.isIdGeneratingFolderType(folderType)) {
      addIds(repository, file)
    }
    parseFileResourceFileAsResourceItem(repository, folderType, file)
  }
}

private fun parseValueFileAsResourceItem(
  repository: PaparazziResourceRepository,
  file: File,
): Boolean {
  var added = false
  if (file.extension == "xml") {
    val reader = XmlUtils.getUtfReader(file)
    val document: Document? = XmlUtils.parseDocument(reader, true)
    if (document != null) {
      val root: String = XmlUtils.getRootTagName(file) ?: return false
      if (root != SdkConstants.TAG_RESOURCES) {
        return false
      }
      val subTags = XmlUtils.getSubTags(
        XmlUtils.getFirstSubTagByName(
          document,
          SdkConstants.TAG_RESOURCES
        )
      )
      for (tag in subTags) {
        val name: String = tag.getAttribute(SdkConstants.ATTR_NAME)
        val type: ResourceType? = ResourceType.fromXmlTag(tag)
        if (type != null && isValidValueResourceName(name)) {
          val item = PaparazziResourceItem(
            file = file,
            name = name,
            type = type,
            repository = repository,
            tag = tag
          )
          repository.addResourceItem(item)
          if (type == ResourceType.STRING &&
            item.configuration.localeQualifier == null &&
            !file.name.contains(DO_NOT_TRANSLATE) &&
            tag.getAttribute(SdkConstants.ATTR_TRANSLATABLE).toBooleanStrictOrNull() != false
          ) {
            val pseudoString = PaparazziPseudoResourceItem(
              file = file,
              name = name,
              type = type,
              repository = repository,
              tag = tag
            )
            repository.addResourceItem(pseudoString)
          }
          added = true
          if (type === ResourceType.STYLEABLE) {
            // For styleables we also need to create attr items for its children.
            val attrs = XmlUtils.getSubTags(tag).iterator()
            while (attrs.hasNext()) {
              val child = attrs.next()
              var attrName: String = child.getAttribute(SdkConstants.ATTR_NAME)
              if (attrName.startsWith(SdkConstants.ANDROID_NS_NAME_PREFIX)) {
                attrName = attrName.substring(SdkConstants.ANDROID_NS_NAME_PREFIX_LEN)
              }
              if (isValidValueResourceName(attrName)
                // Only add attr nodes for elements that specify a format or have flag/enum children; otherwise
                // it's just a reference to an existing attr.
                && (child.getAttribute(SdkConstants.ATTR_FORMAT) != null ||
                  XmlUtils.getSubTags(child).count() > 0)
              ) {
                // Parse attr here
                val attrItem = PaparazziResourceItem(
                  file = file,
                  name = attrName,
                  type = ATTR,
                  repository = repository,
                  tag = child
                )
                repository.addResourceItem(attrItem)
              }
            }
          }
        }
      }
    }
  }
  return added
}

private fun addIds(
  repository: PaparazziResourceRepository,
  file: File,
) {
  if (file.extension == "xml") {
    val reader = XmlUtils.getUtfReader(file)
    val document: Document? = XmlUtils.parseDocument(reader, true)
    if (document != null) {
      addIds(repository, file, document.documentElement)
    }
  }
}

private fun addIds(
  repository: PaparazziResourceRepository,
  file: File,
  tag: Element,
) {
  val subTags = XmlUtils.getSubTags(tag).iterator()
  while (subTags.hasNext()) {
    val subTag = subTags.next()
    addIds(repository, file, subTag)
  }

  val attributes = tag.attributes
  attributes.forEach { node ->
    val attributeValue = node.nodeValue
    if (attributeValue.startsWith("@+")) {
      val id: String = attributeValue.substring(attributeValue.indexOf('/') + 1)
      val idResource = PaparazziResourceItem(
        file = file,
        name = id,
        type = ID,
        repository = repository,
        tag = tag
      )
      repository.addResourceItem(idResource)
    }
  }
}

private fun getFolderType(file: File): ResourceFolderType {
  return ResourceFolderType.getFolderType(file.parentFile.name)
}

private fun parseFileResourceFileAsResourceItem(
  repository: PaparazziResourceRepository,
  folderType: ResourceFolderType,
  file: File
) {
  val type = FolderTypeRelationship.getNonIdRelatedResourceType(folderType)
  val resourceName: String = SdkUtils.fileNameToResourceName(file.name)
  val item = PaparazziResourceItem(file, resourceName, type, repository, null)
  repository.addResourceItem(item)
}

@Contract(value = "null -> false")
private fun isValidValueResourceName(name: String): Boolean {
  return name.isNotEmpty() && ValueResourceNameValidator.getErrorText(
    name,
    null
  ) == null
}
