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
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.ide.common.rendering.api.ResourceValue
import com.android.ide.common.resources.ResourceItem
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.ide.common.util.PathString
import com.android.resources.ResourceFolderType
import com.android.resources.ResourceType
import java.io.File


class PsiResourceItem private constructor(
  file: File
) : ResourceItem {
  val myName: String = file.name
  val folderType: ResourceFolderType = ResourceFolderType.getFolderType(file.parent)
  val folderConfiguration: FolderConfiguration = FolderConfiguration.getConfigForFolder(file.parent)

  override fun getConfiguration(): FolderConfiguration {
    return folderConfiguration!!
  }

  override fun getName(): String {
    return myName
  }

  override fun getType(): ResourceType {
    TODO("Not yet implemented")
  }

  override fun getNamespace(): ResourceNamespace {
    TODO("Not yet implemented")
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

  override fun getResourceValue(): ResourceValue {
    TODO("Not yet implemented")
  }

  override fun getSource(): PathString {
    TODO("Not yet implemented")
  }

  override fun isFileBased(): Boolean {
    TODO("Not yet implemented")
  }

//  private var myResourceValue: ResourceValue? = null
//  private var mySourceFile: PsiResourceFile? = null
//  private val myFilePointer: SmartPsiElementPointer<PsiFile>?
//  private val myTagPointer: SmartPsiElementPointer<XmlTag>?
//
//  /**
//   * This weak reference is kept exclusively for the [.wasTag] method. Once the original
//   * tag is garbage collected, the [.wasTag] method will return false for any tag except
//   * the one pointed to by [.myTagPointer].
//   */
//  private val myOriginalTag: WeakReference<XmlTag>?
//
//  init {
//    val pointerManager: SmartPointerManager = SmartPointerManager.getInstance(file.getProject())
//    myFilePointer = pointerManager.createSmartPsiElementPointer<PsiFile>(file)
//    myTagPointer =
//      if (tag == null) null else pointerManager.createSmartPsiElementPointer<XmlTag>(tag, file)
//    myOriginalTag = if (tag == null) null else WeakReference<XmlTag>(tag)
//  }
//
//  override fun getName(): String {
//    return myName
//  }
//
//  override fun getType(): ResourceType {
//    return myType
//  }
//
//  override fun getRepository(): SingleNamespaceResourceRepository? {
//    return myOwner
//  }
//
//  override fun getNamespace(): ResourceNamespace {
//    return myOwner.namespace
//  }
//
//  override fun getLibraryName(): String? {
//    return null
//  }
//
//  override fun getReferenceToSelf(): ResourceReference {
//    return ResourceReference(namespace, myType, myName)
//  }
//
//  override fun getConfiguration(): FolderConfiguration {
//    val source: PsiResourceFile = sourceFile
//      ?: error("getConfiguration called on a PsiResourceItem with no source")
//    return source.getFolderConfiguration()
//  }
//
//  override fun getKey(): String {
//    val qualifiers = configuration.qualifierString
//    return if (!qualifiers.isEmpty()) {
//      myType.getName() + '-' + qualifiers + '/' + myName
//    } else myType.getName() + '/' + myName
//  }
//
//  // PsiResourceFile constructor sets the source of this item.
//  var sourceFile: PsiResourceFile?
//    get() {
//      if (mySourceFile != null) {
//        return mySourceFileC
//      }
//      val psiFile: PsiFile = psiFile ?: return null
//      val parent: PsiElement =
//        AndroidPsiUtils.getPsiParentSafely(psiFile) as? PsiDirectory ?: return null
//      val name: String = (parent as PsiDirectory).getName()
//      val folderType = ResourceFolderType.getFolderType(name) ?: return null
//      val folderConfiguration = FolderConfiguration.getConfigForFolder(name) ?: return null
//
//      // PsiResourceFile constructor sets the source of this item.
//      return PsiResourceFile(
//        psiFile,
//        ImmutableList.of(this),
//        folderType,
//        RepositoryConfiguration(myOwner, folderConfiguration)
//      )
//    }
//    set(sourceFile) {
//      mySourceFile = sourceFile
//    }
//
//  /**
//   * GETTER WITH SIDE EFFECTS that registers we have taken an interest in this value
//   * so that if the value changes we will get a resource changed event fire.
//   */
//  override fun getResourceValue(): ResourceValue? {
//    if (myResourceValue == null) {
//      val tag: XmlTag? = tag
//      myResourceValue = if (tag == null) {
//        val source: PsiResourceFile = sourceFile
//          ?: error("getResourceValue called on a PsiResourceItem with no source")
//        // Density based resource value?
//        val type = type
//        val density = if (type == DRAWABLE || type == MIPMAP) folderDensity else null
//        val virtualFile: VirtualFile = source.getVirtualFile()
//        val path: String? =
//          if (virtualFile == null) null else VfsUtilCore.virtualToIoFile(virtualFile)
//            .getAbsolutePath()
//        if (density != null) {
//          DensityBasedResourceValueImpl(namespace, myType, myName, path, density, null)
//        } else {
//          ResourceValueImpl(namespace, myType, myName, path, null)
//        }
//      } else {
//        parseXmlToResourceValueSafe(tag)
//      }
//    }
//    return myResourceValue
//  }
//
//  override fun getSource(): PathString? {
//    val psiFile: PsiFile = psiFile ?: return null
//    val virtualFile: VirtualFile = psiFile.getVirtualFile()
//    return if (virtualFile == null) null else PathString(VfsUtilCore.virtualToIoFile(virtualFile))
//  }
//
//  override fun isFileBased(): Boolean {
//    return myTagPointer == null
//  }
//
//  private val folderDensity: Density?
//    private get() {
//      val configuration = configuration
//      val densityQualifier = configuration.densityQualifier
//      return densityQualifier?.value
//    }
//
//  private fun parseXmlToResourceValueSafe(tag: XmlTag?): ResourceValue? {
//    val application: Application = ApplicationManager.getApplication()
//    return if (application.isReadAccessAllowed()) {
//      parseXmlToResourceValue(tag)
//    } else application.runReadAction(Computable<ResourceValue> { parseXmlToResourceValue(tag) } as Computable<ResourceValue?>?)
//  }
//
//  private fun parseXmlToResourceValue(tag: XmlTag?): ResourceValue? {
//    if (tag == null || !tag.isValid()) {
//      return null
//    }
//    val value: ResourceValueImpl
//    value = when (myType) {
//      STYLE -> {
//        val parent = getAttributeValue(tag, SdkConstants.ATTR_PARENT)
//        parseStyleValue(
//          tag, StyleResourceValueImpl(
//            namespace, myName, parent, null
//          )
//        )
//      }
//      STYLEABLE -> parseDeclareStyleable(
//        tag, StyleableResourceValueImpl(
//          namespace, myName, null, null
//        )
//      )
//      ATTR -> parseAttrValue(
//        tag, AttrResourceValueImpl(
//          namespace, myName, null
//        )
//      )
//      ARRAY -> parseArrayValue(tag, object : ArrayResourceValueImpl(
//        namespace, myName, null
//      ) {
//        // Allow the user to specify a specific element to use via tools:index
//        override fun getDefaultIndex(): Int {
//          val index: String = ReadAction.compute {
//            tag.getAttributeValue(
//              SdkConstants.ATTR_INDEX,
//              SdkConstants.TOOLS_URI
//            )
//          }
//          return index?.toInt() ?: super.getDefaultIndex()
//        }
//      })
//      PLURALS -> parsePluralsValue(tag, object : PluralsResourceValueImpl(
//        namespace, myName, null, null
//      ) {
//        // Allow the user to specify a specific quantity to use via tools:quantity
//        override fun getValue(): String {
//          val quantity: String = ReadAction.compute {
//            tag.getAttributeValue(
//              SdkConstants.ATTR_QUANTITY,
//              SdkConstants.TOOLS_URI
//            )
//          }
//          if (quantity != null) {
//            val value = getValue(quantity)
//            if (value != null) {
//              return value
//            }
//          }
//          return super.getValue()
//        }
//      })
//      STRING -> parseTextValue(
//        tag, PsiTextResourceValue(
//          namespace, myName, null, null, null
//        )
//      )
//      else -> parseValue(tag, ResourceValueImpl(namespace, myType, myName, null))
//    }
//    value.namespaceResolver = IdeResourcesUtil.getNamespaceResolver(tag)
//    return value
//  }
//
//  private fun parseDeclareStyleable(
//    tag: XmlTag,
//    declareStyleable: StyleableResourceValueImpl
//  ): StyleableResourceValueImpl {
//    for (child in tag.getSubTags()) {
//      val name = getAttributeValue(child, SdkConstants.ATTR_NAME)
//      if (!StringUtil.isEmpty(name)) {
//        val url = ResourceUrl.parseAttrReference(name)
//        if (url != null) {
//          val resolvedAttr = url.resolve(
//            namespace, IdeResourcesUtil.getNamespaceResolver(tag)
//          )
//          if (resolvedAttr != null) {
//            val attr: AttrResourceValue =
//              parseAttrValue(child, AttrResourceValueImpl(resolvedAttr, null))
//            declareStyleable.addValue(attr)
//          }
//        }
//      }
//    }
//    return declareStyleable
//  }
//
//  /**
//   * The returned [XmlTag] element is guaranteed to be valid if it is not null.
//   */
//  val tag: XmlTag?
//    get() = validElementOrNull(if (myTagPointer == null) null else myTagPointer.getElement())
//
//  /**
//   * The returned [PsiFile] object is guaranteed to be valid if it is not null.
//   */
//  val psiFile: PsiFile?
//    get() = validElementOrNull(if (myFilePointer == null) null else myFilePointer.getElement())
//
//  /**
//   * Returns true if this [PsiResourceItem] was originally or is currently pointing to the given tag.
//   */
//  fun wasTag(tag: XmlTag): Boolean {
//    return myOriginalTag != null && tag === myOriginalTag.get() || tag === this.tag
//  }
//
//  /** Clears the cached value, if any, and returns true if the value was cleared.  */
//  fun recomputeValue(): Boolean {
//    if (myResourceValue == null) {
//      return false
//    }
//
//    // Force recompute in getResourceValue.
//    myResourceValue = null
//    return true
//  }
//
//  override fun equals(o: Any?): Boolean {
//    // Only reference equality; we need to be able to distinguish duplicate elements which can
//    // happen during editing for incremental updating to handle temporarily aliasing items.
//    return this === o
//  }
//
//  override fun hashCode(): Int {
//    return myName.hashCode()
//  }
//
//  override fun toString(): String {
//    val helper = MoreObjects.toStringHelper(this)
//      .add("name", myName)
//      .add("namespace", namespace)
//      .add("type", myType)
//    val tag: XmlTag? = tag
//    if (tag != null) {
//      if (ApplicationManager.getApplication().isReadAccessAllowed()) {
//        helper.add("tag", IdeResourcesUtil.getTextContent(tag))
//      } else {
//        helper.add("tag", ReadAction.compute { IdeResourcesUtil.getTextContent(tag) })
//      }
//    }
//    val file = source
//    if (file != null) {
//      helper.add("file", file.parentFileName + '/' + file.fileName)
//    }
//    return helper.toString()
//  }
//
//  private inner class PsiTextResourceValue internal constructor(
//    namespace: ResourceNamespace, name: String,
//    textValue: String?, rawXmlValue: String?, libraryName: String?
//  ) :
//    TextResourceValueImpl(namespace, name, textValue, rawXmlValue, libraryName) {
//    override fun getRawXmlValue(): String {
//      return ReadAction.compute {
//        val tag: XmlTag = tag
//        if (tag == null || !tag.isValid()) {
//          return@compute value
//        }
//        tag.getValue().getText()
//      }
//    }
//  }
//
//  companion object {
//    /**
//     * Creates a new PsiResourceItem for a given [XmlTag].
//     *
//     * @param name the name of the resource
//     * @param type the type of the resource
//     * @param owner the owning resource repository
//     * @param tag the XML tag to create the resource from
//     */
//    fun forXmlTag(
//      name: String,
//      type: ResourceType,
//      owner: ResourceFolderRepository,
//      tag: XmlTag
//    ): PsiResourceItem {
//      return PsiResourceItem(name, type, owner, tag, tag.getContainingFile())
//    }
//
//    /**
//     * Creates a new PsiResourceItem for a given [PsiFile].
//     *
//     * @param name the name of the resource
//     * @param type the type of the resource
//     * @param owner the owning resource repository
//     * @param file the XML file to create the resource from
//     */
//    fun forFile(
//      name: String,
//      type: ResourceType,
//      owner: ResourceFolderRepository,
//      file: PsiFile
//    ): PsiResourceItem {
//      return PsiResourceItem(name, type, owner, null, file)
//    }
//
//    private fun getAttributeValue(tag: XmlTag, attributeName: String): String? {
//      return tag.getAttributeValue(attributeName)
//    }
//
//    private fun parseStyleValue(
//      tag: XmlTag,
//      styleValue: StyleResourceValueImpl
//    ): StyleResourceValueImpl {
//      for (child in tag.getSubTags()) {
//        val name = getAttributeValue(child, SdkConstants.ATTR_NAME)
//        if (!StringUtil.isEmpty(name)) {
//          val value = ValueXmlHelper.unescapeResourceString(
//            IdeResourcesUtil.getTextContent(child),
//            true,
//            true
//          )
//          val itemValue =
//            StyleItemResourceValueImpl(styleValue.namespace, name, value, styleValue.libraryName)
//          itemValue.namespaceResolver = IdeResourcesUtil.getNamespaceResolver(child)
//          styleValue.addItem(itemValue)
//        }
//      }
//      return styleValue
//    }
//
//    private fun parseAttrValue(
//      attrTag: XmlTag,
//      attrValue: AttrResourceValueImpl
//    ): AttrResourceValueImpl {
//      attrValue.description = getDescription(attrTag)
//      val formats: MutableSet<AttributeFormat> = EnumSet.noneOf(
//        AttributeFormat::class.java
//      )
//      val formatString = getAttributeValue(attrTag, SdkConstants.ATTR_FORMAT)
//      if (formatString != null) {
//        formats.addAll(AttributeFormat.parse(formatString))
//      }
//      for (child in attrTag.getSubTags()) {
//        val tagName: String = child.getName()
//        if (SdkConstants.TAG_ENUM == tagName) {
//          formats.add(ENUM)
//        } else if (SdkConstants.TAG_FLAG == tagName) {
//          formats.add(FLAGS)
//        }
//        val name = getAttributeValue(child, SdkConstants.ATTR_NAME)
//        if (name != null) {
//          var numericValue: Int? = null
//          val value = getAttributeValue(child, SdkConstants.ATTR_VALUE)
//          if (value != null) {
//            try {
//              // Use Long.decode to deal with hexadecimal values greater than 0x7FFFFFFF.
//              numericValue = java.lang.Long.decode(value).toInt()
//            } catch (ignored: NumberFormatException) {
//            }
//          }
//          attrValue.addValue(name, numericValue, getDescription(child))
//        }
//      }
//      attrValue.setFormats(formats)
//      return attrValue
//    }
//
//    private fun getDescription(tag: XmlTag): String? {
//      val comment: XmlComment = XmlUtil.findPreviousComment(tag)
//      if (comment != null) {
//        val text: String = comment.getCommentText()
//        return text.trim { it <= ' ' }
//      }
//      return null
//    }
//
//    private fun parseArrayValue(
//      tag: XmlTag,
//      arrayValue: ArrayResourceValueImpl
//    ): ArrayResourceValueImpl {
//      for (child in tag.getSubTags()) {
//        val text =
//          ValueXmlHelper.unescapeResourceString(IdeResourcesUtil.getTextContent(child), true, true)
//        arrayValue.addElement(text)
//      }
//      return arrayValue
//    }
//
//    private fun parsePluralsValue(
//      tag: XmlTag,
//      value: PluralsResourceValueImpl
//    ): PluralsResourceValueImpl {
//      for (child in tag.getSubTags()) {
//        val quantity: String = child.getAttributeValue(SdkConstants.ATTR_QUANTITY)
//        if (quantity != null) {
//          val text = ValueXmlHelper.unescapeResourceString(
//            IdeResourcesUtil.getTextContent(child),
//            true,
//            true
//          )
//          value.addPlural(quantity, text)
//        }
//      }
//      return value
//    }
//
//    private fun parseValue(tag: XmlTag, value: ResourceValueImpl): ResourceValueImpl {
//      var text: String? = IdeResourcesUtil.getTextContent(tag)
//      text = ValueXmlHelper.unescapeResourceString(text, true, true)
//      value.value = text
//      return value
//    }
//
//    private fun parseTextValue(tag: XmlTag, value: PsiTextResourceValue): PsiTextResourceValue {
//      var text: String? = IdeResourcesUtil.getTextContent(tag)
//      text = ValueXmlHelper.unescapeResourceString(text, true, true)
//      value.value = text
//      return value
//    }
//
//    private fun <E : PsiElement?> validElementOrNull(psiElement: E?): E? {
//      return if (psiElement == null || !psiElement.isValid()) null else psiElement
//    }
//  }
}
