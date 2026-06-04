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
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.validate
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

        val invalidSymbols = catalogSymbols.filterNot { it.validate() }
        if (invalidSymbols.isNotEmpty()) return invalidSymbols

        val catalogs = catalogSymbols.map { it.toCatalogSpec() }
        if (catalogs.isEmpty()) {
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
            it.ui == SettingUiType.Switch && it.titleRes.isBlank()
        }
        if (switchSettingsMissingTitle.isNotEmpty()) {
            switchSettingsMissingTitle.forEach { setting ->
                logger.error(
                    "@AutoSetting ui=Switch must define titleRes for ${setting.key}",
                    catalog.declaration
                )
            }
            return
        }

        val dependencies = Dependencies(
            aggregating = false,
            sources = arrayOf(catalog.declaration.containingFile).filterNotNull().toTypedArray()
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
            fileName = "AutoSettingsUi",
            content = buildUiFile(packageName, catalog.settings)
        )
    }
}

private data class CatalogSpec(
    val declaration: KSClassDeclaration,
    val packageName: String,
    val settingsKeysPackageName: String,
    val sectionSpecs: List<SectionSpec>,
    val settings: List<SettingSpec>
)

private data class SectionSpec(
    val key: String,
    val propertyName: String,
    val titleRes: String,
    val descriptionRes: String,
    val order: Int
)

private data class SettingSpec(
    val propertyName: String,
    val key: String,
    val valueType: SettingValueType,
    val defaultBoolean: Boolean,
    val defaultFloat: Float,
    val defaultInt: Int,
    val defaultLong: Long,
    val defaultString: String,
    val titleRes: String,
    val descriptionRes: String,
    val section: String,
    val order: Int,
    val ui: SettingUiType,
    val access: SettingAccessMode,
    val constantName: String,
    val entryReference: String?,
    val exportable: Boolean,
    val repositoryName: String,
    val normalizerQualifiedName: String?
) {
    val keyName: String = constantName.ifBlank { key.toConstantName() }
    val valueName: String = repositoryName.ifBlank { propertyName }
    val flowName: String = "${valueName}Flow"
    val setterName: String = "set${valueName.replaceFirstChar { it.uppercaseChar() }}"
    val canGenerateAccessor: Boolean = access == SettingAccessMode.ReadWrite
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
        .sortedWith(compareBy<SettingSpec> { it.section }.thenBy { it.order }.thenBy { it.key })
        .toList()
    val sectionSpecs = collectSectionSpecs(settings)
    return CatalogSpec(
        declaration = this,
        packageName = packageName,
        settingsKeysPackageName = settingsKeysPackageName,
        sectionSpecs = sectionSpecs,
        settings = settings
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
                titleRes = annotation.stringArgument("titleRes"),
                descriptionRes = annotation.stringArgument("descriptionRes"),
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
                titleRes = "",
                descriptionRes = "",
                order = Int.MAX_VALUE
            )
        }
        .sortedWith(compareBy<SectionSpec> { it.order }.thenBy { it.key })
}

private fun KSClassDeclaration.collectSettingSpecs(
    sectionHint: String? = null
): Sequence<SettingSpec> {
    return sequence {
        getAllProperties().forEach { property ->
            property.toSettingSpec(sectionHint)?.let { setting -> yield(setting) }
        }
        declarations
            .filterIsInstance<KSClassDeclaration>()
            .forEach { nestedDeclaration ->
                yieldAll(nestedDeclaration.collectSettingSpecs(nestedDeclaration.simpleName.asString()))
            }
    }
}

private fun KSPropertyDeclaration.toSettingSpec(sectionHint: String? = null): SettingSpec? {
    val annotation = annotations.firstByName(AutoSetting::class.qualifiedName.orEmpty()) ?: return null
    val inferredSection = sectionHint ?: parentSectionName() ?: "general"
    return SettingSpec(
        propertyName = simpleName.asString(),
        key = annotation.stringArgument("key"),
        valueType = annotation.enumArgument("type", SettingValueType.Boolean),
        defaultBoolean = annotation.booleanArgument("defaultBoolean"),
        defaultFloat = annotation.floatArgument("defaultFloat"),
        defaultInt = annotation.intArgument("defaultInt"),
        defaultLong = annotation.longArgument("defaultLong"),
        defaultString = annotation.stringArgument("defaultString"),
        titleRes = annotation.stringArgument("titleRes"),
        descriptionRes = annotation.stringArgument("descriptionRes"),
        section = annotation.stringArgument("section").ifBlank { inferredSection },
        order = annotation.intArgument("order"),
        ui = annotation.enumArgument("ui", SettingUiType.None),
        access = annotation.enumArgument("access", SettingAccessMode.ReadWrite),
        constantName = annotation.stringArgument("constantName"),
        entryReference = sourceReference().takeIf { hasAutoSettingEntryType() },
        exportable = annotation.booleanArgument("exportable", defaultValue = true),
        repositoryName = annotation.stringArgument("repositoryName"),
        normalizerQualifiedName = annotation.classArgument("normalizer")
    )
}

private fun KSPropertyDeclaration.hasAutoSettingEntryType(): Boolean {
    return type.resolve().declaration.qualifiedName?.asString() ==
        "moe.ouom.neriplayer.ksp.annotations.AutoSettingEntry"
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
                "    val ${setting.keyName} = ${setting.valueType.preferenceKeyFactory()}(${setting.key.toLiteral()})"
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
            appendLine("        }")
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
        appendLine("import moe.ouom.neriplayer.R")
        appendLine("import moe.ouom.neriplayer.ksp.annotations.AutoSettingIcon")
        appendLine("import moe.ouom.neriplayer.ksp.annotations.SettingAccessMode")
        appendLine("import moe.ouom.neriplayer.ksp.annotations.SettingUiType")
        appendLine("import moe.ouom.neriplayer.ksp.annotations.SettingValueType")
        appendLine()
        appendLine("data class AutoSettingsSectionInfo(")
        appendLine("    val key: String,")
        appendLine("    val titleRes: Int,")
        appendLine("    val descriptionRes: Int,")
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
                    "titleRes = ${section.resourceExpression("titleRes")}, " +
                    "descriptionRes = ${section.resourceExpression("descriptionRes")}, " +
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
                    "keyName = ${setting.key.toLiteral()}, " +
                    "propertyName = ${setting.propertyName.toLiteral()}, " +
                    "section = ${setting.section.toLiteral()}, " +
                    "valueType = SettingValueType.${setting.valueType.name}, " +
                    "ui = SettingUiType.${setting.ui.name}, " +
                    "access = SettingAccessMode.${setting.access.name}, " +
                    "titleRes = ${setting.resourceExpression("titleRes")}, " +
                    "descriptionRes = ${setting.resourceExpression("descriptionRes")}, " +
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

private fun buildUiFile(packageName: String, settings: List<SettingSpec>): String {
    val switchSettings = settings
        .filter {
            it.canGenerateAccessor &&
                it.ui == SettingUiType.Switch &&
                it.valueType == SettingValueType.Boolean
        }
        .sortedWith(compareBy<SettingSpec> { it.section }.thenBy { it.order }.thenBy { it.key })

    return buildString {
        appendGeneratedHeader(packageName)
        appendLine("import androidx.compose.foundation.layout.size")
        appendLine("import androidx.compose.material.icons.Icons")
        appendLine("import androidx.compose.material.icons.outlined.AdsClick")
        appendLine("import androidx.compose.material.icons.outlined.Analytics")
        appendLine("import androidx.compose.material.icons.outlined.AutoAwesome")
        appendLine("import androidx.compose.material.icons.outlined.BlurOn")
        appendLine("import androidx.compose.material.icons.outlined.Brightness4")
        appendLine("import androidx.compose.material.icons.outlined.Download")
        appendLine("import androidx.compose.material.icons.outlined.Error")
        appendLine("import androidx.compose.material.icons.outlined.Home")
        appendLine("import androidx.compose.material.icons.outlined.Info")
        appendLine("import androidx.compose.material.icons.outlined.Keyboard")
        appendLine("import androidx.compose.material.icons.outlined.LibraryMusic")
        appendLine("import androidx.compose.material.icons.outlined.Settings")
        appendLine("import androidx.compose.material.icons.outlined.Subtitles")
        appendLine("import androidx.compose.material.icons.outlined.Tune")
        appendLine("import androidx.compose.material.icons.outlined.Wallpaper")
        appendLine("import androidx.compose.material3.Icon")
        appendLine("import androidx.compose.material3.ListItem")
        appendLine("import androidx.compose.material3.ListItemDefaults")
        appendLine("import androidx.compose.material3.MaterialTheme")
        appendLine("import androidx.compose.material3.Switch")
        appendLine("import androidx.compose.material3.Text")
        appendLine("import androidx.compose.runtime.Composable")
        appendLine("import androidx.compose.runtime.collectAsState")
        appendLine("import androidx.compose.runtime.getValue")
        appendLine("import androidx.compose.ui.Modifier")
        appendLine("import androidx.compose.ui.graphics.Color")
        appendLine("import androidx.compose.ui.graphics.painter.Painter")
        appendLine("import androidx.compose.ui.graphics.vector.ImageVector")
        appendLine("import androidx.compose.ui.res.painterResource")
        appendLine("import androidx.compose.ui.res.stringResource")
        appendLine("import androidx.compose.ui.unit.dp")
        appendLine("import kotlinx.coroutines.CoroutineScope")
        appendLine("import kotlinx.coroutines.launch")
        appendLine("import moe.ouom.neriplayer.R")
        appendLine("import moe.ouom.neriplayer.ksp.annotations.AutoSettingIcon")
        appendLine("import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable")
        appendLine()
        appendLine("@Composable")
        appendLine("fun AutoSettingsListItem(")
        appendLine("    setting: AutoSettingInfo,")
        appendLine("    modifier: Modifier = Modifier,")
        appendLine("    enabled: Boolean = true,")
        appendLine("    showDefaultIcon: Boolean = true,")
        appendLine("    leadingContent: (@Composable () -> Unit)? = null,")
        appendLine("    supportingContent: (@Composable () -> Unit)? = null,")
        appendLine("    trailingContent: (@Composable () -> Unit)? = null,")
        appendLine("    onClick: (() -> Unit)? = null")
        appendLine(") {")
        appendLine("    val title = autoSettingsString(setting.titleRes) ?: setting.keyName")
        appendLine("    val description = autoSettingsString(setting.descriptionRes)")
        appendLine("    val clickableModifier = if (onClick == null) {")
        appendLine("        modifier")
        appendLine("    } else {")
        appendLine("        modifier.settingsItemClickable(enabled = enabled, onClick = onClick)")
        appendLine("    }")
        appendLine("    val autoLeadingContent: (@Composable () -> Unit)? = when {")
        appendLine("        leadingContent != null -> leadingContent")
        appendLine("        showDefaultIcon -> {")
        appendLine("            {")
        appendLine("                AutoSettingsIcon(")
        appendLine("                    painter = autoSettingsIconPainter(setting.iconRes),")
        appendLine("                    imageVector = autoSettingsIconVector(setting.icon),")
        appendLine("                    contentDescription = title")
        appendLine("                )")
        appendLine("            }")
        appendLine("        }")
        appendLine("        else -> null")
        appendLine("    }")
        appendLine("    ListItem(")
        appendLine("        modifier = clickableModifier,")
        appendLine("        leadingContent = autoLeadingContent,")
        appendLine("        headlineContent = { Text(title) },")
        appendLine("        supportingContent = supportingContent ?: description?.let { text ->")
        appendLine("            { Text(text) }")
        appendLine("        },")
        appendLine("        trailingContent = trailingContent,")
        appendLine("        colors = ListItemDefaults.colors(containerColor = Color.Transparent)")
        appendLine("    )")
        appendLine("}")
        appendLine()
        appendLine("@Composable")
        appendLine("fun AutoSettingsSwitchItems(")
        appendLine("    repository: AutoSettingsRepository,")
        appendLine("    scope: CoroutineScope,")
        appendLine("    sectionScope: AutoSettingsSectionScope")
        appendLine(") {")
        appendLine("    AutoSettingsSwitchItems(")
        appendLine("        repository = repository,")
        appendLine("        scope = scope,")
        appendLine("        section = sectionScope.key")
        appendLine("    )")
        appendLine("}")
        appendLine()
        appendLine("@Composable")
        appendLine("fun AutoSettingsSwitchItems(")
        appendLine("    repository: AutoSettingsRepository,")
        appendLine("    scope: CoroutineScope,")
        appendLine("    section: String")
        appendLine(") {")
        appendLine("    when (section) {")
        switchSettings.groupBy { it.section }.forEach { (section, sectionSettings) ->
            appendLine("        ${section.toLiteral()} -> {")
            sectionSettings.forEach { setting ->
                appendLine("            val ${setting.valueName} by repository.${setting.flowName}.collectAsState(initial = ${setting.defaultLiteral()})")
                appendLine("            AutoSettingsSwitchItem(")
                appendLine("                checked = ${setting.valueName},")
                appendLine("                titleRes = R.string.${setting.titleRes},")
                appendLine("                descriptionRes = ${setting.resourceExpression("descriptionRes")},")
                appendLine("                iconPainter = ${setting.iconPainterExpression()},")
                appendLine("                icon = ${setting.iconExpression()},")
                appendLine("                onCheckedChange = { value ->")
                appendLine("                    scope.launch { repository.${setting.setterName}(value) }")
                appendLine("                }")
                appendLine("            )")
            }
            appendLine("        }")
        }
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("@Composable")
        appendLine("private fun autoSettingsIconPainter(iconRes: Int): Painter? {")
        appendLine("    return if (iconRes == 0) null else painterResource(iconRes)")
        appendLine("}")
        appendLine()
        appendLine("private fun autoSettingsIconVector(icon: AutoSettingIcon): ImageVector? {")
        appendLine("    return when (icon) {")
        appendLine("        AutoSettingIcon.None -> null")
        appendLine("        AutoSettingIcon.AdsClick -> Icons.Outlined.AdsClick")
        appendLine("        AutoSettingIcon.Analytics -> Icons.Outlined.Analytics")
        appendLine("        AutoSettingIcon.AutoAwesome -> Icons.Outlined.AutoAwesome")
        appendLine("        AutoSettingIcon.BlurOn -> Icons.Outlined.BlurOn")
        appendLine("        AutoSettingIcon.Brightness4 -> Icons.Outlined.Brightness4")
        appendLine("        AutoSettingIcon.Download -> Icons.Outlined.Download")
        appendLine("        AutoSettingIcon.Error -> Icons.Outlined.Error")
        appendLine("        AutoSettingIcon.Home -> Icons.Outlined.Home")
        appendLine("        AutoSettingIcon.Info -> Icons.Outlined.Info")
        appendLine("        AutoSettingIcon.Keyboard -> Icons.Outlined.Keyboard")
        appendLine("        AutoSettingIcon.LibraryMusic -> Icons.Outlined.LibraryMusic")
        appendLine("        AutoSettingIcon.Settings -> Icons.Outlined.Settings")
        appendLine("        AutoSettingIcon.Subtitles -> Icons.Outlined.Subtitles")
        appendLine("        AutoSettingIcon.Tune -> Icons.Outlined.Tune")
        appendLine("        AutoSettingIcon.Wallpaper -> Icons.Outlined.Wallpaper")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("@Composable")
        appendLine("private fun autoSettingsString(resId: Int): String? {")
        appendLine("    return if (resId == 0) null else stringResource(resId)")
        appendLine("}")
        appendLine()
        appendLine("@Composable")
        appendLine("private fun AutoSettingsSwitchItem(")
        appendLine("    checked: Boolean,")
        appendLine("    titleRes: Int,")
        appendLine("    descriptionRes: Int,")
        appendLine("    iconPainter: Painter?,")
        appendLine("    icon: AutoSettingIcon,")
        appendLine("    onCheckedChange: (Boolean) -> Unit")
        appendLine(") {")
        appendLine("    val title = stringResource(titleRes)")
        appendLine("    ListItem(")
        appendLine("        modifier = Modifier.settingsItemClickable { onCheckedChange(!checked) },")
        appendLine("        leadingContent = {")
        appendLine("            AutoSettingsIcon(")
        appendLine("                painter = iconPainter,")
        appendLine("                imageVector = autoSettingsIconVector(icon),")
        appendLine("                contentDescription = title")
        appendLine("            )")
        appendLine("        },")
        appendLine("        headlineContent = { Text(title) },")
        appendLine("        supportingContent = if (descriptionRes == 0) null else {")
        appendLine("            { Text(stringResource(descriptionRes)) }")
        appendLine("        },")
        appendLine("        trailingContent = {")
        appendLine("            Switch(checked = checked, onCheckedChange = onCheckedChange)")
        appendLine("        },")
        appendLine("        colors = ListItemDefaults.colors(containerColor = Color.Transparent)")
        appendLine("    )")
        appendLine("}")
        appendLine()
        appendLine("@Composable")
        appendLine("private fun AutoSettingsIcon(")
        appendLine("    painter: Painter?,")
        appendLine("    imageVector: ImageVector?,")
        appendLine("    contentDescription: String")
        appendLine(") {")
        appendLine("    if (painter != null) {")
        appendLine("        Icon(")
        appendLine("            painter = painter,")
        appendLine("            contentDescription = contentDescription,")
        appendLine("            modifier = Modifier.size(24.dp),")
        appendLine("            tint = MaterialTheme.colorScheme.onSurface")
        appendLine("        )")
        appendLine("        return")
        appendLine("    }")
        appendLine("    if (imageVector != null) {")
        appendLine("        Icon(")
        appendLine("            imageVector = imageVector,")
        appendLine("            contentDescription = contentDescription,")
        appendLine("            modifier = Modifier.size(24.dp),")
        appendLine("            tint = MaterialTheme.colorScheme.onSurface")
        appendLine("        )")
        appendLine("        return")
        appendLine("    }")
        appendLine("    Icon(")
        appendLine("        imageVector = Icons.Outlined.Settings,")
        appendLine("        contentDescription = contentDescription,")
        appendLine("        modifier = Modifier.size(24.dp),")
        appendLine("        tint = MaterialTheme.colorScheme.onSurface")
        appendLine("    )")
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
    return when (valueType) {
        SettingValueType.Boolean -> defaultBoolean.toString()
        SettingValueType.Float -> "${defaultFloat}f"
        SettingValueType.Int -> defaultInt.toString()
        SettingValueType.Long -> "${defaultLong}L"
        SettingValueType.String -> defaultString.toLiteral()
    }
}

private fun SettingSpec.iconPainterExpression(): String {
    return entryReference?.let { reference ->
        "autoSettingsIconPainter($reference.iconRes)"
    } ?: "null"
}

private fun SettingSpec.iconExpression(): String {
    return entryReference?.let { reference -> "$reference.icon" } ?: "AutoSettingIcon.None"
}

private fun SettingSpec.iconResExpression(): String {
    return entryReference?.let { reference -> "$reference.iconRes" } ?: "0"
}

private fun SettingSpec.resourceExpression(propertyName: String): String {
    val value = when (propertyName) {
        "titleRes" -> titleRes
        "descriptionRes" -> descriptionRes
        else -> ""
    }
    return value.takeIf { it.isNotBlank() }?.let { "R.string.$it" } ?: "0"
}

private fun SectionSpec.resourceExpression(propertyName: String): String {
    val value = when (propertyName) {
        "titleRes" -> titleRes
        "descriptionRes" -> descriptionRes
        else -> ""
    }
    return value.takeIf { it.isNotBlank() }?.let { "R.string.$it" } ?: "0"
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
    val rawName = argument(name)?.toString()?.substringAfterLast('.')
    return enumValues<T>().firstOrNull { it.name == rawName } ?: defaultValue
}

private fun KSAnnotation.classArgument(name: String): String? {
    val value = argument(name) ?: return null
    val qualifiedName = value.toString()
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
