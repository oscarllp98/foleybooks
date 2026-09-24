package com.foleybooks.order.common;

import org.springframework.http.HttpStatusCode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base class for expected, client-visible failures that do not map onto a framework
 * error (business rules, unknown resources, conflicts). {@link GlobalExceptionHandler}
 * renders it as an RFC 7807 ProblemDetail with the given status, type URN, title and
 * detail plus any extra properties (e.g. {@code availableStock}, the 422 carrier of
 * FR-10/LC-12 that OR-06 will throw).
 *
 * <p>Keep messages free of secrets and user-enumerating detail (NFR-01, NFR-06).
 */
public class ApiException extends RuntimeException {

    public static final String TYPE_URN_PREFIX = "urn:foley-books:problem:";

    private final HttpStatusCode status;
    private final String type;
    private final String title;
    private final String detail;
    private final Map<String, Object> properties;

    public ApiException(HttpStatusCode status, String type, String title, String detail) {
        this(status, type, title, detail, Map.of());
    }

    public ApiException(HttpStatusCode status, String type, String title, String detail, Map<String, Object> properties) {
        super(title + " [" + TYPE_URN_PREFIX + type + "]");
        this.status = status;
        this.type = type.startsWith(TYPE_URN_PREFIX) ? type : TYPE_URN_PREFIX + type;
        this.title = title;
        this.detail = detail;
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    public HttpStatusCode getStatus() {
        return status;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getDetail() {
        return detail;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }
}
