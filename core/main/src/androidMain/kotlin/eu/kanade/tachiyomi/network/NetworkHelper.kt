package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import java.io.File
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.Credentials
import okhttp3.OkHttpClient

class NetworkHelper(
    val context: Context,
    private val preferences: NetworkPreferences,
    private val block: (OkHttpClient.Builder) -> Unit,
) {

    val cookieJar = AndroidCookieJar()

    /**
     * Routes this client through a forward proxy, which is what sources behind an IP block need
     * without turning on a device-wide VPN. Credentials are only offered once per challenge, so a
     * proxy that keeps rejecting them fails the call rather than looping.
     */
    private fun applyProxy(builder: OkHttpClient.Builder) {
        val type = when (preferences.proxyType().get()) {
            NetworkPreferences.PROXY_HTTP -> Proxy.Type.HTTP
            NetworkPreferences.PROXY_SOCKS -> Proxy.Type.SOCKS
            else -> return
        }
        val host = preferences.proxyHost().get().trim().takeIf { it.isNotEmpty() } ?: return
        val port = preferences.proxyPort().get().trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return

        builder.proxy(Proxy(type, InetSocketAddress.createUnresolved(host, port)))

        val username = preferences.proxyUsername().get()
        val password = preferences.proxyPassword().get()
        if (username.isEmpty()) return

        // SOCKS auth goes through the JVM authenticator; HTTP proxies are challenged per request
        if (type == Proxy.Type.SOCKS) {
            Authenticator.setDefault(
                object : Authenticator() {
                    override fun getPasswordAuthentication() =
                        PasswordAuthentication(username, password.toCharArray())
                },
            )
            return
        }

        builder.proxyAuthenticator { _, response ->
            if (response.request.header("Proxy-Authorization") != null) return@proxyAuthenticator null
            response.request.newBuilder()
                .header("Proxy-Authorization", Credentials.basic(username, password))
                .build()
        }
    }

    val client: OkHttpClient = run {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.MINUTES)
            .cache(
                Cache(
                    directory = File(context.cacheDir, "network_cache"),
                    maxSize = 5L * 1024 * 1024, // 5 MiB
                )
            )
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor(::defaultUserAgent))
            // No content-encoding interceptor belongs here. extensions-lib 1.6 sources reject a
            // client that strips Accept-Encoding, which is what pairing Brotli with
            // IgnoreGzipInterceptor required, so OkHttp's transparent gzip is left alone.
            // okhttp-brotli stays on the classpath for sources that install it themselves.

        applyProxy(builder)

        block(builder)

        builder.addInterceptor(
            CloudflareInterceptor(context, cookieJar, ::defaultUserAgent),
        )

        when (preferences.dohProvider().get()) {
            PREF_DOH_CLOUDFLARE -> builder.dohCloudflare()
            PREF_DOH_GOOGLE -> builder.dohGoogle()
            PREF_DOH_ADGUARD -> builder.dohAdGuard()
            PREF_DOH_QUAD9 -> builder.dohQuad9()
            PREF_DOH_ALIDNS -> builder.dohAliDNS()
            PREF_DOH_DNSPOD -> builder.dohDNSPod()
            PREF_DOH_360 -> builder.doh360()
            PREF_DOH_QUAD101 -> builder.dohQuad101()
            PREF_DOH_MULLVAD -> builder.dohMullvad()
            PREF_DOH_CONTROLD -> builder.dohControlD()
            PREF_DOH_NJALLA -> builder.dohNajalla()
            PREF_DOH_SHECAN -> builder.dohShecan()
        }

        builder.build()
    }

    @Deprecated("The regular client handles Cloudflare by default")
    @Suppress("UNUSED")
    val cloudflareClient: OkHttpClient = client

    val defaultUserAgent
        get() = preferences.defaultUserAgent().get().replace("\n", " ").trim()
}
