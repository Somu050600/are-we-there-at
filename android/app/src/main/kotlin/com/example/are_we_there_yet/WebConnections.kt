package com.example.are_we_there_yet

import android.content.Context
import android.util.Log
import com.google.android.gms.net.CronetProviderInstaller
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * HTTPS requests through Chrome's network stack (Cronet, from Play services) when the phone has
 * it, then Android's own. Some mobile networks send Google's short links to a carrier-hosted
 * server that a plain Android connection can't reach; Cronet also tries QUIC and races IPv6
 * against IPv4 the way Chrome does.
 */
object WebConnections {
    private const val TAG = "WebConnections"
    private const val INSTALL_WAIT_SECONDS = 3L

    /** Hosts the app asks; QUIC is tried on the first request instead of after a TCP one. */
    private val quicHosts = listOf("maps.app.goo.gl", "goo.gl", "www.google.com", "maps.google.com", "places.googleapis.com")

    @Volatile private var context: Context? = null
    @Volatile private var install: Task<Void>? = null
    @Volatile private var engine: CronetEngine? = null
    @Volatile private var noCronet = false

    /** Starts installing Play services' Cronet so it is ready when a share arrives. */
    fun prepare(context: Context) {
        if (this.context != null) return
        val app = context.applicationContext
        this.context = app
        install = runCatching { CronetProviderInstaller.installProvider(app) }
            .onFailure { Log.w(TAG, "Cronet install failed", it) }
            .getOrNull()
    }

    /**
     * Runs [request] on a connection to [url] from each stack in turn until one gets an answer,
     * and throws the last stack's error when none does. Blocks; call it off the main thread.
     */
    fun <T> request(url: URL, request: (HttpURLConnection) -> T): T {
        var failure: IOException? = null
        val stacks = buildList<() -> URLConnection> {
            cronet()?.let { engine -> add { engine.openConnection(url) } }
            add { url.openConnection() }
        }
        for (open in stacks) {
            var connection: HttpURLConnection? = null
            try {
                connection = open() as? HttpURLConnection ?: continue
                return request(connection)
            } catch (error: IOException) {
                Log.w(TAG, "Request to ${url.host} failed", error)
                failure = error
            } finally {
                connection?.disconnect()
            }
        }
        throw failure ?: IOException("Could not open ${url.host}")
    }

    private fun cronet(): CronetEngine? {
        engine?.let { return it }
        if (noCronet) return null
        val app = context ?: return null
        synchronized(this) {
            engine?.let { return it }
            return try {
                install?.let { Tasks.await(it, INSTALL_WAIT_SECONDS, TimeUnit.SECONDS) }
                val provider = CronetProvider.getAllProviders(app).firstOrNull {
                    it.isEnabled && it.name != CronetProvider.PROVIDER_NAME_FALLBACK
                }
                if (provider == null) noCronet = true
                provider?.createBuilder()
                    ?.enableQuic(true)
                    ?.apply { quicHosts.forEach { host -> addQuicHint(host, 443, 443) } }
                    ?.build()
                    ?.also { engine = it }
            } catch (slow: TimeoutException) {
                // Still installing: Android's stack this time, Cronet on a later request.
                null
            } catch (failure: Exception) {
                Log.w(TAG, "Cronet unavailable", failure)
                noCronet = true
                null
            }
        }
    }
}
