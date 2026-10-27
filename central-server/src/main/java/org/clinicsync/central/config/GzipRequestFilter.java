package org.clinicsync.central.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.clinicsync.common.transport.GzipCodec;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Inflates gzip-compressed sync request bodies ({@code Content-Encoding: gzip}) before Spring MVC
 * deserializes them. Requests without the header pass through untouched, so plain JSON clients
 * keep working.
 */
@Component
public class GzipRequestFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (GzipCodec.isGzip(request.getHeader(HttpHeaders.CONTENT_ENCODING))) {
            filterChain.doFilter(new GzipRequestWrapper(request), response);
        } else {
            filterChain.doFilter(request, response);
        }
    }

    private static final class GzipRequestWrapper extends HttpServletRequestWrapper {

        private GzipRequestWrapper(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            InputStream inflated = GzipCodec.decompress(super.getInputStream());
            return new ServletInputStream() {
                @Override
                public int read() throws IOException {
                    return inflated.read();
                }

                @Override
                public boolean isFinished() {
                    try {
                        return inflated.available() == 0;
                    } catch (IOException e) {
                        return true;
                    }
                }

                @Override
                public boolean isReady() {
                    try {
                        return inflated.available() > 0;
                    } catch (IOException e) {
                        return false;
                    }
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Non-blocking reads are not supported for gzip bodies");
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String characterEncoding = getCharacterEncoding();
            if (characterEncoding == null || characterEncoding.isBlank()) {
                characterEncoding = StandardCharsets.UTF_8.name();
            }
            return new BufferedReader(new InputStreamReader(getInputStream(), characterEncoding));
        }
    }
}
