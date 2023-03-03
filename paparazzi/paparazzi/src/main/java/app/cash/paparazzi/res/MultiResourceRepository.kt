// Copyright Square, Inc.
package app.cash.paparazzi.res

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.ResourceItem
import com.android.ide.common.resources.ResourceRepository
import com.android.ide.common.resources.ResourceVisitor
import com.android.ide.common.resources.ResourceVisitor.VisitResult
import com.android.ide.common.resources.ResourceVisitor.VisitResult.ABORT
import com.android.ide.common.resources.ResourceVisitor.VisitResult.CONTINUE
import com.android.ide.common.resources.SingleNamespaceResourceRepository
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.resources.ResourceType
import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableListMultimap
import com.google.common.collect.ImmutableSet
import com.google.common.collect.ListMultimap
import com.google.common.collect.Maps
import com.google.common.collect.Multimap
import com.google.common.collect.Multiset
import com.google.common.collect.Table
import com.google.common.collect.Tables
import kotlin.collections.Map.Entry

/**
 * A super class for several of the other repositories. Its only purpose is to be able to combine
 * multiple resource repositories and expose it as a single one, applying the “override” semantics
 * of resources: earlier children defining the same resource namespace/type/name combination will
 * replace/hide any subsequent definitions of the same resource.
 *
 *
 * In the resource repository hierarchy, MultiResourceRepository is an internal node, never a leaf.
 */
// TODO: The whole locking scheme for resource repositories needs to be reworked.
abstract class MultiResourceRepository internal constructor(displayName: String) :
  LocalResourceRepository(displayName) {

  private var myLocalResources: ImmutableList<LocalResourceRepository> = ImmutableList.of()

  private var myLibraryResources: ImmutableList<LocalResourceRepository> = ImmutableList.of()

  /** A concatenation of [.myLocalResources] and [.myLibraryResources].  */
  private var myChildren: ImmutableList<ResourceRepository> = ImmutableList.of()

  /** Leaf resource repositories keyed by namespace.  */
  private var myLeafsByNamespace: ImmutableListMultimap<ResourceNamespace, SingleNamespaceResourceRepository> =
    ImmutableListMultimap.of()

  /** Contained single-namespace resource repositories keyed by namespace.  */
  private var myRepositoriesByNamespace: ImmutableListMultimap<ResourceNamespace, SingleNamespaceResourceRepository> =
    ImmutableListMultimap.of()

  private var myResourceComparator =
    ResourceItemComparator(ResourcePriorityComparator(ImmutableList.of()))

  /** Names of resources from local leaf repositories.  */
  private val myResourceNames: Table<SingleNamespaceResourceRepository, ResourceType, Set<String>> =
    Tables.newCustomTable(HashMap()) {
      Maps.newEnumMap(
        ResourceType::class.java
      )
    }

  protected fun setChildren(
    localResources: List<LocalResourceRepository>,
    libraryResources: Collection<LocalResourceRepository>,
    otherResources: Collection<ResourceRepository>
  ) {
    myLocalResources.forEach { it.removeParent(this) }
    myLocalResources = ImmutableList.copyOf(localResources)
    myLibraryResources = ImmutableList.copyOf(libraryResources)
    val size: Int =
      myLocalResources.size + myLibraryResources.size + otherResources.size
    myChildren =
      ImmutableList.builderWithExpectedSize<ResourceRepository>(size)
        .addAll(myLocalResources)
        .addAll(myLibraryResources)
        .addAll(otherResources)
        .build()

    var mapBuilder: ImmutableListMultimap.Builder<ResourceNamespace, SingleNamespaceResourceRepository> =
      ImmutableListMultimap.builder()
    computeLeafs(this, mapBuilder)
    myLeafsByNamespace = mapBuilder.build()
    mapBuilder = ImmutableListMultimap.builder()
    computeNamespaceMap(this, mapBuilder)
    myRepositoriesByNamespace = mapBuilder.build()
    myResourceComparator = ResourceItemComparator(
      ResourcePriorityComparator(myLeafsByNamespace.values())
    )
  }

  /**
   * Returns resource repositories for the given namespace. In case of nested single-namespace repositories only the outermost
   * repositories are returned. Collectively the returned repositories are guaranteed to contain all resources in the given namespace
   * contained in this repository.
   *
   * @param namespace the namespace to return resource repositories for
   * @return a list of namespaces for the given namespace
   */
  fun getRepositoriesForNamespace(namespace: ResourceNamespace): List<SingleNamespaceResourceRepository> {
    return myRepositoriesByNamespace.get(namespace)
  }

  override fun getNamespaces(): Set<ResourceNamespace> {
    return myRepositoriesByNamespace.keySet()
  }

  override fun accept(visitor: ResourceVisitor): VisitResult {
    namespaces.forEach { namespace ->
      if (visitor.shouldVisitNamespace(namespace)) {
        ResourceType.values().forEach { type ->
          if (visitor.shouldVisitResourceType(type)) {
            getMap(namespace, type)?.values()?.forEach { item ->
              if (visitor.visit(item) == ABORT) {
                return ABORT
              }
            }
          }
        }
      }
    }
    return CONTINUE
  }

  override fun getMap(
    namespace: ResourceNamespace,
    resourceType: ResourceType
  ): ListMultimap<String, ResourceItem>? {
    val repositoriesForNamespace: ImmutableList<SingleNamespaceResourceRepository> =
      myLeafsByNamespace.get(namespace)
    if (repositoriesForNamespace.size == 1) {
      val repository: SingleNamespaceResourceRepository = repositoriesForNamespace[0]
      return getResourcesUnderLock(repository, namespace, resourceType)
    }
    var map: ListMultimap<String, ResourceItem>? = null
    // Merge all items of the given type.
    for (repository in repositoriesForNamespace) {
      val items: ListMultimap<String, ResourceItem> =
        getResourcesUnderLock(repository, namespace, resourceType)
      if (!items.isEmpty) {
        if (map == null) {
          // Create a new map.
          // We only add a duplicate item if there isn't an item with the same qualifiers, and it
          // is not a styleable or an id. Styleables and ids are allowed to be defined in multiple
          // places even with the same qualifiers.
          map =
            if (resourceType === ResourceType.STYLEABLE || resourceType === ResourceType.ID) ArrayListMultimap.create() else PerConfigResourceMap(
              myResourceComparator
            )
        }
        map!!.putAll(items)
        if (repository is LocalResourceRepository) {
          myResourceNames.put(repository, resourceType, ImmutableSet.copyOf(items.keySet()))
        }
      }
    }

    return map
  }

  override fun getLeafResourceRepositories(): Collection<SingleNamespaceResourceRepository> {
    return myLeafsByNamespace.values()
  }

  private class ResourcePriorityComparator constructor(repositories: Collection<SingleNamespaceResourceRepository>) :
    Comparator<ResourceItem?> {
    private val repositoryOrdering: HashMap<SingleNamespaceResourceRepository, Int> = hashMapOf()

    init {
      for ((i, repository) in repositories.withIndex()) {
        repositoryOrdering[repository] = i
      }
    }

    override fun compare(item1: ResourceItem?, item2: ResourceItem?): Int {
      return Integer.compare(getOrdering(item1!!), getOrdering(item2!!))
    }

    private fun getOrdering(item: ResourceItem): Int {
      val ordering: Int = repositoryOrdering.getValue(item.repository)
      assert(ordering >= 0)
      return ordering
    }
  }

  /**
   * Custom implementation of [ListMultimap] that may store multiple resource items for
   * the same folder configuration, but for readers exposes ot most one resource item per folder
   * configuration.
   *
   *
   * This ListMultimap implementation is not as robust as Guava multimaps but is sufficient
   * for MultiResourceRepository because the latter always copies data to immutable containers
   * before exposing it to callers.
   */
  private class PerConfigResourceMap(private val myComparator: ResourceItemComparator) :
    ListMultimap<String, ResourceItem> {
    private val myMap: MutableMap<String?, MutableList<ResourceItem>?> = HashMap()
    private var mySize = 0
    private var myValues: Values? = null

    override operator fun get(key: String?): List<ResourceItem> {
      val items: List<ResourceItem>? = myMap[key]
      return items ?: ImmutableList.of()
    }

    override fun keySet(): Set<String?> {
      return myMap.keys
    }

    override fun keys(): Multiset<String> {
      throw UnsupportedOperationException()
    }

    override fun values(): Collection<ResourceItem?> {
      var values = myValues
      if (values == null) {
        values = Values()
        myValues = values
      }
      return values
    }

    override fun entries(): Collection<Entry<String, ResourceItem>> {
      throw UnsupportedOperationException()
    }

    override fun removeAll(key: Any?): List<ResourceItem> {
      val removed: List<ResourceItem>? = myMap.remove(key)
      if (removed != null) {
        mySize -= removed.size
      }
      return removed ?: ImmutableList.of()
    }

    override fun clear() {
      myMap.clear()
      mySize = 0
    }

    override fun size(): Int {
      return mySize
    }

    override fun isEmpty() = mySize == 0

    override fun containsKey(key: Any?): Boolean {
      return myMap.containsKey(key)
    }

    override fun containsValue(value: Any?): Boolean {
      throw UnsupportedOperationException()
    }

    override fun containsEntry(key: Any?, value: Any?): Boolean {
      throw UnsupportedOperationException()
    }

    override fun put(key: String?, item: ResourceItem?): Boolean {
      val list: MutableList<ResourceItem> = myMap.computeIfAbsent(
        key
      ) { PerConfigResourceList() }!!
      val oldSize = list.size
      list.add(item!!)
      mySize += list.size - oldSize
      return true
    }

    override fun remove(key: Any?, value: Any?): Boolean {
      throw UnsupportedOperationException()
    }

    override fun putAll(key: String?, values: MutableIterable<ResourceItem?>): Boolean {
      if (values is Collection<*>) {
        if (values.isEmpty()) {
          return false
        }
        val list: MutableList<ResourceItem> = myMap.computeIfAbsent(
          key
        ) { PerConfigResourceList() }!!
        val oldSize = list.size
        val added = list.addAll((values as Collection<ResourceItem>))
        mySize += list.size - oldSize
        return added
      }
      var added = false
      var list: MutableList<ResourceItem>? = null
      var oldSize = 0
      for (item in values) {
        if (list == null) {
          list = myMap.computeIfAbsent(
            key
          ) { PerConfigResourceList() }
          oldSize = list!!.size
        }
        added = list.add(item!!)
      }
      if (list != null) {
        mySize += list.size - oldSize
      }
      return added
    }

    override fun putAll(multimap: Multimap<out String?, out ResourceItem?>): Boolean {
      multimap.asMap().entries.forEach {
        val key = it.key
        val items: Collection<ResourceItem?> = it.value
        if (key!!.isNotEmpty()) {
          val list: MutableList<ResourceItem>? =
            myMap.computeIfAbsent(key) { PerConfigResourceList() }
          val oldSize = list!!.size
          list.addAll(items.filterNotNull())
          mySize += list.size - oldSize
        }
      }
      return !multimap.isEmpty
    }

    override fun replaceValues(
      key: String?,
      values: MutableIterable<ResourceItem?>
    ): MutableList<ResourceItem?> {
      throw UnsupportedOperationException()
    }

    override fun asMap(): Map<String?, Collection<ResourceItem>> {
      return myMap as Map<String?, Collection<ResourceItem>>
    }

    /**
     * This class has a split personality. The class may store multiple resource items for the same
     * folder configuration, but for callers of non-mutating methods ([.get],
     * [.size], [Iterator.next], etc) it exposes at most one resource item per
     * folder configuration. Which of the resource items with the same folder configuration is
     * visible to non-mutating methods is determined by [ResourcePriorityComparator].
     */
    private inner class PerConfigResourceList : AbstractMutableList<ResourceItem>() {
      /** Resource items sorted by folder configurations. Nested lists are sorted by repository priority.  */
      private val myResourceItems: MutableList<MutableList<ResourceItem>> = ArrayList()

      override fun get(index: Int): ResourceItem {
        return myResourceItems[index][0]
      }

      override fun removeAt(index: Int): ResourceItem {
        return myResourceItems[index][0].also {
          remove(it)
        }
      }

      override fun set(index: Int, element: ResourceItem): ResourceItem {
        TODO("Not yet implemented")
      }

      override val size: Int
        get() = myResourceItems.size

      override fun add(index: Int, element: ResourceItem) {
        TODO("Not yet implemented")
      }

      override fun add(element: ResourceItem): Boolean {
        add(element, 0)
        return true
      }

      override fun addAll(elements: Collection<ResourceItem>): Boolean {
        if (elements.isEmpty()) {
          return false
        }
        if (elements.size == 1) {
          return add(elements.iterator().next())
        }
        val sortedItems: List<ResourceItem> = sortedItems(elements)
        var start = 0
        for (item in sortedItems) {
          start = add(item, start)
        }
        return true
      }

      private fun add(item: ResourceItem, start: Int): Int {
        var index = findConfigIndex(item, start, myResourceItems.size)
        if (index < 0) {
          index = index.inv()
          myResourceItems.add(index, mutableListOf(item))
        } else {
          val nested: MutableList<ResourceItem> = myResourceItems[index]
          // Iterate backwards since it is likely to require fewer iterations.
          var i = nested.size
          while (--i >= 0) {
            if (myComparator.myPriorityComparator.compare(item, nested[i]) > 0) {
              break
            }
          }
          nested.add(i + 1, item)
        }
        return index
      }

      override fun clear() {
        myResourceItems.clear()
      }

      override fun remove(element: ResourceItem): Boolean {
        val index = remove(element, myResourceItems.size)
        return index >= 0
      }

      override fun removeAll(elements: Collection<ResourceItem>): Boolean {
        if (elements.isEmpty()) {
          return false
        }
        if (elements.size == 1) {
          return remove(elements.iterator().next())
        }
        val itemsToDelete: List<ResourceItem> = sortedItems(elements)
        var modified = false
        var end = myResourceItems.size
        var i = itemsToDelete.size
        while (--i >= 0) {
          val index = remove(itemsToDelete[i], end)
          if (index > 0) {
            modified = true
            end = index
          } else {
            end = index.inv()
          }
        }
        return modified
      }

      /**
       * Removes the given resource item from the first `end` elements of [.myResourceItems].
       *
       * @param item the resource item to remove
       * @param end the exclusive end of the range checked for existence of the item being deleted
       * @return if the item to be deleted was found, returns its index, otherwise returns
       * the binary complement of the index pointing to where the item would be inserted
       */
      private fun remove(item: ResourceItem, end: Int): Int {
        val index = findConfigIndex(item, 0, end)
        if (index < 0) {
          return index
        }
        val nested: MutableList<ResourceItem> = myResourceItems[index]
        if (!nested.remove(item)) {
          return (index + 1).inv()
        }
        if (nested.isEmpty()) {
          myResourceItems.removeAt(index)
          return index
        }
        return index + 1
      }

      private fun sortedItems(items: Collection<ResourceItem>): List<ResourceItem> {
        val sortedItems: List<ResourceItem> = ArrayList(items)
        sortedItems.sortedWith(myComparator)
        return sortedItems
      }

      /**
       * Returns index in [.myResourceItems] of the existing resource item with the same
       * configuration as the `item` parameter. If [.myResourceItems] doesn't contains
       * resources with the same configuration, returns binary complement of the insertion point.
       */
      private fun findConfigIndex(item: ResourceItem, start: Int, end: Int): Int {
        val config: FolderConfiguration = item.configuration
        var low = start
        var high = end
        while (low < high) {
          val mid = low + high ushr 1
          val value: FolderConfiguration = myResourceItems[mid][0].configuration
          val c = value.compareTo(config)
          if (c < 0) {
            low = mid + 1
          } else if (c > 0) {
            high = mid
          } else {
            return mid
          }
        }
        return low.inv() // Not found.
      }
    }

    private inner class Values : AbstractCollection<ResourceItem?>() {
      override fun iterator(): Iterator<ResourceItem> {
        return ValuesIterator()
      }

      override val size: Int
        get() = mySize

      private inner class ValuesIterator : Iterator<ResourceItem> {
        private val myOuterCursor: Iterator<List<ResourceItem>?> = myMap.values.iterator()
        private var myCurrentList: List<ResourceItem>? = null
        private var myInnerCursor = 0

        override fun hasNext(): Boolean {
          return myCurrentList != null || myOuterCursor.hasNext()
        }

        override fun next(): ResourceItem {
          if (myCurrentList == null) {
            myCurrentList = myOuterCursor.next()
            myInnerCursor = 0
          }
          return try {
            val item: ResourceItem = myCurrentList!![myInnerCursor]
            if (++myInnerCursor >= myCurrentList!!.size) {
              myCurrentList = null
            }
            item
          } catch (e: IndexOutOfBoundsException) {
            throw NoSuchElementException()
          }
        }
      }
    }
  }

  private class ResourceItemComparator constructor(val myPriorityComparator: Comparator<ResourceItem?>) :
    Comparator<ResourceItem?> {

    override fun compare(item1: ResourceItem?, item2: ResourceItem?): Int {
      val c: Int = item1!!.configuration.compareTo(item2!!.configuration)
      return if (c != 0) {
        c
      } else myPriorityComparator.compare(item1, item2)
    }
  }

  companion object {

    private fun computeLeafs(
      repository: ResourceRepository,
      result: ImmutableListMultimap.Builder<ResourceNamespace, SingleNamespaceResourceRepository>
    ) {
      if (repository is MultiResourceRepository) {
        repository.myChildren.forEach { computeLeafs(it, result) }
      } else {
        repository.leafResourceRepositories.forEach {
          result.put(it.namespace, it)
        }
      }
    }

    private fun computeNamespaceMap(
      repository: ResourceRepository,
      result: ImmutableListMultimap.Builder<ResourceNamespace, SingleNamespaceResourceRepository>
    ) {
      if (repository is SingleNamespaceResourceRepository) {
        val namespace = repository.namespace
        result.put(namespace, repository)
      } else if (repository is MultiResourceRepository) {
        repository.myChildren.forEach {
          computeNamespaceMap(it, result)
        }
      }
    }

    private fun getResourcesUnderLock(
      repository: SingleNamespaceResourceRepository,
      namespace: ResourceNamespace,
      type: ResourceType
    ): ListMultimap<String, ResourceItem> {
      if (repository is LocalResourceRepository) {
        return (repository as LocalResourceRepository).getMapPackageAccessible(namespace, type)
          ?: ImmutableListMultimap.of()
      }
      return repository.getResources(namespace, type)
    }
  }
}
