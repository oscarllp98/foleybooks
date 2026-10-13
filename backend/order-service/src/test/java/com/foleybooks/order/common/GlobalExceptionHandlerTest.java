package com.foleybooks.order.common;

import feign.FeignException;
import feign.Request;
import feign.Response;
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
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
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
    private final MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/api/v1/cart");
    private WebRequest request;

    @BeforeEach
    void setUp() {
        servletRequest.setRequestURI("/api/v1/cart");
        request = new ServletWebRequest(servletRequest);
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void handleMethodArgumentNotValid_whenBodyFieldsInvalid_returnsValidationProblemWithErrors() {
        BindingResult bindingResult = new BeanPropertyBindingResult(new ProbeRequest("", 0), "probeRequest");
        bindingResult.rejectValue("quantity", "Min.probeRequest.quantity", "must be greater than or equal to 1");
        bindingResult.rejectValue("bookId", "NotNull.probeRequest.bookId", "must not be null");
        var exception = new MethodArgumentNotValidException(new MethodParameter(VALIDATE_METHOD, 0), bindingResult);

        var response = handler.handleMethodArgumentNotValid(exception, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
        var problem = problemOf(response);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(problem.getType()).isEqualTo(URI.create("urn:foley-books:problem:validation"));
        assertThat(problem.getTitle()).isEqualTo("Validation failed");
        assertThat(problem.getDetail()).isEqualTo("One or more fields are invalid.");
        assertThat(problem.getInstance()).isEqualTo(URI.create("/api/v1/cart"));
        assertThat(traceIdOf(problem)).matches("[0-9a-f]{8}");
        assertThat(errorsOf(problem))
                .containsExactly(
                        new ValidationError("quantity", "must be greater than or equal to 1"),
                        new ValidationError("bookId", "must not be null"));
    }

    @Test
    void handleHandlerMethodValidationException_whenParameterConstraintFails_returnsErrorForThatParameter() {
        var methodParameter = new MethodParameter(VALIDATE_METHOD, 1);
        methodParameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
        var parameterResult = new ParameterValidationResult(
                methodParameter, 0,
                List.of(new DefaultMessageSourceResolvable(new String[]{"Min.quantity"}, null,
                        "must be greater than or equal to 1")));
        var exception = new HandlerMethodValidationException(
                MethodValidationResult.create(new ProbeRequest("3fa85f64", 1), VALIDATE_METHOD,
                        List.of(parameterResult)));

        var response = handler.handleHandlerMethodValidationException(exception, new HttpHeaders(),
                HttpStatus.BAD_REQUEST, request);

        assertThat(problemStatus(response)).isEqualTo(400);
        assertThat(traceIdOf(problemOf(response))).matches("[0-9a-f]{8}");
        assertThat(errorsOf(problemOf(response)))
                .containsExactly(new ValidationError("quantity", "must be greater than or equal to 1"));
    }

    @Test
    void handleTypeMismatch_whenUuidParameterMalformed_returnsValidationErrorForParameter() {
        // C23 boundary rule (D-07 as amended): a malformed /cart/items/{bookId} path
        // variable is refused as a 400 validation ProblemDetail, never a 404.
        var exception = new MethodArgumentTypeMismatchException("abc", Object.class, "bookId",
                new MethodParameter(VALIDATE_METHOD, 1), new IllegalArgumentException("not a uuid"));

        var response = handler.handleTypeMismatch(exception, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
        var problem = problemOf(response);

        assertThat(problemStatus(response)).isEqualTo(400);
        assertThat(problem.getType()).isEqualTo(URI.create("urn:foley-books:problem:validation"));
        assertThat(errorsOf(problem))
                .containsExactly(new ValidationError("bookId", "has an invalid value"));
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
                violation("quantity", "must be greater than or equal to 1"),
                violation("bookId", "must not be null")));

        var response = handler.handleConstraintViolation(exception, servletRequest);
        var problem = problemOf(response);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(problem.getType()).isEqualTo(URI.create("urn:foley-books:problem:validation"));
        assertThat(errorsOf(problem))
                .containsExactly(
                        new ValidationError("bookId", "must not be null"),
                        new ValidationError("quantity", "must be greater than or equal to 1"));
    }

    @Test
    void handleHttpMessageNotReadable_whenBodyMalformed_returnsGenericBodyProblemWithoutParserDetails() {
        var exception = new HttpMessageNotReadableException(
                "JSON parse error: unexpected token near quantity hunter2", (HttpInputMessage) null);

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
        // The plan §2 422 shape OR-06 throws through this handler.
        var exception = new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-stock",
                "Insufficient stock", "Only 3 units left.", Map.of("availableStock", 3));

        var response = handler.handleApiException(exception, servletRequest);
        var problem = problemOf(response);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(problem.getType()).isEqualTo(URI.create("urn:foley-books:problem:insufficient-stock"));
        assertThat(problem.getTitle()).isEqualTo("Insufficient stock");
        assertThat(problem.getDetail()).isEqualTo("Only 3 units left.");
        assertThat(problem.getInstance()).isEqualTo(URI.create("/api/v1/cart"));
        assertThat(problem.getProperties()).containsEntry("availableStock", 3);
        assertThat(traceIdOf(problem)).matches("[0-9a-f]{8}");
    }

    @Test
    void handleAccessDenied_whenForbidden_returnsGenericProblemWithoutInternalReason() {
        var exception = new AccessDeniedException("user lacks ROLE_CUSTOMER");

        var response = handler.handleAccessDenied(exception, servletRequest);
        var problem = problemOf(response);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(problem.getType()).isEqualTo(URI.create("urn:foley-books:problem:forbidden"));
        assertThat(problem.getDetail()).doesNotContain("ROLE_CUSTOMER");
        assertThat(traceIdOf(problem)).matches("[0-9a-f]{8}");
    }

    @Test
    void handleFeignFailure_whenCatalogUnreachable_returns503DependencyProblemWithoutUpstreamBody() {
        // ADR-005: a down catalog is a traceable dependency error — never an
        // available=false cart answer, never a leak of the upstream body (C24).
        var exception = feignException(503, "upstream said: customer email=hunter2@secret.example");

        var response = handler.handleFeignFailure(exception, servletRequest);
        var problem = problemOf(response);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(problem.getType()).isEqualTo(URI.create("urn:foley-books:problem:catalog-unavailable"));
        assertThat(problem.getTitle()).isEqualTo("Service temporarily unavailable");
        assertThat(problem.getDetail()).isEqualTo("The catalog service could not be reached. Please try again later.");
        assertThat(problem.getDetail()).doesNotContain("hunter2");
        assertThat(problem.getInstance()).isEqualTo(URI.create("/api/v1/cart"));
        assertThat(traceIdOf(problem)).matches("[0-9a-f]{8}");
    }

    @Test
    void handleUnexpected_whenUnknownExceptionThrown_returnsGeneric500WithoutLeakingCause() {
        var exception = new IllegalStateException("connect refused to order-db with password=hunter2");

        var response = handler.handleUnexpected(exception, servletRequest);
        var problem = problemOf(response);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(problem.getType()).isEqualTo(URI.create("urn:foley-books:problem:internal-error"));
        assertThat(problem.getDetail()).isEqualTo("An unexpected error occurred. Please try again later.");
        assertThat(problem.getDetail()).doesNotContain("hunter2").doesNotContain("order-db");
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
        assertThat(problem.getInstance()).isEqualTo(URI.create("/api/v1/cart"));
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

    private static FeignException feignException(int status, String upstreamBody) {
        Request request = Request.create(Request.HttpMethod.GET,
                "http://catalog-service:8082/api/v1/books/batch",
                Collections.<String, Collection<String>>emptyMap(), Request.Body.empty(), null);
        Response response = Response.builder()
                .status(status)
                .reason("Service Unavailable")
                .request(request)
                .headers(Collections.<String, Collection<String>>emptyMap())
                .body(upstreamBody, StandardCharsets.UTF_8)
                .build();
        return FeignException.errorStatus("CatalogClient#batchBooks(Collection)", response);
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

    record ProbeRequest(String bookId, int quantity) {
    }

    @SuppressWarnings("unused")
    private static void validate(ProbeRequest probeRequest, int quantity) {
        // reflection fixture for MethodParameter-based validation exceptions
    }
}
