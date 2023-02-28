package app.cash.paparazzi.res

import com.android.annotations.concurrency.GuardedBy
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.AbstractResourceRepository
import com.android.ide.common.resources.ResourceItem
import com.android.resources.ResourceType
import com.google.common.collect.ImmutableListMultimap
import com.google.common.collect.ImmutableSet
import com.google.common.collect.ListMultimap
import java.util.function.Predicate

abstract class AbstractResourceRepositoryWithLocking :
  AbstractResourceRepository() {

  @GuardedBy("ITEM_MAP_LOCK")
  protected abstract fun getMap(
    namespace: ResourceNamespace,
    resourceType: ResourceType,
  ): ListMultimap<String, ResourceItem>?

  @GuardedBy("ITEM_MAP_LOCK")
  override fun getResourcesInternal(
    namespace: ResourceNamespace,
    resourceType: ResourceType,
  ): ListMultimap<String, ResourceItem> {
    return getMap(namespace, resourceType) ?: ImmutableListMultimap.of()
  }

  override fun getResources(
    namespace: ResourceNamespace,
    resourceType: ResourceType,
    resourceName: String,
  ): List<ResourceItem> {
    synchronized(ITEM_MAP_LOCK) {
      return super.getResources(
        namespace,
        resourceType,
        resourceName
      )
    }
  }

  override fun getResources(
    namespace: ResourceNamespace,
    resourceType: ResourceType,
    filter: Predicate<ResourceItem>
  ): List<ResourceItem> {
    synchronized(ITEM_MAP_LOCK) {
      return super.getResources(
        namespace,
        resourceType,
        filter
      )
    }
  }

  override fun getResources(
    namespace: ResourceNamespace,
    resourceType: ResourceType,
  ): ListMultimap<String, ResourceItem> {
    synchronized(ITEM_MAP_LOCK) {
      return super.getResources(
        namespace,
        resourceType
      )
    }
  }

  override fun getResourceNames(
    namespace: ResourceNamespace,
    resourceType: ResourceType,
  ): Set<String> {
    synchronized(ITEM_MAP_LOCK) {
      val map = getMap(namespace, resourceType)
      return if (map == null) ImmutableSet.of() else ImmutableSet.copyOf(
        map.keySet()
      )
    }
  }

  override fun hasResources(
    namespace: ResourceNamespace,
    resourceType: ResourceType,
    resourceName: String
  ): Boolean {
    synchronized(ITEM_MAP_LOCK) {
      return super.hasResources(
        namespace,
        resourceType,
        resourceName
      )
    }
  }

  override fun hasResources(namespace: ResourceNamespace, resourceType: ResourceType): Boolean {
    synchronized(ITEM_MAP_LOCK) {
      return super.hasResources(
        namespace,
        resourceType
      )
    }
  }

  override fun getResourceTypes(namespace: ResourceNamespace): Set<ResourceType> {
    synchronized(ITEM_MAP_LOCK) {
      return super.getResourceTypes(
        namespace
      )
    }
  }

  companion object {
    /**
     * The lock used to protect map access.
     *
     *
     * In the IDE, this needs to be obtained **AFTER** the IDE read/write lock, to avoid
     * deadlocks (most readers of the repository system execute in a read action, so obtaining the
     * locks in opposite order results in deadlocks).
     */
    val ITEM_MAP_LOCK = Any()
  }
}
