package moe.ouom.neriplayer.ui.i18n

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources

sealed interface UiText {
    fun resolve(resources: Resources): String

    data class Resource(
        @param:StringRes val id: Int,
        val arguments: List<Any?> = emptyList()
    ) : UiText {
        override fun resolve(resources: Resources): String =
            resources.getString(id, *arguments.resolve(resources))
    }

    data class Plural(
        @param:PluralsRes val id: Int,
        val quantity: Int,
        val arguments: List<Any?> = emptyList()
    ) : UiText {
        override fun resolve(resources: Resources): String =
            resources.getQuantityString(id, quantity, *arguments.resolve(resources))
    }

    data class Literal(val value: String) : UiText {
        override fun resolve(resources: Resources): String = value
    }
}

private fun List<Any?>.resolve(resources: Resources): Array<Any?> =
    map { if (it is UiText) it.resolve(resources) else it }.toTypedArray()

@Composable
fun UiText.asString(): String = resolve(LocalResources.current)
