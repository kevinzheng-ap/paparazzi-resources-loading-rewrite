package app.cash.paparazzi.internal.resources

import app.cash.paparazzi.internal.resources.base.BasicPluralsResourceItem
import app.cash.paparazzi.internal.resources.base.BasicTextValueResourceItem
import app.cash.paparazzi.internal.resources.base.BasicValueResourceItem
import app.cash.paparazzi.internal.resources.base.BasicValueResourceItemBase
import com.android.ide.common.resources.configuration.LocaleQualifier
import com.android.resources.ResourceType
import java.util.function.Consumer

private const val DO_NOT_TRANSLATE = "do_not_translate"

fun pseudolocalizeIfNeeded(
  resource: BasicValueResourceItemBase,
  resourceConsumer: Consumer<BasicValueResourceItemBase>
) {
  pseudolocalizeIfNeeded(Pseudolocalizer.Method.ACCENT, resource, resourceConsumer)
  pseudolocalizeIfNeeded(Pseudolocalizer.Method.BIDI, resource, resourceConsumer)
}

private fun pseudolocalizeIfNeeded(
  method: Pseudolocalizer.Method,
  resourceItem: BasicValueResourceItemBase,
  resourceConsumer: Consumer<BasicValueResourceItemBase>
) {
  if (!isPseudolocalizable(resourceItem)) {
    return
  }

  val pseudoLocaleQualifier = when (method) {
    Pseudolocalizer.Method.NONE -> return
    Pseudolocalizer.Method.ACCENT -> "en-rXA"
    Pseudolocalizer.Method.BIDI -> "ar-rXB"
  }

  val pseudoLocaleSourceFile = resourceItem.sourceFile.onNewLocaleQualifier(
    LocaleQualifier.getQualifier(pseudoLocaleQualifier)
  )
  val pseudoItem: BasicValueResourceItemBase = when (resourceItem.resourceType) {
    ResourceType.STRING -> pseudolocalizeString(
      resourceItem as BasicValueResourceItem,
      pseudoLocaleSourceFile,
      method
    )

    ResourceType.PLURALS -> pseudolocalizePlural(
      resourceItem as BasicPluralsResourceItem,
      pseudoLocaleSourceFile,
      method
    )

    else -> return
  }
  resourceConsumer.accept(pseudoItem)
}

private fun isPseudolocalizable(resourceItemBase: BasicValueResourceItemBase): Boolean =
  resourceItemBase.sourceFile.configuration.folderConfiguration.localeQualifier == null &&
    resourceItemBase.sourceFile.relativePath?.contains(DO_NOT_TRANSLATE) != true

private fun pseudolocalizeString(
  original: BasicValueResourceItem,
  sourceFile: ResourceSourceFile,
  method: Pseudolocalizer.Method
): BasicValueResourceItem {
  val pseudoText = original.value?.let { it.pseudoLocalize(method) }
  val pseudoRawXml =
    (original as? BasicTextValueResourceItem)?.rawXmlValue?.let { it.pseudoLocalize(method) }

  val pseudoItem = if (pseudoRawXml == null) {
    BasicValueResourceItem(
      original.type,
      original.name,
      sourceFile,
      original.visibility,
      pseudoText
    )
  } else {
    BasicTextValueResourceItem(
      original.type,
      original.name,
      sourceFile,
      original.visibility,
      pseudoText,
      pseudoRawXml
    )
  }
  pseudoItem.namespaceResolver = original.namespaceResolver
  return pseudoItem
}

private fun pseudolocalizePlural(
  original: BasicPluralsResourceItem,
  sourceFile: ResourceSourceFile,
  method: Pseudolocalizer.Method
): BasicPluralsResourceItem {
  val pseudoValues = original.values.map { it.pseudoLocalize(method) }.toTypedArray()
  val pseudoItem = BasicPluralsResourceItem(
    original,
    sourceFile,
    pseudoValues
  )
  pseudoItem.namespaceResolver = original.namespaceResolver
  return pseudoItem
}
