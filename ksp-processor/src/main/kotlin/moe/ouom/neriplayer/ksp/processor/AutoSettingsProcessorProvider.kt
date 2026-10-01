package moe.ouom.neriplayer.ksp.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import moe.ouom.neriplayer.ksp.annotations.AutoSetting
import moe.ouom.neriplayer.ksp.annotations.AutoSettingsCatalog
import moe.ouom.neriplayer.ksp.annotations.AutoSettingsSection
import moe.ouom.neriplayer.ksp.annotations.SettingAccessMode
import moe.ouom.neriplayer.ksp.annotations.SettingUiType
import moe.ouom.neriplayer.ksp.annotations.SettingValueType

class AutoSettingsProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return AutoSettingsProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger
        )
    }
}

private class AutoSettingsProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {
    private var processed = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (processed) return emptyList()

        val catalogSymbols = resolver
            .getSymbolsWithAnnotation(AutoSettingsCatalog::class.qualifiedName.orEmpty())
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        val catalogs = catalogSymbols.map { it.toCatalogSpec() }
        if (catalogs.isEmpty()) {
            processed = true
            return emptyList()
        }
        if (!validateCatalogOutputs(catalogs.filter { it.settings.isNotEmpty() }, logger)) {
            processed = true
            return emptyList()
        }

        catalogs.forEach { catalog ->
            if (catalog.settings.isEmpty()) {
                logger.warn("AutoSettingsCatalog has no @AutoSetting properties", catalog.declaration)
                return@forEach
            }
            generate(catalog)
        }

        processed = true
        return emptyList()
    }

    private fun generate(catalog: CatalogSpec) {
        val switchSettingsMissingTitle = catalog.settings.filter {
            it.ui == SettingUiType.Switch && it.entryReference == null
        }
        if (switchSettingsMissingTitle.isNotEmpty()) {
            switchSettingsMissingTitle.forEach { setting ->
                logger.error(
                    "@AutoSetting ui=Switch must define autoSetting/autoSwitchSetting titleRes for " +
                        setting.sortKey,
                    catalog.declaration
                )
            }
            return
        }
        if (!validateCatalog(catalog, logger)) {
            return
        }

        val dependencies = Dependencies(
            aggregating = false,
            sources = catalog.sourceFiles.toTypedArray()
        )
        val packageName = catalog.packageName

        codeGenerator.writeKotlin(
            dependencies = dependencies,
            packageName = packageName,
            fileName = "AutoSettingsKeys",
            content = buildKeysFile(packageName, catalog.settings)
        )
        codeGenerator.writeKotlin(
            dependencies = dependencies,
            packageName = catalog.settingsKeysPackageName,
            fileName = "SettingsKeys",
            content = buildSettingsKeysFile(
                packageName = catalog.settingsKeysPackageName,
                generatedPackageName = packageName,
                settings = catalog.settings
            )
        )
        codeGenerator.writeKotlin(
            dependencies = dependencies,
            packageName = packageName,
            fileName = "AutoSettingsRepository",
            content = buildRepositoryFile(packageName, catalog.settings)
        )
        codeGenerator.writeKotlin(
            dependencies = dependencies,
            packageName = packageName,
            fileName = "AutoSettingsBackupKeys",
            content = buildBackupKeysFile(packageName, catalog.settings)
        )
        codeGenerator.writeKotlin(
            dependencies = dependencies,
            packageName = packageName,
            fileName = "AutoSettingsSections",
            content = buildSectionsFile(packageName, catalog.sectionSpecs)
        )
        codeGenerator.writeKotlin(
            dependencies = dependencies,
            packageName = packageName,
            fileName = "AutoSettingsMetadata",
            content = buildMetadataFile(packageName, catalog.sectionSpecs, catalog.settings)
        )
        codeGenerator.writeKotlin(
            dependencies = dependencies,
            packageName = packageName,
            fileName = "AutoSettingsSwitchBindings",
            content = buildSwitchBindingsFile(packageName, catalog.settings)
        )
    }
}

private fun validateCatalogOutputs(catalogs: List<CatalogSpec>, logger: KSPLogger): Boolean {
    var valid = true

    fun reportDuplicatePackage(
        outputName: String,
        packageName: String,
        conflictingCatalogs: List<CatalogSpec>
    ) {
        if (conflictingCatalogs.size <= 1) return
        valid = false
        logger.error(
            "Multiple @AutoSettingsCatalog declarations generate $outputName in package " +
                "'$packageName': ${conflictingCatalogs.joinToString { it.declaration.simpleName.asString() }}",
            conflictingCatalogs.first().declaration
        )
    }

    catalogs
        .groupBy { it.packageName }
        .forEach { (packageName, duplicates) ->
            reportDuplicatePackage("AutoSettings* files", packageName, duplicates)
        }

    catalogs
        .groupBy { it.settingsKeysPackageName }
        .forEach { (packageName, duplicates) ->
            reportDuplicatePackage("SettingsKeys", packageName, duplicates)
        }

    return valid
}

private fun validateCatalog(catalog: CatalogSpec, logger: KSPLogger): Boolean {
    var valid = true

    fun reportDuplicate(
        valueName: String,
        duplicateValue: String,
        settings: List<SettingSpec>
    ) {
        if (settings.size <= 1) return
        valid = false
        logger.error(
            "Duplicate @AutoSetting $valueName '$duplicateValue': " +
                settings.joinToString { it.propertyName },
            catalog.declaration
        )
    }

    catalog.settings
        .filter { it.key != null }
        .groupBy { it.key }
        .forEach { (key, settings) -> reportDuplicate("key", key.orEmpty(), settings) }

    catalog.settings
        .filter { it.key == null && it.defaultExpression == null }
        .forEach { setting ->
            valid = false
            logger.error(
                "@AutoSetting key can be omitted only when the property value is AutoSettingSpec<T>: " +
                    setting.propertyName,
                catalog.declaration
            )
        }

    catalog.settings
        .groupBy { it.keyName }
        .forEach { (keyName, settings) -> reportDuplicate("constantName", keyName, settings) }

    catalog.settings
        .filter { it.canGenerateAccessor }
        .groupBy { it.valueName }
        .forEach { (repositoryName, settings) ->
            reportDuplicate("repositoryName", repositoryName, settings)
        }

    catalog.sectionSpecs
        .groupBy { it.propertyName }
        .filterValues { it.size > 1 }
        .forEach { (propertyName, sections) ->
            valid = false
            logger.error(
                "Duplicate @AutoSettingsSection property '$propertyName': " +
                    sections.joinToString { it.key },
                catalog.declaration
            )
        }

    return valid
}

private data class CatalogSpec(
    val declaration: KSClassDeclaration,
    val packageName: String,
    val settingsKeysPackageName: String,
    val sectionSpecs: List<SectionSpec>,
    val settings: List<SettingSpec>,
    val sourceFiles: List<KSFile>
)

private data class SectionSpec(
    val key: String,
    val propertyName: String,
    val entryReference: String?,
    val order: Int
)

private data class SettingSpec(
    val propertyName: String,
    val key: String?,
    val keyExpression: String,
    val valueType: SettingValueType,
    val defaultBoolean: Boolean,
    val defaultFloat: Float,
    val defaultInt: Int,
    val defaultLong: Long,
    val defaultString: String,
    val defaultExpression: String?,
    val section: String,
    val order: Int,
    val ui: SettingUiType,
    val access: SettingAccessMode,
    val constantName: String,
    val entryReference: String?,
    val exportable: Boolean,
    val repositoryName: String,
    val normalizerQualifiedName: String?,
    val sourceFile: KSFile?
) {
    val keyName: String = constantName.ifBlank { (key ?: propertyName).toConstantName() }
    val valueName: String = repositoryName.ifBlank { propertyName }
    val flowName: String = "${valueName}Flow"
    val setterName: String = "set${valueName.replaceFirstChar { it.uppercaseChar() }}"
    val canGenerateAccessor: Boolean = access == SettingAccessMode.ReadWrite
    val sortKey: String = key ?: propertyName
}

private fun KSClassDeclaration.toCatalogSpec(): CatalogSpec {
    val catalogAnnotation = annotations.firstByName(AutoSettingsCatalog::class.qualifiedName.orEmpty())
    val packageName = catalogAnnotation?.stringArgument("packageName")
        .orEmpty()
        .ifBlank { "moe.ouom.neriplayer.data.settings.generated" }
    val settingsKeysPackageName = catalogAnnotation?.stringArgument("settingsKeysPackageName")
        .orEmpty()
        .ifBlank { "moe.ouom.neriplayer.data.settings" }
    val settings = collectSettingSpecs()
        .sortedWith(compareBy<SettingSpec> { it.section }.thenBy { it.order }.thenBy { it.sortKey })
        .toList()
    val sectionSpecs = collectSectionSpecs(settings)
    return CatalogSpec(
        declaration = this,
        packageName = packageName,
        settingsKeysPackageName = settingsKeysPackageName,
        sectionSpecs = sectionSpecs,
        settings = settings,
        sourceFiles = (listOfNotNull(containingFile) + settings.mapNotNull { it.sourceFile })
            .distinct()
    )
}

private fun KSClassDeclaration.collectSectionSpecs(settings: List<SettingSpec>): List<SectionSpec> {
    val explicitSections = declarations
        .filterIsInstance<KSClassDeclaration>()
        .mapNotNull { nestedDeclaration ->
            val annotation = nestedDeclaration.annotations
                .firstByName(AutoSettingsSection::class.qualifiedName.orEmpty())
                ?: return@mapNotNull null
            val fallbackKey = nestedDeclaration.simpleName.asString()
            SectionSpec(
                key = annotation.stringArgument("key").ifBlank { fallbackKey },
                propertyName = fallbackKey.toSectionPropertyName(),
                entryReference = nestedDeclaration.sectionEntryReference(),
                order = annotation.intArgument("order")
            )
        }
        .associateBy { it.key }

    val knownKeys = settings.map { it.section }.distinct()
    return knownKeys
        .map { key ->
            explicitSections[key] ?: SectionSpec(
                key = key,
                propertyName = key.toSectionPropertyName(),
                entryReference = null,
                order = Int.MAX_VALUE
            )
        }
        .sortedWith(compareBy<SectionSpec> { it.order }.thenBy { it.key })
}

private fun KSClassDeclaration.collectSettingSpecs(
    sectionHint: String? = null
): Sequence<SettingSpec> {
    return sequence {
        val ownerReference = qualifiedName?.asString()
        getAllProperties().forEach { property ->
            property.toSettingSpec(sectionHint, ownerReference)?.let { setting -> yield(setting) }
        }
        declarations
            .filterIsInstance<KSClassDeclaration>()
            .forEach { nestedDeclaration ->
                yieldAll(nestedDeclaration.collectSettingSpecs(nestedDeclaration.sectionKeyHint()))
            }
    }
}

private fun KSClassDeclaration.sectionKeyHint(): String {
    val annotation = annotations.firstByName(AutoSettingsSection::class.qualifiedName.orEmpty())
    return annotation?.stringArgument("key")
        ?.ifBlank { simpleName.asString() }
        ?: simpleName.asString()
}

private fun KSPropertyDeclaration.toSettingSpec(
    sectionHint: String? = null,
    ownerReference: String? = null
): SettingSpec? {
    val annotation = annotations.firstByName(AutoSetting::class.qualifiedName.orEmpty()) ?: return null
    val inferredSection = sectionHint ?: parentSectionName() ?: "general"
    val sourceReference = ownerReference?.let { "$it.${simpleName.asString()}" }
        ?: sourceReference()
    val settingSpecValueType = autoSettingSpecValueType()
    val annotationKey = annotation.stringArgument("key").ifBlank { null }
    val entryReference = sourceReference.takeIf {
        hasAutoSettingEntryType() || settingSpecValueType != null
    }
    val valueType = settingSpecValueType
        ?: annotation.enumArgumentOrNull<SettingValueType>("type")
        ?: SettingValueType.Boolean
    val annotationUi = annotation.enumArgumentOrNull<SettingUiType>("ui") ?: SettingUiType.None
    val ui = if (annotationUi == SettingUiType.None && settingSpecValueType == SettingValueType.Boolean) {
        SettingUiType.Switch
    } else {
        annotationUi
    }
    return SettingSpec(
        propertyName = simpleName.asString(),
        key = annotationKey,
        keyExpression = annotationKey?.toLiteral() ?: "$sourceReference.key",
        valueType = valueType,
        defaultBoolean = annotation.booleanArgument("defaultBoolean"),
        defaultFloat = annotation.floatArgument("defaultFloat"),
        defaultInt = annotation.intArgument("defaultInt"),
        defaultLong = annotation.longArgument("defaultLong"),
        defaultString = annotation.stringArgument("defaultString"),
        defaultExpression = settingSpecValueType?.let { "$sourceReference.defaultValue" },
        section = annotation.stringArgument("section").ifBlank { inferredSection },
        order = annotation.intArgument("order"),
        ui = ui,
        access = annotation.enumArgument("access", SettingAccessMode.ReadWrite),
        constantName = annotation.stringArgument("constantName"),
        entryReference = entryReference,
        exportable = annotation.booleanArgument("exportable", defaultValue = true),
        repositoryName = annotation.stringArgument("repositoryName"),
        normalizerQualifiedName = annotation.classArgument("normalizer"),
        sourceFile = containingFile
    )
}

private fun KSClassDeclaration.sectionEntryReference(): String? {
    return declarations
        .filterIsInstance<KSPropertyDeclaration>()
        .firstOrNull { it.hasAutoSettingsSectionEntryType() }
        ?.sourceReference()
}

private fun KSPropertyDeclaration.hasAutoSettingEntryType(): Boolean {
    return type.resolve().declaration.qualifiedName?.asString() ==
        "moe.ouom.neriplayer.ksp.annotations.AutoSettingEntry"
}

private fun KSPropertyDeclaration.hasAutoSettingsSectionEntryType(): Boolean {
    return type.resolve().declaration.qualifiedName?.asString() ==
        "moe.ouom.neriplayer.ksp.annotations.AutoSettingsSectionEntry"
}

private fun KSPropertyDeclaration.autoSettingSpecValueType(): SettingValueType? {
    val resolvedType = type.resolve()
    val qualifiedName = resolvedType.declaration.qualifiedName?.asString()
    if (qualifiedName != "moe.ouom.neriplayer.ksp.annotations.AutoSettingSpec") {
        return null
    }

    val typeArgument = resolvedType.arguments.firstOrNull()?.type?.resolve()
    return when (typeArgument?.declaration?.qualifiedName?.asString()) {
        "kotlin.Boolean" -> SettingValueType.Boolean
        "kotlin.Float" -> SettingValueType.Float
        "kotlin.Int" -> SettingValueType.Int
        "kotlin.Long" -> SettingValueType.Long
        "kotlin.String" -> SettingValueType.String
        else -> null
    }
}

private fun KSPropertyDeclaration.sourceReference(): String {
    val fullName = qualifiedName?.asString()
    if (!fullName.isNullOrBlank()) return fullName

    val parentName = parentDeclaration?.qualifiedName?.asString()
    return if (parentName.isNullOrBlank()) {
        simpleName.asString()
    } else {
        "$parentName.${simpleName.asString()}"
    }
}

private fun KSPropertyDeclaration.parentSectionName(): String? {
    var current: KSDeclaration? = parentDeclaration
    while (current != null) {
        if (current is KSClassDeclaration && current.hasAutoSettingsCatalogAnnotation()) {
            return null
        }
        if (current is KSClassDeclaration) {
            return current.simpleName.asString()
        }
        current = current.parentDeclaration
    }
    return null
}

private fun KSClassDeclaration.hasAutoSettingsCatalogAnnotation(): Boolean {
    return annotations.firstByName(AutoSettingsCatalog::class.qualifiedName.orEmpty()) != null
}

private fun buildKeysFile(packageName: String, settings: List<SettingSpec>): String {
    val imports = linkedSetOf<String>()
    settings.forEach { setting ->
        imports += when (setting.valueType) {
            SettingValueType.Boolean -> "androidx.datastore.preferences.core.booleanPreferencesKey"
            SettingValueType.Float -> "androidx.datastore.preferences.core.floatPreferencesKey"
            SettingValueType.Int -> "androidx.datastore.preferences.core.intPreferencesKey"
            SettingValueType.Long -> "androidx.datastore.preferences.core.longPreferencesKey"
            SettingValueType.String -> "androidx.datastore.preferences.core.stringPreferencesKey"
        }
    }

    return buildString {
        appendGeneratedHeader(packageName)
        imports.sorted().forEach { appendLine("import $it") }
        appendLine()
        appendLine("object AutoSettingsKeys {")
        settings.forEach { setting ->
            appendLine(
                "    val ${setting.keyName} = ${setting.valueType.preferenceKeyFactory()}(${setting.keyExpression})"
            )
        }
        appendLine("}")
    }
}

private fun buildRepositoryFile(packageName: String, settings: List<SettingSpec>): String {
    val accessibleSettings = settings.filter { it.canGenerateAccessor }

    return buildString {
        appendGeneratedHeader(packageName)
        appendLine("import android.content.Context")
        appendLine("import androidx.datastore.preferences.core.edit")
        appendLine("import kotlinx.coroutines.flow.Flow")
        appendLine("import kotlinx.coroutines.flow.distinctUntilChanged")
        appendLine("import kotlinx.coroutines.flow.map")
        appendLine("import moe.ouom.neriplayer.data.settings.dataStore")
        appendLine()
        appendLine("class AutoSettingsRepository(private val context: Context) {")
        accessibleSettings.forEach { setting ->
            appendLine("    val ${setting.flowName}: Flow<${setting.valueType.kotlinType()}> =")
            appendLine("        context.dataStore.data.map { prefs ->")
            appendLine(
                "            ${setting.normalizeReadExpression("prefs[AutoSettingsKeys.${setting.keyName}] ?: ${setting.defaultLiteral()}")}"
            )
            appendLine("        }.distinctUntilChanged()")
            appendLine()
        }
        accessibleSettings.forEach { setting ->
            appendLine("    suspend fun ${setting.setterName}(value: ${setting.valueType.kotlinType()}) {")
            appendLine("        val normalized = ${setting.normalizeWriteExpression("value")}")
            appendLine("        context.dataStore.edit { prefs ->")
            appendLine("            prefs[AutoSettingsKeys.${setting.keyName}] = normalized")
            appendLine("        }")
            appendLine("    }")
            appendLine()
        }
        appendLine("}")
    }
}

private fun buildSettingsKeysFile(
    packageName: String,
    generatedPackageName: String,
    settings: List<SettingSpec>
): String {
    return buildString {
        appendGeneratedHeader(packageName)
        appendLine("import $generatedPackageName.AutoSettingsKeys")
        appendLine()
        appendLine("object SettingsKeys {")
        settings.forEach { setting ->
            appendLine("    val ${setting.keyName} = AutoSettingsKeys.${setting.keyName}")
        }
        appendLine("}")
    }
}

private fun buildBackupKeysFile(packageName: String, settings: List<SettingSpec>): String {
    fun keysOf(type: SettingValueType): String {
        return settings
            .filter { it.exportable && it.valueType == type }
            .joinToString(separator = ",\n") { "    AutoSettingsKeys.${it.keyName}" }
            .ifBlank { "" }
    }

    return buildString {
        appendGeneratedHeader(packageName)
        appendLine("import androidx.datastore.preferences.core.Preferences")
        appendLine()
        appendLine("object AutoSettingsBackupKeys {")
        appendLine("    val booleanKeys: List<Preferences.Key<Boolean>> = listOf(")
        appendLine(keysOf(SettingValueType.Boolean))
        appendLine("    )")
        appendLine()
        appendLine("    val floatKeys: List<Preferences.Key<Float>> = listOf(")
        appendLine(keysOf(SettingValueType.Float))
        appendLine("    )")
        appendLine()
        appendLine("    val intKeys: List<Preferences.Key<Int>> = listOf(")
        appendLine(keysOf(SettingValueType.Int))
        appendLine("    )")
        appendLine()
        appendLine("    val longKeys: List<Preferences.Key<Long>> = listOf(")
        appendLine(keysOf(SettingValueType.Long))
        appendLine("    )")
        appendLine()
        appendLine("    val stringKeys: List<Preferences.Key<String>> = listOf(")
        appendLine(keysOf(SettingValueType.String))
        appendLine("    )")
        appendLine("}")
    }
}

private fun buildSectionsFile(packageName: String, sectionSpecs: List<SectionSpec>): String {
    return buildString {
        appendGeneratedHeader(packageName)
        appendLine("object AutoSettingsSections {")
        sectionSpecs.forEach { section ->
            appendLine("    const val ${section.propertyName}: String = ${section.key.toLiteral()}")
        }
        appendLine("}")
    }
}

private fun buildMetadataFile(
    packageName: String,
    sectionSpecs: List<SectionSpec>,
    settings: List<SettingSpec>
): String {
    return buildString {
        appendGeneratedHeader(packageName)
        appendLine("import androidx.datastore.preferences.core.Preferences")
        appendLine("import moe.ouom.neriplayer.ksp.annotations.AutoSettingIcon")
        appendLine("import moe.ouom.neriplayer.ksp.annotations.SettingAccessMode")
        appendLine("import moe.ouom.neriplayer.ksp.annotations.SettingUiType")
        appendLine("import moe.ouom.neriplayer.ksp.annotations.SettingValueType")
        appendLine()
        appendLine("data class AutoSettingsSectionInfo(")
        appendLine("    val key: String,")
        appendLine("    val titleRes: Int,")
        appendLine("    val descriptionRes: Int,")
        appendLine("    val iconRes: Int,")
        appendLine("    val icon: AutoSettingIcon,")
        appendLine("    val order: Int")
        appendLine(")")
        appendLine()
        appendLine("data class AutoSettingInfo(")
        appendLine("    val key: Preferences.Key<*>,")
        appendLine("    val keyName: String,")
        appendLine("    val propertyName: String,")
        appendLine("    val section: String,")
        appendLine("    val valueType: SettingValueType,")
        appendLine("    val ui: SettingUiType,")
        appendLine("    val access: SettingAccessMode,")
        appendLine("    val titleRes: Int,")
        appendLine("    val descriptionRes: Int,")
        appendLine("    val iconRes: Int,")
        appendLine("    val icon: AutoSettingIcon,")
        appendLine("    val order: Int,")
        appendLine("    val exportable: Boolean")
        appendLine(")")
        appendLine()
        appendLine("data class AutoSettingsSectionScope(")
        appendLine("    val key: String,")
        appendLine("    val info: AutoSettingsSectionInfo,")
        appendLine("    val settings: List<AutoSettingInfo>")
        appendLine(")")
        appendLine()
        appendLine("object AutoSettingsMetadata {")
        appendLine("    val sections: List<AutoSettingsSectionInfo> = listOf(")
        appendLine(
            sectionSpecs.joinToString(separator = ",\n") { section ->
                "        AutoSettingsSectionInfo(" +
                    "key = ${section.key.toLiteral()}, " +
                    "titleRes = ${section.titleResExpression()}, " +
                    "descriptionRes = ${section.descriptionResExpression()}, " +
                    "iconRes = ${section.iconResExpression()}, " +
                    "icon = ${section.iconExpression()}, " +
                    "order = ${section.order}" +
                    ")"
            }
        )
        appendLine("    )")
        appendLine()
        appendLine("    val settings: List<AutoSettingInfo> = listOf(")
        appendLine(
            settings.joinToString(separator = ",\n") { setting ->
                "        AutoSettingInfo(" +
                    "key = AutoSettingsKeys.${setting.keyName}, " +
                    "keyName = ${setting.keyExpression}, " +
                    "propertyName = ${setting.propertyName.toLiteral()}, " +
                    "section = ${setting.section.toLiteral()}, " +
                    "valueType = SettingValueType.${setting.valueType.name}, " +
                    "ui = SettingUiType.${setting.ui.name}, " +
                    "access = SettingAccessMode.${setting.access.name}, " +
                    "titleRes = ${setting.titleResExpression()}, " +
                    "descriptionRes = ${setting.descriptionResExpression()}, " +
                    "iconRes = ${setting.iconResExpression()}, " +
                    "icon = ${setting.iconExpression()}, " +
                    "order = ${setting.order}, " +
                    "exportable = ${setting.exportable}" +
                    ")"
            }
        )
        appendLine("    )")
        appendLine()
        appendLine("    fun settingsIn(section: String): List<AutoSettingInfo> =")
        appendLine("        settings.filter { it.section == section }.sortedBy { it.order }")
        appendLine()
        appendLine("    fun section(key: String): AutoSettingsSectionInfo? =")
        appendLine("        sections.firstOrNull { it.key == key }")
        appendLine()
        appendLine("    fun requireSection(key: String): AutoSettingsSectionInfo =")
        appendLine("        requireNotNull(section(key)) { \"Missing generated section metadata for \$key\" }")
        appendLine()
        appendLine("    fun setting(keyName: String): AutoSettingInfo? =")
        appendLine("        settings.firstOrNull { it.keyName == keyName }")
        appendLine()
        appendLine("    fun setting(key: Preferences.Key<*>): AutoSettingInfo? =")
        appendLine("        setting(key.name)")
        appendLine()
        appendLine("    fun requireSetting(keyName: String): AutoSettingInfo =")
        appendLine("        requireNotNull(setting(keyName)) { \"Missing generated setting metadata for \$keyName\" }")
        appendLine()
        appendLine("    fun requireSetting(key: Preferences.Key<*>): AutoSettingInfo =")
        appendLine("        requireSetting(key.name)")
        appendLine()
        appendLine("    fun scope(key: String): AutoSettingsSectionScope =")
        appendLine("        AutoSettingsSectionScope(")
        appendLine("            key = key,")
        appendLine("            info = requireSection(key),")
        appendLine("            settings = settingsIn(key)")
        appendLine("        )")
        appendLine("}")
        appendLine()
        appendLine("object AutoSettingsScopes {")
        sectionSpecs.forEach { section ->
            appendLine("    val ${section.propertyName}: AutoSettingsSectionScope")
            appendLine("        get() = AutoSettingsMetadata.scope(AutoSettingsSections.${section.propertyName})")
            appendLine()
        }
        appendLine("}")
    }
}

private fun buildSwitchBindingsFile(packageName: String, settings: List<SettingSpec>): String {
    val switchSettings = settings
        .filter {
            it.canGenerateAccessor &&
                it.ui == SettingUiType.Switch &&
                it.valueType == SettingValueType.Boolean
        }
        .sortedWith(compareBy<SettingSpec> { it.section }.thenBy { it.order }.thenBy { it.sortKey })

    return buildString {
        appendGeneratedHeader(packageName)
        appendLine("import kotlinx.coroutines.flow.Flow")
        appendLine()
        appendLine("data class AutoSettingsSwitchBinding(")
        appendLine("    val setting: AutoSettingInfo,")
        appendLine("    val defaultValue: Boolean,")
        appendLine("    val read: (AutoSettingsRepository) -> Flow<Boolean>,")
        appendLine("    val write: suspend (AutoSettingsRepository, Boolean) -> Unit")
        appendLine(")")
        appendLine()
        appendLine("object AutoSettingsSwitchBindings {")
        appendLine("    val entries: List<AutoSettingsSwitchBinding> = listOf(")
        appendLine(
            switchSettings.joinToString(separator = ",\n") { setting ->
                "        AutoSettingsSwitchBinding(\n" +
                    "            setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.${setting.keyName}),\n" +
                    "            defaultValue = ${setting.defaultLiteral()},\n" +
                    "            read = { repository -> repository.${setting.flowName} },\n" +
                    "            write = { repository, value -> repository.${setting.setterName}(value) }\n" +
                    "        )"
            }
        )
        appendLine("    )")
        appendLine()
        appendLine("    fun inSection(section: String): List<AutoSettingsSwitchBinding> =")
        appendLine("        entries.filter { it.setting.section == section }")
        appendLine("}")
    }
}

private fun SettingSpec.normalizeReadExpression(valueExpression: String): String {
    return normalizerQualifiedName?.let { "$it.normalize($valueExpression)" } ?: valueExpression
}

private fun SettingSpec.normalizeWriteExpression(valueExpression: String): String {
    return normalizerQualifiedName?.let { "$it.normalize($valueExpression)" } ?: valueExpression
}

private fun SettingSpec.defaultLiteral(): String {
    defaultExpression?.let { return it }
    return when (valueType) {
        SettingValueType.Boolean -> defaultBoolean.toString()
        SettingValueType.Float -> "${defaultFloat}f"
        SettingValueType.Int -> defaultInt.toString()
        SettingValueType.Long -> "${defaultLong}L"
        SettingValueType.String -> defaultString.toLiteral()
    }
}

private fun SettingSpec.iconResExpression(): String {
    return entryReference?.let { reference -> "$reference.iconRes" } ?: "0"
}

private fun SettingSpec.iconExpression(): String {
    return entryReference?.let { reference -> "$reference.icon" } ?: "AutoSettingIcon.None"
}

private fun SettingSpec.titleResExpression(): String {
    return entryReference?.let { reference -> "$reference.titleRes" } ?: "0"
}

private fun SettingSpec.descriptionResExpression(): String {
    return entryReference?.let { reference -> "$reference.descriptionRes" } ?: "0"
}

private fun SectionSpec.titleResExpression(): String {
    return entryReference?.let { reference -> "$reference.titleRes" } ?: "0"
}

private fun SectionSpec.descriptionResExpression(): String {
    return entryReference?.let { reference -> "$reference.descriptionRes" } ?: "0"
}

private fun SectionSpec.iconResExpression(): String {
    return entryReference?.let { reference -> "$reference.iconRes" } ?: "0"
}

private fun SectionSpec.iconExpression(): String {
    return entryReference?.let { reference -> "$reference.icon" } ?: "AutoSettingIcon.None"
}

private fun SettingValueType.preferenceKeyFactory(): String {
    return when (this) {
        SettingValueType.Boolean -> "booleanPreferencesKey"
        SettingValueType.Float -> "floatPreferencesKey"
        SettingValueType.Int -> "intPreferencesKey"
        SettingValueType.Long -> "longPreferencesKey"
        SettingValueType.String -> "stringPreferencesKey"
    }
}

private fun SettingValueType.kotlinType(): String {
    return when (this) {
        SettingValueType.Boolean -> "Boolean"
        SettingValueType.Float -> "Float"
        SettingValueType.Int -> "Int"
        SettingValueType.Long -> "Long"
        SettingValueType.String -> "String"
    }
}

private fun Sequence<KSAnnotation>.firstByName(name: String): KSAnnotation? {
    return firstOrNull { annotation ->
        annotation.annotationType.resolve().declaration.qualifiedName?.asString() == name
    }
}

private fun KSAnnotation.stringArgument(name: String): String {
    return argument(name) as? String ?: ""
}

private fun KSAnnotation.booleanArgument(name: String, defaultValue: Boolean = false): Boolean {
    return argument(name) as? Boolean ?: defaultValue
}

private fun KSAnnotation.floatArgument(name: String): Float {
    return when (val value = argument(name)) {
        is Float -> value
        is Double -> value.toFloat()
        else -> 0f
    }
}

private fun KSAnnotation.intArgument(name: String): Int {
    return (argument(name) as? Number)?.toInt() ?: 0
}

private fun KSAnnotation.longArgument(name: String): Long {
    return (argument(name) as? Number)?.toLong() ?: 0L
}

private inline fun <reified T : Enum<T>> KSAnnotation.enumArgument(
    name: String,
    defaultValue: T
): T {
    return enumArgumentOrNull<T>(name) ?: defaultValue
}

private inline fun <reified T : Enum<T>> KSAnnotation.enumArgumentOrNull(name: String): T? {
    val rawName = argument(name)?.toString()?.substringAfterLast('.')
    return enumValues<T>().firstOrNull { it.name == rawName }
}

private fun KSAnnotation.classArgument(name: String): String? {
    val value = argument(name) ?: return null
    val qualifiedName = when (value) {
        is KSType -> value.declaration.qualifiedName?.asString() ?: value.toString()
        else -> value.toString()
    }
    return qualifiedName.takeUnless {
        it == "kotlin.Unit" || it == "Unit" || it == "Unit::class"
    }
}

private fun KSAnnotation.argument(name: String): Any? {
    return arguments.firstOrNull { it.name?.asString() == name }?.value
}

private fun StringBuilder.appendGeneratedHeader(packageName: String) {
    appendLine("package $packageName")
    appendLine()
    appendLine("// Generated by AutoSettingsProcessor, do not edit manually")
    appendLine()
}

private fun CodeGenerator.writeKotlin(
    dependencies: Dependencies,
    packageName: String,
    fileName: String,
    content: String
) {
    createNewFile(
        dependencies = dependencies,
        packageName = packageName,
        fileName = fileName,
        extensionName = "kt"
    ).bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.write(content)
    }
}

private fun String.toLiteral(): String {
    val escaped = buildString {
        this@toLiteral.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
    return "\"$escaped\""
}

private fun String.toConstantName(): String {
    val result = StringBuilder()
    forEachIndexed { index, char ->
        when {
            char.isLetterOrDigit() -> {
                if (char.isUpperCase() && index > 0 && result.lastOrNull() != '_') {
                    result.append('_')
                }
                result.append(char.uppercaseChar())
            }
            result.lastOrNull() != '_' -> result.append('_')
        }
    }
    return result.toString().trim('_').ifBlank { "SETTING" }
}

private fun String.toSectionPropertyName(): String {
    val sanitized = filter { it.isLetterOrDigit() || it == '_' }
        .ifBlank { "general" }
    val identifier = if (sanitized.first().isDigit()) "_$sanitized" else sanitized
    return if (identifier in RESERVED_KOTLIN_IDENTIFIERS) {
        "${identifier}Section"
    } else {
        identifier
    }
}

private val RESERVED_KOTLIN_IDENTIFIERS = setOf(
    "as",
    "break",
    "class",
    "continue",
    "do",
    "else",
    "false",
    "for",
    "fun",
    "if",
    "in",
    "interface",
    "is",
    "null",
    "object",
    "package",
    "return",
    "super",
    "this",
    "throw",
    "true",
    "try",
    "typealias",
    "typeof",
    "val",
    "var",
    "when",
    "while",
    "toString",
    "hashCode",
    "equals"
)
