package com.foleybooks.catalog.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The single error surface of catalog-service (D-15, NFR-06): every failure — bean
 * validation, malformed requests, {@link ApiException} business rules and unexpected
 * server errors — is rendered as an RFC 7807 ProblemDetail with a
 * {@code urn:foley-books:problem:*} type URN, the request path as {@code instance} and a
 * {@code traceId} that correlates the response with the server logs. 400s additionally
 * carry an {@code errors[]} array of {@link ValidationError}. Unexpected failures return
 * a generic message; the real cause is only ever logged.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String VALIDATION_TYPE = ApiException.TYPE_URN_PREFIX + "validation";
    static final String MALFORMED_BODY_TYPE = ApiException.TYPE_URN_PREFIX + "malformed-request-body";
    static final String FORBIDDEN_TYPE = ApiException.TYPE_URN_PREFIX + "forbidden";
    static final String INTERNAL_ERROR_TYPE = ApiException.TYPE_URN_PREFIX + "internal-error";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        log.debug("Handled API error [{}] on {} {}: {}", TraceIdFilter.currentTraceId(),
                request.getMethod(), request.getRequestURI(), ex.getType());
        ProblemDetail problem = problem(ex.getStatus(), ex.getType(), ex.getTitle(), ex.getDetail(), request);
        ex.getProperties().forEach(problem::setProperty);
        return problemResponse(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.debug("Access denied [{}] on {} {}", TraceIdFilter.currentTraceId(),
                request.getMethod(), request.getRequestURI());
        ProblemDetail problem = problem(HttpStatus.FORBIDDEN, FORBIDDEN_TYPE, "Forbidden",
                "You are not allowed to perform this action.", request);
        return problemResponse(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest request) {
        List<ValidationError> errors = ex.getConstraintViolations().stream()
                .map(violation -> new ValidationError(String.valueOf(violation.getPropertyPath()), violation.getMessage()))
                .sorted(Comparator.comparing(ValidationError::field))
                .toList();
        return problemResponse(validationProblem(errors, request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        String traceId = TraceIdFilter.currentTraceId();
        log.error("Unexpected error [{}] on {} {}", traceId, request.getMethod(), request.getRequestURI(), ex);
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_TYPE, "Internal server error",
                "An unexpected error occurred. Please try again later.", request);
        return problemResponse(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<ValidationError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ValidationError(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();
        return handleExceptionInternal(ex, validationProblem(errors, servletRequest(request)),
                new HttpHeaders(), status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        List<ValidationError> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            String field = result.getMethodParameter().getParameterName() != null
                    ? result.getMethodParameter().getParameterName()
                    : "request";
            for (MessageSourceResolvable resolvable : result.getResolvableErrors()) {
                errors.add(new ValidationError(field, resolvable.getDefaultMessage()));
            }
        }
        return handleExceptionInternal(ex, validationProblem(errors, servletRequest(request)),
                new HttpHeaders(), status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String field = ex instanceof MethodArgumentTypeMismatchException parameterEx
                ? parameterEx.getName()
                : "request";
        ValidationError error = new ValidationError(field, "has an invalid value");
        return handleExceptionInternal(ex, validationProblem(List.of(error), servletRequest(request)),
                new HttpHeaders(), status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ValidationError error = new ValidationError(ex.getParameterName(), "is required");
        return handleExceptionInternal(ex, validationProblem(List.of(error), servletRequest(request)),
                new HttpHeaders(), status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        HttpServletRequest servletRequest = servletRequest(request);
        log.debug("Malformed request body [{}] on {}", TraceIdFilter.currentTraceId(),
                servletRequest != null ? servletRequest.getRequestURI() : "unknown");
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, MALFORMED_BODY_TYPE, "Malformed request body",
                "The request body is missing or malformed.", servletRequest);
        return handleExceptionInternal(ex, problem, new HttpHeaders(), status, request);
    }

    /**
     * Decorates every framework-produced ProblemDetail (405, 404, 415, ...) that still
     * carries the default {@code about:blank} type with the service's URN namespace,
     * the request path as instance and the trace id. Problems this class already built
     * keep their own semantic type.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        Object decorated = body instanceof ProblemDetail problem ? decorate(problem, statusCode, request) : body;
        return super.createResponseEntity(decorated, headers, statusCode, request);
    }

    private ProblemDetail decorate(ProblemDetail problem, HttpStatusCode status, WebRequest request) {
        if (problem.getType() == null || problem.getType().equals(URI.create("about:blank"))) {
            problem.setType(URI.create(typeUrn(status)));
        }
        HttpServletRequest servletRequest = servletRequest(request);
        if (servletRequest != null && problem.getInstance() == null) {
            problem.setInstance(URI.create(servletRequest.getRequestURI()));
        }
        if (problem.getProperties() == null || problem.getProperties().get("traceId") == null) {
            problem.setProperty("traceId", TraceIdFilter.currentTraceId());
        }
        return problem;
    }

    private static ResponseEntity<ProblemDetail> problemResponse(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private ProblemDetail validationProblem(List<ValidationError> errors, @Nullable HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, VALIDATION_TYPE, "Validation failed",
                "One or more fields are invalid.", request);
        problem.setProperty("errors", errors);
        return problem;
    }

    private ProblemDetail problem(HttpStatusCode status, String type, String title, String detail,
                                  @Nullable HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(type));
        if (request != null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        problem.setProperty("traceId", TraceIdFilter.currentTraceId());
        return problem;
    }

    private static String typeUrn(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String slug = resolved != null
                ? resolved.name().toLowerCase(Locale.ROOT).replace('_', '-')
                : "error";
        return ApiException.TYPE_URN_PREFIX + slug;
    }

    @Nullable
    private static HttpServletRequest servletRequest(WebRequest request) {
        return request instanceof ServletWebRequest servletWebRequest ? servletWebRequest.getRequest() : null;
    }
}
