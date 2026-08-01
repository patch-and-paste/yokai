package eu.kanade.tachiyomi.network

import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.core.preference.PreferenceStore

class NetworkPreferences(
    private val preferenceStore: PreferenceStore,
    private val verboseLogging: Boolean,
) {

    fun verboseLogging() = preferenceStore.getBoolean("verbose_logging", verboseLogging)

    fun dohProvider() = preferenceStore.getInt("doh_provider", -1)

    fun defaultUserAgent() = preferenceStore.getString("default_user_agent", DEFAULT_USER_AGENT)

    /** 0 none, 1 HTTP, 2 SOCKS5. Applies to the app's own client, not the whole device. */
    fun proxyType() = preferenceStore.getInt("proxy_type", PROXY_NONE)

    fun proxyHost() = preferenceStore.getString("proxy_host", "")

    /** Stored as text because the settings screen edits it with a text field. */
    fun proxyPort() = preferenceStore.getString("proxy_port", "")

    fun proxyUsername() = preferenceStore.getString(Preference.privateKey("proxy_username"), "")

    fun proxyPassword() = preferenceStore.getString(Preference.privateKey("proxy_password"), "")

    companion object {
        const val PROXY_NONE = 0
        const val PROXY_HTTP = 1
        const val PROXY_SOCKS = 2

        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
    }
}
