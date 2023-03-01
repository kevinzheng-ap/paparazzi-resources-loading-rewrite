// Copyright Square, Inc.
package app.cash.paparazzi.res

import com.android.ide.common.rendering.api.ResourceNamespace
import java.util.Collections

/**
 * @see StudioResourceRepositoryManager.getProjectResources
 */
internal class ProjectResourceRepository private constructor(
  localResources: List<LocalResourceRepository>
) :
  MultiResourceRepository("with modules") {

  init {
    setChildren(localResources, emptyList(), emptyList())
  }

  companion object {
    fun create(
      resourceDirectories: List<String>,
      namespace: ResourceNamespace,
    ): ProjectResourceRepository {
      val resources = computeRepositories(resourceDirectories, namespace)
      return ProjectResourceRepository(resources)
    }

    private fun computeRepositories(
      resourceDirectories: List<String>,
      namespace: ResourceNamespace,
    ): List<LocalResourceRepository> {
      val resources: LocalResourceRepository =
        ModuleResourceRepository.forMainResources(namespace, resourceDirectories)

      // val main: LocalResourceRepository = StudioResourceRepositoryManager.getModuleResources(facet)
      //
      // // List of module facets the given module depends on.
      // val dependencies: List<AndroidFacet> =
      //   AndroidDependenciesCache.getAndroidResourceDependencies(facet.getModule())
      // if (dependencies.isEmpty()) {
      //   return Collections.singletonList(main)
      // }
      // val resources: MutableList<LocalResourceRepository> = ArrayList(dependencies.size + 1)
      // resources.add(main)
      // for (dependency in dependencies) {
      //   resources.add(StudioResourceRepositoryManager.getModuleResources(dependency))
      // }
      return listOf(resources)
    }
  }
}
