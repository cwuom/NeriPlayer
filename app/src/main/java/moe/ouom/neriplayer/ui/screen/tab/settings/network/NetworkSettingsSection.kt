package moe.ouom.neriplayer.ui.screen.tab.settings.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.net.Proxy
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CommonR
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsKeys
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.network.proxy.DnsProvider
import moe.ouom.neriplayer.network.proxy.NetworkProxyEndpoint
import moe.ouom.neriplayer.network.proxy.parseDohEndpoint
import moe.ouom.neriplayer.ui.component.common.NeriChoiceChip
import moe.ouom.neriplayer.ui.component.overlay.ScrollableDialogColumn
import moe.ouom.neriplayer.ui.screen.tab.settings.component.AutoSettingSpecListItem
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
internal fun ProxyDialog(current: NetworkProxyEndpoint?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var type by rememberSaveable { mutableStateOf(current?.type ?: Proxy.Type.DIRECT) }
    var host by rememberSaveable { mutableStateOf(current?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf(current?.port?.toString() ?: "8080") }
    val endpoint = remember(type, host, port) { NetworkProxyEndpoint.fromFields(type, host, port) }
    NetworkDialog(
        title = stringResource(CommonR.string.settings_custom_proxy),
        description = stringResource(CommonR.string.settings_custom_proxy_desc),
        onDismiss = onDismiss,
        valid = type == Proxy.Type.DIRECT || endpoint != null,
        onSave = { onSave(if (type == Proxy.Type.DIRECT) "" else requireNotNull(endpoint).uri) }
    ) {
        NetworkChoices {
            NeriChoiceChip(
                selected = type == Proxy.Type.DIRECT,
                onClick = { type = Proxy.Type.DIRECT },
                label = stringResource(CommonR.string.network_proxy_off)
            )
            NeriChoiceChip(type == Proxy.Type.HTTP, { type = Proxy.Type.HTTP }, "HTTP")
            NeriChoiceChip(type == Proxy.Type.SOCKS, { type = Proxy.Type.SOCKS }, "SOCKS5")
        }
        if (type != Proxy.Type.DIRECT) {
            MiuixSettingsTextField(
                value = host, onValueChange = { host = it }, singleLine = true,
                label = { Text(stringResource(CommonR.string.network_proxy_host)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
            )
            MiuixSettingsTextField(
                value = port, onValueChange = { port = it }, singleLine = true,
                label = { Text(stringResource(CommonR.string.network_proxy_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
            )
        }
    }
}

@Composable
internal fun DohDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val custom = "custom"
    val preset = DnsProvider.entries.find { it.url == current }
    var selected by rememberSaveable { mutableStateOf(preset?.url ?: if (current.isEmpty()) "" else custom) }
    var url by rememberSaveable { mutableStateOf(if (selected == custom) current else "") }
    val customEndpoint = parseDohEndpoint(url)
    NetworkDialog(
        title = stringResource(CommonR.string.settings_dns_over_https),
        description = stringResource(CommonR.string.settings_dns_over_https_desc),
        onDismiss = onDismiss,
        valid = selected != custom || customEndpoint != null,
        onSave = { onSave(if (selected == custom) requireNotNull(customEndpoint).toString() else selected) }
    ) {
        NetworkChoices {
            NeriChoiceChip(selected.isEmpty(), { selected = "" }, stringResource(CommonR.string.network_system_dns))
            DnsProvider.entries.forEach { provider ->
                NeriChoiceChip(selected == provider.url, { selected = provider.url }, provider.title)
            }
            NeriChoiceChip(selected == custom, { selected = custom }, stringResource(CommonR.string.network_custom_dns))
        }
        if (selected == custom) {
            MiuixSettingsTextField(
                value = url, onValueChange = { url = it }, singleLine = true,
                label = { Text(stringResource(CommonR.string.network_resolver_url)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NetworkChoices(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) { content() }
}

@Composable
private fun NetworkDialog(
    title: String,
    description: String,
    onDismiss: () -> Unit,
    valid: Boolean,
    onSave: () -> Unit,
    content: @Composable () -> Unit
) {
    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            ScrollableDialogColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            MiuixSettingsTextButton(onClick = onSave, enabled = valid, text = { Text(stringResource(CommonR.string.action_confirm)) })
        },
        dismissButton = {
            MiuixSettingsTextButton(onClick = onDismiss, text = { Text(stringResource(CommonR.string.action_cancel)) })
        }
    )
}
