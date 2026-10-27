package org.clinicsync.edge.transport;

import org.clinicsync.common.transport.GzipCodec;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.io.InputStream;

/**
 * Sync transport codec for the edge node:
 * <ul>
 *   <li>advertises {@code Accept-Encoding: gzip} and transparently inflates gzip responses, so
 *       central can compress pull pages and FHIR payloads;</li>
 *   <li>compresses request bodies over {@link GzipCodec#MIN_COMPRESS_BYTES} and marks them with
 *       {@code Content-Encoding: gzip}, which the central {@code GzipRequestFilter} inflates.</li>
 * </ul>
 */
public class GzipClientInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        byte[] payload = body;

        if (body != null && body.length >= GzipCodec.MIN_COMPRESS_BYTES) {
            payload = GzipCodec.compress(body);
            request.getHeaders().set(HttpHeaders.CONTENT_ENCODING, "gzip");
        }
        request.getHeaders().set(HttpHeaders.ACCEPT_ENCODING, "gzip");

        ClientHttpResponse response = execution.execute(request, payload);
        return inflateIfGzipped(response);
    }

    private static ClientHttpResponse inflateIfGzipped(ClientHttpResponse response) throws IOException {
        if (!GzipCodec.isGzip(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING))) {
            return response;
        }
        InputStream inflated = GzipCodec.decompress(response.getBody());
        return new InflatedResponse(response, inflated);
    }

    /** Delegating response whose body stream has been inflated. */
    private static final class InflatedResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final InputStream body;

        private InflatedResponse(ClientHttpResponse delegate, InputStream body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public int getRawStatusCode() throws IOException {
            return delegate.getRawStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public void close() {
            try {
                body.close();
            } catch (IOException ignored) {
                // best effort
            }
            delegate.close();
        }

        @Override
        public InputStream getBody() {
            return body;
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }
    }
}
