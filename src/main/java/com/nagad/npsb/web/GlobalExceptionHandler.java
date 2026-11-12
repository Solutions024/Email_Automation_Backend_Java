package com.nagad.npsb.web;

import com.nagad.npsb.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.PathContainer;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.pattern.PathPattern;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every error answered the way the FastAPI backend answered it, so the frontend sees no difference:
 * {"detail": "..."} for HTTP errors, 422 {"detail": [{type, loc, msg, input}]} for invalid input,
 * 404 / 405 for unknown paths / methods, a 307 redirect for a path with / without its trailing slash,
 * and a plain-text 500 for anything unexpected (logged with its stack trace).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Request attribute: why the request failed (for the [HTTP] line of the application log). */
    public static final String PROBLEM_ATTRIBUTE = "npsb.problem";

    private final ObjectProvider<RequestMappingHandlerMapping> handlerMapping;
    private volatile List<PathPattern> routes;

    public GlobalExceptionHandler(@Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
        this.handlerMapping = handlerMapping;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> api(ApiException e, HttpServletRequest request) {
        request.setAttribute(PROBLEM_ATTRIBUTE, e.text());
        ResponseEntity.BodyBuilder b = ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_JSON);
        e.headers().forEach(b::header);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", e.detail());
        return b.body(body);
    }

    @ExceptionHandler(RequestValidationException.class)
    public ResponseEntity<Object> validation(RequestValidationException e, HttpServletRequest request) {
        return unprocessable(e.errors(), request);
    }

    /** A path or query parameter of the wrong type (e.g. /api/uploads/abc). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> typeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        ApiException signIn = CurrentUser.notSignedIn(request);
        if (signIn != null) return api(signIn, request);  // the sign-in was checked first
        String where = e.getParameter().hasParameterAnnotation(PathVariable.class) ? "path" : "query";
        Class<?> type = e.getRequiredType();
        Object input = e.getValue();
        Map<String, Object> error;
        if (type == Boolean.class || type == boolean.class) {
            error = Body.error("bool_parsing", List.of(where, e.getName()), "Input should be a valid boolean, unable to interpret input", input);
        } else {
            error = Body.error("int_parsing", List.of(where, e.getName()),
                    "Input should be a valid integer, unable to parse string as an integer", input);
        }
        return unprocessable(List.of(error), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Object> missingQuery(MissingServletRequestParameterException e, HttpServletRequest request) {
        return unprocessable(List.of(Body.error("missing", List.of("query", e.getParameterName()), "Field required", null)), request);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Object> missingPart(MissingServletRequestPartException e, HttpServletRequest request) {
        return unprocessable(List.of(Body.error("missing", List.of("body", e.getRequestPartName()), "Field required", null)), request);
    }

    /** A body that is not JSON (or no body where one is required). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.startsWith("Required request body is missing")) {
            return unprocessable(List.of(Body.error("missing", List.of("body"), "Field required", null)), request);
        }
        return unprocessable(List.of(Body.error("json_invalid", List.of("body", 0), "JSON decode error", Map.of())), request);
    }

    @ExceptionHandler({MaxUploadSizeExceededException.class, MultipartException.class})
    public ResponseEntity<Object> tooLarge(MultipartException e, HttpServletRequest request) {
        ApiException signIn = CurrentUser.notSignedIn(request);
        if (signIn != null) return api(signIn, request);  // the sign-in was checked first
        if (e instanceof MaxUploadSizeExceededException || causedBySize(e)) return api(Uploads.fileTooLarge(), request);
        return unprocessable(List.of(Body.error("missing", List.of("body", "file"), "Field required", null)), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Object> methodNotAllowed(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        request.setAttribute(PROBLEM_ATTRIBUTE, "Method Not Allowed");
        ResponseEntity.BodyBuilder b = ResponseEntity.status(405).contentType(MediaType.APPLICATION_JSON);
        Set<HttpMethod> allowed = e.getSupportedHttpMethods();
        if (allowed != null && !allowed.isEmpty()) {
            // a fixed order (Spring keeps no route order): GET, POST, PUT, PATCH, DELETE, ...
            b.header(HttpHeaders.ALLOW, allowed.stream().map(HttpMethod::name)
                    .sorted(Comparator.comparingInt(GlobalExceptionHandler::methodOrder)).collect(Collectors.joining(", ")));
        }
        return b.body(Map.of("detail", "Method Not Allowed"));
    }

    /** Unknown path: 404, or 307 to the same path with / without the trailing slash when that one exists. */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Object> notFound(Exception e, HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String other = path.endsWith("/") ? path.substring(0, path.length() - 1) : path + "/";
        if (!other.isEmpty() && !path.equals("/") && routeExists(other)) {
            String query = request.getQueryString();
            // the Location keeps the context path, so the browser stays under it (SERVER_CONTEXT_PATH)
            return ResponseEntity.status(307).header(HttpHeaders.LOCATION,
                    request.getContextPath() + other + (query == null ? "" : "?" + query)).build();
        }
        request.setAttribute(PROBLEM_ATTRIBUTE, "Not Found");
        return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON).body(Map.of("detail", "Not Found"));
    }

    /**
     * Other Spring MVC errors (unsupported media type, ...): their status with {"detail": reason}.
     * Anything unexpected: logged with its stack trace, answered with a plain 500.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception e, HttpServletRequest request) {
        if (e instanceof ErrorResponse error && error.getStatusCode().value() < 500) {
            int status = error.getStatusCode().value();
            HttpStatus known = HttpStatus.resolve(status);
            String reason = known != null ? known.getReasonPhrase() : String.valueOf(status);
            request.setAttribute(PROBLEM_ATTRIBUTE, reason);
            return ResponseEntity.status(status).headers(error.getHeaders()).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("detail", reason));
        }
        LOG.error("Request failed: {} {}", request.getMethod(), request.getRequestURI(), e);
        request.setAttribute(PROBLEM_ATTRIBUTE, String.valueOf(e));
        return ResponseEntity.status(500).contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body("Internal Server Error");
    }

    private static ResponseEntity<Object> unprocessable(List<Map<String, Object>> errors, HttpServletRequest request) {
        request.setAttribute(PROBLEM_ATTRIBUTE, String.valueOf(errors));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", errors);
        return ResponseEntity.status(422).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static int methodOrder(String method) {
        int i = List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS").indexOf(method);
        return i < 0 ? 99 : i;
    }

    private static boolean causedBySize(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (t instanceof IllegalStateException && m != null && m.contains("size")) return true;
            if (t.getClass().getSimpleName().contains("SizeLimitExceeded")) return true;
        }
        return false;
    }

    private boolean routeExists(String path) {
        List<PathPattern> patterns = routes;
        if (patterns == null) {
            patterns = new ArrayList<>();
            RequestMappingHandlerMapping mapping = handlerMapping.getIfAvailable();
            if (mapping != null) {
                for (var info : mapping.getHandlerMethods().keySet()) {
                    if (info.getPathPatternsCondition() != null) patterns.addAll(info.getPathPatternsCondition().getPatterns());
                }
            }
            routes = patterns;
        }
        PathContainer container = PathContainer.parsePath(path);
        for (PathPattern p : patterns) if (p.matches(container)) return true;
        return false;
    }
}
