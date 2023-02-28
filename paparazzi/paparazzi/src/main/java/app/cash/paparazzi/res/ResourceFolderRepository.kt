package app.cash.paparazzi.res

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.ResourceItem
import com.android.resources.ResourceType
import com.google.common.collect.ListMultimap

/**
 * The [ResourceFolderRepository] is leaf in the repository tree, and is used for user editable resources (e.g. the resources in the
 * project, typically the res/main source set.) Each ResourceFolderRepository contains the resources provided by a single res folder. This
 * repository is built on top of IntelliJ’s PSI infrastructure. This allows it (along with PSI listeners) to be updated incrementally; for
 * example, when it notices that the user is editing the value inside a <string> element in a value folder XML file, it will directly update
 * the resource value for the given resource item, and so on.
 *
 *
 * For efficiency, the ResourceFolderRepository is initialized using non-PSI parsers and then
 * lazily switches to PSI parsers after edits. See also `README.md` in this package.
 *
 *
 * Remaining work:
 *
 *  * Find some way to have event updates in this resource folder directly update parent repositories
 * (typically [ModuleResourceRepository])
 *  * Add defensive checks for non-read permission reads of resource values
 *  * Idea: For [.scheduleScan]; compare the removed items from the added items, and if they're the same, avoid
 * creating a new generation.
 *  * Register the PSI project listener as a project service instead.
 *
</string> */
class ResourceFolderRepository private constructor(
  @field:NotNull
  /**
   * Returns the AndroidFacet of the module containing the resource folder.
   */
  @get:NotNull @param:NotNull val facet: AndroidFacet,
  @NotNull resourceDir: VirtualFile,
  @NotNull namespace: ResourceNamespace,
  @Nullable cachingData: ResourceFolderRepositoryCachingData
) : LocalResourceRepository(resourceDir.getName()) {

  @NotNull
  private val myPsiListener: PsiTreeChangeListener

  @NotNull
  private val myResourceDir: VirtualFile

  @NotNull
  private val myNamespace: ResourceNamespace

  /**
   * Common prefix of paths of all file resources.  Used to compose resource paths returned by
   * the [BasicFileResourceItem.getSource] method.
   */
  @NotNull
  private val myResourcePathPrefix: String

  /**
   * Same as [.myResourcePathPrefix] but in a form of [PathString].  Used to produce
   * resource paths returned by the [BasicFileResourceItem.getOriginalSource] method.
   */
  @NotNull
  private val myResourcePathBase: PathString

  // Statistics of the initial repository loading.
  @get:TestOnly var numXmlFilesLoadedInitially // Doesn't count files that were explicitly skipped.
    = 0
    private set
  @get:TestOnly var numXmlFilesLoadedInitiallyFromSources = 0
    private set

  @GuardedBy("ITEM_MAP_LOCK") @NotNull
  private val myResourceTable: Map<ResourceType, ListMultimap<String, ResourceItem>> = EnumMap(
    ResourceType::class.java
  )

  @NotNull
  private val mySources: ConcurrentMap<VirtualFile, ResourceItemSource<*>> = ConcurrentHashMap()

  @NotNull
  private val myPsiManager: PsiManager

  @NotNull
  private val myPsiNameHelper: PsiNameHelper

  @NotNull
  private val myWolfTheProblemSolver: WolfTheProblemSolver

  @NotNull
  private val myPsiDocumentManager: PsiDocumentManager

  // Repository updates have to be applied in FIFO order to produce correct results.
  @NotNull
  private val updateExecutor: ExecutorService =
    AppExecutorUtil.createBoundedApplicationPoolExecutor("ResourceFolderRepository", 1)

  @GuardedBy("updateQueue") @NotNull
  private val updateQueue: Deque<Runnable> = ArrayDeque()

  @NotNull
  private val scanLock = Any()

  @GuardedBy("scanLock") @NotNull
  private val pendingScans: Set<VirtualFile> = HashSet()

  @GuardedBy("scanLock") @NotNull
  private val runningScans: HashMap<VirtualFile, ProgressIndicator> = HashMap()
  @get:VisibleForTesting var fileRescans = 0
    private set
  @get:VisibleForTesting var layoutlibCacheFlushes = 0
    private set

  init {
    myResourceDir = resourceDir
    myNamespace = namespace
    myResourcePathPrefix = portableFileName(myResourceDir.getPath()) + '/'
    myResourcePathBase = PathString(myResourcePathPrefix)
    myPsiManager = PsiManager.getInstance(project)
    myPsiDocumentManager = PsiDocumentManager.getInstance(project)
    myPsiNameHelper = PsiNameHelper.getInstance(project)
    myWolfTheProblemSolver = WolfTheProblemSolver.getInstance(project)
    val psiListener: PsiTreeChangeListener = IncrementalUpdatePsiListener()
    myPsiListener =
      if (LOG.isDebugEnabled()) LoggingPsiTreeChangeListener(psiListener, LOG) else psiListener
    val loader = Loader(this, cachingData)
    loader.load()
    Disposer.register(facet, updateExecutor::shutdownNow)
    ResourceUpdateTracer.logDirect {
      TraceUtils.getSimpleId(this) + " " + pathForLogging(resourceDir) + " created for module " + facet.getModule()
        .getName()
    }
  }

  @get:NotNull val resourceDir: VirtualFile
    get() = myResourceDir

  // Resource folder is not a library.
  @get:Nullable val libraryName: String?
    get() = null // Resource folder is not a library.
  @get:NotNull val origin: Path
    get() = Paths.get(myResourceDir.getPath())

  @NotNull fun getResourceUrl(@NotNull relativeResourcePath: String): String {
    return myResourcePathPrefix + relativeResourcePath
  }

  @NotNull fun getSourceFile(
    @NotNull relativeResourcePath: String?,
    forFileResource: Boolean
  ): PathString {
    return myResourcePathBase.resolve(relativeResourcePath)
  }

  @get:Nullable val packageName: String
    get() = ResourceRepositoryImplUtil.getPackageName(myNamespace, facet)

  fun containsUserDefinedResources(): Boolean {
    return true
  }

  /**
   * Inserts the given resources into this repository, while holding the global repository lock.
   */
  private fun commitToRepository(@NotNull itemsByType: Map<ResourceType, ListMultimap<String, ResourceItem>>) {
    if (!itemsByType.isEmpty()) {
      synchronized(ITEM_MAP_LOCK) { commitToRepositoryWithoutLock(itemsByType) }
    }
  }

  /**
   * Inserts the given resources into this repository without acquiring any locks. Safe to call only while
   * holding [.ITEM_MAP_LOCK] or during construction of ResourceFolderRepository.
   */
  private fun commitToRepositoryWithoutLock(@NotNull itemsByType: Map<ResourceType, ListMultimap<String, ResourceItem>>) {
    ResourceUpdateTracer.log { getSimpleId(this) + ".commitToRepositoryWithoutLock" }
    for (entry in itemsByType.entrySet()) {
      if (ResourceUpdateTracer.isTracingActive()) {
        for (item in entry.getValue().values()) {
          ResourceUpdateTracer.log { getSimpleId(this) + ": Committing " + item.getType() + '/' + item.getName() }
        }
      }
      val map: ListMultimap<String, ResourceItem> = getOrCreateMap(entry.getKey())
      map.putAll(entry.getValue())
      // Dump resource trace if some strings exist only in non-default locale.
      // Such situation may happen either due to use action, or due to a missed resource update.
      if (ResourceUpdateTracer.isTracingActive() && entry.getKey() === ResourceType.STRING) {
        for (name in entry.getValue().keySet()) {
          val items: List<ResourceItem> = map.get(name)
          if (!items.isEmpty()) {
            val item: ResourceItem = items[0]
            val configuration: FolderConfiguration = item.getConfiguration()
            if (configuration.getLocaleQualifier() != null) {
              ResourceUpdateTracer.dumpTrace(
                "Resource " + item.getReferenceToSelf()
                  .getResourceUrl() + " is missing in the default locale"
              )
              break
            }
          }
        }
      }
    }
  }

  /**
   * Determines if it's unnecessary to write or update the file-backed cache.
   * If only a few items were reparsed, then the cache is fresh enough.
   *
   * @return true if this repo is backed by a fresh file cache
   */
  @VisibleForTesting fun hasFreshFileCache(): Boolean {
    return numXmlFilesLoadedInitiallyFromSources <= numXmlFilesLoadedInitially * CACHE_STALENESS_THRESHOLD
  }

  @Nullable private fun ensureValid(@NotNull psiFile: PsiFile?): PsiFile? {
    if (psiFile.isValid()) {
      return psiFile
    }
    val virtualFile: VirtualFile = psiFile.getVirtualFile()
    return if (virtualFile != null && virtualFile.exists() && !project.isDisposed()) {
      myPsiManager.findFile(virtualFile)
    } else null
  }

  private fun scanFileResourceFileAsPsi(
    @NotNull file: PsiFile,
    @NotNull folderType: ResourceFolderType,
    @NotNull folderConfiguration: FolderConfiguration,
    @NotNull type: ResourceType,
    idGenerating: Boolean,
    @NotNull result: Map<ResourceType, ListMultimap<String, ResourceItem>>
  ) {
    // XML or image.
    val resourceName: String = SdkUtils.fileNameToResourceName(file.getName())
    if (!checkResourceFilename(file, folderType)) {
      return  // Not a valid file resource name.
    }
    val configuration = RepositoryConfiguration(this, folderConfiguration)
    val item: PsiResourceItem = PsiResourceItem.forFile(resourceName, type, this, file)
    if (idGenerating) {
      val items: List<PsiResourceItem> = ArrayList()
      items.add(item)
      addToResult(item, result)
      addIds(file, items, result)
      val resourceFile = PsiResourceFile(file, items, folderType, configuration)
      mySources.put(file.getVirtualFile(), resourceFile)
    } else {
      val resourceFile =
        PsiResourceFile(file, Collections.singletonList(item), folderType, configuration)
      mySources.put(file.getVirtualFile(), resourceFile)
      addToResult(item, result)
    }
  }

  @NotNull override fun accept(@NotNull visitor: ResourceVisitor): ResourceVisitor.VisitResult {
    if (visitor.shouldVisitNamespace(myNamespace)) {
      synchronized(ITEM_MAP_LOCK) {
        if (acceptByResources(myResourceTable, visitor) == ResourceVisitor.VisitResult.ABORT) {
          return ResourceVisitor.VisitResult.ABORT
        }
      }
    }
    return ResourceVisitor.VisitResult.CONTINUE
  }

  @GuardedBy("ITEM_MAP_LOCK") @Nullable override fun getMap(
    @NotNull namespace: ResourceNamespace,
    @NotNull type: ResourceType
  ): ListMultimap<String, ResourceItem>? {
    return if (!namespace.equals(myNamespace)) {
      null
    } else myResourceTable[type]
  }

  @GuardedBy("ITEM_MAP_LOCK") @NotNull
  private fun getOrCreateMap(@NotNull type: ResourceType): ListMultimap<String, ResourceItem> {
    // Use LinkedListMultimap to preserve ordering for editors that show original order.
    return myResourceTable.computeIfAbsent(type) { k -> LinkedListMultimap.create() }
  }

  @get:NotNull val namespace: ResourceNamespace
    get() = myNamespace

  private fun addIds(
    @NotNull element: PsiElement,
    @NotNull items: List<PsiResourceItem>,
    @NotNull result: Map<ResourceType, ListMultimap<String, ResourceItem>>
  ) {
    if (element is XmlTag) {
      addIds(element as XmlTag, items, result)
    }
    val xmlTags: Collection<XmlTag> = PsiTreeUtil.findChildrenOfType(element, XmlTag::class.java)
    for (tag in xmlTags) {
      addIds(tag, items, result)
    }
  }

  private fun addIds(
    @NotNull tag: XmlTag,
    @NotNull items: List<PsiResourceItem>,
    @NotNull result: Map<ResourceType, ListMultimap<String, ResourceItem>>
  ) {
    assert(tag.isValid())
    for (attribute in tag.getAttributes()) {
      val id = createIdNameFromAttribute(attribute)
      if (id != null) {
        val item: PsiResourceItem =
          PsiResourceItem.forXmlTag(id, ResourceType.ID, this, attribute.getParent())
        items.add(item)
        addToResult(item, result)
      }
    }
  }

  /**
   * If the attribute value has the form "@+id/ *name*" and the *name* part is a valid
   * resource name, returns it. Otherwise, returns null.
   */
  @Nullable private fun createIdNameFromAttribute(@NotNull attribute: XmlAttribute): String? {
    val attributeValue: String = StringUtil.notNullize(attribute.getValue()).trim()
    if (attributeValue.startsWith(NEW_ID_PREFIX) && !attribute.getNamespace().equals(TOOLS_URI)) {
      val id: String = attributeValue.substring(NEW_ID_PREFIX.length())
      if (isValidValueResourceName(id)) {
        return id
      }
    }
    return null
  }

  private fun scanValueFileAsPsi(
    @NotNull result: Map<ResourceType, ListMultimap<String, ResourceItem>>,
    @NotNull file: PsiFile, @NotNull folderConfiguration: FolderConfiguration
  ): Boolean {
    var added = false
    val fileType: FileType = file.getFileType()
    if (fileType === XmlFileType.INSTANCE) {
      val xmlFile: XmlFile = file as XmlFile
      assert(xmlFile.isValid())
      val document: XmlDocument = xmlFile.getDocument()
      if (document != null) {
        val root: XmlTag = document.getRootTag() ?: return false
        if (!root.getName().equals(TAG_RESOURCES)) {
          return false
        }
        val subTags: Array<XmlTag> = root.getSubTags() // Not recursive, right?
        val items: List<PsiResourceItem> = ArrayList(subTags.size)
        for (tag in subTags) {
          ProgressManager.checkCanceled()
          val name: String = tag.getAttributeValue(ATTR_NAME)
          val type: ResourceType = getResourceTypeForResourceTag(tag)
          if (type != null && isValidValueResourceName(name)) {
            val item: PsiResourceItem = PsiResourceItem.forXmlTag(name, type, this, tag)
            addToResult(item, result)
            items.add(item)
            added = true
            if (type === ResourceType.STYLEABLE) {
              // For styleables we also need to create attr items for its children.
              val attrs: Array<XmlTag> = tag.getSubTags()
              if (attrs.size > 0) {
                for (child in attrs) {
                  val attrName: String = child.getAttributeValue(ATTR_NAME)
                  if (isValidValueResourceName(attrName) && !attrName.startsWith(
                      ANDROID_NS_NAME_PREFIX
                    ) // Only add attr nodes for elements that specify a format or have flag/enum children; otherwise
                    // it's just a reference to an existing attr.
                    && (child.getAttribute(ATTR_FORMAT) != null || child.getSubTags().length > 0)
                  ) {
                    val attrItem: PsiResourceItem =
                      PsiResourceItem.forXmlTag(attrName, ResourceType.ATTR, this, child)
                    items.add(attrItem)
                    addToResult(attrItem, result)
                  }
                }
              }
            }
          }
        }
        val resourceFile =
          PsiResourceFile(file, items, VALUES, RepositoryConfiguration(this, folderConfiguration))
        mySources.put(file.getVirtualFile(), resourceFile)
      }
    }
    return added
  }

  private fun checkResourceFilename(
    @NotNull file: PathString,
    @NotNull folderType: ResourceFolderType
  ): Boolean {
    if (FileResourceNameValidator.getErrorTextForFileResource(
        file.getFileName(),
        folderType
      ) != null
    ) {
      val virtualFile: VirtualFile = FileExtensions.toVirtualFile(file)
      if (virtualFile != null) {
        myWolfTheProblemSolver.reportProblemsFromExternalSource(virtualFile, this)
      }
    }
    return myPsiNameHelper.isIdentifier(SdkUtils.fileNameToResourceName(file.getFileName()))
  }

  private fun checkResourceFilename(
    @NotNull file: PsiFile,
    @NotNull folderType: ResourceFolderType
  ): Boolean {
    if (FileResourceNameValidator.getErrorTextForFileResource(file.getName(), folderType) != null) {
      val virtualFile: VirtualFile = file.getVirtualFile()
      if (virtualFile != null) {
        myWolfTheProblemSolver.reportProblemsFromExternalSource(virtualFile, this)
      }
    }
    return myPsiNameHelper.isIdentifier(SdkUtils.fileNameToResourceName(file.getName()))
  }

  /**
   * Returns true if the given element represents a resource folder
   * (e.g. res/values-en-rUS or layout-land, *not* the root res/ folder).
   */
  private fun isResourceFolder(@NotNull virtualFile: VirtualFile): Boolean {
    if (virtualFile.isDirectory()) {
      val parentDirectory: VirtualFile = virtualFile.getParent()
      if (parentDirectory != null) {
        return parentDirectory.equals(myResourceDir)
      }
    }
    return false
  }

  private fun isResourceFile(@NotNull virtualFile: VirtualFile): Boolean {
    val parent: VirtualFile = virtualFile.getParent()
    return parent != null && isResourceFolder(parent)
  }

  private fun isResourceFile(@NotNull psiFile: PsiFile): Boolean {
    return isResourceFile(psiFile.getVirtualFile())
  }

  private fun isScanPending(@NotNull virtualFile: VirtualFile): Boolean {
    synchronized(scanLock) { return pendingScans.contains(virtualFile) }
  }

  /**
   * Schedules a scan of the given resource file if it belongs to this repository.
   */
  fun convertToPsiIfNeeded(@NotNull virtualFile: VirtualFile) {
    val parent: VirtualFile = virtualFile.getParent()
    val grandparent: VirtualFile? = if (parent == null) null else parent.getParent()
    if (myResourceDir.equals(grandparent)) {
      scheduleScan(virtualFile)
    }
  }

  /**
   * Schedules a rescan to convert any map ResourceItems to PSI if needed, and returns true if conversion
   * was needed (incremental updates which rely on PSI were not possible).
   */
  private fun convertToPsiIfNeeded(
    @NotNull psiFile: PsiFile?,
    @NotNull folderType: ResourceFolderType?
  ): Boolean {
    val virtualFile: VirtualFile = psiFile.getVirtualFile()
    val resourceFile: ResourceItemSource<*> = mySources.get(virtualFile)
    if (resourceFile is PsiResourceFile) {
      return false
    }
    // This schedules a rescan, and when the actual scan happens it will purge non-PSI
    // items as needed, populate psi items, and add to myFileTypes once done.
    if (LOG.isDebugEnabled()) {
      LOG.debug("Converting to PSI ", psiFile)
    }
    ResourceUpdateTracer.log { getSimpleId(this) + ".convertToPsiIfNeeded " + pathForLogging(psiFile) + " converting to PSI" }
    scheduleScan(virtualFile, folderType)
    return true
  }

  fun scheduleScan(@NotNull virtualFile: VirtualFile?) {
    val folderType: ResourceFolderType = IdeResourcesUtil.getFolderType(virtualFile)
    if (folderType != null) {
      scheduleScan(virtualFile, folderType)
    }
  }

  private fun scheduleScan(
    @NotNull virtualFile: VirtualFile,
    @NotNull folderType: ResourceFolderType?
  ) {
    ResourceUpdateTracer.log { getSimpleId(this) + ".scheduleScan " + pathForLogging(virtualFile) }
    synchronized(scanLock) {
      if (!pendingScans.add(virtualFile)) {
        ResourceUpdateTracer.log { getSimpleId(this) + ".scheduleScan " + pathForLogging(virtualFile) + " pending already" }
        return
      }
    }
    scheduleUpdate {
      ResourceUpdateTracer.log { getSimpleId(this) + ".scheduleScan " + pathForLogging(virtualFile) + " preparing to scan" }
      if (!virtualFile.isValid() || !isScanPending(virtualFile)) {
        ResourceUpdateTracer.log { getSimpleId(this) + ".scheduleScan " + pathForLogging(virtualFile) + " pending already" }
        return@scheduleUpdate
      }
      val psiFile: PsiFile? = findPsiFile(virtualFile)
      if (psiFile == null) {
        ResourceUpdateTracer.log {
          getSimpleId(this) + ".scheduleScan no PSI " + pathForLogging(
            virtualFile
          )
        }
        return@scheduleUpdate
      }
      var runHandle: ProgressIndicator?
      synchronized(scanLock) {
        if (!pendingScans.remove(virtualFile)) {
          ResourceUpdateTracer.log {
            getSimpleId(this) + ".scheduleScan " + pathForLogging(
              virtualFile
            ) + " scanned already"
          }
          return@scheduleUpdate
        }
        runHandle = EmptyProgressIndicator()
        val oldRunHandle: ProgressIndicator? = runningScans.put(virtualFile, runHandle)
        if (oldRunHandle != null) {
          oldRunHandle.cancel()
        }
      }
      try {
        ProgressManager.getInstance().runProcess({ scan(psiFile, folderType) }, runHandle)
      } finally {
        synchronized(scanLock) {
          runningScans.remove(virtualFile, runHandle)
          ResourceUpdateTracer.log {
            getSimpleId(this) + ".scheduleScan " + pathForLogging(
              virtualFile
            ) + " finished scanning"
          }
        }
      }
    }
  }

  @NotNull private fun pathForLogging(@NotNull virtualFile: VirtualFile): String {
    return ResourceUpdateTracer.pathForLogging(virtualFile, project)
  }

  @Nullable private fun pathForLogging(@Nullable file: PsiFile?): String? {
    return if (file == null) null else pathForLogging(file.getVirtualFile())
  }

  /**
   * Runs the given update action on [.updateExecutor] in a read action.
   * All update actions are executed in the same order they were scheduled.
   */
  private fun scheduleUpdate(@NotNull updateAction: Runnable) {
    ResourceUpdateTracer.log { getSimpleId(this) + ".scheduleUpdate scheduling " + updateAction }
    var wasEmpty: Boolean
    synchronized(updateQueue) {
      wasEmpty = updateQueue.isEmpty()
      updateQueue.add(updateAction)
    }
    if (wasEmpty) {
      try {
        updateExecutor.execute {
          while (true) {
            var action: Runnable
            synchronized(updateQueue) {
              action = updateQueue.poll()
              if (action == null) {
                break
              }
            }
            ResourceUpdateTracer.log { getSimpleId(this) + ": Update " + action + " started" }
            try {
              ReadAction.nonBlocking(action).expireWith(facet).executeSynchronously()
              ResourceUpdateTracer.log { getSimpleId(this) + ": Update " + action + " finished" }
            } catch (e: ProcessCanceledException) {
              ResourceUpdateTracer.log { getSimpleId(this) + ": Update " + action + " was canceled" }
              // The current update action has been canceled. Proceed to the next one in the queue.
            } catch (e: Throwable) {
              ResourceUpdateTracer.log {
                getSimpleId(this) + ": Update " + action + " finished with exception " + e + '\n' +
                  TraceUtils.getStackTrace(e)
              }
              LOG.error(e)
            }
          }
        }
      } catch (ignore: RejectedExecutionException) {
        // The executor has been shut down.
      }
    }
  }

  fun invokeAfterPendingUpdatesFinish(@NotNull executor: Executor, @NotNull callback: Runnable) {
    ResourceUpdateTracer.log { getSimpleId(this) + ".invokeAfterPendingUpdatesFinish " + callback }
    scheduleUpdate { executor.execute(callback) }
  }

  @Nullable private fun findPsiFile(@NotNull virtualFile: VirtualFile): PsiFile? {
    return try {
      PsiManager.getInstance(project).findFile(virtualFile)
    } catch (e: AlreadyDisposedException) {
      null
    }
  }

  private fun scan(@NotNull psiFile: PsiFile?, @NotNull folderType: ResourceFolderType?) {
    ProgressManager.checkCanceled()
    if (!isResourceFile(psiFile) || !isRelevantFile(psiFile) || psiFile.getProject().isDisposed()) {
      return
    }
    ResourceUpdateTracer.log { getSimpleId(this) + ".scan " + pathForLogging(psiFile) }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Rescanning ", psiFile)
    }
    val result: Map<ResourceType, ListMultimap<String, ResourceItem>> = HashMap()
    var file: PsiFile? = psiFile
    if (folderType === VALUES) {
      // For unit test tracking purposes only.
      fileRescans++

      // First delete out the previous items.
      val source: ResourceItemSource<*> = mySources.remove(file.getVirtualFile())
      var removed = false
      if (source != null) {
        removed = removeItemsFromSource(source)
      }
      file = ensureValid(file)
      var added = false
      if (file != null) {
        // Add items for this file.
        // Since we have a folder type.
        val parent: PsiDirectory = file.getParent()!!
        val fileParent: PsiDirectory = psiFile.getParent()
        if (fileParent != null) {
          val folderConfiguration: FolderConfiguration =
            FolderConfiguration.getConfigForFolder(fileParent.getName())
          if (folderConfiguration != null) {
            ProgressManager.checkCanceled()
            added = scanValueFileAsPsi(result, file, folderConfiguration)
          }
        }
      }
      if (added || removed) {
        // TODO: Consider doing a deeper diff of the changes to the resource items
        //       to determine if the removed and added items actually differ.
        setModificationCount(ourModificationCounter.incrementAndGet())
        invalidateParentCaches(this, ResourceType.values())
      }
    } else if (checkResourceFilename(file, folderType)) {
      val source: ResourceItemSource<*> = mySources.get(file.getVirtualFile())
      if (source is PsiResourceFile && file.getFileType() === XmlFileType.INSTANCE) {
        // If the old file was a PsiResourceFile for an XML file, we can update ID ResourceItems in place.
        val psiResourceFile: PsiResourceFile = source as PsiResourceFile
        // Already seen this file; no need to do anything unless it's an XML file with generated ids;
        // in that case we may need to update the id's.
        if (FolderTypeRelationship.isIdGeneratingFolderType(folderType)) {
          // For unit test tracking purposes only.
          fileRescans++

          // We've already seen this resource, so no change in the ResourceItem for the
          // file itself (e.g. @layout/foo from layout-land/foo.xml). However, we may have
          // to update the id's:
          val idsBefore: Set<String> = HashSet()
          synchronized(ITEM_MAP_LOCK) {
            val idMultimap: ListMultimap<String, ResourceItem>? = myResourceTable[ResourceType.ID]
            if (idMultimap != null) {
              val idItems: List<PsiResourceItem> = ArrayList()
              for (item in psiResourceFile) {
                if (item.getType() === ResourceType.ID) {
                  idsBefore.add(item.getName())
                  idItems.add(item)
                }
              }
              for (id in idsBefore) {
                // TODO(sprigogin): Simplify this code since the following comment is out of date.
                // Note that ResourceFile has a flat map (not a multimap) so it doesn't
                // record all items (unlike the myItems map) so we need to remove the map
                // items manually, can't just do map.remove(item.getName(), item)
                val mapItems: List<ResourceItem> = idMultimap.get(id)
                if (!mapItems.isEmpty()) {
                  val toDelete: List<ResourceItem> = ArrayList(mapItems.size())
                  for (mapItem in mapItems) {
                    if (mapItem is PsiResourceItem && (mapItem as PsiResourceItem).getSourceFile() === psiResourceFile) {
                      toDelete.add(mapItem)
                    }
                  }
                  for (item in toDelete) {
                    idMultimap.remove(item.getName(), item)
                  }
                }
              }
              for (item in idItems) {
                psiResourceFile.removeItem(item)
              }
            }
          }

          // Add items for this file.
          val idItems: List<PsiResourceItem> = ArrayList()
          file = ensureValid(file)
          if (file != null) {
            ProgressManager.checkCanceled()
            addIds(file, idItems, result)
          }
          if (!idItems.isEmpty()) {
            for (item in idItems) {
              psiResourceFile.addItem(item)
            }
          }

          // Identities may have changed even if the ids are the same, so update maps.
          setModificationCount(ourModificationCounter.incrementAndGet())
          invalidateParentCaches(this, ResourceType.ID)
        }
      } else {
        // Either we're switching to PSI or the file is not XML (image or font), which is not incremental.
        // Remove old items first, rescan below to add back, but with a possibly different multimap list order.
        if (source != null) {
          removeItemsFromSource(source)
        }
        // For unit test tracking purposes only.
        fileRescans++
        // Since we have a folder type.
        val parent: PsiDirectory = file.getParent()!!
        val type: ResourceType = FolderTypeRelationship.getNonIdRelatedResourceType(folderType)
        val idGeneratingFolder: Boolean =
          FolderTypeRelationship.isIdGeneratingFolderType(folderType)
        ProgressManager.checkCanceled()
        clearLayoutlibCaches(file.getVirtualFile(), folderType)
        file = ensureValid(file)
        if (file != null) {
          val fileParent: PsiDirectory = psiFile.getParent()
          if (fileParent != null) {
            val folderConfiguration: FolderConfiguration =
              FolderConfiguration.getConfigForFolder(fileParent.getName())
            if (folderConfiguration != null) {
              val idGeneratingFile =
                idGeneratingFolder && file.getFileType() === XmlFileType.INSTANCE
              ProgressManager.checkCanceled()
              scanFileResourceFileAsPsi(
                file,
                folderType,
                folderConfiguration,
                type,
                idGeneratingFile,
                result
              )
            }
          }
          setModificationCount(ourModificationCounter.incrementAndGet())
          invalidateParentCaches(this, ResourceType.values())
        }
      }
    }
    commitToRepository(result)
    ResourceUpdateTracer.log { getSimpleId(this) + ".scan " + pathForLogging(psiFile) + " end" }
  }

  private fun scan(@NotNull file: VirtualFile) {
    val folderType: ResourceFolderType = IdeResourcesUtil.getFolderType(file)
    if (folderType == null || !isResourceFile(file) || !isRelevantFile(file)) {
      return
    }
    if (!file.exists()) {
      removeResourcesContainedInFileOrDirectory(file)
      return
    }
    val psiFile: PsiFile = myPsiManager.findFile(file)
    if (psiFile != null) {
      val document: Document = myPsiDocumentManager.getDocument(psiFile)
      if (document != null && myPsiDocumentManager.isUncommited(document)) {
        // The Document has uncommitted changes, so scanning the PSI will yield stale results.
        // Request a commit and scan once it's done.
        if (LOG.isDebugEnabled()) {
          LOG.debug("Committing ", document)
        }
        ApplicationManager.getApplication().invokeLaterOnWriteThread {
          myPsiDocumentManager.commitDocument(document)
          scheduleScan(file, folderType)
        }
        return
      }
      scan(psiFile, folderType)
    }
  }

  /**
   * Removes resource items matching the given source file and tag.
   *
   * @return true if any resource items were removed from the repository
   */
  private fun removeItemsForTag(
    @NotNull source: ResourceItemSource<PsiResourceItem>,
    @NotNull xmlTag: XmlTag,
    @NotNull resourceType: ResourceType
  ): Boolean {
    var changed = false
    synchronized(ITEM_MAP_LOCK) {
      val sourceIter: Iterator<PsiResourceItem> = source.iterator()
      while (sourceIter.hasNext()) {
        val item: PsiResourceItem = sourceIter.next()
        if (item.wasTag(xmlTag)) {
          val map: ListMultimap<String, ResourceItem> = myResourceTable[resourceType]
          val items: List<ResourceItem> = map.get(item.getName())
          val iter: Iterator<ResourceItem> = items.iterator()
          while (iter.hasNext()) {
            val candidate: ResourceItem = iter.next()
            if (candidate === item) {
              iter.remove()
              changed = true
              break
            }
          }
          sourceIter.remove()
        }
      }
      return changed
    }
  }

  /**
   * Removes all resource items associated the given source file.
   *
   * @return true if any resource items were removed from the repository
   */
  private fun removeItemsFromSource(@NotNull source: ResourceItemSource<*>): Boolean {
    var changed = false
    synchronized(ITEM_MAP_LOCK) {
      for (item in source) {
        val map: ListMultimap<String, ResourceItem> = myResourceTable[item.getType()]
        val items: List<ResourceItem> = map.get(item.getName())
        val iter: Iterator<ResourceItem> = items.iterator()
        while (iter.hasNext()) {
          val candidate: ResourceItem = iter.next()
          if (candidate === item) {
            iter.remove()
            changed = true
            break
          }
        }
        if (items.isEmpty()) {
          map.removeAll(item.getName())
        }
      }
    }
    return changed
  }

  /**
   * Calls the provided `consumer` asynchronously passing the [AndroidTargetData] associated
   * with the given file.
   */
  private fun getAndroidTargetDataThenRun(
    @NotNull file: VirtualFile,
    @NotNull consumer: Consumer<AndroidTargetData>
  ) {
    ApplicationManager.getApplication().executeOnPooledThread {
      if (facet.isDisposed()) {
        return@executeOnPooledThread
      }
      val configurationManager: ConfigurationManager = ConfigurationManager.findExistingInstance(
        facet.getModule()
      ) ?: return@executeOnPooledThread
      val target: IAndroidTarget = configurationManager.getConfiguration(file).getTarget()
        ?: return@executeOnPooledThread
      consumer.accept(AndroidTargetData.getTargetData(target, facet.getModule()))
    }
  }

  /**
   * Called when a bitmap file has been changed or deleted. Clears out any caches for that image
   * inside LayoutLibrary.
   */
  private fun bitmapUpdated(@NotNull bitmap: VirtualFile) {
    val module: Module = facet.getModule()
    getAndroidTargetDataThenRun(
      bitmap,
      Consumer<AndroidTargetData> { targetData -> targetData.clearLayoutBitmapCache(module) })
  }

  /**
   * Called when a font file has been changed or deleted. Removes the corresponding file from the Typeface
   * cache inside LayoutLibrary.
   */
  fun clearFontCache(@NotNull virtualFile: VirtualFile) {
    getAndroidTargetDataThenRun(
      virtualFile,
      Consumer<AndroidTargetData> { targetData -> targetData.clearFontCache(virtualFile.getPath()) })
  }

  @get:NotNull val psiListener: PsiTreeChangeListener
    get() = myPsiListener

  protected fun setModificationCount(count: Long) {
    ResourceUpdateTracer.log { getSimpleId(this) + ".setModificationCount " + count }
    super.setModificationCount(count)
  }

  /**
   * PSI listener which keeps the repository up to date. It handles simple edits synchronously and schedules rescans for other events.
   *
   * @see IncrementalUpdatePsiListener
   */
  private inner class IncrementalUpdatePsiListener : PsiTreeChangeAdapter() {
    private var myIgnoreChildrenChanged = false
    fun childAdded(@NotNull event: PsiTreeChangeEvent) {
      ResourceUpdateTracer.log { getSimpleId(this) + ".childAdded " + pathForLogging(event.getFile()) }
      myIgnoreChildrenChanged = try {
        val psiFile: PsiFile = event.getFile()
        if (psiFile != null && isRelevantFile(psiFile)) {
          val virtualFile: VirtualFile = psiFile.getVirtualFile()
          // If the file is currently being scanned, schedule a new scan to avoid a race condition
          // between the incremental update and the running scan.
          if (rescheduleScanIfRunning(virtualFile)) {
            return
          }

          // Some child was added within a file.
          val folderType: ResourceFolderType = IdeResourcesUtil.getFolderType(psiFile)
          if (folderType != null && isResourceFile(psiFile)) {
            val child: PsiElement = event.getChild()
            val parent: PsiElement = event.getParent()
            if (folderType === VALUES) {
              if (child is XmlTag) {
                val tag: XmlTag = child as XmlTag
                if (convertToPsiIfNeeded(psiFile, folderType)) {
                  return
                }
                scheduleUpdate {
                  if (!tag.isValid()) {
                    scan(psiFile, folderType)
                    return@scheduleUpdate
                  }
                  if (isItemElement(tag)) {
                    val source: ResourceItemSource<*> = mySources.get(virtualFile)
                    if (source != null) {
                      assert(source is PsiResourceFile)
                      val psiResourceFile: PsiResourceFile = source as PsiResourceFile
                      val name: String = tag.getAttributeValue(ATTR_NAME)
                      if (isValidValueResourceName(name)) {
                        val type: ResourceType = getResourceTypeForResourceTag(tag)
                        if (type === ResourceType.STYLEABLE) {
                          // Can't handle declare styleable additions incrementally yet; need to update paired attr items.
                          scan(psiFile, folderType)
                          return@scheduleUpdate
                        }
                        if (type != null) {
                          val item: PsiResourceItem = PsiResourceItem.forXmlTag(
                            name,
                            type,
                            this@ResourceFolderRepository,
                            tag
                          )
                          synchronized(ITEM_MAP_LOCK) {
                            getOrCreateMap(type).put(name, item)
                            psiResourceFile.addItem(item)
                            setModificationCount(ourModificationCounter.incrementAndGet())
                            invalidateParentCaches(this@ResourceFolderRepository, type)
                          }
                          return@scheduleUpdate
                        }
                      }
                    }
                  }

                  // See if you just added a new item inside a <style> or <array> or <declare-styleable> etc.
                  val parentTag: XmlTag = tag.getParentTag()
                  if (parentTag != null && getResourceTypeForResourceTag(parentTag) != null) {
                    // Yes just invalidate the corresponding cached value.
                    val parentItem: ResourceItem? = findValueResourceItem(parentTag, psiFile)
                    if (parentItem is PsiResourceItem) {
                      if ((parentItem as PsiResourceItem).recomputeValue()) {
                        setModificationCount(ourModificationCounter.incrementAndGet())
                      }
                      ResourceUpdateTracer.log {
                        getSimpleId(this) + ".childAdded " + pathForLogging(event.getFile()) +
                          " recomputed: " + parentItem
                      }
                      return@scheduleUpdate
                    }
                  }

                  // Else: fall through and do full file rescan.
                  scan(psiFile, folderType)
                }
              } else if (parent is XmlText) {
                // If the edit is within an item tag.
                val text: XmlText = parent as XmlText
                handleValueXmlTextEdit(text.getParentTag(), psiFile)
              } else if (child is XmlText) {
                // If the edit is within an item tag.
                handleValueXmlTextEdit(parent, psiFile)
              } else if (parent !is XmlComment && child !is XmlComment) {
                scheduleScan(virtualFile, folderType)
              }
              // Can ignore comment edits or new comments.
              return
            } else if (FolderTypeRelationship.isIdGeneratingFolderType(folderType) && psiFile.getFileType() === XmlFileType.INSTANCE) {
              if (parent is XmlComment || child is XmlComment) {
                return
              }
              if (parent is XmlText || child is XmlText && child.getText().trim().isEmpty()) {
                return
              }
              if (parent is XmlElement && child is XmlElement) {
                if (child is XmlTag) {
                  scheduleUpdate {
                    if (!child.isValid()) {
                      scan(psiFile, folderType)
                      return@scheduleUpdate
                    }
                    val result: Map<ResourceType, ListMultimap<String, ResourceItem>> = HashMap()
                    val items: List<PsiResourceItem> = ArrayList()
                    addIds(child, items, result)
                    if (!items.isEmpty()) {
                      val resourceFile: ResourceItemSource<*> =
                        mySources.get(psiFile.getVirtualFile())
                      if (resourceFile !is PsiResourceFile) {
                        scan(psiFile, folderType)
                        return@scheduleUpdate
                      }
                      val psiResourceFile: PsiResourceFile = resourceFile as PsiResourceFile
                      for (item in items) {
                        psiResourceFile.addItem(item)
                      }
                      commitToRepository(result)
                      setModificationCount(ourModificationCounter.incrementAndGet())
                      invalidateParentCaches(this@ResourceFolderRepository, ResourceType.ID)
                    }
                  }
                  return
                }
                if (child is XmlAttribute || parent is XmlAttribute) {
                  // We check both because invalidation might come from XmlAttribute if it is inserted at once.
                  val attribute: XmlAttribute =
                    if (parent is XmlAttribute) parent as XmlAttribute else child as XmlAttribute
                  val id = createIdNameFromAttribute(attribute)
                  if (id != null) {
                    if (convertToPsiIfNeeded(psiFile, folderType)) {
                      return
                    }
                    scheduleUpdate {
                      if (!attribute.isValid()) {
                        scan(psiFile, folderType)
                        return@scheduleUpdate
                      }
                      val newIdResource: PsiResourceItem = PsiResourceItem.forXmlTag(
                        id,
                        ResourceType.ID,
                        this@ResourceFolderRepository,
                        attribute.getParent()
                      )
                      synchronized(ITEM_MAP_LOCK) {
                        val resourceFile: ResourceItemSource<*> =
                          mySources.get(psiFile.getVirtualFile())
                        if (resourceFile != null) {
                          assert(resourceFile is PsiResourceFile)
                          val psiResourceFile: PsiResourceFile = resourceFile as PsiResourceFile
                          psiResourceFile.addItem(newIdResource)
                          ResourceUpdateTracer.log { getSimpleId(this) + ": Adding id/" + newIdResource.getName() }
                          getOrCreateMap(ResourceType.ID).put(
                            newIdResource.getName(),
                            newIdResource
                          )
                          setModificationCount(ourModificationCounter.incrementAndGet())
                          invalidateParentCaches(this@ResourceFolderRepository, ResourceType.ID)
                        }
                      }
                    }
                    return
                  }
                }
              }
            } else if (folderType === FONT) {
              clearFontCache(psiFile.getVirtualFile())
            }
          }
        }
        true
      } finally {
        ResourceUpdateTracer.log { getSimpleId(this) + ".childAdded " + pathForLogging(event.getFile()) + " end" }
      }
    }

    fun childRemoved(@NotNull event: PsiTreeChangeEvent) {
      ResourceUpdateTracer.log { getSimpleId(this) + ".childRemoved " + pathForLogging(event.getFile()) }
      try {
        val psiFile: PsiFile = event.getFile()
        if (psiFile != null && isRelevantFile(psiFile)) {
          val virtualFile: VirtualFile = psiFile.getVirtualFile()
          // If the file is currently being scanned, schedule a new scan to avoid a race condition
          // between the incremental update and the running scan.
          if (rescheduleScanIfRunning(virtualFile)) {
            return
          }

          // Some child was removed within a file.
          val folderType: ResourceFolderType = IdeResourcesUtil.getFolderType(virtualFile)
          if (folderType != null && isResourceFile(virtualFile)) {
            val child: PsiElement = event.getChild()
            val parent: PsiElement = event.getParent()
            if (folderType === VALUES) {
              if (child is XmlTag) {
                val tag: XmlTag = child as XmlTag

                // See if you just removed an item inside a <style> or <array> or <declare-styleable> etc.
                if (parent is XmlTag) {
                  var parentTag: XmlTag = parent as XmlTag
                  if (getResourceTypeForResourceTag(parentTag) != null) {
                    if (convertToPsiIfNeeded(psiFile, folderType)) {
                      return
                    }
                    // Yes just invalidate the corresponding cached value.
                    val resourceItem: ResourceItem? = findValueResourceItem(parentTag, psiFile)
                    if (resourceItem is PsiResourceItem) {
                      if ((resourceItem as PsiResourceItem).recomputeValue()) {
                        setModificationCount(ourModificationCounter.incrementAndGet())
                      }
                      ResourceUpdateTracer.log {
                        getSimpleId(this) + ".childRemoved " + pathForLogging(event.getFile()) +
                          " recomputed: " + resourceItem
                      }
                      if (resourceItem.getType() === ResourceType.ATTR) {
                        parentTag = parentTag.getParentTag()
                        if (parentTag != null && getResourceTypeForResourceTag(parentTag) === ResourceType.STYLEABLE) {
                          val declareStyleable: ResourceItem? =
                            findValueResourceItem(parentTag, psiFile)
                          if (declareStyleable is PsiResourceItem) {
                            if ((declareStyleable as PsiResourceItem).recomputeValue()) {
                              setModificationCount(ourModificationCounter.incrementAndGet())
                            }
                          }
                        }
                      }
                      return
                    }
                  }
                }
                if (isItemElement(tag)) {
                  if (convertToPsiIfNeeded(psiFile, folderType)) {
                    return
                  }
                  scheduleUpdate {
                    val source: ResourceItemSource<*> = mySources.get(virtualFile)
                    if (source == null) {
                      scan(psiFile, folderType)
                      return@scheduleUpdate
                    }
                    val resourceFile: PsiResourceFile = source as PsiResourceFile
                    val name: String
                    name = if (tag.isValid()) {
                      tag.getAttributeValue(ATTR_NAME)
                    } else {
                      val item: ResourceItem? = findValueResourceItem(tag, psiFile)
                      if (item == null) {
                        // Can't find the name of the deleted tag; just do a full rescan.
                        scan(psiFile, folderType)
                        return@scheduleUpdate
                      }
                      item.getName()
                    }
                    if (name != null) {
                      val type: ResourceType = getResourceTypeForResourceTag(tag)
                      if (type != null) {
                        synchronized(ITEM_MAP_LOCK) {
                          val removed = removeItemsForTag(resourceFile, tag, type)
                          if (removed) {
                            setModificationCount(ourModificationCounter.incrementAndGet())
                            invalidateParentCaches(this@ResourceFolderRepository, type)
                          }
                        }
                      }
                    }
                  }
                }
                return
              } else if (parent is XmlText) {
                // If the edit is within an item tag.
                val text: XmlText = parent as XmlText
                handleValueXmlTextEdit(text.getParentTag(), psiFile)
              } else if (child is XmlText) {
                handleValueXmlTextEdit(parent, psiFile)
              } else if (parent is XmlComment || child is XmlComment) {
                // Can ignore comment edits or removed comments.
                return
              } else {
                // Some other change: do full file rescan.
                scheduleScan(virtualFile, folderType)
              }
            } else if (FolderTypeRelationship.isIdGeneratingFolderType(folderType) && psiFile.getFileType() === XmlFileType.INSTANCE) {
              // TODO: Handle removals of id's (values an attributes) incrementally.
              scheduleScan(virtualFile, folderType)
            } else if (folderType === FONT) {
              clearFontCache(virtualFile)
            }
          }
        }
        myIgnoreChildrenChanged = true
      } finally {
        ResourceUpdateTracer.log { getSimpleId(this) + ".childRemoved " + pathForLogging(event.getFile()) + " end" }
      }
    }

    fun childReplaced(@NotNull event: PsiTreeChangeEvent) {
      ResourceUpdateTracer.log { getSimpleId(this) + ".childReplaced " + pathForLogging(event.getFile()) }
      myIgnoreChildrenChanged = try {
        val psiFile: PsiFile = event.getFile()
        if (psiFile != null) {
          val virtualFile: VirtualFile = psiFile.getVirtualFile()
          // If the file is currently being scanned, schedule a new scan to avoid a race condition
          // between the incremental update and the running scan.
          if (rescheduleScanIfRunning(virtualFile)) {
            return
          }

          // This method is called when you edit within a file.
          if (isRelevantFile(virtualFile)) {
            // First determine if the edit is non-consequential.
            // That's the case if the XML edited is not a resource file (e.g. the manifest file),
            // or if it's within a file that is not a value file or an id-generating file (layouts and menus),
            // such as editing the content of a drawable XML file.
            val folderType: ResourceFolderType = IdeResourcesUtil.getFolderType(virtualFile)
            if (folderType != null && FolderTypeRelationship.isIdGeneratingFolderType(folderType) && psiFile.getFileType() === XmlFileType.INSTANCE) {
              // The only way the edit affected the set of resources was if the user added or removed an
              // id attribute. Since these can be added redundantly we can't automatically remove the old
              // value if you renamed one, so we'll need a full file scan.
              // However, we only need to do this scan if the change appears to be related to ids; this can
              // only happen if the attribute value is changed.
              val parent: PsiElement = event.getParent()
              val child: PsiElement = event.getChild()
              if (parent is XmlText || child is XmlText || parent is XmlComment || child is XmlComment) {
                return
              }
              if (parent is XmlElement && child is XmlElement) {
                if (event.getOldChild() === event.getNewChild()) {
                  // We're not getting accurate PSI information: we have to do a full file scan.
                  scheduleScan(virtualFile, folderType)
                  return
                }
                if (child is XmlAttributeValue) {
                  assert(parent is XmlAttribute) { parent }
                  val attribute: XmlAttribute = parent as XmlAttribute
                  val oldChild: PsiElement = event.getOldChild()
                  val newChild: PsiElement = event.getNewChild()
                  if (oldChild is XmlAttributeValue && newChild is XmlAttributeValue) {
                    val oldText: String = (oldChild as XmlAttributeValue).getValue().trim()
                    val newText: String = (newChild as XmlAttributeValue).getValue().trim()
                    if (oldText.startsWith(NEW_ID_PREFIX) || newText.startsWith(NEW_ID_PREFIX)) {
                      val resourceFile: ResourceItemSource<*> =
                        mySources.get(psiFile.getVirtualFile())
                      if (resourceFile !is PsiResourceFile) {
                        scheduleScan(virtualFile, folderType)
                        return
                      }
                      val oldResourceUrl: ResourceUrl = ResourceUrl.parse(oldText)
                      val newResourceUrl: ResourceUrl = ResourceUrl.parse(newText)

                      // Make sure to compare name as well as urlType, e.g. if both have @+id or not.
                      if (Objects.equals(oldResourceUrl, newResourceUrl)) {
                        // Can happen when there are error nodes (e.g. attribute value not yet closed during typing etc).
                        return
                      }
                      val xmlTag: XmlTag = attribute.getParent()
                      scheduleUpdate {
                        if (!xmlTag.isValid()) {
                          scan(psiFile, folderType)
                          return@scheduleUpdate
                        }
                        val result: Map<ResourceType, ListMultimap<String, ResourceItem>> =
                          HashMap()
                        val items: ArrayList<PsiResourceItem> = ArrayList()
                        addIds(xmlTag, items, result)
                        synchronized(ITEM_MAP_LOCK) {
                          val psiResourceFile: PsiResourceFile = resourceFile as PsiResourceFile
                          removeItemsForTag(psiResourceFile, xmlTag, ResourceType.ID)
                          for (item in items) {
                            psiResourceFile.addItem(item)
                          }
                          commitToRepositoryWithoutLock(result)
                          setModificationCount(ourModificationCounter.incrementAndGet())
                        }
                      }
                      return
                    }
                  }
                } else if (parent is XmlAttributeValue) {
                  val grandParent: PsiElement = parent.getParent()
                  if (grandParent is XmlProcessingInstruction) {
                    // Don't care about edits in the processing instructions, e.g. editing the encoding attribute in
                    // <?xml version="1.0" encoding="utf-8"?>
                    return
                  }
                  assert(grandParent is XmlAttribute) { parent }
                  val attribute: XmlAttribute = grandParent as XmlAttribute
                  val xmlTag: XmlTag = attribute.getParent()
                  val oldText: String = StringUtil.notNullize(event.getOldChild().getText()).trim()
                  val newText: String = StringUtil.notNullize(event.getNewChild().getText()).trim()
                  ResourceUpdateTracer.log {
                    getSimpleId(this) + ".childReplaced " + pathForLogging(event.getFile()) +
                      " oldText: \"" + oldText + "\" newText: \"" + newText + "\""
                  }
                  if (oldText.startsWith(NEW_ID_PREFIX) || newText.startsWith(NEW_ID_PREFIX)) {
                    val resourceFile: ResourceItemSource<*> =
                      mySources.get(psiFile.getVirtualFile())
                    if (resourceFile !is PsiResourceFile) {
                      scheduleScan(virtualFile, folderType)
                      return
                    }
                    val oldResourceUrl: ResourceUrl = ResourceUrl.parse(oldText)
                    val newResourceUrl: ResourceUrl = ResourceUrl.parse(newText)

                    // Make sure to compare name as well as urlType, e.g. if both have @+id or not.
                    if (Objects.equals(oldResourceUrl, newResourceUrl)) {
                      // Can happen when there are error nodes (e.g. attribute value not yet closed during typing etc).
                      return
                    }
                    scheduleUpdate {
                      if (!xmlTag.isValid()) {
                        scan(psiFile, folderType)
                        return@scheduleUpdate
                      }
                      val result: Map<ResourceType, ListMultimap<String, ResourceItem>> = HashMap()
                      val items: ArrayList<PsiResourceItem> = ArrayList()
                      addIds(xmlTag, items, result)
                      synchronized(ITEM_MAP_LOCK) {
                        val psiResourceFile: PsiResourceFile = resourceFile as PsiResourceFile
                        removeItemsForTag(psiResourceFile, xmlTag, ResourceType.ID)
                        commitToRepository(result)
                        for (item in items) {
                          psiResourceFile.addItem(item)
                        }
                        setModificationCount(ourModificationCounter.incrementAndGet())
                        invalidateParentCaches(this@ResourceFolderRepository, ResourceType.ID)
                      }
                    }
                    return
                  }
                }
                // This is an XML change within an ID generating folder to something that it's not an ID. While we do not need
                // to generate the ID, we need to notify that something relevant has changed.
                // One example of this change would be an edit to a drawable.
                setModificationCount(ourModificationCounter.incrementAndGet())
                return
              }

              // TODO: Handle adding/removing elements in layouts incrementally.
              scheduleScan(virtualFile, folderType)
            } else if (folderType === VALUES) {
              // This is a folder that *may* contain XML files. Check if this is a relevant XML edit.
              val parent: PsiElement = event.getParent()
              if (parent is XmlElement) {
                // Editing within an XML file
                // An edit in a comment can be ignored
                // An edit in a text inside an element can be used to invalidate the ResourceValue of an element
                //    (need to search upwards since strings can have HTML content)
                // An edit between elements can be ignored
                // An edit to an attribute name (not the attribute value for the attribute named "name"...) can
                //     sometimes be ignored (if you edit type or name, consider what to do)
                // An edit of an attribute value can affect the name of type so update item
                // An edit of other parts; for example typing in a new <string> item character by character.
                // etc.
                if (parent is XmlComment) {
                  // Nothing to do
                  return
                }

                // See if you just removed an item inside a <style> or <array> or <declare-styleable> etc.
                if (parent is XmlTag) {
                  val parentTag: XmlTag = parent as XmlTag
                  if (getResourceTypeForResourceTag(parentTag) != null) {
                    if (convertToPsiIfNeeded(psiFile, folderType)) {
                      return
                    }
                    // Yes just invalidate the corresponding cached value.
                    val resourceItem: ResourceItem? = findValueResourceItem(parentTag, psiFile)
                    if (resourceItem is PsiResourceItem) {
                      if ((resourceItem as PsiResourceItem).recomputeValue()) {
                        setModificationCount(ourModificationCounter.incrementAndGet())
                      }
                      ResourceUpdateTracer.log {
                        getSimpleId(this) + ".childReplaced " + pathForLogging(event.getFile()) +
                          " recomputed: " + resourceItem
                      }
                      return
                    }
                  }
                  if (parentTag.getName().equals(TAG_RESOURCES) &&
                    event.getOldChild() is XmlText &&
                    event.getNewChild() is XmlText
                  ) {
                    return
                  }
                }
                if (parent is XmlText) {
                  val text: XmlText = parent as XmlText
                  handleValueXmlTextEdit(text.getParentTag(), psiFile)
                  return
                }
                if (parent is XmlAttributeValue) {
                  val attribute: PsiElement = parent.getParent()
                  if (attribute is XmlProcessingInstruction) {
                    // Don't care about edits in the processing instructions, e.g. editing the encoding attribute in
                    // <?xml version="1.0" encoding="utf-8"?>
                    return
                  }
                  val tag: PsiElement = attribute.getParent()
                  assert(attribute is XmlAttribute) { attribute }
                  val xmlAttribute: XmlAttribute = attribute as XmlAttribute
                  assert(tag is XmlTag) { tag }
                  val xmlTag: XmlTag = tag as XmlTag
                  val attributeName: String = xmlAttribute.getName()
                  // We could also special-case handling of editing the type attribute, and the parent attribute,
                  // but editing these is rare enough that we can just stick with the fallback full file scan for those
                  // scenarios.
                  if (isItemElement(xmlTag) && attributeName == ATTR_NAME) {
                    // Edited the name of the item: replace it.
                    val type: ResourceType = getResourceTypeForResourceTag(xmlTag)
                    if (type != null) {
                      val oldName: String = event.getOldChild().getText()
                      val newName: String = event.getNewChild().getText()
                      ResourceUpdateTracer.log {
                        getSimpleId(this) + ".childReplaced " + pathForLogging(event.getFile()) +
                          " oldName: \"" + oldName + "\" newName: \"" + newName + "\""
                      }
                      if (oldName == newName) {
                        // Can happen when there are error nodes (e.g. attribute value not yet closed during typing etc).
                        return
                      }
                      // findResourceItem depends on PSI in some cases, so we need to bail and rescan if not PSI.
                      if (convertToPsiIfNeeded(psiFile, folderType)) {
                        return
                      }
                      scheduleUpdate {
                        if (!xmlTag.isValid()) {
                          scan(psiFile, folderType)
                          return@scheduleUpdate
                        }
                        val item: ResourceItem? = findResourceItem(type, psiFile, oldName, xmlTag)
                        if (item == null && isValidValueResourceName(oldName)) {
                          scan(psiFile, folderType)
                          return@scheduleUpdate
                        }
                        synchronized(ITEM_MAP_LOCK) {
                          val items: ListMultimap<String, ResourceItem>? = myResourceTable[type]
                          if (items == null) {
                            scan(psiFile, folderType)
                            return@scheduleUpdate
                          }
                          if (item != null) {
                            // Found the relevant item: delete it and create a new one in a new location.
                            items.remove(oldName, item)
                          }
                          if (isValidValueResourceName(newName)) {
                            val newItem: PsiResourceItem = PsiResourceItem.forXmlTag(
                              newName,
                              type,
                              this@ResourceFolderRepository,
                              xmlTag
                            )
                            items.put(newName, newItem)
                            val resourceFile: ResourceItemSource<*> =
                              mySources.get(psiFile.getVirtualFile())
                            if (resourceFile != null) {
                              val psiResourceFile: PsiResourceFile = resourceFile as PsiResourceFile
                              if (item != null) {
                                psiResourceFile.removeItem(item as PsiResourceItem?)
                              }
                              psiResourceFile.addItem(newItem)
                            } else {
                              assert(false) { item }
                            }
                          }
                          setModificationCount(ourModificationCounter.incrementAndGet())
                          invalidateParentCaches(this@ResourceFolderRepository, type)
                        }

                        // Invalidate surrounding declare styleable if any.
                        if (type === ResourceType.ATTR) {
                          val parentTag: XmlTag = xmlTag.getParentTag()
                          if (parentTag != null && getResourceTypeForResourceTag(parentTag) === ResourceType.STYLEABLE) {
                            val style: ResourceItem? = findValueResourceItem(parentTag, psiFile)
                            if (style is PsiResourceItem) {
                              (style as PsiResourceItem).recomputeValue()
                            }
                            ResourceUpdateTracer.log {
                              getSimpleId(this) + ".childReplaced " + pathForLogging(event.getFile()) +
                                " recomputed: " + style
                            }
                          }
                        }
                      }
                      return
                    } else {
                      val parentTag: XmlTag = xmlTag.getParentTag()
                      if (parentTag != null && getResourceTypeForResourceTag(parentTag) != null) {
                        // <style>, or <plurals>, or <array>, or <string-array>, ...
                        // Edited the attribute value of an item that is wrapped in a <style> tag: invalidate parent cached value.
                        if (convertToPsiIfNeeded(psiFile, folderType)) {
                          return
                        }
                        val resourceItem: ResourceItem? = findValueResourceItem(parentTag, psiFile)
                        if (resourceItem is PsiResourceItem) {
                          if ((resourceItem as PsiResourceItem).recomputeValue()) {
                            setModificationCount(ourModificationCounter.incrementAndGet())
                          }
                          ResourceUpdateTracer.log {
                            getSimpleId(this) + ".childReplaced " + pathForLogging(event.getFile()) +
                              " recomputed: " + resourceItem
                          }
                          return
                        }
                      }
                    }
                  }
                }
              }

              // Fall through: We were not able to directly manipulate the repository to accommodate
              // the edit, so re-scan the whole value file instead.
              scheduleScan(virtualFile, folderType)
            } else if (folderType === COLOR) {
              val parent: PsiElement = event.getParent()
              if (parent is XmlElement) {
                if (parent is XmlComment) {
                  return  // Nothing to do.
                }
                if (parent is XmlAttributeValue) {
                  val attribute: PsiElement = parent.getParent()
                  if (attribute is XmlProcessingInstruction) {
                    // Don't care about edits in the processing instructions, e.g. editing the encoding attribute in
                    // <?xml version="1.0" encoding="utf-8"?>
                    return
                  }
                }
                setModificationCount(ourModificationCounter.incrementAndGet())
                return
              }
            } else if (folderType === FONT) {
              clearFontCache(psiFile.getVirtualFile())
            } else if (folderType != null) {
              val parent: PsiElement = event.getParent()
              if (parent is XmlElement) {
                if (parent is XmlComment) {
                  return  // Nothing to do.
                }

                // A change to an XML file that does not require adding/removing resources.
                // This could be a change to the contents of an XML file in the raw folder.
                setModificationCount(ourModificationCounter.incrementAndGet())
              }
            } // else: can ignore this edit.
          }
        }
        true
      } finally {
        ResourceUpdateTracer.log { getSimpleId(this) + ".childReplaced " + pathForLogging(event.getFile()) + " end" }
      }
    }

    /**
     * If the given resource file is currently being scanned, reschedules the ongoing scan.
     *
     * @param virtualFile the resource file to check
     * @return true if the scan is pending or has been rescheduled, false otherwise
     */
    private fun rescheduleScanIfRunning(@NotNull virtualFile: VirtualFile): Boolean {
      synchronized(scanLock) {
        if (pendingScans.contains(virtualFile)) {
          ResourceUpdateTracer.log {
            getSimpleId(this) + ".rescheduleScanIfRunning " + pathForLogging(virtualFile) +
              " scan is already pending"
          }
          return true
        }
        if (runningScans.containsKey(virtualFile)) {
          ResourceUpdateTracer.log {
            getSimpleId(this) + ".rescheduleScanIfRunning " + pathForLogging(virtualFile) +
              " rescheduling scan"
          }
          scheduleScan(virtualFile)
          return true
        }
      }
      return false
    }

    private fun handleValueXmlTextEdit(@Nullable parent: PsiElement, @NotNull psiFile: PsiFile) {
      if (parent !is XmlTag) {
        // Edited text outside the root element.
        return
      }
      val parentTag: XmlTag = parent as XmlTag
      val parentTagName: String = parentTag.getName()
      if (parentTagName == TAG_RESOURCES) {
        // Editing whitespace between top level elements; ignore.
        return
      }
      val virtualFile: VirtualFile = psiFile.getVirtualFile()
      if (parentTagName == TAG_ITEM) {
        val style: XmlTag = parentTag.getParentTag()
        if (style != null && ResourceType.fromXmlTagName(style.getName()) != null) {
          val folderType: ResourceFolderType = IdeResourcesUtil.getFolderType(psiFile)!!
          if (convertToPsiIfNeeded(psiFile, folderType)) {
            return
          }
          // <style>, or <plurals>, or <array>, or <string-array>, ...
          // Edited the text value of an item that is wrapped in a <style> tag: invalidate.
          scheduleUpdate {
            if (!style.isValid()) {
              scheduleScan(virtualFile, folderType)
              return@scheduleUpdate
            }
            val item: ResourceItem? = findValueResourceItem(style, psiFile)
            if (item is PsiResourceItem) {
              val cleared: Boolean = (item as PsiResourceItem).recomputeValue()
              if (cleared) { // Only bump revision if this is a value which has already been observed!
                setModificationCount(ourModificationCounter.incrementAndGet())
              }
              ResourceUpdateTracer.log {
                getSimpleId(this) + ".handleValueXmlTextEdit " + pathForLogging(virtualFile) +
                  " recomputed: " + item
              }
            }
          }
          return
        }
      }

      // Find surrounding item.
      val itemTag: XmlTag? = findItemElement(parentTag)
      if (itemTag != null) {
        val folderType: ResourceFolderType = IdeResourcesUtil.getFolderType(psiFile)!!
        if (convertToPsiIfNeeded(psiFile, folderType)) {
          return
        }
        scheduleUpdate {
          if (!itemTag.isValid()) {
            scheduleScan(virtualFile, folderType)
            return@scheduleUpdate
          }
          val item: ResourceItem? = findValueResourceItem(itemTag, psiFile)
          if (item is PsiResourceItem) {
            // Edited XML value.
            val cleared: Boolean = (item as PsiResourceItem).recomputeValue()
            if (cleared) { // Only bump revision if this is a value which has already been observed!
              setModificationCount(ourModificationCounter.incrementAndGet())
            }
            ResourceUpdateTracer.log {
              getSimpleId(this) + ".handleValueXmlTextEdit " + pathForLogging(virtualFile) +
                " recomputed: " + item
            }
          }
        }
      }

      // Fully handled; other whitespace changes do not affect resources.
    }

    fun beforeChildrenChange(@NotNull event: PsiTreeChangeEvent) {
      ResourceUpdateTracer.log { getSimpleId(this) + ".beforeChildrenChange " + pathForLogging(event.getFile()) }
      myIgnoreChildrenChanged = false
    }

    fun childrenChanged(@NotNull event: PsiTreeChangeEvent) {
      ResourceUpdateTracer.log { getSimpleId(this) + ".childrenChanged " + pathForLogging(event.getFile()) }
      val parent: PsiElement = event.getParent()
      // Called after children have changed. There are typically individual childMoved, childAdded etc
      // calls that we hook into for more specific details. However, there are some events we don't
      // catch using those methods, and for that we have the below handling.
      if (myIgnoreChildrenChanged) {
        // We've already processed this change as one or more individual childMoved, childAdded, childRemoved etc calls.
        // However, we sometimes get some surprising (=bogus) events where the parent and the child
        // are the same, and in those cases there may be other child events we need to process
        // so fall through and process the whole file.
        if (parent !== event.getChild()) {
          ResourceUpdateTracer.log {
            getSimpleId(this) + ".childrenChanged " + pathForLogging(event.getFile()) +
              " event already processed"
          }
          return
        }
      } else if (event is PsiTreeChangeEventImpl && (event as PsiTreeChangeEventImpl).isGenericChange()) {
        ResourceUpdateTracer.log {
          getSimpleId(this) + ".childrenChanged " + pathForLogging(event.getFile()) +
            " generic change"
        }
        return
      }

      // Avoid the next check for files. If they have not been loaded, getFirstChild will trigger a file load
      // that can be expensive.
      val firstChild: PsiElement? =
        if (parent != null && parent !is PsiFile) parent.getFirstChild() else null
      if (firstChild is PsiWhiteSpace && firstChild === parent.getLastChild()) {
        ResourceUpdateTracer.log { getSimpleId(this) + ".childrenChanged " + pathForLogging(event.getFile()) + " white space" }
        // This event is just adding white spaces.
        return
      }
      val psiFile: PsiFile = event.getFile()
      if (psiFile != null && isRelevantFile(psiFile)) {
        val virtualFile: VirtualFile = psiFile.getVirtualFile()
        if (virtualFile != null) {
          val folderType: ResourceFolderType = IdeResourcesUtil.getFolderType(psiFile)
          if (folderType != null && isResourceFile(psiFile)) {
            // TODO: If I get an XmlText change and the parent is the resources tag or it's a layout, nothing to do.
            scheduleScan(virtualFile, folderType)
          }
        }
      } else {
        if (LOG.isDebugEnabled()) {
          val throwable = Throwable()
          throwable.fillInStackTrace()
          LOG.debug(
            "Received unexpected childrenChanged event for inter-file operations",
            throwable
          )
        }
      }
      ResourceUpdateTracer.log { getSimpleId(this) + ".childrenChanged " + pathForLogging(event.getFile()) + " end" }
    }
  }

  fun onFileCreated(@NotNull file: VirtualFile?) {
    ResourceUpdateTracer.log { getSimpleId(this) + ".onFileCreated " + pathForLogging(file) }
    scheduleScan(file)
  }

  fun onFileOrDirectoryRemoved(@NotNull file: VirtualFile) {
    ResourceUpdateTracer.log {
      getSimpleId(this) + ".onFileOrDirectoryRemoved " + pathForLogging(
        file
      )
    }
    scheduleUpdate { removeResourcesContainedInFileOrDirectory(file) }
  }

  private fun removeResourcesContainedInFileOrDirectory(@NotNull file: VirtualFile) {
    ResourceUpdateTracer.log {
      getSimpleId(this) + ".processRemovalOfFileOrDirectory " + pathForLogging(
        file
      )
    }
    if (file.isDirectory()) {
      val iterator: Unit = mySources.entrySet().iterator()
      while (iterator.hasNext()) {
        val entry: Unit = iterator.next()
        iterator.remove()
        val sourceFile: VirtualFile = entry.getKey()
        if (VfsUtilCore.isAncestor(file, sourceFile, true)) {
          val source: ResourceItemSource<*> = entry.getValue()
          removeSource(sourceFile, source)
        }
      }
    } else {
      val source: ResourceItemSource<*> = mySources.remove(file)
      if (source != null) {
        removeSource(file, source)
      }
      myWolfTheProblemSolver.clearProblemsFromExternalSource(file, this)
    }
  }

  private fun removeSource(@NotNull file: VirtualFile, @NotNull source: ResourceItemSource<*>) {
    ResourceUpdateTracer.log { getSimpleId(this) + ".onSourceRemoved " + pathForLogging(file) }
    val removed = removeItemsFromSource(source)
    if (removed) {
      setModificationCount(ourModificationCounter.incrementAndGet())
      invalidateParentCaches(this, ResourceType.values())
    }
    val folderType: ResourceFolderType = IdeResourcesUtil.getFolderType(file)
    if (folderType != null) {
      clearLayoutlibCaches(file, folderType)
    }
  }

  /**
   * Returns the surrounding "item" element, or null if not found.
   */
  @Nullable private fun findItemElement(@NotNull tag: XmlTag): XmlTag? {
    var parentTag: XmlTag = tag
    while (parentTag != null) {
      if (isItemElement(parentTag)) {
        return parentTag
      }
      parentTag = parentTag.getParentTag()
    }
    return null
  }

  @get:NotNull private val project: Project
    private get() = facet.getModule().getProject()

  private fun clearLayoutlibCaches(
    @NotNull file: VirtualFile,
    @NotNull folderType: ResourceFolderType?
  ) {
    if (SdkConstants.EXT_XML.equals(file.getExtension())) {
      return
    }
    if (folderType === DRAWABLE) {
      layoutlibCacheFlushes++
      bitmapUpdated(file)
    } else if (folderType === FONT) {
      layoutlibCacheFlushes++
      clearFontCache(file)
    }
  }

  @Nullable private fun findValueResourceItem(
    @NotNull tag: XmlTag,
    @NotNull file: PsiFile
  ): ResourceItem? {
    if (!tag.isValid()) {
      // This function should only be used if we know file's items are PsiResourceItems.
      val resourceFile: ResourceItemSource<*> = mySources.get(file.getVirtualFile())
      if (resourceFile != null) {
        assert(resourceFile is PsiResourceFile)
        val psiResourceFile: PsiResourceFile = resourceFile as PsiResourceFile
        for (item in psiResourceFile) {
          if (item.wasTag(tag)) {
            return item
          }
        }
      }
      return null
    }
    val name: String = tag.getAttributeValue(ATTR_NAME)
    synchronized(ITEM_MAP_LOCK) {
      return if (name != null) findValueResourceItem(
        tag,
        file,
        name
      ) else null
    }
  }

  @Nullable private fun findValueResourceItem(
    @NotNull tag: XmlTag,
    @NotNull file: PsiFile,
    @NotNull name: String
  ): ResourceItem? {
    val type: ResourceType = getResourceTypeForResourceTag(tag)
    return findResourceItem(type, file, name, tag)
  }

  @Nullable private fun findResourceItem(
    @Nullable type: ResourceType?,
    @NotNull file: PsiFile,
    @Nullable name: String?,
    @Nullable tag: XmlTag?
  ): ResourceItem? {
    if (type == null || name == null) {
      return null
    }

    // Do IO work before obtaining the lock:
    val ioFile: File = VfsUtilCore.virtualToIoFile(file.getVirtualFile())
    synchronized(ITEM_MAP_LOCK) {
      val map: ListMultimap<String, ResourceItem> = myResourceTable[type] ?: return null
      val items: List<ResourceItem> = map.get(name)
      if (tag != null) {
        // Only PsiResourceItems can match.
        for (resourceItem in items) {
          if (resourceItem is PsiResourceItem) {
            val psiResourceItem: PsiResourceItem = resourceItem as PsiResourceItem
            if (psiResourceItem.wasTag(tag)) {
              return resourceItem
            }
          }
        }
      } else {
        // Check all items for the right source file.
        for (item in items) {
          if (item is PsiResourceItem) {
            if (Objects.equals((item as PsiResourceItem).getPsiFile(), file)) {
              return item
            }
          } else {
            val resourceFile: ResourceFile = (item as ResourceMergerItem).getSourceFile()
            if (resourceFile != null && FileUtil.filesEqual(resourceFile.getFile(), ioFile)) {
              return item
            }
          }
        }
      }
    }
    return null
  }

  // For debugging only
  @NotNull override fun toString(): String {
    return javaClass.simpleName + " for " + myResourceDir + ": @" + Integer.toHexString(
      System.identityHashCode(
        this
      )
    )
  }

  @NotNull protected fun computeResourceDirs(): Set<VirtualFile> {
    return Collections.singleton(myResourceDir)
  }

  /**
   * {@inheritDoc}
   *
   *
   * This override is needed because this repository uses [VfsResourceFile] that is a subclass of
   * [ResourceSourceFile] used by [RepositoryLoader]. If the combined hash of file timestamp
   * and length doesn't match the stream, the method returns an invalid [VfsResourceFile] containing
   * a null [VirtualFile] reference. Validity of the [VfsResourceFile] is checked later inside
   * the [Loader.addResourceItem] method. This process
   * creates few objects that are discarded later, but an alternative of returning null instead of an invalid
   * [VfsResourceFile] would lead to pretty unnatural nullability conditions in [RepositoryLoader].
   * @see VfsResourceFile.serialize
   */
  @NotNull @Throws(IOException::class) fun deserializeResourceSourceFile(
    @NotNull stream: Base128InputStream, @NotNull configurations: List<RepositoryConfiguration>
  ): VfsResourceFile {
    val relativePath: String = stream.readString()
      ?: throw Base128InputStream.StreamFormatException.invalidFormat()
    val configIndex: Int = stream.readInt()
    val configuration: RepositoryConfiguration = configurations[configIndex]
    var virtualFile: VirtualFile? =
      (configuration.getRepository() as ResourceFolderRepository).resourceDir.findFileByRelativePath(
        relativePath
      )
    if (!stream.validateContents(FileTimeStampLengthHasher.hash(virtualFile))) {
      virtualFile = null
    }
    return VfsResourceFile(virtualFile, configuration)
  }

  /**
   * {@inheritDoc}
   *
   *
   * This override is needed because this repository uses [VfsFileResourceItem] that is a subclass of
   * [BasicFileResourceItem] used by [RepositoryLoader]. If the combined hash of file timestamp
   * and length doesn't match the stream, the method returns an invalid [VfsFileResourceItem] containing
   * a null [VirtualFile] reference. Validity of the [VfsFileResourceItem] is checked later inside
   * the [Loader.addResourceItem] method. This process
   * creates few objects that are discarded later, but an alternative of returning null instead of an invalid
   * [VfsFileResourceItem] would lead to pretty unnatural nullability conditions in [RepositoryLoader].
   * @see VfsFileResourceItem.serialize
   *
   * @see BasicFileResourceItem.serialize
   */
  @NotNull @Throws(IOException::class) fun deserializeFileResourceItem(
    @NotNull stream: Base128InputStream,
    @NotNull resourceType: ResourceType?,
    @NotNull name: String?,
    @NotNull visibility: ResourceVisibility?,
    @NotNull configurations: List<RepositoryConfiguration>
  ): BasicFileResourceItem {
    val relativePath: String = stream.readString()
      ?: throw Base128InputStream.StreamFormatException.invalidFormat()
    val configIndex: Int = stream.readInt()
    val configuration: RepositoryConfiguration = configurations[configIndex]
    val encodedDensity: Int = stream.readInt()
    var virtualFile: VirtualFile? =
      (configuration.getRepository() as ResourceFolderRepository).resourceDir.findFileByRelativePath(
        relativePath
      )
    var idGenerating = false
    val folderName: String = PathString(relativePath).getParentFileName()
    if (folderName != null) {
      val folderType: ResourceFolderType = ResourceFolderType.getFolderType(folderName)
      idGenerating =
        folderType != null && FolderTypeRelationship.isIdGeneratingFolderType(folderType)
    }
    return if (idGenerating) {
      if (!stream.validateContents(FileTimeStampLengthHasher.hash(virtualFile))) {
        virtualFile = null
      }
      if (encodedDensity == 0) {
        return VfsFileResourceItem(
          resourceType,
          name,
          configuration,
          visibility,
          relativePath,
          virtualFile
        )
      }
      val density: Density = Density.values().get(encodedDensity - 1)
      VfsDensityBasedFileResourceItem(
        resourceType,
        name,
        configuration,
        visibility,
        relativePath,
        virtualFile,
        density
      )
    } else {
      // The resource item corresponding to a file that is not id-generating is valid regardless of the changes to
      // the contents of the file. BasicFileResourceItem and BasicDensityBasedFileResourceItem are sufficient in
      // this case since there is no need for timestamp/length check.
      if (encodedDensity == 0) {
        return BasicFileResourceItem(resourceType, name, configuration, visibility, relativePath)
      }
      val density: Density = Density.values().get(encodedDensity - 1)
      BasicDensityBasedFileResourceItem(
        resourceType,
        name,
        configuration,
        visibility,
        relativePath,
        density
      )
    }
  }

  protected fun invalidateParentCaches() {
    synchronized(ITEM_MAP_LOCK) { super.invalidateParentCaches() }
  }

  protected fun invalidateParentCaches(
    @NotNull repository: SingleNamespaceResourceRepository?,
    @NotNull vararg types: ResourceType?
  ) {
    synchronized(ITEM_MAP_LOCK) { super.invalidateParentCaches(repository, types) }
  }

  /**
   * Tracks state used by the initial scan, which may be used to save the state to a cache.
   * The file cache omits non-XML single-file items, since those are easily derived from the file path.
   */
  private class Loader internal constructor(
    @field:NotNull @param:NotNull private val myRepository: ResourceFolderRepository,
    @Nullable cachingData: ResourceFolderRepositoryCachingData?
  ) : RepositoryLoader<ResourceFolderRepository?>(
    VfsUtilCore.virtualToIoFile(repository.myResourceDir).toPath(), null, repository.namespace
  ) {
    @NotNull
    private val myResourceDir: VirtualFile

    @NotNull
    private val myPsiManager: PsiManager

    @Nullable
    private val myCachingData: ResourceFolderRepositoryCachingData?

    @NotNull
    private val myResources: Map<ResourceType, ListMultimap<String, ResourceItem>> = EnumMap(
      ResourceType::class.java
    )

    @NotNull
    private val mySources: Map<VirtualFile, ResourceItemSource<BasicResourceItem>> = HashMap()

    @NotNull
    private val myFileResources: Map<VirtualFile, BasicFileResourceItem> = HashMap()

    // The following two fields are used as a cache of size one for quick conversion from a PathString to a VirtualFile.
    @Nullable
    private var myLastVirtualFile: VirtualFile? = null

    @Nullable
    private var myLastPathString: PathString? = null

    @NotNull
    var myFilesToReparseAsPsi: Set<VirtualFile> = HashSet()
    private val myFileDocumentManager: FileDocumentManager

    init {
      myResourceDir = repository.myResourceDir
      myPsiManager = repository.myPsiManager
      myCachingData = cachingData
      // TODO: Add visibility support.
      myDefaultVisibility = ResourceVisibility.UNDEFINED
      myFileDocumentManager = FileDocumentManager.getInstance()
    }

    fun load() {
      if (!myResourceDir.isValid()) {
        return
      }
      loadFromPersistentCache()
      ApplicationManager.getApplication().runReadAction { psiDirsForListener }
      scanResFolder()
      populateRepository()
      ApplicationManager.getApplication().runReadAction { scanQueuedPsiResources() }
      if (myCachingData != null && !myRepository.hasFreshFileCache()) {
        val executor: Executor = myCachingData.getCacheCreationExecutor()
        if (executor != null) {
          executor.execute { createCacheFile() }
        }
      }
    }

    private fun loadFromPersistentCache() {
      if (myCachingData == null) {
        return
      }
      val fileHeader = getCacheFileHeader(myCachingData)
      try {
        Base128InputStream(myCachingData.getCacheFile()).use { stream ->
          if (!stream.validateContents(fileHeader)) {
            return  // Cache file header doesn't match.
          }
          ResourceSerializationUtil.readResourcesFromStream(
            stream, Maps.newHashMapWithExpectedSize(1000), null, myRepository
          ) { item -> addResourceItem(item, myRepository) }
        }
      } catch (ignored: NoSuchFileException) {
        // Cache file does not exist.
      } catch (e: ProcessCanceledException) {
        throw e
      } catch (e: Throwable) {
        // Remove incomplete data.
        mySources.clear()
        myFileResources.clear()
        LOG.warn("Failed to load resources from cache file " + myCachingData.getCacheFile(), e)
      }
    }

    protected fun getCacheFileHeader(@NotNull cachingData: ResourceFolderRepositoryCachingData?): ByteArray {
      return ResourceSerializationUtil.getCacheFileHeader { stream ->
        stream.write(CACHE_FILE_HEADER)
        stream.writeString(CACHE_FILE_FORMAT_VERSION)
        stream.writeString(myResourceDir.getPath())
        stream.writeString(cachingData.getCodeVersion())
      }
    }

    private fun createCacheFile() {
      assert(myCachingData != null)
      val header = getCacheFileHeader(myCachingData)
      try {
        createPersistentCache(
          myCachingData.getCacheFile(),
          header
        ) { stream -> writeResourcesToStream(myResources, stream) { config -> true } }
      } catch (e: Throwable) {
        LOG.error(e)
      }
    }

    private fun scanResFolder() {
      try {
        for (subDir in myResourceDir.getChildren()) {
          if (subDir.isValid() && subDir.isDirectory()) {
            val folderName: String = subDir.getName()
            val folderInfo: FolderInfo = FolderInfo.create(folderName, myFolderConfigCache)
            if (folderInfo != null) {
              val configuration: RepositoryConfiguration =
                getConfiguration(myRepository, folderInfo.configuration)
              for (file in subDir.getChildren()) {
                if (file.getName().startsWith(".")) {
                  continue  // Skip file with the name starting with a dot.
                }
                // If there is an unsaved Document for this file, data read from persistent cache may be stale and data read using
                // loadResourceFile below will be stale as it reads straight from disk. Schedule a PSI-based parse.
                if (myFileDocumentManager.isFileModified(file)) {
                  myFilesToReparseAsPsi.add(file)
                  continue
                }
                if (if (folderInfo.folderType === VALUES) mySources.containsKey(file) else myFileResources.containsKey(
                    file
                  )
                ) {
                  if (isParsableFile(file, folderInfo)) {
                    countCacheHit()
                  }
                  continue
                }
                val pathString: PathString = FileExtensions.toPathString(file)
                myLastVirtualFile = file
                myLastPathString = pathString
                try {
                  loadResourceFile(pathString, folderInfo, configuration)
                  if (isParsableFile(file, folderInfo)) {
                    countCacheMiss()
                  }
                } catch (e: ParsingException) {
                  // Reparse the file as PSI. The PSI parser is more forgiving than KXmlParser because
                  // it is designed to work with potentially malformed files in the middle of editing.
                  myFilesToReparseAsPsi.add(file)
                }
              }
            }
          }
        }
      } catch (e: ProcessCanceledException) {
        throw e
      } catch (e: Exception) {
        LOG.error("Failed to load resources from $myResourceDirectoryOrFile", e)
      }
      super.finishLoading(myRepository)

      // Associate file resources with sources.
      for (entry in myFileResources.entrySet()) {
        val virtualFile: VirtualFile = entry.getKey()
        val item: BasicFileResourceItem = entry.getValue()
        val source: ResourceItemSource<BasicResourceItem> =
          mySources.computeIfAbsent(virtualFile) { file ->
            VfsResourceFile(
              file,
              item.getRepositoryConfiguration()
            )
          }
        source.addItem(item)
      }

      // Populate the myResources map.
      val sortedSources: List<ResourceItemSource<BasicResourceItem>> = ArrayList(mySources.values())
      // Sort sources according to folder configurations to have deterministic ordering of resource items in myResources.
      sortedSources.sort(SOURCE_COMPARATOR)
      for (source in sortedSources) {
        for (item in source) {
          getOrCreateMap(item.getType()).put(item.getName(), item)
        }
      }
    }

    private fun loadResourceFile(
      @NotNull file: PathString,
      @NotNull folderInfo: FolderInfo?,
      @NotNull configuration: RepositoryConfiguration
    ) {
      if (folderInfo.resourceType == null) {
        if (isXmlFile(file)) {
          parseValueResourceFile(file, configuration)
        }
      } else if (myRepository.checkResourceFilename(file, folderInfo.folderType)) {
        if (isXmlFile(file) && folderInfo.isIdGenerating) {
          parseIdGeneratingResourceFile(file, configuration)
        }
        val item: BasicFileResourceItem = createFileResourceItem(
          file,
          folderInfo.resourceType,
          configuration,
          folderInfo.isIdGenerating
        )
        addResourceItem(item, item.getRepository() as ResourceFolderRepository)
      }
    }

    private fun populateRepository() {
      myRepository.mySources.putAll(mySources)
      myRepository.commitToRepositoryWithoutLock(myResources)
    }

    @NotNull
    private fun getOrCreateMap(@NotNull resourceType: ResourceType): ListMultimap<String, ResourceItem> {
      return myResources.computeIfAbsent(resourceType) { type -> LinkedListMultimap.create() }
    }

    @NotNull @Throws(IOException::class)
    protected fun getInputStream(@NotNull file: PathString): InputStream {
      val virtualFile: VirtualFile = getVirtualFile(file)
        ?: throw NoSuchFileException(file.getNativePath())
      return virtualFile.getInputStream()
    }

    @Nullable private fun getVirtualFile(@NotNull file: PathString): VirtualFile? {
      return if (file.equals(myLastPathString)) myLastVirtualFile else FileExtensions.toVirtualFile(
        file
      )
    }

    /**
     * Currently, [com.intellij.psi.impl.file.impl.PsiVFSListener] requires that at least the parent directory of each file has been
     * accessed as PSI before bothering to notify any listener of events. So, make a quick pass to grab the necessary PsiDirectories.
     */
    private val psiDirsForListener: Unit
      private get() {
        val resourceDirPsi: PsiDirectory = myPsiManager.findDirectory(myResourceDir)
        if (resourceDirPsi != null) {
          resourceDirPsi.getSubdirectories()
        }
      }

    fun addResourceItem(
      @NotNull item: BasicResourceItem,
      @NotNull repository: ResourceFolderRepository?
    ) {
      if (item is BasicValueResourceItemBase) {
        val sourceFile: VfsResourceFile =
          (item as BasicValueResourceItemBase).getSourceFile() as VfsResourceFile
        val virtualFile: VirtualFile = sourceFile.getVirtualFile()
        if (virtualFile != null && virtualFile.isValid() && !virtualFile.isDirectory()) {
          sourceFile.addItem(item)
          mySources.put(virtualFile, sourceFile)
        }
      } else if (item is VfsFileResourceItem) {
        val fileResourceItem: VfsFileResourceItem = item as VfsFileResourceItem
        val virtualFile: VirtualFile = fileResourceItem.getVirtualFile()
        if (virtualFile != null && virtualFile.isValid() && !virtualFile.isDirectory()) {
          myFileResources.put(virtualFile, fileResourceItem)
        }
      } else if (item is BasicFileResourceItem) {
        val fileResourceItem: BasicFileResourceItem = item as BasicFileResourceItem
        val virtualFile: VirtualFile? = getVirtualFile(fileResourceItem.getSource())
        if (virtualFile != null && virtualFile.isValid() && !virtualFile.isDirectory()) {
          myFileResources.put(virtualFile, fileResourceItem)
        }
      } else {
        throw IllegalArgumentException("Unexpected type: " + item.getClass().getName())
      }
    }

    @NotNull private fun createFileResourceItem(
      @NotNull file: PathString,
      @NotNull resourceType: ResourceType,
      @NotNull configuration: RepositoryConfiguration,
      idGenerating: Boolean
    ): BasicFileResourceItem {
      val resourceName: String = SdkUtils.fileNameToResourceName(file.getFileName())
      val visibility: ResourceVisibility = getVisibility(resourceType, resourceName)
      var density: Density? = null
      if (DensityBasedResourceValue.isDensityBasedResourceType(resourceType)) {
        val densityQualifier: DensityQualifier =
          configuration.getFolderConfiguration().getDensityQualifier()
        if (densityQualifier != null) {
          density = densityQualifier.getValue()
        }
      }
      return createFileResourceItem(
        file,
        resourceType,
        resourceName,
        configuration,
        visibility,
        density,
        idGenerating
      )
    }

    @NotNull protected fun createResourceSourceFile(
      @NotNull file: PathString,
      @NotNull configuration: RepositoryConfiguration?
    ): ResourceSourceFile {
      val virtualFile: VirtualFile? = getVirtualFile(file)
      return VfsResourceFile(virtualFile, configuration)
    }

    @NotNull private fun createFileResourceItem(
      @NotNull file: PathString,
      @NotNull type: ResourceType,
      @NotNull name: String,
      @NotNull configuration: RepositoryConfiguration,
      @NotNull visibility: ResourceVisibility,
      @Nullable density: Density?,
      idGenerating: Boolean
    ): BasicFileResourceItem {
      if (!idGenerating) {
        return super.createFileResourceItem(file, type, name, configuration, visibility, density)
      }
      val virtualFile: VirtualFile? = getVirtualFile(file)
      val relativePath: String = getResRelativePath(file)
      return if (density == null) VfsFileResourceItem(
        type,
        name,
        configuration,
        visibility,
        relativePath,
        virtualFile
      ) else VfsDensityBasedFileResourceItem(
        type,
        name,
        configuration,
        visibility,
        relativePath,
        virtualFile,
        density
      )
    }

    protected fun handleParsingError(@NotNull file: PathString?, @NotNull e: Exception?) {
      throw ParsingException(e)
    }

    /**
     * For resource files that failed when scanning with a VirtualFile, retries with PsiFile.
     */
    private fun scanQueuedPsiResources() {
      for (file in myFilesToReparseAsPsi) {
        myRepository.scan(file)
      }
    }

    private fun countCacheHit() {
      ++myRepository.numXmlFilesLoadedInitially
    }

    private fun countCacheMiss() {
      ++myRepository.numXmlFilesLoadedInitially
      ++myRepository.numXmlFilesLoadedInitiallyFromSources
    }

    companion object {
      private fun isParsableFile(
        @NotNull file: VirtualFile,
        @NotNull folderInfo: FolderInfo?
      ): Boolean {
        return (folderInfo.folderType === VALUES || folderInfo.isIdGenerating) && isXmlFile(file.getName())
      }
    }
  }

  private class ParsingException internal constructor(cause: Throwable?) : RuntimeException(cause)
  companion object {
    /**
     * Increment when making changes that may affect content of repository cache files.
     * Used together with CachingData.codeVersion. Important for developer builds.
     */
    const val CACHE_FILE_FORMAT_VERSION = "2"
    private val CACHE_FILE_HEADER: ByteArray = "Resource cache".getBytes(UTF_8)

    /**
     * Maximum fraction of resources out of date in the cache for the cache to be considered fresh.
     *
     *
     * Loading without cache takes approximately twice as long as with the cache. This means that
     * if x% of all resources are loaded from sources because the cache is not completely up to date,
     * it introduces approximately x% loading time overhead. 5% overhead seems acceptable since it
     * is well within natural variation. Since cache file creation is asynchronous, the cost of
     * keeping cache fresh is pretty low.
     */
    private const val CACHE_STALENESS_THRESHOLD = 0.05
    private val SOURCE_COMPARATOR: Comparator<ResourceItemSource<*>> =
      Comparator.comparing(ResourceItemSource::getFolderConfiguration)
    private val LOG: Logger = Logger.getInstance(ResourceFolderRepository::class.java)

    /**
     * Creates a ResourceFolderRepository and loads its contents.
     *
     *
     * If `cachingData` is not null, an attempt is made
     * to load resources from the cache file specified in `cachingData`. While loading from the cache resources
     * defined in the XML files that changed recently are skipped. Whether an XML has changed or not is determined by
     * comparing the combined hash of the file modification time and the length obtained by calling
     * [VirtualFile.getTimeStamp] and [VirtualFile.getLength] with the hash value stored in the cache.
     * The checks are located in [.deserializeResourceSourceFile] and [.deserializeFileResourceItem].
     *
     *
     * The remaining resources are then loaded by parsing XML files that were not present in the cache or were newer
     * than their cached versions.
     *
     *
     * If a significant (determined by [.CACHE_STALENESS_THRESHOLD]) percentage of resources was loaded by parsing
     * XML files and `cachingData.cacheCreationExecutor` is not null, the new cache file is created using that
     * executor, possibly after this method has already returned.
     *
     *
     * After creation the contents of the repository are maintained to be up-to-date by listening to VFS and PSI events.
     *
     *
     * NOTE: You should normally use [ResourceFolderRegistry.get] rather than this method.
     */
    fun create(
      facet: AndroidFacet,
      dir: String,
      namespace: ResourceNamespace
    ): ResourceFolderRepository {
      return ResourceFolderRepository(facet, dir, namespace)
    }

    private fun addToResult(
      item: ResourceItem,
      result: Map<ResourceType, ListMultimap<String, ResourceItem>>
    ) {
      // The insertion order matters, see AppResourceRepositoryTest.testStringOrder.
      result.computeIfAbsent(item.getType()) { t -> LinkedListMultimap.create() }
        .put(item.getName(), item)
    }

    @Contract(value = "null -> false")
    private fun isValidValueResourceName(@Nullable name: String): Boolean {
      return !StringUtil.isEmpty(name) && ValueResourceNameValidator.getErrorText(
        name,
        null
      ) == null
    }

    private fun isItemElement(@NotNull xmlTag: XmlTag): Boolean {
      val tag: String = xmlTag.getName()
      return if (tag == TAG_RESOURCES) {
        false
      } else tag == TAG_ITEM || ResourceType.fromXmlTagName(tag) != null
    }
  }
}

