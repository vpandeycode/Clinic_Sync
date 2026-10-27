package org.clinicsync.common.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * gzip helpers shared by both sides of the sync transport (fixed decision: gzip-compressed
 * batches). Bodies below {@link #MIN_COMPRESS_BYTES} are sent uncompressed — gzip overhead would
 * outweigh the savings on tiny acks and pages.
 */
public final class GzipCodec {

    public static final int MIN_COMPRESS_BYTES = 512;

    private GzipCodec() {}

    public static byte[] compress(byte[] input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length / 2 + 16);
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(input);
        }
        return out.toByteArray();
    }

    public static InputStream decompress(InputStream input) throws IOException {
        return new GZIPInputStream(input);
    }

    public static boolean isGzip(String contentEncoding) {
        return contentEncoding != null && contentEncoding.toLowerCase(java.util.Locale.ROOT).contains("gzip");
    }
}
