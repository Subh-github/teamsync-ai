package com.subh.controller;

import com.subh.dto.ChatMessageDTO;
import com.subh.service.ChatMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

/**
 * WebSocket controller for handling real-time STOMP chat messages.
 *
 * <p>Clients send messages to {@code /app/chat/{chatId}} and this controller
 * delegates processing to {@link ChatMessageService} in a separate bean.
 * This separation is critical for {@code @Async} to work correctly — Spring's
 * proxy-based AOP silently ignores {@code @Async} on self-invocations.</p>
 *
 * <h3>Message Flow</h3>
 * <ol>
 *   <li>Client sends STOMP message to {@code /app/chat/{chatId}}</li>
 *   <li>This controller receives it via {@code @MessageMapping}</li>
 *   <li>Delegates to {@code ChatMessageService.processIncomingMessage()} (async)</li>
 *   <li>Service validates sender, persists to Redis, broadcasts to {@code /topic/chat/{chatId}}</li>
 * </ol>
 *
 * <h3>Design Decision: No blocking in WS thread</h3>
 * <p>This controller does NOT perform any I/O (Feign calls, Redis writes).
 * All work is delegated to the async service method to keep the WebSocket
 * thread pool responsive under load.</p>
 *
 * @see ChatMessageService
 * @see com.subh.config.WebSocketConfig
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ChatWebSocketController {

    private final ChatMessageService chatMessageService;

    /**
     * Handles incoming STOMP messages for a specific chat room.
     *
     * <p>This method immediately delegates to the async service layer.
     * The WebSocket thread is released instantly — all validation,
     * persistence, and broadcast happen asynchronously.</p>
     *
     * @param chatId  the target chat room identifier (from the destination path)
     * @param message the incoming chat message DTO
     */
    @MessageMapping("/chat/{chatId}")
    public void handleMessage(@DestinationVariable String chatId,
                               ChatMessageDTO message) {
        log.debug("Received STOMP message for room {} from sender {}", chatId, message.senderId());
        chatMessageService.processIncomingMessage(chatId, message);
    }
}
