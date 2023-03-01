package app.cash.paparazzi.res


/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.android.ide.common.rendering.api.DensityBasedResourceValueImpl
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.ide.common.rendering.api.ResourceValue
import com.android.ide.common.rendering.api.ResourceValueImpl
import com.android.ide.common.resources.ResourceItem
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.ide.common.util.PathString
import com.android.resources.ResourceType
import com.android.resources.ResourceType.DRAWABLE
import com.android.resources.ResourceType.MIPMAP
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VfsUtilCore
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import java.io.File

class PsiResourceItem constructor(
  file: File,
  private val name: String,
  private val type: ResourceType,
  private val namespace: ResourceNamespace
) : ResourceItem {
  private val folderConfiguration: FolderConfiguration = FolderConfiguration.getConfigForFolder(file.parentFile.name)

  override fun getConfiguration(): FolderConfiguration {
    return folderConfiguration
  }

  override fun getName(): String {
    return name
  }

  override fun getType(): ResourceType {
    return type
  }

  override fun getNamespace(): ResourceNamespace {
    return namespace
  }

  override fun getLibraryName(): String {
    TODO("Not yet implemented")
  }

  override fun getReferenceToSelf(): ResourceReference {
    TODO("Not yet implemented")
  }

  override fun getKey(): String {
    TODO("Not yet implemented")
  }

  override fun getResourceValue(): ResourceValue? {
    if (myResourceValue == null) {
      val tag: XmlTag = getTag()
      if (tag == null) {
        val source: PsiResourceFile = getSourceFile()
          ?: error("getResourceValue called on a PsiResourceItem with no source")
        // Density based resource value?
        val type = getType()
        val density: Density? = if (type == DRAWABLE || type == MIPMAP) getFolderDensity() else null
        val virtualFile: VirtualFile = source.getVirtualFile()
        val path: String? =
          if (virtualFile == null) null else VfsUtilCore.virtualToIoFile(virtualFile)
            .getAbsolutePath()
        if (density != null) {
          myResourceValue =
            DensityBasedResourceValueImpl(getNamespace(), myType, myName, path, density, null)
        } else {
          myResourceValue = ResourceValueImpl(getNamespace(), myType, myName, path, null)
        }
      } else {
        myResourceValue = parseXmlToResourceValueSafe(tag)
      }
    }
    return myResourceValue
  }

  override fun getSource(): PathString {
    TODO("Not yet implemented")
  }

  override fun isFileBased(): Boolean {
    TODO("Not yet implemented")
  }
}

