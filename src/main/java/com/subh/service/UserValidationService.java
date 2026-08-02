package com.subh.service;

import com.subh.client.UserServiceClient;
import com.subh.exception.ChatServiceException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service wrapping the Feign call to user-service for sender validation.
 *
 * <p>Decorated with Resilience4j {@code @CircuitBreaker} and {@code @Retry}
 * to handle user-service unavailability gracefully.</p>
 *
 * <h3>Fail-Open Strategy</h3>
 * <p>The fallback method deliberately <strong>fails open</strong> — when
 * user-service is unavailable, messages are still allowed through with
 * a logged warning. This is a conscious product decision: chat availability
 * is prioritized over strict sender validation.</p>
 *
 * <p>Contrast with a payment/fintech flow where you'd <strong>fail closed</strong>
 * (block the transaction if validation is unavailable). For a chat feature,
 * the blast radius of a false-positive (letting a message from an unknown
 * sender through) is much smaller than blocking all chat during a
 * user-service outage.</p>
 *
 * @see UserServiceClient
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserValidationService {

    private final UserServiceClient userServiceClient;

    /**
     * Validates that a sender exists in user-service.
     *
     * <p>Calls user-service's {@code GET /users/{id}} endpoint.
     * A successful 200 response means the user exists. A {@code FeignException}
     * (4xx/5xx) triggers retry and eventually the circuit breaker fallback.</p>
     *
     * @param userId the sender's UUID string to validate
     * @throws ChatServiceException if the user is confirmed to not exist (404)
     */
    @CircuitBreaker(name = "userService", fallbackMethod = "fallbackValidate")
    @Retry(name = "userService")
    public void validateSender(String userId) {
        try {
            userServiceClient.getUserById(userId);
            log.debug("Sender {} validated successfully against user-service", userId);
        } catch (feign.FeignException.NotFound e) {
            // User definitively doesn't exist — this is NOT a transient failure.
            // Don't let the circuit breaker mask a genuine 404.
            throw new ChatServiceException("Unknown sender: " + userId);
        }
    }

    /**
     * Fallback method invoked when user-service is unavailable.
     *
     * <p><strong>Fails open:</strong> logs a warning and allows the message
     * through rather than blocking chat entirely. This is the correct
     * degradation mode for a messaging feature where availability trumps
     * strict validation.</p>
     *
     * @param userId the sender's UUID that could not be validated
     * @param t      the exception that triggered the fallback
     */
    @SuppressWarnings("unused") // Referenced by name in @CircuitBreaker annotation
    private void fallbackValidate(String userId, Throwable t) {
        log.warn("user-service unavailable, skipping sender validation for userId={}. Reason: {}",
                userId, t.getMessage());
        // Fail open: allow the message through
    }
}
