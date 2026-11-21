package moe.ouom.neriplayer.core.di

internal fun applyBypassProxyChange(
    enabled: Boolean,
    currentValue: Boolean,
    applyValue: (Boolean) -> Unit,
    onChanged: () -> Unit
) {
    if (enabled == currentValue) return
    applyValue(enabled)
    onChanged()
}
