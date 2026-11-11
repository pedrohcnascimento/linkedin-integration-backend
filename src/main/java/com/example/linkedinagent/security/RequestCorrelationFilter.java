package com.example.linkedinagent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID_KEY = "requestId";
    public static final int MAX_REQUEST_BODY_BYTES = 65_536;
    private static final Pattern SAFE_REQUEST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
        MDC.put(MDC_REQUEST_ID_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            if (request.getContentLengthLong() > MAX_REQUEST_BODY_BYTES) {
                rejectOversizedPayload(response);
                return;
            }
            BoundedHttpServletRequestWrapper boundedRequest =
                    new BoundedHttpServletRequestWrapper(request, MAX_REQUEST_BODY_BYTES);
            try {
                filterChain.doFilter(boundedRequest, response);
            } catch (Exception exception) {
                if (hasCause(exception, PayloadTooLargeInputStreamException.class) && !response.isCommitted()) {
                    rejectOversizedPayload(response);
                } else {
                    throw exception;
                }
            }
        } finally {
            MDC.remove(MDC_REQUEST_ID_KEY);
        }
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> target) {
        Throwable current = throwable;
        while (current != null) {
            if (target.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void rejectOversizedPayload(HttpServletResponse response) throws IOException {
        response.resetBuffer();
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"Request body exceeds the maximum allowed size.\"}");
        response.flushBuffer();
    }

    static String resolveRequestId(String candidate) {
        if (candidate != null && SAFE_REQUEST_ID_PATTERN.matcher(candidate.trim()).matches()) {
            return candidate.trim();
        }
        return UUID.randomUUID().toString();
    }

    private static final class PayloadTooLargeInputStreamException extends RuntimeException {
    }

    private static final class BoundedHttpServletRequestWrapper extends HttpServletRequestWrapper {
        private final int maxBytes;

        private BoundedHttpServletRequestWrapper(HttpServletRequest request, int maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private int totalRead;

                @Override
                public int read() throws IOException {
                    int b = delegate.read();
                    if (b != -1) {
                        increment(1);
                    }
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int count = delegate.read(b, off, len);
                    if (count > 0) {
                        increment(count);
                    }
                    return count;
                }

                private void increment(int count) {
                    totalRead += count;
                    if (totalRead > maxBytes) {
                        throw new PayloadTooLargeInputStreamException();
                    }
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    delegate.setReadListener(readListener);
                }

                @Override
                public void close() throws IOException {
                    delegate.close();
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
