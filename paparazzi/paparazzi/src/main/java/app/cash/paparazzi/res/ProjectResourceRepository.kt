// Copyright Square, Inc.
package app.cash.paparazzi.res

import java.util.Collections

/**
 * @see StudioResourceRepositoryManager.getProjectResources
 */
internal class ProjectResourceRepository private constructor(
  facet: AndroidFacet,
  localResources: List<LocalResourceRepository>
) :
  MultiResourceRepository(facet.getModule() + " with modules") {
  private val myFacet: AndroidFacet

  init {
    myFacet = facet
    setChildren(localResources, emptyList(), emptyList())
  }

  companion object {
    fun create(facet: AndroidFacet): ProjectResourceRepository {
      val resources = computeRepositories(facet)
      return ProjectResourceRepository(facet, resources)
    }

    private fun computeRepositories(facet: AndroidFacet): List<LocalResourceRepository> {
      val main: LocalResourceRepository = StudioResourceRepositoryManager.getModuleResources(facet)

      // List of module facets the given module depends on.
      val dependencies: List<AndroidFacet> =
        AndroidDependenciesCache.getAndroidResourceDependencies(facet.getModule())
      if (dependencies.isEmpty()) {
        return Collections.singletonList(main)
      }
      val resources: MutableList<LocalResourceRepository> = ArrayList(dependencies.size + 1)
      resources.add(main)
      for (dependency in dependencies) {
        resources.add(StudioResourceRepositoryManager.getModuleResources(dependency))
      }
      return resources
    }
  }
}
