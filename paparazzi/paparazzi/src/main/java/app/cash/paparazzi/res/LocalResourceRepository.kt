// Copyright Square, Inc.
package app.cash.paparazzi.res

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.AbstractResourceRepository
import com.android.ide.common.resources.ResourceItem
import com.android.ide.common.resources.ResourceVisitor
import com.android.ide.common.resources.ResourceVisitor.VisitResult
import com.android.ide.common.resources.ResourceVisitor.VisitResult.CONTINUE
import com.android.ide.common.resources.SingleNamespaceResourceRepository
import com.android.resources.ResourceType
import com.google.common.collect.ImmutableListMultimap
import com.google.common.collect.ImmutableSet
import com.google.common.collect.ListMultimap

internal abstract class LocalResourceRepository protected constructor() :
  AbstractResourceRepository() {

  private var myParents: MutableList<MultiResourceRepository>? = null

  protected abstract fun getMap(
    namespace: ResourceNamespace,
    resourceType: ResourceType,
  ): ListMultimap<String, ResourceItem>?

  fun addParent(parent: MultiResourceRepository) {
    if (myParents == null) {
      myParents = ArrayList(2) // Don't expect many parents
    }
    myParents!!.add(parent)
  }

  fun removeParent(parent: MultiResourceRepository) {
    myParents?.remove(parent)
  }

  override fun getPublicResources(
    namespace: ResourceNamespace,
    type: ResourceType
  ): Collection<ResourceItem> {
    // TODO(namespaces): Implement.
    throw UnsupportedOperationException("Not implemented yet")
  }

  override fun getResourcesInternal(
    namespace: ResourceNamespace,
    resourceType: ResourceType,
  ): ListMultimap<String, ResourceItem> {
    return getMap(namespace, resourceType) ?: ImmutableListMultimap.of()
  }

  override fun getResourceNames(
    namespace: ResourceNamespace,
    resourceType: ResourceType,
  ): Set<String> {
    val map = getMap(namespace, resourceType)
    return if (map == null) ImmutableSet.of() else ImmutableSet.copyOf(map.keySet())
  }

  /**
   * Package accessible version of [.getMap].
   * Do not call outside of [MultiResourceRepository].
   */
  fun getMapPackageAccessible(
    namespace: ResourceNamespace,
    type: ResourceType
  ): ListMultimap<String, ResourceItem>? {
    return getMap(namespace, type)
  }

  open class EmptyRepository(private val myNamespace: ResourceNamespace) :
    LocalResourceRepository(), SingleNamespaceResourceRepository {

    override fun getMap(
      namespace: ResourceNamespace,
      resourceType: ResourceType
    ): ListMultimap<String, ResourceItem>? {
      return null
    }

    override fun getNamespace(): ResourceNamespace {
      return myNamespace
    }

    override fun getPackageName(): String {
      return myNamespace.packageName
    }

    override fun accept(visitor: ResourceVisitor): VisitResult {
      return CONTINUE
    }
  }
}
