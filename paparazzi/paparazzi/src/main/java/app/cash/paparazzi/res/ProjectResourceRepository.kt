// Copyright Square, Inc.
package app.cash.paparazzi.res

import com.android.ide.common.rendering.api.ResourceNamespace

internal class ProjectResourceRepository private constructor(
  localResources: List<LocalResourceRepository>,
  libraryResources: List<LocalResourceRepository>
) : MultiResourceRepository() {

  init {
    setChildren(localResources, libraryResources, emptyList())
  }

  companion object {
    fun create(
      localResourceDirs: List<String>,
      libraryResourceDirs: List<String> = emptyList(),
      namespace: ResourceNamespace,
    ): ProjectResourceRepository {
      val localeResources = localResourceDirs.toResources(namespace)
      val libraryResources = libraryResourceDirs.toResources(namespace)
      return ProjectResourceRepository(localeResources, libraryResources)
    }

    private fun List<String>.toResources(
      namespace: ResourceNamespace,
    ): List<LocalResourceRepository> = if (isEmpty()) {
      listOf(EmptyRepository(namespace))
    } else {
      map { ResourceFolderRepository(it, namespace) }
    }
  }
}
