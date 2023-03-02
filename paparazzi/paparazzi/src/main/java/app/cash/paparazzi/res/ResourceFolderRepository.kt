package app.cash.paparazzi.res

import com.android.SdkConstants.ANDROID_NS_NAME_PREFIX
import com.android.SdkConstants.ATTR_FORMAT
import com.android.SdkConstants.ATTR_NAME
import com.android.SdkConstants.TAG_RESOURCES
import com.android.annotations.concurrency.GuardedBy
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.ResourceItem
import com.android.ide.common.resources.ResourceVisitor
import com.android.ide.common.resources.SingleNamespaceResourceRepository
import com.android.ide.common.resources.ValueResourceNameValidator
import com.android.resources.ResourceType
import com.android.resources.ResourceType.ATTR
import com.android.utils.XmlUtils
import com.google.common.collect.LinkedListMultimap
import com.google.common.collect.ListMultimap
import org.jetbrains.annotations.Contract
import org.w3c.dom.Document
import org.w3c.dom.Node
import java.io.File
import java.util.EnumMap

/**
 * The [ResourceFolderRepository] is leaf in the repository tree, and is used for user editable resources (e.g. the resources in the
 * project, typically the res/main source set.) Each ResourceFolderRepository contains the resources provided by a single res folder. This
 * repository is built on top of IntelliJ’s PSI infrastructure. This allows it (along with PSI listeners) to be updated incrementally; for
 * example, when it notices that the user is editing the value inside a <string> element in a value folder XML file, it will directly update
 * the resource value for the given resource item, and so on.
 *
 *
 * For efficiency, the ResourceFolderRepository is initialized using non-PSI parsers and then
 * lazily switches to PSI parsers after edits. See also `README.md` in this package.
 *
 *
 * Remaining work:
 *
 *  * Find some way to have event updates in this resource folder directly update parent repositories
 * (typically [ModuleResourceRepository])
 *  * Add defensive checks for non-read permission reads of resource values
 *  * Idea: For [.scheduleScan]; compare the removed items from the added items, and if they're the same, avoid
 * creating a new generation.
 *  * Register the PSI project listener as a project service instead.
 *
</string> */
class ResourceFolderRepository constructor(
  resourceDir: String,
  namespace: ResourceNamespace,
) : LocalResourceRepository(resourceDir), SingleNamespaceResourceRepository {

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
      synchronized(ITEM_MAP_LOCK) { commitToRepositoryWithoutLock(itemsByType) }
    }
  }

  /**
   * Inserts the given resources into this repository without acquiring any locks. Safe to call only while
   * holding [.ITEM_MAP_LOCK] or during construction of ResourceFolderRepository.
   */
  private fun commitToRepositoryWithoutLock(itemsByType: Map<ResourceType, ListMultimap<String, ResourceItem>>) {
    for (entry in itemsByType) {
      val map: ListMultimap<String, ResourceItem> = getOrCreateMap(entry.key)
      map.putAll(entry.value)
    }
  }

  override fun accept(visitor: ResourceVisitor): ResourceVisitor.VisitResult {
    if (visitor.shouldVisitNamespace(myNamespace)) {
      synchronized(ITEM_MAP_LOCK) {
        if (acceptByResources(myResourceTable, visitor) == ResourceVisitor.VisitResult.ABORT) {
          return ResourceVisitor.VisitResult.ABORT
        }
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

  @GuardedBy("ITEM_MAP_LOCK")
  override fun getMap(
    namespace: ResourceNamespace,
    type: ResourceType
  ): ListMultimap<String, ResourceItem>? {
    return if (namespace != myNamespace) {
      null
    } else myResourceTable[type]
  }

  private fun getOrCreateMap(type: ResourceType): ListMultimap<String, ResourceItem> {
    // Use LinkedListMultimap to preserve ordering for editors that show original order.
    return myResourceTable.computeIfAbsent(type) { LinkedListMultimap.create() }
  }

  // This reads the value.xml
  private fun scanValueFileAsPsi(
    result: MutableMap<ResourceType, ListMultimap<String, ResourceItem>>,
    file: File,
  ): Boolean {
    var added = false
    // TODO
    if (file.extension == "xml") {
      val reader = XmlUtils.getUtfReader(file)
      val document: Document? = XmlUtils.parseDocument(reader, true)
      if (document != null) {
        val root: String = XmlUtils.getRootTagName(file) ?: return false
        if (root != TAG_RESOURCES) {
          return false
        }
        val subTags = XmlUtils.getSubTags(document.firstChild) // Not recursive, right?
        for (tag in subTags) {
          val name: String = tag.getAttribute(ATTR_NAME)
          val type: ResourceType? = getResourceTypeForResourceTag(tag)
          if (type != null && isValidValueResourceName(name)) {
            val item = PsiResourceItem(
              file = file,
              name = name,
              type = type,
              namespace = namespace,
              tag = tag
            )
            addToResult(item, result)
            added = true
            if (type === ResourceType.STYLEABLE) {
              // For styleables we also need to create attr items for its children.
              val attrs = XmlUtils.getSubTags(tag)
              if (attrs.count() > 0) {
                for (child in attrs) {
                  val attrName: String = child.getAttribute(ATTR_NAME)
                  if (isValidValueResourceName(attrName) && !attrName.startsWith(
                      ANDROID_NS_NAME_PREFIX
                    ) // Only add attr nodes for elements that specify a format or have flag/enum children; otherwise
                    // it's just a reference to an existing attr.
                    && (child.getAttribute(ATTR_FORMAT) != null || XmlUtils.getSubTags(child)
                      .count() > 0)
                  ) {
                    // Parse attr here
                    val attrItem = PsiResourceItem(
                      file = file,
                      name = name,
                      type = ATTR,
                      namespace = namespace,
                      tag = child)
                    addToResult(attrItem, result)
                  }
                }
              }
            }
          }
        }
      }
    }
    return added
  }

  private fun getResourceTypeForResourceTag(tag: Node): ResourceType? = ResourceType.fromXmlTag(tag)

  private fun scan(file: File) {
    val result: MutableMap<ResourceType, ListMultimap<String, ResourceItem>> =
      EnumMap(com.android.resources.ResourceType::class.java)
    scanValueFileAsPsi(result, file)
    commitToRepository(result)
  }

  companion object {
    /**
     * Creates a ResourceFolderRepository and loads its contents.
     *
     *
     * If `cachingData` is not null, an attempt is made
     * to load resources from the cache file specified in `cachingData`. While loading from the cache resources
     * defined in the XML files that changed recently are skipped. Whether an XML has changed or not is determined by
     * comparing the combined hash of the file modification time and the length obtained by calling
     * [VirtualFile.getTimeStamp] and [VirtualFile.getLength] with the hash value stored in the cache.
     * The checks are located in [.deserializeResourceSourceFile] and [.deserializeFileResourceItem].
     *
     *
     * The remaining resources are then loaded by parsing XML files that were not present in the cache or were newer
     * than their cached versions.
     *
     *
     * If a significant (determined by [.CACHE_STALENESS_THRESHOLD]) percentage of resources was loaded by parsing
     * XML files and `cachingData.cacheCreationExecutor` is not null, the new cache file is created using that
     * executor, possibly after this method has already returned.
     *
     *
     * After creation the contents of the repository are maintained to be up-to-date by listening to VFS and PSI events.
     *
     *
     * NOTE: You should normally use [ResourceFolderRegistry.get] rather than this method.
     */
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
