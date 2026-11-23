package moe.ouom.neriplayer.ui.screen.tab.settings.network

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.net.Proxy
import java.net.URI
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CommonR
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsKeys
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.network.proxy.DnsProvider
import moe.ouom.neriplayer.network.proxy.NetworkProxyEndpoint
import moe.ouom.neriplayer.network.proxy.parseDohEndpoint
import moe.ouom.neriplayer.ui.screen.tab.settings.component.AutoSettingSpecListItem
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import moe.ouom.neriplayer.ui.settings.AutoSettingsListItem

@Composable
internal fun NetworkSettingsSection(
    repository: SettingsRepository,
    bypassSystemProxy: Boolean,
    onBypassSystemProxyChange: (Boolean) -> Unit,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val schema = AutoSettingsSchema.network
    val proxy by remember(repository) { repository.settingFlow(schema.proxyEndpoint) }
        .collectAsStateWithLifecycle(initialValue = null)
    val doh by remember(repository) { repository.settingFlow(schema.dohEndpoint) }
        .collectAsStateWithLifecycle(initialValue = null)
    var showProxy by rememberSaveable { mutableStateOf(false) }
    var showDoh by rememberSaveable { mutableStateOf(false) }
    val endpoint = NetworkProxyEndpoint.parse(proxy.orEmpty())

    AutoSettingSpecListItem(
        setting = schema.proxyEndpoint,
        enabled = proxy != null,
        supportingContent = { Text(endpoint?.uri ?: stringResource(CommonR.string.network_proxy_disabled)) },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished,
        onClick = { showProxy = true }
    )
    AutoSettingsListItem(
        setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.BYPASS_PROXY),
        enabled = endpoint == null,
        supportingContent = {
            Text(stringResource(if (endpoint == null) CommonR.string.settings_bypass_proxy_desc else CommonR.string.network_proxy_priority))
        },
        trailingContent = {
            MiuixSettingsSwitch(
                checked = bypassSystemProxy,
                onCheckedChange = onBypassSystemProxyChange,
                enabled = endpoint == null
            )
        },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished,
        onClick = { onBypassSystemProxyChange(!bypassSystemProxy) }
    )
    AutoSettingSpecListItem(
        setting = schema.dohEndpoint,
        enabled = doh != null,
        supportingContent = {
            Text(DnsProvider.entries.find { it.url == doh }?.title
                ?: parseDohEndpoint(doh.orEmpty())?.host
                ?: stringResource(CommonR.string.network_system_dns))
        },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished,
        onClick = { showDoh = true }
    )

    if (showProxy && proxy != null) {
        ProxyDialog(endpoint, onDismiss = { showProxy = false }) { value ->
            scope.launch { repository.setSetting(schema.proxyEndpoint, value) }
            showProxy = false
        }
    }
    if (showDoh && doh != null) {
        DohDialog(doh.orEmpty(), onDismiss = { showDoh = false }) { value ->
            scope.launch { repository.setSetting(schema.dohEndpoint, value) }
            showDoh = false
        }
    }
}

@Composable
private fun ProxyDialog(current: NetworkProxyEndpoint?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var type by rememberSaveable { mutableStateOf(current?.type ?: Proxy.Type.DIRECT) }
    var host by rememberSaveable { mutableStateOf(current?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf(current?.port?.toString() ?: "8080") }
    val endpoint = remember(type, host, port) {
        val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return@remember null
        val scheme = if (type == Proxy.Type.SOCKS) "socks5" else "http"
        val address = runCatching { URI(scheme, null, host.trim(), portNumber, null, null, null) }.getOrNull()
        address?.toASCIIString()?.let(NetworkProxyEndpoint::parse)
    }
    NetworkDialog(
        title = stringResource(CommonR.string.settings_custom_proxy),
        onDismiss = onDismiss,
        valid = type == Proxy.Type.DIRECT || endpoint != null,
        onSave = { onSave(if (type == Proxy.Type.DIRECT) "" else requireNotNull(endpoint).uri) }
    ) {
        listOf(Proxy.Type.DIRECT, Proxy.Type.HTTP, Proxy.Type.SOCKS).forEach { option ->
            MiuixSettingsChoiceRow(
                title = when (option) {
                    Proxy.Type.DIRECT -> stringResource(CommonR.string.network_proxy_disabled)
                    Proxy.Type.HTTP -> "HTTP"
                    Proxy.Type.SOCKS -> "SOCKS5"
                },
                selected = option == type,
                onClick = { type = option }
            )
        }
        if (type != Proxy.Type.DIRECT) {
            MiuixSettingsTextField(
                value = host, onValueChange = { host = it }, singleLine = true,
                label = { Text(stringResource(CommonR.string.network_proxy_host)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            MiuixSettingsTextField(
                value = port, onValueChange = { port = it }, singleLine = true,
                label = { Text(stringResource(CommonR.string.network_proxy_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
        Text(stringResource(CommonR.string.settings_custom_proxy_desc))
    }
}

@Composable
private fun DohDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val custom = "custom"
    val preset = DnsProvider.entries.find { it.url == current }
    var selected by rememberSaveable { mutableStateOf(preset?.url ?: if (current.isEmpty()) "" else custom) }
    var url by rememberSaveable { mutableStateOf(if (selected == custom) current else "") }
    val customEndpoint = parseDohEndpoint(url)
    NetworkDialog(
        title = stringResource(CommonR.string.settings_dns_over_https),
        onDismiss = onDismiss,
        valid = selected != custom || customEndpoint != null,
        onSave = { onSave(if (selected == custom) requireNotNull(customEndpoint).toString() else selected) }
    ) {
        MiuixSettingsChoiceRow(stringResource(CommonR.string.network_system_dns), selected.isEmpty(), { selected = "" })
        DnsProvider.entries.forEach { provider ->
            MiuixSettingsChoiceRow(provider.title, selected == provider.url, { selected = provider.url })
        }
        MiuixSettingsChoiceRow(stringResource(CommonR.string.network_custom_dns), selected == custom, { selected = custom })
        if (selected == custom) {
            MiuixSettingsTextField(
                value = url, onValueChange = { url = it }, singleLine = true,
                label = { Text(stringResource(CommonR.string.network_resolver_url)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
        }
        Text(stringResource(CommonR.string.settings_dns_over_https_desc))
    }
}

@Composable
private fun NetworkDialog(
    title: String,
    onDismiss: () -> Unit,
    valid: Boolean,
    onSave: () -> Unit,
    content: @Composable () -> Unit
) {
    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { content() } },
        confirmButton = {
            MiuixSettingsTextButton(onClick = onSave, enabled = valid, text = { Text(stringResource(CommonR.string.action_confirm)) })
        },
        dismissButton = {
            MiuixSettingsTextButton(onClick = onDismiss, text = { Text(stringResource(CommonR.string.action_cancel)) })
        }
    )
}
