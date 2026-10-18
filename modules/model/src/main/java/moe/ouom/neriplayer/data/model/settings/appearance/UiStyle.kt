package moe.ouom.neriplayer.data.model.settings.appearance

/**
 * Whole-app visual language. [Material] keeps the Material You look,
 * [Clean] is the preview theme with neutral surfaces, continuous corners and glass chrome.
 */
enum class UiStyle(val storageValue: String) {
    Material("material"),
    Clean("clean");

    companion object {
        const val DEFAULT_STORAGE_VALUE = "material"

        fun fromStorage(value: String?): UiStyle =
            entries.firstOrNull { it.storageValue == value } ?: Material
    }
}
