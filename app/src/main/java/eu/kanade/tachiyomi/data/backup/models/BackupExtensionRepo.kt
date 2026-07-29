package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import yokai.domain.extension.repo.model.ExtensionRepo

/**
 * Field numbers match upstream's `BackupExtensionStore` so backups stay interchangeable, including
 * the out of order website/signing key pair. Yokai has no equivalent of the Discord contact, so
 * number 6 is carried through a restore but never populated.
 */
@Serializable
data class BackupExtensionRepo(
    @ProtoNumber(1) var baseUrl: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var shortName: String? = null,
    @ProtoNumber(4) var website: String = "",
    @ProtoNumber(5) var signingKeyFingerprint: String = "",
    @ProtoNumber(6) var contactDiscord: String? = null,
    @ProtoNumber(7) var isLegacy: Boolean? = null,
    @ProtoNumber(8) var extensionListUrl: String? = null,
) {
    fun toExtensionRepo() = ExtensionRepo(
        baseUrl = baseUrl,
        name = name,
        shortName = shortName,
        website = website,
        signingKeyFingerprint = signingKeyFingerprint,
        // Backups written before the store format existed only ever held legacy repos
        isLegacy = isLegacy ?: true,
        extensionListUrl = extensionListUrl,
    )

    companion object {
        fun copyFrom(repo: ExtensionRepo) = BackupExtensionRepo(
            baseUrl = repo.baseUrl,
            name = repo.name,
            shortName = repo.shortName,
            website = repo.website,
            signingKeyFingerprint = repo.signingKeyFingerprint,
            isLegacy = repo.isLegacy,
            extensionListUrl = repo.extensionListUrl,
        )
    }
}
