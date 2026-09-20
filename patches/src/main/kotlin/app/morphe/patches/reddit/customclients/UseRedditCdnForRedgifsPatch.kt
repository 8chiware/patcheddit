/*
 * Copyright 2026 wchill.
 * https://github.com/wchill/patcheddit
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.reddit.customclients

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly

/**
 * Plays Redgifs posts from Reddit's own cached copy instead of from Redgifs.
 *
 * Purely an extension-side change: it enables the Reddit preview capture and the local
 * `/v2/gifs/<id>` response inside the Redgifs interceptor that "Fix Redgifs API" installs,
 * so it needs no fingerprints of its own and cannot break on an app update on its own.
 */
fun useRedditCdnForRedgifsPatch(
    fixRedgifsApiPatch: Patch<*>,
    block: BytecodePatchBuilder.() -> Unit = {},
) = bytecodePatch(
    name = "Use Reddit CDN for Redgifs",
    description = "Plays Redgifs posts from the copy Reddit caches on its own CDN" +
        " (v.redd.it / preview.redd.it) rather than fetching them from Redgifs." +
        " It is the same media the post list already shows, it needs no Redgifs token," +
        " and Redgifs is never contacted for it. Posts Reddit has no video copy of are" +
        " still loaded from Redgifs as before.",
    default = true,
) {
    dependsOn(
        // The interceptor this patch enables is installed by the Redgifs API patch.
        fixRedgifsApiPatch,
        bytecodePatch {
            execute {
                Fingerprint(
                    definingClass = "Lapp/morphe/extension/shared/fixes/redgifs/RedditCdnPreviewCache;",
                    name = "isPatchIncluded",
                ).method.returnEarly(true)
            }
        }
    )

    block()
}
