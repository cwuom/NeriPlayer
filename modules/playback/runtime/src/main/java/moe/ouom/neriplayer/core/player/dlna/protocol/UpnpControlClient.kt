package moe.ouom.neriplayer.core.player.dlna.protocol

import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDevice
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaTransportState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * UPnP SOAP & GENA control client for DLNA MediaRenderers.
 * Always uses Proxy.NO_PROXY so LAN renderer traffic never traverses app/system proxies.
 */
class UpnpControlClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(3_000L, TimeUnit.MILLISECONDS)
        .readTimeout(5_000L, TimeUnit.MILLISECONDS)
        .writeTimeout(5_000L, TimeUnit.MILLISECONDS)
        .callTimeout(8_000L, TimeUnit.MILLISECONDS)
        .build()
) {
    data class PositionInfo(
        val positionMs: Long,
        val durationMs: Long,
        val trackUri: String = ""
    )

    data class GenaSubscription(
        val sid: String,
        val timeoutSeconds: Int
    )

    fun getProtocolInfo(device: DlnaDevice): Result<String> {
        if (device.connectionManagerUrl.isBlank()) {
            return Result.success("")
        }
        return callSoap(
            controlUrl = device.connectionManagerUrl,
            serviceType = UpnpXmlParser.SERVICE_CONNECTION_MANAGER,
            action = "GetProtocolInfo",
            bodyXml = ""
        ).map { values -> values["Sink"].orEmpty() }
    }

    fun setAvTransportUri(device: DlnaDevice, uri: String, didlMetaData: String): Result<Unit> {
        val body = "<InstanceID>0</InstanceID>" +
            "<CurrentURI>${DidlLiteBuilder.escapeXml(uri)}</CurrentURI>" +
            "<CurrentURIMetaData>${DidlLiteBuilder.escapeXml(didlMetaData)}</CurrentURIMetaData>"
        return callSoap(
            controlUrl = device.avTransportControlUrl,
            serviceType = UpnpXmlParser.SERVICE_AV_TRANSPORT,
            action = "SetAVTransportURI",
            bodyXml = body
        ).map { }
    }

    fun setNextAvTransportUri(device: DlnaDevice, uri: String, didlMetaData: String): Result<Unit> {
        val body = "<InstanceID>0</InstanceID>" +
            "<NextURI>${DidlLiteBuilder.escapeXml(uri)}</NextURI>" +
            "<NextURIMetaData>${DidlLiteBuilder.escapeXml(didlMetaData)}</NextURIMetaData>"
        return callSoap(
            controlUrl = device.avTransportControlUrl,
            serviceType = UpnpXmlParser.SERVICE_AV_TRANSPORT,
            action = "SetNextAVTransportURI",
            bodyXml = body
        ).map { }
    }

    fun play(device: DlnaDevice): Result<Unit> =
        callSoap(
            controlUrl = device.avTransportControlUrl,
            serviceType = UpnpXmlParser.SERVICE_AV_TRANSPORT,
            action = "Play",
            bodyXml = "<InstanceID>0</InstanceID><Speed>1</Speed>"
        ).map { }

    fun pause(device: DlnaDevice): Result<Unit> =
        callSoap(
            controlUrl = device.avTransportControlUrl,
            serviceType = UpnpXmlParser.SERVICE_AV_TRANSPORT,
            action = "Pause",
            bodyXml = "<InstanceID>0</InstanceID>"
        ).map { }

    fun stop(device: DlnaDevice): Result<Unit> =
        callSoap(
            controlUrl = device.avTransportControlUrl,
            serviceType = UpnpXmlParser.SERVICE_AV_TRANSPORT,
            action = "Stop",
            bodyXml = "<InstanceID>0</InstanceID>"
        ).map { }

    fun seekRelTime(device: DlnaDevice, positionMs: Long): Result<Unit> {
        val target = UpnpXmlParser.formatUpnpTime(positionMs)
        return callSoap(
            controlUrl = device.avTransportControlUrl,
            serviceType = UpnpXmlParser.SERVICE_AV_TRANSPORT,
            action = "Seek",
            bodyXml = "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$target</Target>"
        ).map { }
    }

    fun getTransportState(device: DlnaDevice): Result<DlnaTransportState> =
        callSoap(
            controlUrl = device.avTransportControlUrl,
            serviceType = UpnpXmlParser.SERVICE_AV_TRANSPORT,
            action = "GetTransportInfo",
            bodyXml = "<InstanceID>0</InstanceID>"
        ).map { values -> parseTransportState(values["CurrentTransportState"]) }

    fun getPositionInfo(device: DlnaDevice): Result<PositionInfo> =
        callSoap(
            controlUrl = device.avTransportControlUrl,
            serviceType = UpnpXmlParser.SERVICE_AV_TRANSPORT,
            action = "GetPositionInfo",
            bodyXml = "<InstanceID>0</InstanceID>"
        ).map { values ->
            val relMs = values["RelTime"]?.let(UpnpXmlParser::parseUpnpTimeToMs) ?: 0L
            val durMs = values["TrackDuration"]?.let(UpnpXmlParser::parseUpnpTimeToMs) ?: 0L
            PositionInfo(
                positionMs = relMs,
                durationMs = durMs,
                trackUri = values["TrackURI"].orEmpty()
            )
        }

    fun getVolume(device: DlnaDevice): Result<Int> {
        if (device.renderingControlUrl.isBlank()) {
            return Result.failure(IllegalStateException("RenderingControl unsupported"))
        }
        return callSoap(
            controlUrl = device.renderingControlUrl,
            serviceType = UpnpXmlParser.SERVICE_RENDERING_CONTROL,
            action = "GetVolume",
            bodyXml = "<InstanceID>0</InstanceID><Channel>Master</Channel>"
        ).mapCatching { values ->
            val raw = values["CurrentVolume"]?.toIntOrNull()
                ?: error("Missing CurrentVolume")
            raw.coerceIn(0, 100)
        }
    }

    fun setVolume(device: DlnaDevice, volume: Int): Result<Unit> {
        if (device.renderingControlUrl.isBlank()) {
            return Result.failure(IllegalStateException("RenderingControl unsupported"))
        }
        val safeVolume = volume.coerceIn(0, 100)
        return callSoap(
            controlUrl = device.renderingControlUrl,
            serviceType = UpnpXmlParser.SERVICE_RENDERING_CONTROL,
            action = "SetVolume",
            bodyXml = "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$safeVolume</DesiredVolume>"
        ).map { }
    }

    fun subscribeGena(eventSubUrl: String, callbackUrl: String, timeoutSec: Int = 300): Result<GenaSubscription> {
        if (eventSubUrl.isBlank()) return Result.failure(IllegalArgumentException("No eventSubURL"))
        return runCatching {
            val request = Request.Builder()
                .url(eventSubUrl)
                .method("SUBSCRIBE", ByteArray(0).toRequestBody(null))
                .header("CALLBACK", "<$callbackUrl>")
                .header("NT", "upnp:event")
                .header("TIMEOUT", "Second-$timeoutSec")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("SUBSCRIBE failed with HTTP ${response.code}")
                }
                val sid = response.header("SID")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: error("SUBSCRIBE response missing SID")
                val parsedTimeout = response.header("TIMEOUT")
                    ?.substringAfter("Second-", missingDelimiterValue = "")
                    ?.toIntOrNull()
                    ?: timeoutSec
                GenaSubscription(sid = sid, timeoutSeconds = parsedTimeout)
            }
        }
    }

    fun unsubscribeGena(eventSubUrl: String, sid: String): Result<Unit> {
        if (eventSubUrl.isBlank() || sid.isBlank()) return Result.success(Unit)
        return runCatching {
            val request = Request.Builder()
                .url(eventSubUrl)
                .method("UNSUBSCRIBE", ByteArray(0).toRequestBody(null))
                .header("SID", sid)
                .build()
            httpClient.newCall(request).execute().use { }
        }
    }

    fun callSoap(
        controlUrl: String,
        serviceType: String,
        action: String,
        bodyXml: String
    ): Result<Map<String, String>> {
        if (controlUrl.isBlank()) {
            return Result.failure(IllegalArgumentException("Control URL is blank for $action"))
        }
        return runCatching {
            val envelope = UpnpXmlParser.buildSoapEnvelope(serviceType, action, bodyXml)
            val request = Request.Builder()
                .url(controlUrl)
                .post(envelope.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                .header("SOAPACTION", "\"$serviceType#$action\"")
                .build()
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                if (response.code != 200 && response.code != 500) {
                    error("SOAP $action failed HTTP ${response.code}")
                }
                when (val parsed = UpnpXmlParser.parseSoapResponse(responseBody, action)) {
                    is UpnpXmlParser.SoapParseResult.Success -> parsed.values
                    is UpnpXmlParser.SoapParseResult.Fault ->
                        error("UPnP fault ${parsed.errorCode}: ${parsed.errorDescription}")
                    is UpnpXmlParser.SoapParseResult.Invalid ->
                        error("Invalid SOAP response for $action: ${parsed.reason}")
                }
            }
        }
    }

    internal fun parseTransportState(raw: String?): DlnaTransportState {
        return when (raw?.trim()?.uppercase()) {
            "PLAYING" -> DlnaTransportState.PLAYING
            "PAUSED_PLAYBACK" -> DlnaTransportState.PAUSED_PLAYBACK
            "STOPPED" -> DlnaTransportState.STOPPED
            "TRANSITIONING" -> DlnaTransportState.TRANSITIONING
            "NO_MEDIA_PRESENT" -> DlnaTransportState.NO_MEDIA_PRESENT
            else -> DlnaTransportState.UNKNOWN
        }
    }
}
