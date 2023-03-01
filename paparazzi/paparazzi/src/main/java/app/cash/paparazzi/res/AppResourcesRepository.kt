// Copyright Square, Inc.
package app.cash.paparazzi.res

internal class AppResourceRepository private constructor(
  localResources: List<LocalResourceRepository>,
  libraryResources: Collection<LocalResourceRepository>
) : MultiResourceRepository(" with modules and libraries") {

  init {
    setChildren(
      localResources,
      libraryResources,
      emptyList()
    )
  }

  companion object {
    // fun create(
    //   facet: AndroidFacet,
    // ): AppResourceRepository = AppResourceRepository(facet, computeLocalRepositories(facet), emptyList())
    //
    // private fun computeLocalRepositories(facet: AndroidFacet): List<LocalResourceRepository> {
    //   return listOf(
    //     ProjectResourceRepository.create(facet),
    //   )
    // }
  }
}
