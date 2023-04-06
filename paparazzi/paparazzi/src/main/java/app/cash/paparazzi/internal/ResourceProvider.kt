package app.cash.paparazzi.internal

import com.android.ide.common.resources.ResourceValueMap
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.resources.ResourceType

interface ResourceProvider {

  fun getConfiguredResources(
    referenceConfig: FolderConfiguration
  ): Map<ResourceType, ResourceValueMap>
}
