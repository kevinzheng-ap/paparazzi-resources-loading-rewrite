// Copyright Square, Inc.
package app.cash.paparazzi.res

internal class AppResourceRepository private constructor(
  facet: AndroidFacet,
  localResources: List<LocalResourceRepository>,
  libraryResources: Collection<LocalResourceRepository>
) : MultiResourceRepository(facet.getModule() + " with modules and libraries") {
  private val myFacet: AndroidFacet

  init {
    myFacet = facet
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
