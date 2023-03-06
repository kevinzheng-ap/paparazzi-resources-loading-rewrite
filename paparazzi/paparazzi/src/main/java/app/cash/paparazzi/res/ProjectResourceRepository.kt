// Copyright Square, Inc.
package app.cash.paparazzi.res

import com.android.ide.common.rendering.api.ResourceNamespace

internal class ProjectResourceRepository private constructor(
  localResources: List<LocalResourceRepository>
) : MultiResourceRepository() {

  init {
    setChildren(localResources, emptyList(), emptyList())
  }

  companion object {
    fun create(
      localResourceDirs: List<String>,
      namespace: ResourceNamespace,
    ): ProjectResourceRepository {
      val localeResources = if (localResourceDirs.isEmpty()) {
        listOf(EmptyRepository(namespace))
      } else {
        localResourceDirs.map { ResourceFolderRepository(it, namespace) }
      }
      return ProjectResourceRepository(localeResources)
    }
  }
}
