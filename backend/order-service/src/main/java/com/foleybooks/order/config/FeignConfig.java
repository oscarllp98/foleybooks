package com.foleybooks.order.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.foleybooks.order.cart.client.CatalogErrorDecoder;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import feign.optionals.OptionalDecoder;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.cloud.openfeign.support.ResponseEntityDecoder;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

/**
 * Feign plumbing for the east-west catalog hop (AGENTS.md §4, ADR-005). The client
 * interface is {@code cart/client/CatalogClient} (OR-05); this class owns what ADR-005
 * assigns to {@code config/FeignConfig}: the transport codecs.
 *
 * <p>The decoder is Jackson configured with {@code FAIL_ON_UNKNOWN_PROPERTIES = false}
 * so catalog-service stays free to grow its published {@code BookResponse} (ADR-009):
 * the six fields cart enrichment consumes are the contract, and an added field can
 * never break cart reads. The consumed shape is re-declared consumer-side because
 * AGENTS.md §4 forbids a shared library module.
 *
 * <p>The {@link ErrorDecoder} is the transport half of ADR-005's
 * "absence is data; unavailability is an error": catalog's specific
 * {@code book-not-found} 404 becomes the domain {@code BookNotFoundException},
 * every other failure — other 404s included — stays a {@code FeignException}
 * for {@code GlobalExceptionHandler} to render as 503 {@code catalog-unavailable}
 * — never a fabricated unavailable-cart-line. ADR-005 assigns the bean to this
 * class; its implementation is {@code cart/client/CatalogErrorDecoder}, the
 * transport half of the client adapter it decodes for.
 *
 * <p>No {@code Authorization} relay interceptor exists and none is added speculatively
 * (ADR-005, C1): catalog GETs are anonymous (C22), and the caller's token is validated
 * once at this service's own security boundary (ADR-008) — identity never crosses into
 * catalog.
 */
@Configuration
public class FeignConfig {

    @Bean
    Decoder feignDecoder() {
        ObjectMapper tolerantMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        ObjectFactory<HttpMessageConverters> converters = () ->
                new HttpMessageConverters(new MappingJackson2HttpMessageConverter(tolerantMapper));
        return new OptionalDecoder(new ResponseEntityDecoder(new SpringDecoder(converters)));
    }

    @Bean
    ErrorDecoder catalogErrorDecoder() {
        return new CatalogErrorDecoder();
    }
}
