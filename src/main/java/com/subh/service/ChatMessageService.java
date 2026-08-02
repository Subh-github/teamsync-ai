package com.subh.service;

import com.subh.dto.ChatMessageDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Core message processing orchestrator for the chat pipeline.
 *
 * <p>This service is the single entry point for all incoming chat messages.
 * It coordinates the full processing flow:</p>
 * <ol>
 *   <li>Set MDC context for structured logging</li>
 *   <li>Validate the sender against user-service (circuit-breaker-protected)</li>
 *   <li>Enrich the message with server-generated ID and timestamp</li>
 *   <li>Persist to Redis sorted set for 24hr retrieval</li>
 *   <li>Broadcast to all subscribers on the room's topic</li>
 * </ol>
 *
 * <h3>Why this is a separate bean from ChatWebSocketController</h3>
 * <p>Spring's {@code @Async} only works through proxy interception.
 * If {@code processIncomingMessage} were called from within the same
 * class, the {@code @Async} annotation would be silently ignored
 * (self-invocation trap). Keeping the controller and service in
 * separate beans ensures the proxy is always in the call path.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>This service is stateless — all state lives in Redis and Postgres.
 * Multiple async threads can safely invoke it concurrently.</p>
 *
 * @see ChatWebSocketController
 * @see RedisChatStore
 * @see UserValidationService
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatMessageService {

    private final RedisChatStore redisChatStore;
    private final UserValidationService userValidationService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Processes an incoming chat message asynchronously.
     *
     * <p>This method runs on the {@code chatAsyncExecutor} thread pool,
     * freeing the WebSocket thread immediately. The MDC context is
     * propagated via the {@link com.subh.config.AsyncConfig} task decorator.</p>
     *
     * <p>The message is enriched server-side with:</p>
     * <ul>
     *   <li>{@code messageId} — a new UUID for idempotency and event correlation</li>
     *   <li>{@code timestamp} — the server's current UTC instant (prevents client spoofing)</li>
     * </ul>
     *
     * @param chatId  the target chat room identifier
     * @param message the incoming message from the client
     */
    @Async("chatAsyncExecutor")
    public void processIncomingMessage(String chatId, ChatMessageDTO message) {
        // 1. Set MDC context for structured logging
        MDC.put("chatId", chatId);
        MDC.put("userId", message.senderId());

        try {
            log.info("Processing message from sender {} in room {}", message.senderId(), chatId);

            // 2. Validate sender exists (Feign + circuit breaker)
            userValidationService.validateSender(message.senderId());

            // 3. Enrich with server-side fields
            ChatMessageDTO enrichedMessage = new ChatMessageDTO(
                    UUID.randomUUID(),                                      // server-generated ID
                    message.senderId(),
                    message.senderName(),
                    message.content(),
                    Instant.now(),                                          // server timestamp
                    message.type() != null ? message.type() : ChatMessageDTO.MessageType.CHAT
            );

            // 4. Persist to Redis sorted set
            redisChatStore.saveMessage(chatId, enrichedMessage);

            // 5. Broadcast to all subscribers
            String destination = "/topic/chat/" + chatId;
            messagingTemplate.convertAndSend(destination, enrichedMessage);

            log.info("Message {} processed and broadcast to {}", enrichedMessage.messageId(), destination);

            // 6. TODO: Once Kafka is wired, publish ChatMessageCreatedEvent
            //    so ai-service can embed it into pgvector via the Outbox Pattern.
            //    Example:
            //    kafkaTemplate.send("chat.messages", enrichedMessage);

        } catch (Exception e) {
            log.error("Failed to process message in room {} from sender {}: {}",
                    chatId, message.senderId(), e.getMessage(), e);
            // Don't rethrow — the WebSocket client already received the message
            // and we don't want to kill the async thread. Log and move on.
        } finally {
            MDC.clear();
        }
    }
}
