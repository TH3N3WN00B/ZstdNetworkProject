package com.rigorberto.zstdnetworkproject;

/**
 * Wire protocol for the zstd capability handshake exchanged during the login phase between the
 * Velocity proxy and zstd-capable clients.
 *
 * <p>The proxy sends a login plugin message on {@link #CHANNEL} carrying a single version byte.
 * Clients that support zstd compression answer with the same byte; vanilla clients (or clients
 * without the mod) do not recognize the channel and NAK the query. The proxy then enables zstd
 * compression only for clients that confirmed, and keeps vanilla zlib for everyone else.
 */
public final class ZstdNegotiation {

    public static final String CHANNEL_NAMESPACE = "zstdnetworkproject";
    public static final String CHANNEL_PATH = "capable";
    public static final String CHANNEL = CHANNEL_NAMESPACE + ":" + CHANNEL_PATH;

    /**
     * Current version of the negotiation protocol. The proxy sends this as a single byte and the
     * client echoes it back to signal zstd support. Bump it whenever the meaning of the query or
     * the shape of the compressed stream changes in a breaking way.
     */
    public static final byte PROTOCOL_VERSION = 1;

    /**
     * The payload the proxy sends with the login plugin message to probe for zstd support.
     */
    public static byte[] queryPayload() {
        return new byte[]{PROTOCOL_VERSION};
    }

    /**
     * Whether a client response to the login probe confirms zstd support.
     *
     * @param response the response body received from the client, or null when the client did not
     *                 recognise the channel (vanilla clients NAK the query)
     */
    public static boolean isSupportedResponse(byte[] response) {
        return response != null && response.length == 1 && response[0] == PROTOCOL_VERSION;
    }

    private ZstdNegotiation() {
    }
}
