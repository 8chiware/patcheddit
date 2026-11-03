package app.morphe.patches.reddit.customclients.sync.syncforreddit.fix.comments

import app.morphe.patcher.Fingerprint

// CommentsPresenterImpl's "load more" handler: logs "Requesting more: ", then builds the request from
// its only parameter, the Post row of the "more" item.
internal val requestMoreFingerprint = Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    strings = listOf("Requesting more: "),
    custom = { _, classDef -> classDef.sourceFile == "CommentsPresenterImpl.java" }
)

// OAuthCommentsMoreRequest(Context, String listId, String linkId, String moreId, String children,
// int position, int level, String sort, Listener, ErrorListener).
internal val moreRequestConstructorFingerprint = Fingerprint(
    name = "<init>",
    returnType = "V",
    parameters = listOf(
        "Landroid/content/Context;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "I",
        "I",
        "Ljava/lang/String;",
        "Lcom/android/volley/Response\$Listener;",
        "Lcom/android/volley/Response\$ErrorListener;",
    ),
    strings = listOf("api/morechildren?always_show_media=1&profile_img=true"),
    custom = { _, classDef -> classDef.sourceFile == "OAuthCommentsMoreRequest.java" }
)

// OAuthCommentsMoreRequest.parseNetworkResponse(NetworkResponse).
internal val moreRequestParseResponseFingerprint = Fingerprint(
    classFingerprint = moreRequestConstructorFingerprint,
    name = "parseNetworkResponse",
    returnType = "Lcom/android/volley/Response;",
    parameters = listOf("Lcom/android/volley/NetworkResponse;"),
)
