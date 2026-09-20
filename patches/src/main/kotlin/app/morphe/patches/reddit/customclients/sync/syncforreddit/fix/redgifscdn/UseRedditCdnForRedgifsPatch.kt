/*
 * Copyright 2026 wchill.
 * https://github.com/wchill/patcheddit
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.reddit.customclients.sync.syncforreddit.fix.redgifscdn

import app.morphe.patches.reddit.customclients.AppCompatibility
import app.morphe.patches.reddit.customclients.sync.syncforreddit.fix.redgifs.fixRedgifsApi
import app.morphe.patches.reddit.customclients.useRedditCdnForRedgifsPatch

/**
 * Sync renders Redgifs posts in the feed from Reddit's preview CDN, but tapping through
 * starts ImageViewerFragment, which resolves the gif ID and asks api.redgifs.com for the
 * media - the only request in the flow that reaches Redgifs, and the one their verification
 * gate applies to. This makes the tap-through play the same Reddit-hosted copy the feed
 * already uses, so the Redgifs request never goes out.
 */
@Suppress("unused")
val useRedditCdnForRedgifs = useRedditCdnForRedgifsPatch(fixRedgifsApi) {
    compatibleWith(*AppCompatibility.SyncForReddit)
}
