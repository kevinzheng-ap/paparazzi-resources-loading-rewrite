// Copyright Square, Inc.
package app.cash.paparazzi.res

import com.android.ide.common.rendering.api.ResourceNamespace
import java.util.Collections

internal class ProjectResourceRepository private constructor(
  localResources: List<LocalResourceRepository>
) : MultiResourceRepository("with modules") {

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
      return listOf(resources)
    }
  }
}
