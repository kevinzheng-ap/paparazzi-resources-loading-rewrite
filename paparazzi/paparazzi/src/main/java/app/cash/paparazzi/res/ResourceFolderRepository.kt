package app.cash.paparazzi.res

import com.android.SdkConstants
import com.android.SdkConstants.ANDROID_NS_NAME_PREFIX
import com.android.SdkConstants.ATTR_FORMAT
import com.android.SdkConstants.ATTR_NAME
import com.android.SdkConstants.TAG_RESOURCES
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.ResourceItem
import com.android.ide.common.resources.ResourceVisitor
import com.android.ide.common.resources.SingleNamespaceResourceRepository
import com.android.ide.common.resources.ValueResourceNameValidator
import com.android.resources.FolderTypeRelationship
import com.android.resources.ResourceFolderType
import com.android.resources.ResourceType
import com.android.resources.ResourceType.ATTR
import com.android.resources.ResourceType.ID
import com.android.utils.SdkUtils
import com.android.utils.XmlUtils
import com.android.utils.forEach
import com.google.common.collect.LinkedListMultimap
import com.google.common.collect.ListMultimap
import org.jetbrains.annotations.Contract
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.EnumMap

internal class ResourceFolderRepository constructor(
  resourceDir: String,
  namespace: ResourceNamespace,
) : LocalResourceRepository(), SingleNamespaceResourceRepository {

  private val myNamespace: ResourceNamespace

  private val myResourceTable: MutableMap<ResourceType, ListMultimap<String, ResourceItem>> =
    EnumMap(
      ResourceType::class.java
    )

  init {
    myNamespace = namespace
    scan(File(resourceDir))
  }

  /**
   * Inserts the given resources into this repository, while holding the global repository lock.
   */
  private fun commitToRepository(itemsByType: Map<ResourceType, ListMultimap<String, ResourceItem>>) {
    if (itemsByType.isNotEmpty()) {
      for (entry in itemsByType) {
        val map: ListMultimap<String, ResourceItem> = getOrCreateMap(entry.key)
        map.putAll(entry.value)
      }
    }
  }

  private fun getOrCreateMap(type: ResourceType): ListMultimap<String, ResourceItem> {
    return myResourceTable.computeIfAbsent(type) { LinkedListMultimap.create() }
  }

  override fun accept(visitor: ResourceVisitor): ResourceVisitor.VisitResult {
    if (visitor.shouldVisitNamespace(myNamespace)) {
      if (acceptByResources(myResourceTable, visitor) == ResourceVisitor.VisitResult.ABORT) {
        return ResourceVisitor.VisitResult.ABORT
      }
    }
    return ResourceVisitor.VisitResult.CONTINUE
  }

  override fun getNamespace(): ResourceNamespace {
    return myNamespace
  }

  override fun getPackageName(): String {
    throw UnsupportedOperationException()
  }

  override fun getMap(
    namespace: ResourceNamespace,
    resourceType: ResourceType
  ): ListMultimap<String, ResourceItem>? {
    return myResourceTable[resourceType].takeIf { namespace == myNamespace }
  }

  // This reads the value.xml
  private fun scanValueFileAsResourceItem(
    result: MutableMap<ResourceType, ListMultimap<String, ResourceItem>>,
    file: File,
  ): Boolean {
    var added = false
    if (file.extension == "xml") {
      val reader = XmlUtils.getUtfReader(file)
      val document: Document? = XmlUtils.parseDocument(reader, true)
      if (document != null) {
        val root: String = XmlUtils.getRootTagName(file) ?: return false
        if (root != TAG_RESOURCES) {
          return false
        }
        val subTags = XmlUtils.getSubTags(XmlUtils.getFirstSubTagByName(document, TAG_RESOURCES))
        for (tag in subTags) {
          val name: String = tag.getAttribute(ATTR_NAME)
          val type: ResourceType? = ResourceType.fromXmlTag(tag)
          if (type != null && isValidValueResourceName(name)) {
            val item = PaparazziResourceItem(
              file = file,
              name = name,
              type = type,
              repository = this,
              tag = tag
            )
            addToResult(item, result)
            added = true
            if (type === ResourceType.STYLEABLE) {
              // For styleables we also need to create attr items for its children.
              val attrs = XmlUtils.getSubTags(tag).iterator()
              while (attrs.hasNext()) {
                val child = attrs.next()
                var attrName: String = child.getAttribute(ATTR_NAME)
                if (attrName.startsWith(ANDROID_NS_NAME_PREFIX)) {
                  attrName = attrName.substring(SdkConstants.ANDROID_NS_NAME_PREFIX_LEN)
                }
                if (isValidValueResourceName(attrName)
                  // Only add attr nodes for elements that specify a format or have flag/enum children; otherwise
                  // it's just a reference to an existing attr.
                  && (child.getAttribute(ATTR_FORMAT) != null || XmlUtils.getSubTags(child)
                    .count() > 0)
                ) {
                  // Parse attr here
                  val attrItem = PaparazziResourceItem(
                    file = file,
                    name = attrName,
                    type = ATTR,
                    repository = this,
                    tag = child
                  )
                  addToResult(attrItem, result)
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
    file: File,
    result: MutableMap<ResourceType, ListMultimap<String, ResourceItem>>,
  ) {
    if (file.extension == "xml") {
      val reader = XmlUtils.getUtfReader(file)
      val document: Document? = XmlUtils.parseDocument(reader, true)
      if (document != null) {
        addIds(file, document.documentElement, result)
      }
    }
  }

  private fun addIds(
    file: File,
    tag: Element,
    result: MutableMap<ResourceType, ListMultimap<String, ResourceItem>>,
  ) {
    val subTags = XmlUtils.getSubTags(tag).iterator()
    while (subTags.hasNext()) {
      val subTag = subTags.next()
      addIds(file, subTag, result)
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
          repository = this,
          tag = tag
        )
        addToResult(idResource, result)
      }
    }
  }

  private fun getFolderType(file: File): ResourceFolderType {
    return ResourceFolderType.getFolderType(file.parentFile.name)
  }

  private fun scan(file: File) {
    val folderType = getFolderType(file)
    val result: MutableMap<ResourceType, ListMultimap<String, ResourceItem>> =
      EnumMap(com.android.resources.ResourceType::class.java)
    if (folderType == ResourceFolderType.VALUES) {
      scanValueFileAsResourceItem(result, file)
    } else {
      if (FolderTypeRelationship.isIdGeneratingFolderType(folderType)) {
        addIds(file, result)
      }
      scanFileResourceFileAsResourceItem(folderType, result, file)
    }
    commitToRepository(result)
  }

  private fun scanFileResourceFileAsResourceItem(
    folderType: ResourceFolderType,
    result: MutableMap<ResourceType, ListMultimap<String, ResourceItem>>,
    file: File
  ) {
    val type = FolderTypeRelationship.getNonIdRelatedResourceType(folderType)
    val resourceName: String = SdkUtils.fileNameToResourceName(file.name)
    val item = PaparazziResourceItem(file, resourceName, type, this, null)
    addToResult(item, result)
  }

  companion object {
    fun create(
      dir: String,
      namespace: ResourceNamespace
    ): ResourceFolderRepository {
      return ResourceFolderRepository(dir, namespace)
    }

    private fun addToResult(
      item: ResourceItem,
      result: MutableMap<ResourceType, ListMultimap<String, ResourceItem>>
    ) {
      // The insertion order matters, see AppResourceRepositoryTest.testStringOrder.
      result.computeIfAbsent(item.type) { LinkedListMultimap.create() }
        .put(item.name, item)
    }

    @Contract(value = "null -> false")
    private fun isValidValueResourceName(name: String): Boolean {
      return name.isNotEmpty() && ValueResourceNameValidator.getErrorText(
        name,
        null
      ) == null
    }
  }
}
