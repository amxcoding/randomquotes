package com.amxcoding.randomquotes.api.handlers;

import com.amxcoding.randomquotes.api.models.error.ErrorResponse;
import com.amxcoding.randomquotes.application.exceptions.ApiException;
import com.amxcoding.randomquotes.application.exceptions.providers.QuoteProviderException;
import com.amxcoding.randomquotes.application.exceptions.repositories.QuoteLikePersistenceException;
import com.amxcoding.randomquotes.application.exceptions.repositories.QuotePersistenceException;
import com.amxcoding.randomquotes.application.exceptions.services.QuoteCacheException;
import com.amxcoding.randomquotes.application.exceptions.services.QuoteFetchOrchestratorException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@SuppressWarnings("LoggingPlaceholderCountMatchesArgumentCount")
@Component
@Order(-2)
public class GlobalWebFluxExceptionHandler implements ErrorWebExceptionHandler {

    private static final String DEFAULT_ERROR_MESSAGE = "Oops! Something went wrong. We are looking into it!";
    private static final Logger logger = LoggerFactory.getLogger(GlobalWebFluxExceptionHandler.class);

    private final ObjectMapper objectMapper;

    public GlobalWebFluxExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Catches and handles exceptions globally in a WebFlux application. It differentiates between specific API errors
     * (such as quote or like-related issues) and generic errors, logs relevant information, and generates a structured
     * error response to return to the client.
     */
    @Override
    @NonNull
    public Mono<Void> handle(@NonNull ServerWebExchange exchange, @NonNull Throwable throwable) {
        DataBufferFactory bufferFactory = exchange.getResponse().bufferFactory();
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        ErrorResponse errorResponse;
        String requestUri = exchange.getRequest().getURI().toString();

        if (throwable instanceof ApiException apiException) {
            status = apiException.getStatus();
            String detailString = apiException.getDetails() != null && !apiException.getDetails().isEmpty()
                    ? String.join(", ", apiException.getDetails())
                    : "";
            String message = getApiExceptionMessage(apiException);

            errorResponse = new ErrorResponse(status.value(), message, detailString);

            // Log based on status
            if (status.is4xxClientError()) {
                logger.warn("ApiClientError", "Error for request '{}': {} (Status: {}). Details: {}", requestUri, message, status, detailString);
            } else {
                logger.error("ApiServerError", "Error for request '{}': {} (Status: {}). Details: {}", requestUri, message, status, detailString, apiException);
            }

        } else {
            // Catch-all for generic Exception
            errorResponse = new ErrorResponse(status.value(), DEFAULT_ERROR_MESSAGE, "");
            logger.error("InternalError", "Unexpected error for request '{}': {}", requestUri, throwable.getMessage(), throwable);
        }

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        try {
            byte[] errorBytes = objectMapper.writeValueAsBytes(errorResponse);
            DataBuffer dataBuffer = bufferFactory.wrap(errorBytes);
            return exchange.getResponse().writeWith(Mono.just(dataBuffer));
        } catch (JsonProcessingException e) {
            logger.error("Error writing JSON response", e);
            return exchange.getResponse().writeWith(Mono.just(bufferFactory.wrap(DEFAULT_ERROR_MESSAGE.getBytes(StandardCharsets.UTF_8))));
        }
    }

    /**
     * Returns a custom error message based on the type of ApiException, providing user-friendly feedback
     * for issues like quote service unavailability or persistence problems.
     */
    private String getApiExceptionMessage(ApiException ex) {
        return switch (ex) {
            case QuoteProviderException exProvider -> "The quote service is currently not available. Please try again later";
            case QuotePersistenceException exPersistence -> "We encountered an issue while processing the quote data. Please try again later.";
            case QuoteLikePersistenceException exPersistence -> "We encountered an issue while updating your likes. Please try again later.";
            case QuoteFetchOrchestratorException exOrchestrator -> "The quote service is currently not available. Please try again later";
            case QuoteCacheException exCache -> "The quote service is currently not available. Please try again later";
            default -> DEFAULT_ERROR_MESSAGE;
        };
    }
}