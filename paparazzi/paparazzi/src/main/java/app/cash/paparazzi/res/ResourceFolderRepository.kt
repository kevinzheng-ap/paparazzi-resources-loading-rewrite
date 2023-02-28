package app.cash.paparazzi.res

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.ResourceItem
import com.android.ide.common.resources.ResourceVisitor
import com.android.ide.common.resources.ResourceVisitor.VisitResult
import com.android.ide.common.resources.ResourceVisitor.VisitResult.ABORT
import com.android.ide.common.resources.SingleNamespaceResourceRepository
import com.android.resources.ResourceType
import com.google.common.collect.ListMultimap

class ResourceFolderRepository : LocalResourceRepository("") {
  override fun getMap(
    namespace: ResourceNamespace,
    resourceType: ResourceType
  ): ListMultimap<String, ResourceItem>? {
    return null
  }

  override fun accept(visitor: ResourceVisitor?): VisitResult {
    return ABORT
  }

  override fun getNamespaces(): MutableSet<ResourceNamespace> {
    return mutableSetOf()
  }

  override fun getLeafResourceRepositories(): MutableCollection<SingleNamespaceResourceRepository> {
    return mutableListOf()
  }
}
