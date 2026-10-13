package com.foleybooks.order.common;

import feign.FeignException;
import feign.Request;
import feign.Response;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wire-shape verification of the ProblemDetail contract on the JSON level (plan §6.2 style),
 * via standalone MockMvc so the advice runs through the real exception-resolver and
 * message-converter chain.
 */
class GlobalExceptionHandlerMockMvcTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MDC.clear();
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void create_whenBodyFieldsInvalid_returnsProblemWithFieldErrorsAndTraceId() throws Exception {
        mockMvc.perform(post("/probe/cart/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\": null, \"quantity\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:validation"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("One or more fields are invalid."))
                .andExpect(jsonPath("$.instance").value("/probe/cart/items"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("bookId", "quantity")))
                .andExpect(jsonPath("$.errors[*].message",
                        containsInAnyOrder("must not be null", "must be greater than or equal to 1")));
    }

    @Test
    void checked_whenParameterBelowMinimum_returnsValidationErrorForParameter() throws Exception {
        mockMvc.perform(get("/probe/checked").param("n", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("n"))
                .andExpect(jsonPath("$.errors[0].message").value("must be greater than or equal to 1"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));
    }

    @Test
    void paged_whenParameterNotNumeric_returnsValidationErrorForParameter() throws Exception {
        mockMvc.perform(get("/probe/paged").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("page"))
                .andExpect(jsonPath("$.errors[0].message").value("has an invalid value"));
    }

    @Test
    void probe_whenBodyNotJson_returnsMalformedBodyProblem() throws Exception {
        mockMvc.perform(post("/probe/cart/items").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:malformed-request-body"))
                .andExpect(jsonPath("$.title").value("Malformed request body"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));
    }

    @Test
    void business_whenApiExceptionThrown_returnsDeclaredUrnAndExtraProperties() throws Exception {
        mockMvc.perform(get("/probe/business"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:insufficient-stock"))
                .andExpect(jsonPath("$.title").value("Insufficient stock"))
                .andExpect(jsonPath("$.detail").value("Only 3 units left."))
                .andExpect(jsonPath("$.availableStock").value(3))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));
    }

    @Test
    void catalogDown_whenFeignExceptionThrown_returns503DependencyProblemWithoutUpstreamBody() throws Exception {
        mockMvc.perform(get("/probe/catalog-down"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:catalog-unavailable"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")))
                .andExpect(content().string(not(containsString("hunter2"))));
    }

    @Test
    void denied_whenAccessDenied_returnsGenericForbiddenWithoutReason() throws Exception {
        mockMvc.perform(get("/probe/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:forbidden"))
                .andExpect(jsonPath("$.detail").value("You are not allowed to perform this action."))
                .andExpect(content().string(not(containsString("ROLE_CUSTOMER"))));
    }

    @Test
    void boom_whenUnknownExceptionThrown_returnsGeneric500WithoutLeakingCause() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:internal-error"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred. Please try again later."))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")))
                .andExpect(content().string(not(containsString("hunter2"))));
    }

    @Test
    void anyProblem_whenMdcHasTraceId_isCorrelatedWithTheRequestTrace() throws Exception {
        MDC.put(TraceIdFilter.MDC_KEY, "feedbeef");

        mockMvc.perform(get("/probe/business"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.traceId").value("feedbeef"));
    }

    @RestController
    static class ProbeController {

        record ProbeRequest(
                @NotNull(message = "must not be null") String bookId,
                @Min(value = 1, message = "must be greater than or equal to 1") int quantity) {
        }

        @PostMapping("/probe/cart/items")
        ResponseEntity<Void> create(@Valid @RequestBody ProbeRequest request) {
            return ResponseEntity.ok().build();
        }

        @GetMapping("/probe/checked")
        ResponseEntity<Void> checked(@RequestParam @Min(1) int n) {
            return ResponseEntity.ok().build();
        }

        @GetMapping("/probe/paged")
        ResponseEntity<Void> paged(@RequestParam int page) {
            return ResponseEntity.ok().build();
        }

        @GetMapping("/probe/business")
        ResponseEntity<Void> business() {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-stock",
                    "Insufficient stock", "Only 3 units left.", Map.of("availableStock", 3));
        }

        @GetMapping("/probe/catalog-down")
        ResponseEntity<Void> catalogDown() {
            Request request = Request.create(Request.HttpMethod.GET,
                    "http://catalog-service:8082/api/v1/books/batch",
                    Collections.<String, Collection<String>>emptyMap(), Request.Body.empty(), null);
            Response response = Response.builder()
                    .status(503)
                    .reason("Service Unavailable")
                    .request(request)
                    .headers(Collections.<String, Collection<String>>emptyMap())
                    .body("upstream said: secret=hunter2", StandardCharsets.UTF_8)
                    .build();
            throw FeignException.errorStatus("CatalogClient#batchBooks(Collection)", response);
        }

        @GetMapping("/probe/denied")
        ResponseEntity<Void> denied() {
            throw new AccessDeniedException("user lacks ROLE_CUSTOMER");
        }

        @GetMapping("/probe/boom")
        ResponseEntity<Void> boom() {
            throw new IllegalStateException("connect refused to order-db with password=hunter2");
        }
    }
}
