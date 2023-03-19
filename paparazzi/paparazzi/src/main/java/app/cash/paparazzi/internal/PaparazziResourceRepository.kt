// Copyright Square, Inc.
package app.cash.paparazzi.internal

import app.cash.paparazzi.internal.parsers.parse
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.AbstractResourceRepository
import com.android.ide.common.resources.ResourceItem
import com.android.ide.common.resources.ResourceTable
import com.android.ide.common.resources.ResourceVisitor
import com.android.ide.common.resources.ResourceVisitor.VisitResult
import com.android.ide.common.resources.ResourceVisitor.VisitResult.ABORT
import com.android.ide.common.resources.ResourceVisitor.VisitResult.CONTINUE
import com.android.ide.common.resources.SingleNamespaceResourceRepository
import com.android.resources.ResourceType
import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.ImmutableListMultimap
import com.google.common.collect.ImmutableSet
import com.google.common.collect.ListMultimap

internal class PaparazziResourceRepository constructor(
  resources: List<String>,
  private val namespace: ResourceNamespace
) : AbstractResourceRepository(), SingleNamespaceResourceRepository {

  private val resourceTable: ResourceTable = ResourceTable()

  init {
    resources.forEach { parse(it, this) }
  }

  override fun getPublicResources(
    namespace: ResourceNamespace,
    type: ResourceType
  ): Collection<ResourceItem> {
    throw UnsupportedOperationException("Not implemented yet")
  }

  override fun getResourceNames(
    namespace: ResourceNamespace,
    resourceType: ResourceType,
  ): Set<String> {
    val map = getResources(namespace, resourceType)
    return if (map == null) ImmutableSet.of() else ImmutableSet.copyOf(map.keySet())
  }

  override fun getNamespace(): ResourceNamespace = namespace

  override fun getPackageName(): String {
    throw UnsupportedOperationException("Not implemented yet")
  }

  override fun getResourcesInternal(
    namespace: ResourceNamespace,
    resourceType: ResourceType,
  ): ListMultimap<String, ResourceItem> {
    return resourceTable.get(namespace, resourceType) ?: ImmutableListMultimap.of()
  }

  fun addResourceItem(item: ResourceItem) {
      val resourceType = item.type
      var map: ListMultimap<String, ResourceItem>? = resourceTable.get(namespace, resourceType)
      if (map == null) {
        map = ArrayListMultimap.create()
        resourceTable.put(namespace, resourceType, map!!)
      }

      map.put(item.name, item)
  }

  override fun accept(visitor: ResourceVisitor): VisitResult {
    namespaces.forEach { namespace ->
      if (visitor.shouldVisitNamespace(namespace)) {
        ResourceType.values().forEach { type ->
          if (visitor.shouldVisitResourceType(type)) {
            getResources(namespace, type)?.values()?.forEach { item ->
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
}
