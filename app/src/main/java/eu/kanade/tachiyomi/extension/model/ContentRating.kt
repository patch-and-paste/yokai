package eu.kanade.tachiyomi.extension.model

import dev.icerock.moko.resources.StringResource
import yokai.i18n.MR

/**
 * How explicit an extension declares its content to be.
 *
 * Extension lib 1.6 exposes this through the `tachiyomix.contentWarning` manifest metadata, where
 * `0` is safe, `1` is mixed and `2` is NSFW. Older extensions only carry the binary
 * `tachiyomi.extension.nsfw` flag, so they can only ever be [SAFE] or [NSFW].
 *
 * Ordering is meaningful: the entries go from least to most explicit, so a rating can be compared
 * against the highest rating the user allows.
 */
enum class ContentRating(
    /** Short label shown next to the extension in the list, or null when there is nothing to warn about. */
    val badgeResId: StringResource?,
    /** Full sentence shown on the extension details screen. */
    val warningResId: StringResource?,
    /** Label for this rating as the highest one the user allows. */
    val allowedTitleResId: StringResource,
) {
    SAFE(null, null, MR.strings.content_rating_allow_safe),
    MIXED(MR.strings.mixed_short, MR.strings.may_contain_nsfw, MR.strings.content_rating_allow_mixed),
    NSFW(MR.strings.nsfw_short, MR.strings.contains_nsfw, MR.strings.content_rating_allow_nsfw),
    ;

    companion object {
        /**
         * Maps a `tachiyomix.contentWarning` **manifest** value, which the tachiyomix manifest spec
         * numbers `0 = safe, 1 = mixed, 2 = NSFW`.
         *
         * The store index uses different wire numbers and reserves 0 for "unspecified".
         * [yokai.data.extension.repo.model.NetworkExtensionStore.ContentWarning] maps those values
         * directly.
         *
         * Anything unrecognised is treated as [NSFW] so an extension can never end up less
         * restricted than it declared.
         */
        fun fromManifestContentWarning(value: Int): ContentRating = entries.getOrElse(value) { NSFW }

        /** Maps the pre-1.6 binary `tachiyomi.extension.nsfw` flag. */
        fun fromNsfwFlag(isNsfw: Boolean): ContentRating = if (isNsfw) NSFW else SAFE
    }
}
