package moe.ouom.neriplayer.data.local.database.migration.legacy

import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Locale

internal data class LegacyCatalogLookup(
    val stableKeysByReference: Map<String, Set<String>>,
    val stableKeysByNormalizedName: Map<String, Set<String>>
)

internal fun buildLegacyCatalogLookup(
    db: SupportSQLiteDatabase
): LegacyCatalogLookup {
    if (!hasTable(db, "downloaded_song_catalog")) {
        return LegacyCatalogLookup(emptyMap(), emptyMap())
    }
    val stableKeysByReference = linkedMapOf<String, MutableSet<String>>()
    val stableKeysByNormalizedName = linkedMapOf<String, MutableSet<String>>()
    val availableColumns = tableColumnNames(db, "downloaded_song_catalog")
    val projectionColumns = LEGACY_CATALOG_LOOKUP_COLUMNS.filter {
        it in availableColumns
    }
    if (projectionColumns.isEmpty()) {
        return LegacyCatalogLookup(emptyMap(), emptyMap())
    }
    val projection = projectionColumns.joinToString(", ") { "`$it`" }
    forEachLegacyBatch(
        db = db,
        tableName = "downloaded_song_catalog",
        projection = projection
    ) { cursor, _ ->
        val stableKey = cursorString(cursor, "stable_key")
            ?: deriveCatalogStableKey(cursor)
            ?: return@forEachLegacyBatch
        listOfNotNull(
            cursorString(cursor, "file_path"),
            cursorString(cursor, "media_uri"),
            cursorString(cursor, "catalog_key")
        ).forEach { reference ->
            stableKeysByReference.getOrPut(reference) { linkedSetOf() }
                .add(stableKey)
            normalizeLegacyBasename(reference)?.let { normalizedName ->
                stableKeysByNormalizedName.getOrPut(
                    normalizedName
                ) {
                    linkedSetOf()
                }.add(stableKey)
            }
        }
    }
    return LegacyCatalogLookup(
        stableKeysByReference = stableKeysByReference.mapValues { (_, keys) ->
            keys.toSet()
        },
        stableKeysByNormalizedName = stableKeysByNormalizedName
            .mapValues { (_, keys) -> keys.toSet() }
    )
}

internal fun findCatalogStableKeyForAudioName(
    catalogLookup: LegacyCatalogLookup,
    audioName: String?
): String? {
    val normalizedName = normalizeLegacyBasename(audioName) ?: return null
    return catalogLookup.stableKeysByNormalizedName[normalizedName]?.singleOrNull()
}

internal fun findCatalogStableKeyForSnapshotEntry(
    catalogLookup: LegacyCatalogLookup,
    reference: String?,
    mediaUri: String?,
    name: String?
): String? {
    val exactMatches = listOfNotNull(reference, mediaUri)
        .map(String::trim)
        .filter(String::isNotBlank)
        .flatMap { candidate ->
            catalogLookup.stableKeysByReference[candidate].orEmpty()
        }
        .toSet()
    return when {
        exactMatches.size == 1 -> exactMatches.first()
        exactMatches.isNotEmpty() -> null
        else -> normalizeLegacyBasename(name)
            ?.let(catalogLookup.stableKeysByNormalizedName::get)
            ?.singleOrNull()
    }
}

internal fun normalizeLegacyBasename(value: String?): String? {
    if (value == null) return null
    val basename = value.trim().trimEnd('/', '\\')
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .substringAfterLast(':')
    return basename.takeIf(String::isNotBlank)?.lowercase(Locale.ROOT)
}

internal val LEGACY_CATALOG_LOOKUP_COLUMNS = listOf(
    "stable_key",
    "id",
    "file_path",
    "media_uri",
    "catalog_key"
)
