/*
 * Portions of this file are adapted from Capsulyric.
 * Copyright (c) 2026 FrancoGiudans.
 *
 * Capsulyric is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * See THIRD_PARTY_LICENSES.md and LICENSE for the applicable terms.
 */

package moe.ouom.neriplayer.shizuku

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Reflection compatibility layer ported from Capsulyric.
 *
 * HyperOS builds have shipped both the modern chain-aware firewall methods and
 * older network-management signatures. Try the modern API first, then the
 * legacy forms, and include the actual methods exposed by the binder in the
 * failure message so a device-specific mismatch is diagnosable.
 */
internal object FirewallCompat {
    internal const val OEM_DENY_CHAIN = 9
    internal const val RULE_DEFAULT = 0
    internal const val RULE_ALLOW = 1
    internal const val RULE_DENY = 2

    private data class ServiceBackend(
        val serviceName: String,
        val stubClassName: String,
        val label: String
    )

    private val serviceBackends = listOf(
        ServiceBackend(
            serviceName = "connectivity",
            stubClassName = "android.net.IConnectivityManager\$Stub",
            label = "ConnectivityManager"
        ),
        ServiceBackend(
            serviceName = "network_management",
            stubClassName = "android.os.INetworkManagementService\$Stub",
            label = "NetworkManagementService"
        )
    )

    fun setPackageNetworkingEnabled(
        uid: Int,
        enabled: Boolean,
        serviceFetcher: (serviceName: String, stubClassName: String, label: String) -> Any
    ) {
        val failures = mutableListOf<String>()
        for (backend in serviceBackends) {
            try {
                applyFirewallRule(serviceFetcher(backend.serviceName, backend.stubClassName, backend.label), uid, enabled)
                return
            } catch (error: Throwable) {
                val detail = buildString {
                    append("${backend.label} failed: ${error.message}")
                    val available = summarizeFirewallMethods(backend, serviceFetcher)
                    if (available.isNotBlank()) append(" | available=$available")
                }
                failures += detail
            }
        }
        throw IllegalStateException(
            "No compatible firewall backend found for uid=$uid. ${failures.joinToString(" || ")}"
        )
    }

    private fun applyFirewallRule(service: Any, uid: Int, enabled: Boolean) {
        val modernRule = if (enabled) RULE_DEFAULT else RULE_DENY
        val legacyRule = if (enabled) RULE_ALLOW else RULE_DENY
        val failures = mutableListOf<String>()

        if (!enabled) {
            runCatching {
                callMethodResilient(service, listOf("setFirewallChainEnabled"), OEM_DENY_CHAIN, true)
            }.onFailure { failures += "setFirewallChainEnabled: ${it.message}" }
        }

        val modernAttempts = listOf<() -> String>(
            {
                callMethodResilient(
                    service,
                    listOf("setUidFirewallRule", "setFirewallUidRule"),
                    OEM_DENY_CHAIN,
                    uid,
                    modernRule
                )
            },
            {
                callMethodResilient(
                    service,
                    listOf("setUidFirewallRules", "setFirewallUidRules"),
                    OEM_DENY_CHAIN,
                    intArrayOf(uid),
                    intArrayOf(modernRule)
                )
            }
        )
        if (runAttempts(modernAttempts, failures)) return

        if (!enabled) {
            runCatching {
                callMethodResilient(service, listOf("setFirewallEnabled"), true)
            }.onFailure { failures += "setFirewallEnabled: ${it.message}" }
        }

        val legacyAttempts = listOf<() -> String>(
            {
                callMethodResilient(
                    service,
                    listOf("setUidFirewallRule", "setFirewallUidRule"),
                    uid,
                    enabled
                )
            },
            {
                callMethodResilient(
                    service,
                    listOf("setUidFirewallRule", "setFirewallUidRule"),
                    uid,
                    legacyRule
                )
            },
            {
                callMethodResilient(
                    service,
                    listOf("setUidFirewallRules", "setFirewallUidRules"),
                    intArrayOf(uid),
                    intArrayOf(legacyRule)
                )
            }
        )
        if (runAttempts(legacyAttempts, failures)) return

        throw IllegalStateException(
            "No compatible firewall API on ${service.javaClass.name}. ${failures.joinToString(" || ")}"
        )
    }

    private fun runAttempts(
        attempts: List<() -> String>,
        failures: MutableList<String>
    ): Boolean {
        for (attempt in attempts) {
            try {
                attempt()
                return true
            } catch (error: Throwable) {
                failures += error.message ?: error.javaClass.name
            }
        }
        return false
    }

    private fun callMethodResilient(target: Any, methodNames: List<String>, vararg args: Any): String {
        val methods = buildList {
            for (methodName in methodNames) {
                addAll(target.javaClass.methods.filter {
                    it.name == methodName && it.parameterCount == args.size
                })
            }
        }
        if (methods.isEmpty()) {
            throw NoSuchMethodException(
                "Could not find any of ${methodNames.joinToString()} with ${args.size} args on ${target.javaClass.name}"
            )
        }

        var lastError: Throwable? = null
        for (method in methods) {
            try {
                invokeMethod(target, method, args)
                return method.name
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw lastError ?: NoSuchMethodException(
            "Methods ${methodNames.joinToString()} exist on ${target.javaClass.name} but none accepted the arguments"
        )
    }

    private fun invokeMethod(target: Any, method: Method, args: Array<out Any>) {
        method.isAccessible = true
        val adaptedArgs = Array(args.size) { index ->
            val arg = args[index]
            val expected = method.parameterTypes[index]
            when {
                expected == Int::class.javaPrimitiveType -> when (arg) {
                    is Int -> arg
                    is Boolean -> if (arg) 1 else 0
                    is Number -> arg.toInt()
                    else -> throw IllegalArgumentException("Unsupported arg $arg for int parameter")
                }
                expected == Boolean::class.javaPrimitiveType -> when (arg) {
                    is Boolean -> arg
                    is Number -> arg.toInt() != 0
                    else -> throw IllegalArgumentException("Unsupported arg $arg for boolean parameter")
                }
                expected == IntArray::class.java -> when (arg) {
                    is IntArray -> arg
                    is Array<*> -> IntArray(arg.size) { i -> (arg[i] as Number).toInt() }
                    else -> throw IllegalArgumentException("Unsupported arg $arg for int[] parameter")
                }
                expected.isInstance(arg) -> arg
                else -> throw IllegalArgumentException("Arg $arg does not match ${expected.name}")
            }
        }
        try {
            method.invoke(target, *adaptedArgs)
        } catch (error: InvocationTargetException) {
            throw error.targetException ?: error
        }
    }

    private fun summarizeFirewallMethods(
        backend: ServiceBackend,
        serviceFetcher: (serviceName: String, stubClassName: String, label: String) -> Any
    ): String = runCatching {
        serviceFetcher(backend.serviceName, backend.stubClassName, backend.label).javaClass.methods
            .filter { it.name.contains("Firewall", ignoreCase = true) }
            .sortedWith(compareBy({ it.name }, { it.parameterCount }))
            .joinToString("; ") { method ->
                val params = method.parameterTypes.joinToString(",") { it.simpleName }
                "${method.name}($params)"
            }
    }.getOrElse { "" }
}
