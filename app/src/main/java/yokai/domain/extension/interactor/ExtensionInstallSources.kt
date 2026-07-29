package yokai.domain.extension.interactor

import eu.kanade.tachiyomi.core.preference.getAndSet
import yokai.domain.source.SourcePreferences

/**
 * Stores each extension's source repo as a `pkgName|repoBaseUrl` entry in one preference, matching
 * the format [TrustExtension] uses for trusted signatures.
 */
class ExtensionInstallSources(private val sourcePreferences: SourcePreferences) {

    fun get(pkgName: String): String? = getAll()[pkgName]

    /** Returns all recorded install sources. */
    fun getAll(): Map<String, String> = sourcePreferences.extensionInstallSources().get()
        .mapNotNull { entry ->
            val pkgName = entry.substringBefore(SEPARATOR, missingDelimiterValue = "")
            val repoUrl = entry.substringAfter(SEPARATOR, missingDelimiterValue = "")
            if (pkgName.isBlank() || repoUrl.isBlank()) null else pkgName to repoUrl
        }
        .toMap()

    fun set(pkgName: String, repoUrl: String) {
        if (repoUrl.isBlank()) return
        sourcePreferences.extensionInstallSources().getAndSet { sources ->
            sources.withoutEntryFor(pkgName).also { it += pkgName + SEPARATOR + repoUrl }
        }
    }

    fun remove(pkgName: String) {
        sourcePreferences.extensionInstallSources().getAndSet { sources ->
            sources.withoutEntryFor(pkgName)
        }
    }

    private fun Set<String>.withoutEntryFor(pkgName: String): MutableSet<String> =
        filterNotTo(mutableSetOf()) { it.startsWith(pkgName + SEPARATOR) }

    private companion object {
        /** A package name can't contain it, and neither can the URLs repos are added under. */
        const val SEPARATOR = "|"
    }
}
