package app.cash.paparazzi.internal.pseudolocale

import app.cash.paparazzi.internal.PaparazziResourceItem
import com.android.ide.common.resources.SingleNamespaceResourceRepository
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.resources.ResourceType
import org.w3c.dom.Element
import java.io.File

internal class PaparazziPseudoResourceItem(
  file: File,
  name: String,
  type: ResourceType,
  repository: SingleNamespaceResourceRepository,
  tag: Element?
) : PaparazziResourceItem(file, name, type, repository, tag) {
  override val folderConfiguration: FolderConfiguration =
    FolderConfiguration.getConfigForFolder("value-en-rXA")

  override fun getTextContent(tag: Element): String {
    return super.getTextContent(tag).pseudoLocalize()
  }
}
