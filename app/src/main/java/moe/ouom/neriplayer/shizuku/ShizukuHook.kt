/*
 * Portions of this file are adapted from Capsulyric.
 * Copyright (c) 2026 FrancoGiudans.
 *
 * Capsulyric is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * Portions of the implementation are adapted from InstallerX Revived
 * (https://github.com/wxxsfxyzm/InstallerX-Revived), Copyright (C) 2023–2026
 * iamr0s and InstallerX Revived contributors, under GPL-3.0-only.
 *
 * See THIRD_PARTY_LICENSES.md and LICENSE for the applicable terms.
 */

package moe.ouom.neriplayer.shizuku

import android.content.Context
import android.net.IConnectivityManager
import android.os.IBinder
import android.os.INetworkManagementService
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Capsulyric's Shizuku hook path, adapted for NeriPlayer.
 *
 * The important detail here is that the binder is wrapped before the hidden
 * service interface is reconstructed. This lets the privileged Shizuku binder
 * forward the firewall transaction instead of invoking the app-side service
 * proxy directly.
 */
internal object ShizukuHook {
    private const val TAG = "NeriPlayerShizukuHook"
    private val hookedServiceCache = ConcurrentHashMap<String, Any>()

    @Volatile
    private var hookedConnectivityManager: IConnectivityManager? = null

    @Volatile
    private var hookedNetworkManagementService: INetworkManagementService? = null

    /** Drop all app-side proxies after a Shizuku restart or binder transaction failure. */
    fun clearCaches() {
        synchronized(this) {
            hookedConnectivityManager = null
            hookedNetworkManagementService = null
            hookedServiceCache.clear()
        }
    }

    fun setPackageNetworkingEnabled(uid: Int, enabled: Boolean) {
        // Hidden API exemptions are process-local. Reapply them at the privileged boundary and
        // rebuild every proxy so a restarted Shizuku server cannot leave a stale wrapper behind.
        AndroidRuntimeCompatibility.installHiddenApiExemptions()
        clearCaches()
        var typedFailure: Throwable? = null
        try {
            setPackageNetworkingEnabledViaHookedConnectivity(uid, enabled)
            return
        } catch (error: Throwable) {
            typedFailure = error
            Log.w(TAG, "Typed hooked connectivity path failed; trying network-management service", error)
        }

        try {
            setPackageNetworkingEnabledViaHookedNetworkManagement(uid, enabled)
            return
        } catch (networkManagementFailure: Throwable) {
            typedFailure?.addSuppressed(networkManagementFailure)
            Log.w(TAG, "Typed network-management path failed; using reflective fallback", networkManagementFailure)
        }

        try {
            FirewallCompat.setPackageNetworkingEnabled(uid, enabled) { serviceName, stubClassName, label ->
                getWrappedService(serviceName, stubClassName, label)
            }
            Log.d(
                TAG,
                "XMSF networking ${if (enabled) "restored" else "blocked"} for uid=$uid via reflection"
            )
        } catch (fallbackFailure: Throwable) {
            typedFailure?.let(fallbackFailure::addSuppressed)
            throw IllegalStateException(
                buildString {
                    append("Typed hooked IConnectivityManager failed")
                    append(": ")
                    append(typedFailure?.javaClass?.name ?: "unknown")
                    typedFailure?.message?.takeIf(String::isNotBlank)?.let {
                        append(": ")
                        append(it)
                    }
                    append("; reflective fallback failed: ")
                    append(fallbackFailure.message ?: fallbackFailure.javaClass.name)
                },
                fallbackFailure
            )
        }
    }

    private fun setPackageNetworkingEnabledViaHookedConnectivity(uid: Int, enabled: Boolean) {
        val chain = FirewallCompat.OEM_DENY_CHAIN
        val rule = if (enabled) FirewallCompat.RULE_DEFAULT else FirewallCompat.RULE_DENY
        val connectivityManager = getHookedConnectivityManager()

        if (!enabled) {
            // OEM_DENY_3 is a blacklist chain. Enabling the chain is required before
            // the per-UID DENY rule can take effect. Never disable it during restore:
            // other packages may be using the same chain.
            connectivityManager.setFirewallChainEnabled(chain, true)
        }
        connectivityManager.setUidFirewallRule(chain, uid, rule)
        Log.i(
            TAG,
            "XMSF network ${if (enabled) "restored" else "blocked"} for uid=$uid on chain=$chain"
        )
    }

    private fun setPackageNetworkingEnabledViaHookedNetworkManagement(uid: Int, enabled: Boolean) {
        val chain = FirewallCompat.OEM_DENY_CHAIN
        val rule = if (enabled) FirewallCompat.RULE_DEFAULT else FirewallCompat.RULE_DENY
        val networkManagementService = getHookedNetworkManagementService()

        if (!enabled) {
            networkManagementService.setFirewallChainEnabled(chain, true)
        }
        networkManagementService.setUidFirewallRule(chain, uid, rule)
        Log.i(
            TAG,
            "XMSF network ${if (enabled) "restored" else "blocked"} for uid=$uid on chain=$chain via network-management"
        )
    }

    private fun getHookedConnectivityManager(): IConnectivityManager {
        hookedConnectivityManager?.let { return it }
        return synchronized(this) {
            hookedConnectivityManager?.let { return@synchronized it }
            Log.d(TAG, "Creating hooked IConnectivityManager")
            val originalBinder = SystemServiceHelper.getSystemService(Context.CONNECTIVITY_SERVICE)
                ?: error("connectivity binder is null")
            val originalCm = IConnectivityManager.Stub.asInterface(originalBinder)
                ?: error("ConnectivityManager unavailable")
            val wrapper: IBinder = ShizukuBinderWrapper(originalCm.asBinder())
            val hooked = IConnectivityManager.Stub.asInterface(wrapper)
                ?: error("ConnectivityManager wrapper unavailable")
            hookedConnectivityManager = hooked
            hooked
        }
    }

    private fun getHookedNetworkManagementService(): INetworkManagementService {
        hookedNetworkManagementService?.let { return it }
        return synchronized(this) {
            hookedNetworkManagementService?.let { return@synchronized it }
            Log.d(TAG, "Creating hooked INetworkManagementService")
            val originalBinder = SystemServiceHelper.getSystemService("network_management")
                ?: error("network_management binder is null")
            val originalService = INetworkManagementService.Stub.asInterface(originalBinder)
                ?: error("NetworkManagementService unavailable")
            val wrapper: IBinder = ShizukuBinderWrapper(originalService.asBinder())
            val hooked = INetworkManagementService.Stub.asInterface(wrapper)
                ?: error("NetworkManagementService wrapper unavailable")
            hookedNetworkManagementService = hooked
            hooked
        }
    }

    private fun getWrappedService(serviceName: String, stubClassName: String, label: String): Any {
        hookedServiceCache[serviceName]?.let { return it }
        return synchronized(this) {
            hookedServiceCache[serviceName]?.let { return@synchronized it }

            val originalBinder = SystemServiceHelper.getSystemService(serviceName)
                ?: error("$serviceName binder is null")
            val stubClass = Class.forName(stubClassName)
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val originalService = asInterface.invoke(null, originalBinder)
                ?: error("$label original asInterface returned null")
            val serviceBinder = originalService.javaClass.getMethod("asBinder")
                .invoke(originalService) as? IBinder
                ?: error("$label asBinder returned null")
            val wrappedBinder: IBinder = ShizukuBinderWrapper(serviceBinder)
            val wrappedService = asInterface.invoke(null, wrappedBinder)
                ?: error("$label wrapped asInterface returned null")

            hookedServiceCache[serviceName] = wrappedService
            Log.i(TAG, "Created hooked service wrapper for $serviceName")
            wrappedService
        }
    }
}
