/*
 * Copyright 2026 wchill.
 * https://github.com/wchill/patcheddit
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.shared.fixes.redgifs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import app.morphe.extension.shared.Logger;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Serves Redgifs posts from Reddit's own CDN copy instead of from Redgifs.
 *
 * <p>Every Redgifs link post that Reddit returns carries a Reddit-hosted copy of the media in
 * its {@code preview} object ({@code reddit_video_preview.fallback_url} on v.redd.it, or an
 * mp4 preview variant on preview.redd.it). That copy is what the post list already renders,
 * it needs no Redgifs token, and Redgifs is never contacted for it.
 *
 * <p>This class snoops those preview URLs out of the Reddit API responses that pass through
 * {@link BaseFixRedgifsApiPatch} (the interceptor is installed on the app's shared OkHttp
 * client, which serves both hosts), keys them by Redgifs gif ID, and hands them back when the
 * app later asks Redgifs for the same ID. {@link BaseFixRedgifsApiPatch} then answers that
 * request locally with a synthetic {@code /v2/gifs/<id>} body pointing at the Reddit CDN URL,
 * so the app's normal player plays the Reddit copy and the Redgifs request never goes out.
 *
 * <p>Posts with no Reddit-hosted <em>video</em> preview (image-type Redgifs posts, or posts
 * Reddit never cached) are not stored, so those fall through to the unchanged Redgifs path.
 */
public final class RedditCdnPreviewCache {

    /**
     * How many Redgifs posts to remember. Entries are a few hundred bytes each and are only
     * useful for as long as Reddit's signed preview URLs stay valid, so this is deliberately
     * small; least-recently-used entries are dropped first.
     */
    private static final int MAX_ENTRIES = 512;

    /** Upper bound on how much of a Reddit response body is buffered for scanning. */
    private static final long MAX_SNIFF_BYTES = 4L * 1024 * 1024;

    /** Guards against pathological nesting in an unexpected response shape. */
    private static final int MAX_SCAN_DEPTH = 24;

    private static final String GIF_PATH_PREFIX = "/v2/gifs/";

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final Map<String, RedditMedia> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, RedditMedia>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, RedditMedia> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    private RedditCdnPreviewCache() {
    }

    public static boolean isPatchIncluded() {
        // Overridden by patch.
        return false;
    }

    /**
     * The Reddit-hosted copy of one Redgifs post's media.
     */
    private static final class RedditMedia {
        final String videoUrl;
        @Nullable
        final String posterUrl;
        final int width;
        final int height;
        final double duration;
        final boolean hasAudio;

        RedditMedia(String videoUrl, @Nullable String posterUrl,
                    int width, int height, double duration, boolean hasAudio) {
            this.videoUrl = videoUrl;
            this.posterUrl = posterUrl;
            this.width = width;
            this.height = height;
            this.duration = duration;
            this.hasAudio = hasAudio;
        }
    }

    // region Capture

    /**
     * Scans a Reddit API response for Redgifs posts and remembers Reddit's own copy of their
     * media. Called for every non-Redgifs response passing through the interceptor; cheap to
     * call and never throws.
     *
     * <p>The response body is only peeked, never consumed, so the caller's response stays
     * readable.
     */
    public static void captureRedditResponse(@NonNull Request request, @NonNull Response response) {
        if (!isPatchIncluded()) return;

        try {
            String host = request.url().host();
            if (!host.equals("reddit.com") && !host.endsWith(".reddit.com")) return;
            if (!response.isSuccessful()) return;

            ResponseBody body = response.body();
            if (body == null) return;
            long contentLength = body.contentLength();
            if (contentLength > MAX_SNIFF_BYTES) {
                Logger.printInfo(() -> "Redgifs CDN: skipping oversized Reddit response for "
                        + request.url().encodedPath());
                return;
            }

            byte[] bytes = response.peekBody(MAX_SNIFF_BYTES).bytes();
            if (bytes.length >= MAX_SNIFF_BYTES) {
                // Truncated at the peek limit: neither the gzip stream nor the JSON would
                // parse, so drop it rather than logging a failure for every oversized page.
                Logger.printInfo(() -> "Redgifs CDN: skipping truncated Reddit response for "
                        + request.url().encodedPath());
                return;
            }
            // An application interceptor only sees decompressed bytes when OkHttp itself asked
            // for the compression. When the caller sets its own Accept-Encoding (Volley does),
            // the header survives and the body is still gzipped here.
            if ("gzip".equalsIgnoreCase(response.header("Content-Encoding"))) {
                bytes = gunzip(bytes);
            }
            String json = new String(bytes, UTF_8);

            // Cheap pre-filter: most Reddit responses contain no Redgifs posts at all, and
            // parsing a full listing is far more expensive than scanning it for a substring.
            if (!json.contains("redgifs")) return;

            int found = scan(new JSONTokener(json).nextValue(), 0);
            if (found > 0) {
                final int total = found;
                Logger.printInfo(() -> "Redgifs CDN: cached " + total + " Reddit preview URL(s) from "
                        + request.url().encodedPath());
            }
        } catch (Exception ex) {
            // Capture is best-effort: a failure here just means the Redgifs path is used.
            Logger.printException(() -> "Redgifs CDN: failed to scan Reddit response", ex);
        }
    }

    /**
     * Walks the response tree looking for post objects. Listings, comment pages, multireddits
     * and crossposts all nest posts differently, so rather than encoding any one shape this
     * matches on the post itself: an object with a {@code preview} and a Redgifs {@code url}.
     */
    private static int scan(@Nullable Object node, int depth) {
        if (node == null || depth > MAX_SCAN_DEPTH) return 0;

        int found = 0;
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0, length = array.length(); i < length; i++) {
                found += scan(array.opt(i), depth + 1);
            }
        } else if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            found += capturePost(object);
            // Keep descending even after a match: crossposts carry a second post object
            // (crosspost_parent_list) whose preview may be the only one Reddit populated.
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                found += scan(object.opt(keys.next()), depth + 1);
            }
        }
        return found;
    }

    /**
     * Stores the Reddit-hosted media for one post, if it is a Redgifs post that Reddit has a
     * video copy of. Returns the number of entries added (0 or 1).
     */
    private static int capturePost(@NonNull JSONObject post) {
        JSONObject preview = post.optJSONObject("preview");
        if (preview == null) return 0;

        String link = post.optString("url_overridden_by_dest", "");
        if (!isRedgifsLink(link)) {
            link = post.optString("url", "");
            if (!isRedgifsLink(link)) return 0;
        }

        String id = extractGifId(link);
        if (isEmpty(id)) return 0;

        String videoUrl = null;
        int width = 0;
        int height = 0;
        double duration = 0;
        boolean hasAudio = false;

        // Reddit's own transcode of the linked video. Preferred: it is a plain progressive
        // mp4 on v.redd.it, unsigned and long-lived.
        JSONObject videoPreview = preview.optJSONObject("reddit_video_preview");
        if (videoPreview != null) {
            videoUrl = unescape(videoPreview.optString("fallback_url", ""));
            width = videoPreview.optInt("width", 0);
            height = videoPreview.optInt("height", 0);
            duration = videoPreview.optDouble("duration", 0);
            // "is_gif" marks a transcode with no audio track, which is what Reddit produces
            // for the silent gif-style posts that make up most of Redgifs.
            hasAudio = !videoPreview.optBoolean("is_gif", false);
        }

        JSONArray images = preview.optJSONArray("images");
        JSONObject firstImage = images == null ? null : images.optJSONObject(0);

        // Fall back to the mp4 preview variant on preview.redd.it.
        if (isEmpty(videoUrl) && firstImage != null) {
            JSONObject variants = firstImage.optJSONObject("variants");
            JSONObject mp4 = variants == null ? null : variants.optJSONObject("mp4");
            JSONObject source = mp4 == null ? null : mp4.optJSONObject("source");
            if (source != null) {
                videoUrl = unescape(source.optString("url", ""));
                width = source.optInt("width", 0);
                height = source.optInt("height", 0);
            }
        }

        // No Reddit-hosted video: image-type Redgifs posts and posts Reddit never cached are
        // left to the existing Redgifs path rather than handing a still to the video player.
        if (isEmpty(videoUrl)) return 0;

        String posterUrl = null;
        if (firstImage != null) {
            JSONObject source = firstImage.optJSONObject("source");
            if (source != null) {
                posterUrl = unescape(source.optString("url", ""));
                if (isEmpty(posterUrl)) posterUrl = null;
            }
        }

        final String key = id.toLowerCase(Locale.ROOT);
        final String finalVideoUrl = videoUrl;
        cache.put(key, new RedditMedia(videoUrl, posterUrl, width, height, duration, hasAudio));
        Logger.printInfo(() -> "Redgifs CDN: cached " + key + " -> " + finalVideoUrl);
        return 1;
    }

    // endregion

    // region Playback

    /**
     * Builds a Redgifs {@code /v2/gifs/<id>} response body pointing at Reddit's copy of the
     * media, or null when the gif was not seen in a Reddit response (in which case the caller
     * must fall through to the real Redgifs request).
     *
     * @param encodedPath the intercepted request path, e.g. {@code /v2/gifs/somegifid}.
     */
    @Nullable
    public static String buildGifResponseBody(@NonNull String encodedPath) {
        if (!isPatchIncluded()) return null;

        try {
            if (!encodedPath.startsWith(GIF_PATH_PREFIX)) return null;
            String id = encodedPath.substring(GIF_PATH_PREFIX.length());
            int slash = id.indexOf('/');
            if (slash >= 0) id = id.substring(0, slash);
            if (isEmpty(id)) return null;

            final String key = id.toLowerCase(Locale.ROOT);
            RedditMedia media = cache.get(key);
            if (media == null) {
                Logger.printInfo(() -> "Redgifs CDN: no Reddit copy cached for " + key
                        + ", using Redgifs");
                return null;
            }

            Logger.printInfo(() -> "Redgifs CDN: serving " + key + " from Reddit CDN: "
                    + media.videoUrl);
            return buildResponseBody(id, media);
        } catch (Exception ex) {
            Logger.printException(() -> "Redgifs CDN: failed to build local response for "
                    + encodedPath, ex);
            return null;
        }
    }

    /**
     * Mirrors the shape of a real Redgifs {@code /v2/gifs/<id>} response, with every media URL
     * pointing at Reddit's CDN. The v1 {@code gfyItem} object is included alongside it because
     * Redgifs support in these clients grew out of their Gfycat support and some of them still
     * read the v1 field names; a client only reads the shape it knows and ignores the other.
     */
    private static String buildResponseBody(String id, RedditMedia media) throws Exception {
        String poster = media.posterUrl == null ? media.videoUrl : media.posterUrl;

        JSONObject urls = new JSONObject();
        urls.put("hd", media.videoUrl);
        urls.put("sd", media.videoUrl);
        urls.put("poster", poster);
        urls.put("thumbnail", poster);
        urls.put("vthumbnail", media.videoUrl);

        JSONObject gif = new JSONObject();
        gif.put("id", id);
        gif.put("urls", urls);
        gif.put("width", media.width);
        gif.put("height", media.height);
        gif.put("duration", media.duration);
        gif.put("hasAudio", media.hasAudio);
        // 1 = video. Image posts are never cached, so this is always a video.
        gif.put("type", 1);
        gif.put("published", true);
        gif.put("verified", true);
        gif.put("createDate", System.currentTimeMillis() / 1000);
        gif.put("likes", 0);
        gif.put("views", 0);
        gif.put("tags", new JSONArray());
        gif.put("userName", "");
        gif.put("avgColor", "#000000");

        JSONObject gfyItem = new JSONObject();
        gfyItem.put("gfyId", id);
        gfyItem.put("gfyName", id);
        gfyItem.put("mp4Url", media.videoUrl);
        gfyItem.put("webmUrl", media.videoUrl);
        gfyItem.put("mobileUrl", media.videoUrl);
        gfyItem.put("miniUrl", media.videoUrl);
        gfyItem.put("posterUrl", poster);
        gfyItem.put("mobilePosterUrl", poster);
        gfyItem.put("miniPosterUrl", poster);
        gfyItem.put("width", media.width);
        gfyItem.put("height", media.height);
        gfyItem.put("hasAudio", media.hasAudio);

        JSONObject response = new JSONObject();
        response.put("gif", gif);
        response.put("gfyItem", gfyItem);
        return response.toString();
    }

    // endregion

    // region Helpers

    private static boolean isRedgifsLink(@Nullable String url) {
        if (isEmpty(url)) return false;
        // Substring match rather than a parse: this runs for every object in a listing, and
        // the full URL is validated by extractGifId anyway.
        return url.contains("redgifs.com") || url.contains("gfycat.com");
    }

    /**
     * Extracts the gif ID from a Redgifs or Gfycat URL, matching what the clients themselves
     * send to {@code /v2/gifs/<id>} so that the captured ID and the requested ID agree.
     * Handles the watch/ifr page URLs Reddit stores as the post URL as well as the direct
     * media URLs ({@code i.redgifs.com/i/<id>.jpg}, {@code <id>-mobile.mp4}).
     */
    @Nullable
    static String extractGifId(@Nullable String url) {
        if (isEmpty(url)) return null;
        try {
            int queryStart = url.indexOf('?');
            if (queryStart >= 0) url = url.substring(0, queryStart);
            int fragmentStart = url.indexOf('#');
            if (fragmentStart >= 0) url = url.substring(0, fragmentStart);
            if (url.endsWith("/")) url = url.substring(0, url.length() - 1);

            String id = url.substring(url.lastIndexOf('/') + 1);
            id = id.replace(".gif", "").replace(".mp4", "").replace(".webm", "")
                    .replace(".jpg", "").replace(".jpeg", "").replace(".png", "")
                    .replace("-mobile", "").replace("-size_restricted", "")
                    .replace("-max-1mb-poster", "");
            if (id.contains("-")) id = id.split("-")[0];
            return isEmpty(id) ? null : id;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Reddit HTML-escapes the ampersands in its signed preview URLs. */
    private static String unescape(String url) {
        return url.replace("&amp;", "&");
    }

    private static boolean isEmpty(@Nullable String value) {
        return value == null || value.isEmpty();
    }

    private static byte[] gunzip(byte[] compressed) throws Exception {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 4);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    // endregion
}
