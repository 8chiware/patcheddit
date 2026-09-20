package app.morphe.extension.shared.fixes.redgifs;

import androidx.annotation.NonNull;

import org.json.JSONException;

import java.io.IOException;
import java.net.HttpURLConnection;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.PatchedditInterceptor;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;


public abstract class BaseFixRedgifsApiPatch extends PatchedditInterceptor {
    protected static BaseFixRedgifsApiPatch INSTANCE;
    public abstract String getDefaultUserAgent();

    public boolean isPatchIncluded() {
        // Overridden by patch.
        return false;
    }

    @NonNull
    @Override
    public Response doIntercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        if (!request.url().host().equals("api.redgifs.com")) {
            Response response = chain.proceed(request);
            // Reddit API traffic shares this client. Every Redgifs post it returns carries a
            // Reddit-hosted copy of the media in its preview object; remember those so the
            // Redgifs request below can be answered without contacting Redgifs at all.
            RedditCdnPreviewCache.captureRedditResponse(request, response);
            return response;
        }

        final String path = request.url().encodedPath();
        Logger.printInfo(() -> "Redgifs: intercepted " + request.method() + " " + path);

        // Reddit's own copy of this gif, if the post it came from has been seen. Answering
        // here short-circuits the whole Redgifs flow: no temporary token, no request to
        // Redgifs, and nothing for their verification gate to apply to.
        String redditCdnResponseBody = RedditCdnPreviewCache.buildGifResponseBody(path);
        if (redditCdnResponseBody != null) {
            return buildLocalJsonResponse(request, redditCdnResponseBody);
        }

        String userAgent = getDefaultUserAgent();
        boolean forceTokenRefresh = false;

        if (request.header("Authorization") != null) {
            Response response;
            try {
                response = chain.proceed(request.newBuilder().header("User-Agent", userAgent).build());
            } catch (IOException ex) {
                Logger.printException(() -> "Redgifs: request failed (existing Authorization) for " + path, ex);
                throw ex;
            }
            if (response.isSuccessful()) {
                Logger.printInfo(() -> "Redgifs: request succeeded (existing Authorization) for " + path
                        + ", code=" + response.code());
                return response;
            }
            Logger.printInfo(() -> "Redgifs: request failed (existing Authorization) for " + path
                    + ", code=" + response.code() + "; refreshing token");
            // It's possible that the user agent is being overwritten later down in the interceptor
            // chain, so make sure we grab the new user agent from the request headers.
            String responseUserAgent = response.request().header("User-Agent");
            if (responseUserAgent != null && !responseUserAgent.isEmpty()) {
                userAgent = responseUserAgent;
            }
            response.close();

            // Redgifs tokens are tied to the IP address that requested them. A cached token can
            // therefore become invalid before its normal expiry when the device changes networks.
            // Do not retry the failed request with the same cached token.
            forceTokenRefresh = true;
        }
        final String finalUserAgent = userAgent;

        try {
            // Emulate response for the old client IP lookup endpoint, which Redgifs has removed.
            // It was only ever used to populate the legacy "user-addr" query parameter, which
            // current Redgifs endpoints accept but ignore, so the actual value doesn't matter.
            if (path.equals("/info")) {
                Logger.printInfo(() -> "Redgifs: emulating /info response locally");
                return buildLocalJsonResponse(request, RedgifsTokenManager.getEmulatedIpResponseBody());
            }

            RedgifsTokenManager.RedgifsToken token;
            try {
                token = RedgifsTokenManager.refreshToken(userAgent, forceTokenRefresh);
            } catch (IOException ex) {
                Logger.printException(() -> "Redgifs: failed to obtain temporary token for user agent \""
                        + finalUserAgent + "\"", ex);

                // The client asks for an OAuth token before it asks for the gif, so a token
                // failure ends playback before reaching the gif request that Reddit's CDN copy
                // could have answered. Hand back a placeholder token and let the flow continue:
                // a gif with no cached Reddit copy still fails, just one request later.
                if (path.equals("/v2/oauth/client") && RedditCdnPreviewCache.isPatchIncluded()) {
                    Logger.printInfo(() -> "Redgifs: emulating /v2/oauth/client with a placeholder"
                            + " token so the Reddit CDN copy can still be used");
                    String placeholderBody = RedgifsTokenManager.getEmulatedOAuthResponseBody(
                            new RedgifsTokenManager.RedgifsToken("", System.currentTimeMillis() / 1000));
                    return buildLocalJsonResponse(request, placeholderBody);
                }

                throw ex;
            }

            // Emulate response for old OAuth endpoint
            if (path.equals("/v2/oauth/client")) {
                Logger.printInfo(() -> "Redgifs: emulating /v2/oauth/client response locally");
                String responseBody = RedgifsTokenManager.getEmulatedOAuthResponseBody(token);
                return buildLocalJsonResponse(request, responseBody);
            }

            Request modifiedRequest = request.newBuilder()
                    .header("Authorization", "Bearer " + token.getAccessToken())
                    .header("User-Agent", userAgent)
                    .build();
            Response response;
            try {
                response = chain.proceed(modifiedRequest);
            } catch (IOException ex) {
                Logger.printException(() -> "Redgifs: request failed for " + path, ex);
                throw ex;
            }
            Logger.printInfo(() -> "Redgifs: request for " + path + " returned code=" + response.code());
            return response;
        } catch (JSONException ex) {
            Logger.printException(() -> "Could not parse Redgifs response", ex);
            throw new IOException(ex);
        }
    }

    private static Response buildLocalJsonResponse(Request request, String jsonBody) {
        return new Response.Builder()
                .message("OK")
                .code(HttpURLConnection.HTTP_OK)
                .protocol(Protocol.HTTP_1_1)
                .request(request)
                .header("Content-Type", "application/json")
                .body(ResponseBody.create(jsonBody, MediaType.get("application/json")))
                .build();
    }
}
