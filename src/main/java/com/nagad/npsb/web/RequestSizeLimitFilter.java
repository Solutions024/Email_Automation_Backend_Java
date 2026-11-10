package com.nagad.npsb.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Request bodies (JSON) are limited to 30 MB, because they are read into memory: a body that says it is larger
 * (Content-Length) is refused with 413 before it is read, and any other body (sent in chunks, without a
 * Content-Length) is answered with the same 413 as soon as reading it passes the limit. Uploads (multipart) are
 * limited by spring.servlet.multipart.* and answered by GlobalExceptionHandler.
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String type = request.getContentType();
        boolean multipart = type != null && type.toLowerCase().startsWith("multipart/");
        if (multipart) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > Uploads.MAX_BODY_BYTES) {
            ApiException e = Uploads.requestTooLarge();
            request.setAttribute(GlobalExceptionHandler.PROBLEM_ATTRIBUTE, e.text());
            byte[] body = ("{\"detail\":\"" + e.text() + "\"}").getBytes(StandardCharsets.UTF_8);
            response.setStatus(413);
            response.setContentType("application/json");
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            return;
        }
        chain.doFilter(new LimitedBodyRequest(request, Uploads.MAX_BODY_BYTES), response);
    }

    /** The request with a body that can't be read past the limit (413 ApiException when it is). */
    static final class LimitedBodyRequest extends HttpServletRequestWrapper {
        private final long limit;
        private ServletInputStream stream;

        LimitedBodyRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) stream = new LimitedInputStream(super.getInputStream(), limit);
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset;
            try {
                charset = encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
            } catch (IllegalArgumentException e) {
                throw new UnsupportedEncodingException(encoding);
            }
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    /** Counts the bytes read and throws the 413 once there are more than the limit. */
    static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream in;
        private final long limit;
        private long count;

        LimitedInputStream(ServletInputStream in, long limit) {
            this.in = in;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) counted(1);
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) counted(n);
            return n;
        }

        private void counted(int n) {
            count += n;
            if (count > limit) throw Uploads.requestTooLarge();
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
