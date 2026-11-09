package com.nagad.npsb.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JSON bodies over 30 MB are refused with 413 before they are read (uploads are limited by
 * spring.servlet.multipart.* and answered by GlobalExceptionHandler).
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String type = request.getContentType();
        boolean multipart = type != null && type.toLowerCase().startsWith("multipart/");
        if (!multipart && request.getContentLengthLong() > Uploads.MAX_BODY_BYTES) {
            ApiException e = Uploads.requestTooLarge();
            request.setAttribute(GlobalExceptionHandler.PROBLEM_ATTRIBUTE, e.text());
            byte[] body = ("{\"detail\":\"" + e.text() + "\"}").getBytes(StandardCharsets.UTF_8);
            response.setStatus(413);
            response.setContentType("application/json");
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            return;
        }
        chain.doFilter(request, response);
    }
}
