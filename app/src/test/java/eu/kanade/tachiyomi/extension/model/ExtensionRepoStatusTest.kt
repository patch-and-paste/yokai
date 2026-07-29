package eu.kanade.tachiyomi.extension.model

import eu.kanade.tachiyomi.extension.model.ExtensionRepoStatus.Action
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import yokai.domain.extension.repo.model.ExtensionRepo

class ExtensionRepoStatusTest {

    private val keiyoushi = repo("https://keiyoushi.example/index.min.json", "Keiyoushi", KEY_A)
    private val mirror = repo("https://mirror.example/index", "Mirror", KEY_A)
    private val fork = repo("https://fork.example/index", "Fork", KEY_B)

    private val installed = installed(versionCode = 10, signatureHash = KEY_A)

    @Test
    fun `names the repo it was installed from even after that repo drops the package`() {
        val statuses = ExtensionRepoStatus.listFor(
            installed = installed,
            repos = listOf(keiyoushi, mirror),
            candidates = listOf(available(mirror, versionCode = 12)),
            installSourceUrl = keiyoushi.baseUrl,
        )

        val source = statuses.first()
        assertEquals("Keiyoushi", source.repoName)
        assertTrue(source.isInstallSource)
        assertNull(source.available, "Keiyoushi no longer lists it")
        assertEquals(Action.NONE, source.action)

        val other = statuses[1]
        assertEquals("Mirror", other.repoName)
        assertEquals(Action.UPDATE, other.action)
        assertFalse(other.requiresReinstall, "same signing key, so it can be replaced in place")
    }

    @Test
    fun `a repo at the same version is a switch, not an update`() {
        val statuses = ExtensionRepoStatus.listFor(
            installed = installed,
            repos = listOf(keiyoushi, mirror),
            candidates = listOf(available(keiyoushi, versionCode = 10), available(mirror, versionCode = 10)),
            installSourceUrl = keiyoushi.baseUrl,
        )

        assertEquals(Action.NONE, statuses.single { it.isInstallSource }.action)

        val switch = statuses.single { !it.isInstallSource }
        assertEquals(Action.SWITCH, switch.action)
        // Same version means no in-place install, even though the key lines up
        assertTrue(switch.requiresReinstall)
    }

    @Test
    fun `an older version elsewhere is offered as a downgrade`() {
        val statuses = ExtensionRepoStatus.listFor(
            installed = installed,
            repos = listOf(keiyoushi, fork),
            candidates = listOf(available(fork, versionCode = 8)),
            installSourceUrl = keiyoushi.baseUrl,
        )

        val downgrade = statuses.single { it.repoName == "Fork" }
        assertEquals(Action.DOWNGRADE, downgrade.action)
        assertFalse(downgrade.signatureMatches)
        assertTrue(downgrade.requiresReinstall)
    }

    @Test
    fun `a newer version signed with another key still has to be reinstalled`() {
        val statuses = ExtensionRepoStatus.listFor(
            installed = installed,
            repos = listOf(keiyoushi, fork),
            candidates = listOf(available(fork, versionCode = 20)),
            installSourceUrl = keiyoushi.baseUrl,
        )

        val update = statuses.single { it.repoName == "Fork" }
        assertEquals(Action.UPDATE, update.action)
        assertTrue(update.requiresReinstall)
    }

    @Test
    fun `without a recorded install source a single matching signing key stands in for one`() {
        val statuses = ExtensionRepoStatus.listFor(
            installed = installed,
            repos = listOf(keiyoushi, fork),
            candidates = listOf(available(keiyoushi, versionCode = 10)),
            installSourceUrl = null,
        )

        val source = statuses.single { it.isInstallSource }
        assertEquals("Keiyoushi", source.repoName)
        assertTrue(source.isInferredInstallSource)
    }

    @Test
    fun `two repos sharing a signing key are too ambiguous to guess between`() {
        val statuses = ExtensionRepoStatus.listFor(
            installed = installed,
            repos = listOf(keiyoushi, mirror),
            candidates = listOf(available(keiyoushi, versionCode = 10), available(mirror, versionCode = 10)),
            installSourceUrl = null,
        )

        assertTrue(statuses.none { it.isInstallSource })
        // Neither is "the" install source, so both are offered as somewhere to switch to
        assertTrue(statuses.all { it.action == Action.SWITCH })
    }

    @Test
    fun `repos that neither installed nor offer the package are left out`() {
        val statuses = ExtensionRepoStatus.listFor(
            installed = installed,
            repos = listOf(keiyoushi, mirror, fork),
            candidates = listOf(available(mirror, versionCode = 10)),
            installSourceUrl = keiyoushi.baseUrl,
        )

        assertEquals(listOf("Keiyoushi", "Mirror"), statuses.map { it.repoName })
    }

    @Test
    fun `the newest listing wins when nothing is installed yet`() {
        val candidates = listOf(
            available(keiyoushi, versionCode = 10),
            available(fork, versionCode = 14),
            available(mirror, versionCode = 12),
        )

        val picked = pickExtensionCandidate(candidates, installed = null, repos = listOf(keiyoushi, mirror, fork))

        assertEquals(fork.baseUrl, picked?.repoUrl)
    }

    @Test
    fun `a repo that can update in place beats a newer one that cannot`() {
        val candidates = listOf(
            available(fork, versionCode = 20),
            available(mirror, versionCode = 12),
        )

        val picked = pickExtensionCandidate(candidates, installed, repos = listOf(keiyoushi, mirror, fork))

        assertEquals(mirror.baseUrl, picked?.repoUrl, "Fork is newer but would need an uninstall first")
    }

    @Test
    fun `among repos that can all update in place the newest wins`() {
        val candidates = listOf(
            available(keiyoushi, versionCode = 11),
            available(mirror, versionCode = 12),
        )

        val picked = pickExtensionCandidate(candidates, installed, repos = listOf(keiyoushi, mirror, fork))

        assertEquals(mirror.baseUrl, picked?.repoUrl)
    }

    @Test
    fun `a newer lib version breaks a tie on version code`() {
        val candidates = listOf(
            available(keiyoushi, versionCode = 10, libVersion = 1.5),
            available(mirror, versionCode = 10, libVersion = 1.6),
        )

        val picked = pickExtensionCandidate(candidates, installed, repos = listOf(keiyoushi, mirror))

        assertEquals(mirror.baseUrl, picked?.repoUrl)
    }

    @Test
    fun `an even tie falls back to the order the repos were added in`() {
        val candidates = listOf(
            available(mirror, versionCode = 10),
            available(keiyoushi, versionCode = 10),
        )

        val picked = pickExtensionCandidate(candidates, installed, repos = listOf(keiyoushi, mirror))

        assertEquals(keiyoushi.baseUrl, picked?.repoUrl)
    }

    private fun repo(baseUrl: String, name: String, key: String) = ExtensionRepo(
        baseUrl = baseUrl,
        name = name,
        shortName = null,
        website = "https://example.org",
        signingKeyFingerprint = key,
    )

    private fun installed(versionCode: Long, signatureHash: String?) = Extension.Installed(
        name = "Example",
        pkgName = PKG_NAME,
        versionName = "1.$versionCode.0",
        versionCode = versionCode,
        libVersion = 1.5,
        lang = "en",
        contentRating = ContentRating.SAFE,
        pkgFactory = null,
        sources = emptyList(),
        icon = null,
        isShared = true,
        signatureHash = signatureHash,
    )

    private fun available(repo: ExtensionRepo, versionCode: Long, libVersion: Double = 1.5) =
        Extension.Available(
            name = "Example",
            pkgName = PKG_NAME,
            versionName = "1.$versionCode.0",
            versionCode = versionCode,
            libVersion = libVersion,
            lang = "en",
            contentRating = ContentRating.SAFE,
            apkUrl = "${repo.baseUrl}/example.apk",
            iconUrl = "${repo.baseUrl}/example.png",
            sources = emptyList(),
            repoUrl = repo.baseUrl,
        )

    private companion object {
        const val PKG_NAME = "org.example.extension"
        const val KEY_A = "aaaa1111"
        const val KEY_B = "bbbb2222"
    }
}
