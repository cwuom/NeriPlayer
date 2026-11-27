package moe.ouom.neriplayer.platform.youtube.api.protocol

import java.util.Locale
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeCatalogPolicy

object YouTubeCatalogSource {
    private class Providers(val policy: () -> YouTubeCatalogPolicy, val systemLocale: () -> Locale)
    private val defaults = object : YouTubeCatalogPolicy {}

    @Volatile
    private var providers = Providers({ defaults }, Locale::getDefault)

    fun bind(policy: () -> YouTubeCatalogPolicy, systemLocale: () -> Locale) {
        providers = Providers(policy, systemLocale)
    }

    internal val policy: YouTubeCatalogPolicy get() = providers.policy()
    internal fun systemLocale(): Locale = providers.systemLocale()
}
