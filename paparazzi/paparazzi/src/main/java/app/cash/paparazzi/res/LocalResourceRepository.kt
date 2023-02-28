// Copyright Square, Inc.
package app.cash.paparazzi.res

import androidx.annotation.GuardedBy
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.ResourceItem
import com.android.ide.common.resources.ResourceVisitor
import com.android.ide.common.resources.ResourceVisitor.VisitResult
import com.android.ide.common.resources.ResourceVisitor.VisitResult.CONTINUE
import com.android.ide.common.resources.SingleNamespaceResourceRepository
import com.android.resources.ResourceType
import com.google.common.collect.ListMultimap

// TODO: The whole locking scheme for resource repositories needs to be reworked.
abstract class LocalResourceRepository protected constructor(val displayName: String) :
  AbstractResourceRepositoryWithLocking() {

  @GuardedBy("ITEM_MAP_LOCK")
  private var myParents: MutableList<MultiResourceRepository>? = null

  fun addParent(parent: MultiResourceRepository) {
    synchronized(ITEM_MAP_LOCK) {
      if (myParents == null) {
        myParents = ArrayList(2) // Don't expect many parents
      }
      myParents!!.add(parent)
    }
  }

  fun removeParent(parent: MultiResourceRepository) {
    synchronized(ITEM_MAP_LOCK) {
      myParents?.remove(parent)
    }
  }

  override fun getPublicResources(
    namespace: ResourceNamespace,
    type: ResourceType
  ): Collection<ResourceItem> {
    // TODO(namespaces): Implement.
    throw UnsupportedOperationException("Not implemented yet")
  }

  /**
   * Package accessible version of [.getMap].
   * Do not call outside of [MultiResourceRepository].
   */
  @GuardedBy("ITEM_MAP_LOCK") fun getMapPackageAccessible(
    namespace: ResourceNamespace,
    type: ResourceType
  ): ListMultimap<String, ResourceItem>? {
    return getMap(namespace, type)
  }

  open class EmptyRepository(private val myNamespace: ResourceNamespace) :
    LocalResourceRepository(""), SingleNamespaceResourceRepository {

    @GuardedBy("ITEM_MAP_LOCK")
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
