package com.foleybooks.catalog.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private static final Method VALIDATE_METHOD;

    static {
        try {
            VALIDATE_METHOD = GlobalExceptionHandlerTest.class.getDeclaredMethod("validate", ProbeRequest.class, int.class);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/api/v1/books");
    private WebRequest request;

    @BeforeEach
    void setUp() {
        servletRequest.setRequestURI("/api/v1/books");
        request = new ServletWebRequest(servletRequest);
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void handleMethodArgumentNotValid_whenBodyFieldsInvalid_returnsValidationProblemWithErrors() {
        BindingResult bindingResult = new BeanPropertyBindingResult(new ProbeRequest("", "short"), "probeRequest");
        bindingResult.rejectValue("isbn", "Pattern.probeRequest.isbn", "must be digits only");
        bindingResult.rejectValue("title", "NotBlank.probeRequest.title", "must not be blank");
        var exception = new MethodArgumentNotValidException(new MethodParameter(VALIDATE_METHOD, 0), bindingResult);

        var response = handler.handleMethodArgumentNotValid(exception, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
        var problem = problemOf(response);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(problem.getType()).isEqualTo(URI.create("urn:foley-books:problem:validation"));
        assertThat(problem.getTitle()).isEqualTo("Validation failed");
        assertThat(problem.getDetail()).isEqualTo("One or more fields are invalid.");
        assertThat(problem.getInstance()).isEqualTo(URI.create("/api/v1/books"));
        assertThat(traceIdOf(problem)).matches("[0-9a-f]{8}");
        assertThat(errorsOf(problem))
                .containsExactly(
                        new ValidationError("isbn", "must be digits only"),
                        new ValidationError("title", "must not be blank"));
    }

    @Test
    void handleHandlerMethodValidationException_whenParameterConstraintFails_returnsErrorForThatParameter() {
        var methodParameter = new MethodParameter(VALIDATE_METHOD, 1);
        methodParameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
        var parameterResult = new ParameterValidationResult(
                methodParameter, 0,
                List.of(new DefaultMessageSourceResolvable(new String[]{"Min.page"}, null,
                        "must be greater than or equal to 1")));
        var exception = new HandlerMethodValidationException(
                MethodValidationResult.create(new ProbeRequest("Clean Code", "9780132350884"), VALIDATE_METHOD,
                        List.of(parameterResult)));

        var response = handler.handleHandlerMethodValidationException(exception, new HttpHeaders(),
                HttpStatus.BAD_REQUEST, request);

        assertThat(problemStatus(response)).isEqualTo(400);
        assertThat(traceIdOf(problemOf(response))).matches("[0-9a-f]{8}");
        assertThat(errorsOf(problemOf(response)))
                .containsExactly(new ValidationError("page", "must be greater than or equal to 1"));
    }

    @Test
    void handleTypeMismatch_whenNumericParameterMalformed_returnsValidationErrorForParameter() {
        var exception = new MethodArgumentTypeMismatchException("abc", int.class, "page",
                new MethodParameter(VALIDATE_METHOD, 1), new NumberFormatException("For input string: \"abc\""));

        var response = handler.handleTypeMismatch(exception, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
        var problem = problemOf(response);

        assertThat(problemStatus(response)).isEqualTo(400);
        assertThat(problem.getType()).isEqualTo(URI.create("urn:foley-books:problem:validation"));
        assertThat(errorsOf(problem))
                .containsExactly(new ValidationError("page", "has an invalid value"));
    }

    @Test
    void handleMissingServletRequestParameter_whenParameterAbsent_returnsRequiredErrorForParameter() {
        var exception = new MissingServletRequestParameterException("ids", "string");

        var response = handler.handleMissingServletRequestParameter(exception, new HttpHeaders(),
                HttpStatus.BAD_REQUEST, request);

        assertThat(problemStatus(response)).isEqualTo(400);
        assertThat(errorsOf(problemOf(response)))
                .containsExactly(new ValidationError("ids", "is required"));
    }

    @Test
    void handleConstraintViolation_whenViolationsPresent_returnsErrorsSortedByPath() {
        var exception = new ConstraintViolationException(Set.of(
                violation("isbn", "must be digits only"),
                violation("title", "must not be blank")));

        var response = handler.handleConstraintViolation(exception, servletRequest);
        var problem = problemOf(response);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(problem.getType()).isEqualTo(URI.create("urn:foley-books:problem:validation"));
        assertThat(errorsOf(problem))
                .containsExactly(
                        new ValidationError("isbn", "must be digits only"),
                        new ValidationError("title", "must not be blank"));
    }

    @Test
    void handleHttpMessageNotReadable_whenBodyMalformed_returnsGenericBodyProblemWithoutParserDetails() {
        var exception = new HttpMessageNotReadableException(
                "JSON parse error: unexpected token near isbn hunter2", (HttpInputMessage) null);

        var response = handler.handleHttpMessageNotReadable(exception, new HttpHeaders(),
                HttpStatus.BAD_REQUEST, request);
        var problem = problemOf(response);

        assertThat(problemStatus(response)).isEqualTo(400);
        assertThat(problem.getType()).isEqualTo(URI.create("urn:foley-books:problem:malformed-request-body"));
        assertThat(problem.getTitle()).isEqualTo("Malformed request body");
        assertThat(problem.getDetail()).doesNotContain("hunter2");
    }

    @Test
    void handleApiException_whenBusinessRuleFails_returnsDeclaredStatusAndExtraProperties() {
        var exception = new ApiException(HttpStatus.NOT_FOUND, "book-not-found",
                "Book not found", "No book exists with the given id.", Map.of("bookId", "3fa85f64"));

        var response = handler.handleApiException(exception, servletRequest);
        var problem = problemOf(response);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(problem.getType()).isEqualTo(URI.create("urn:foley-books:problem:book-not-found"));
        assertThat(problem.getTitle()).isEqualTo("Book not found");
        assertThat(problem.getDetail()).isEqualTo("No book exists with the given id.");
        assertThat(problem.getInstance()).isEqualTo(URI.create("/api/v1/books"));
        assertThat(problem.getProperties()).containsEntry("bookId", "3fa85f64");
        assertThat(traceIdOf(problem)).matches("[0-9a-f]{8}");
    }

    @Test
    void handleAccessDenied_whenForbidden_returnsGenericProblemWithoutInternalReason() {
        var exception = new AccessDeniedException("user lacks ROLE_ADMIN");

        var response = handler.handleAccessDenied(exception, servletRequest);
        var problem = problemOf(response);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(problem.getType()).isEqualTo(URI.create("urn:foley-books:problem:forbidden"));
        assertThat(problem.getDetail()).doesNotContain("ROLE_ADMIN");
        assertThat(traceIdOf(problem)).matches("[0-9a-f]{8}");
    }

    @Test
    void handleUnexpected_whenUnknownExceptionThrown_returnsGeneric500WithoutLeakingCause() {
        var exception = new IllegalStateException("connect refused to catalog-db with password=hunter2");

        var response = handler.handleUnexpected(exception, servletRequest);
        var problem = problemOf(response);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(problem.getType()).isEqualTo(URI.create("urn:foley-books:problem:internal-error"));
        assertThat(problem.getDetail()).isEqualTo("An unexpected error occurred. Please try again later.");
        assertThat(problem.getDetail()).doesNotContain("hunter2").doesNotContain("catalog-db");
        assertThat(traceIdOf(problem)).matches("[0-9a-f]{8}");
    }

    @Test
    void handle_whenAnyExceptionThrownAndMdcHasTraceId_reusesCorrelationId() {
        MDC.put(TraceIdFilter.MDC_KEY, "cafe0001");

        var response = handler.handleUnexpected(new IllegalStateException("boom"), servletRequest);

        assertThat(traceIdOf(problemOf(response))).isEqualTo("cafe0001");
    }

    @Test
    void handleException_whenMethodNotSupported_returnsFrameworkProblemWithUrnAndTraceId() throws Exception {
        var response = handler.handleException(new HttpRequestMethodNotSupportedException("PATCH"), request);
        var problem = problemOf(response);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(problem.getType()).isEqualTo(URI.create("urn:foley-books:problem:method-not-allowed"));
        assertThat(problem.getInstance()).isEqualTo(URI.create("/api/v1/books"));
        assertThat(traceIdOf(problem)).matches("[0-9a-f]{8}");
    }

    @Test
    void handleException_whenResponseStatusExceptionCarriesOwnStatus_returnsDecoratedProblem() throws Exception {
        var response = handler.handleException(new ResponseStatusException(HttpStatus.GONE, "Link expired"), request);
        var problem = problemOf(response);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(problem.getType()).isEqualTo(URI.create("urn:foley-books:problem:gone"));
        assertThat(problem.getDetail()).contains("Link expired");
        assertThat(traceIdOf(problem)).matches("[0-9a-f]{8}");
    }

    private static ConstraintViolation<?> violation(String path, String message) {
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        when(violation.getPropertyPath()).thenReturn(path(path));
        when(violation.getMessage()).thenReturn(message);
        return violation;
    }

    private static Path path(String value) {
        return new Path() {
            @Override
            public Iterator<Node> iterator() {
                return List.<Node>of().iterator();
            }

            @Override
            public String toString() {
                return value;
            }
        };
    }

    private static ProblemDetail problemOf(ResponseEntity<?> response) {
        assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);
        return (ProblemDetail) response.getBody();
    }

    private static int problemStatus(ResponseEntity<?> response) {
        return response.getStatusCode().value();
    }

    @SuppressWarnings("unchecked")
    private static List<ValidationError> errorsOf(ProblemDetail problem) {
        return (List<ValidationError>) problem.getProperties().get("errors");
    }

    private static String traceIdOf(ProblemDetail problem) {
        return (String) problem.getProperties().get("traceId");
    }

    record ProbeRequest(String title, String isbn) {
    }

    @SuppressWarnings("unused")
    private static void validate(ProbeRequest probeRequest, int page) {
        // reflection fixture for MethodParameter-based validation exceptions
    }
}
