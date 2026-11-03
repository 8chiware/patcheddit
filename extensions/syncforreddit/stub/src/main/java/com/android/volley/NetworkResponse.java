/*
 * Compile-time stub for Volley's NetworkResponse, which Sync for Reddit ships unobfuscated.
 * Signature ported from trulow/sync-reddit-fixes (GPLv3), which targets the same version.
 */
package com.android.volley;

import java.util.Map;

public class NetworkResponse {
    public final int statusCode;
    public final byte[] data;
    public final Map<String, String> headers;
    public final boolean notModified;
    public final long networkTimeMs;

    public NetworkResponse(int statusCode, byte[] data, Map<String, String> headers, boolean notModified,
                           long networkTimeMs) {
        throw new UnsupportedOperationException("Stub");
    }
}
