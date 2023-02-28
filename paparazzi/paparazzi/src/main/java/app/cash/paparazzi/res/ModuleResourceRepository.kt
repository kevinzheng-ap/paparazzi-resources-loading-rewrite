// Copyright Square, Inc.
package app.cash.paparazzi.res

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.SingleNamespaceResourceRepository
import com.google.common.base.MoreObjects

/**
 * @see StudioResourceRepositoryManager.getModuleResources
 */
internal class ModuleResourceRepository private constructor(
  facet: AndroidFacet,
  namespace: ResourceNamespace,
  delegates: List<LocalResourceRepository>,
) : MultiResourceRepository(facet.getModule()), SingleNamespaceResourceRepository {
  private val myFacet: AndroidFacet

  private val myNamespace: ResourceNamespace

  init {
    myFacet = facet
    myNamespace = namespace
    setChildren(delegates, emptyList(), emptyList())
  }

  override fun getNamespace(): ResourceNamespace {
    return myNamespace
  }

  override fun getPackageName(): String {
    throw UnsupportedOperationException()
  }

  override fun getNamespaces(): Set<ResourceNamespace> {
    return setOf(namespace)
  }

  override fun getLeafResourceRepositories(): Collection<SingleNamespaceResourceRepository> {
    return listOf(this)
  }

  override fun toString(): String {
    return MoreObjects.toStringHelper(this)
      .toString()
  }

  companion object {
    /**
     * Creates a new resource repository for the given module, **not** including its dependent modules.
     *
     *
     * The returned repository needs to be registered with a [com.intellij.openapi.Disposable] parent.
     *
     * @param facet the facet for the module
     * @param namespace the namespace for the repository
     * @return the resource repository
     */
    fun forMainResources(
      facet: AndroidFacet,
      namespace: ResourceNamespace,
      resourceDirectories: List<String>,
    ): LocalResourceRepository {
      if (resourceDirectories.isEmpty()) {
        return EmptyRepository(namespace)
      }
      val childRepositories: MutableList<LocalResourceRepository> =
        ArrayList(resourceDirectories.size)
      addRepositoriesInReverseOverlayOrder(
        resourceDirectories,
        childRepositories,
        facet
      )
      return ModuleResourceRepository(
        facet,
        namespace,
        childRepositories,
      )
    }

    /**
     * Inserts repositories for the given `resourceDirectories` into `childRepositories`, in the right order.
     *
     *
     * `resourceDirectories` is assumed to be in the order returned from
     * [SourceProviderManager.getCurrentSourceProviders], which is the inverse of what we need. The code in
     * [MultiResourceRepository.getMap] gives priority to child repositories which are earlier
     * in the list, so after creating repositories for every folder, we add them in reverse to the list.
     *
     * @param resourceDirectories directories for which repositories should be constructed
     * @param childRepositories the list of repositories to which new repositories will be added
     * @param facet [AndroidFacet] that repositories will correspond to
     * @param resourceFolderRegistry [ResourceFolderRegistry] used to construct the repositories
     */
    private fun addRepositoriesInReverseOverlayOrder(
      resourceDirectories: List<String>,
      childRepositories: MutableList<LocalResourceRepository>,
      facet: AndroidFacet
    ) {
      var i = resourceDirectories.size
      while (--i >= 0) {
        val repository = ResourceFolderRepository()
        childRepositories.add(repository)
      }
    }
  }
}
