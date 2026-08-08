package com.subh.service;

import com.subh.dto.ChatMessageDTO;
import com.subh.dto.ChatMessageDTO.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ChatMessageService}.
 *
 * <p>Validates the message processing pipeline: sender validation,
 * message enrichment, Redis persistence, and WebSocket broadcast.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatMessageService Unit Tests")
class ChatMessageServiceTest {

    @Mock
    private RedisChatStore redisChatStore;

    @Mock
    private UserValidationService userValidationService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private ChatMessageService chatMessageService;

    @Captor
    private ArgumentCaptor<ChatMessageDTO> messageCaptor;

    @Captor
    private ArgumentCaptor<String> destinationCaptor;

    private static final String CHAT_ID = "room-123";
    private static final String SENDER_ID = UUID.randomUUID().toString();
    private static final String SENDER_NAME = "John Doe";

    private ChatMessageDTO incomingMessage;

    @BeforeEach
    void setUp() {
        incomingMessage = new ChatMessageDTO(
                null,           // messageId — set server-side
                SENDER_ID,
                SENDER_NAME,
                "Hello, team!",
                null,           // timestamp — set server-side
                MessageType.CHAT
        );
    }

    @Test
    @DisplayName("Happy path: validate → save → broadcast with enriched fields")
    void processIncomingMessage_happyPath_validatesAndSavesAndBroadcasts() {
        // Given
        doNothing().when(userValidationService).validateSender(SENDER_ID);

        // When
        chatMessageService.processIncomingMessage(CHAT_ID, incomingMessage);

        // Then — sender was validated
        verify(userValidationService).validateSender(SENDER_ID);

        // Then — message was saved to Redis with enriched fields
        verify(redisChatStore).saveMessage(eq(CHAT_ID), messageCaptor.capture());
        ChatMessageDTO savedMessage = messageCaptor.getValue();
        assertThat(savedMessage.messageId()).isNotNull();
        assertThat(savedMessage.timestamp()).isNotNull();
        assertThat(savedMessage.senderId()).isEqualTo(SENDER_ID);
        assertThat(savedMessage.senderName()).isEqualTo(SENDER_NAME);
        assertThat(savedMessage.content()).isEqualTo("Hello, team!");
        assertThat(savedMessage.type()).isEqualTo(MessageType.CHAT);

        // Then — message was broadcast to the correct topic
        verify(messagingTemplate).convertAndSend(
                destinationCaptor.capture(),
                messageCaptor.capture()
        );
        assertThat(destinationCaptor.getValue()).isEqualTo("/topic/chat/" + CHAT_ID);
    }

    @Test
    @DisplayName("Server-side enrichment: messageId and timestamp are generated")
    void processIncomingMessage_enrichesServerSideFields() {
        // Given
        doNothing().when(userValidationService).validateSender(SENDER_ID);
        Instant beforeProcessing = Instant.now();

        // When
        chatMessageService.processIncomingMessage(CHAT_ID, incomingMessage);

        // Then
        verify(redisChatStore).saveMessage(eq(CHAT_ID), messageCaptor.capture());
        ChatMessageDTO enriched = messageCaptor.getValue();

        assertThat(enriched.messageId()).isNotNull();
        assertThat(enriched.timestamp()).isNotNull();
        assertThat(enriched.timestamp()).isAfterOrEqualTo(beforeProcessing);
    }

    @Test
    @DisplayName("Null message type defaults to CHAT")
    void processIncomingMessage_nullType_defaultsToChat() {
        // Given
        ChatMessageDTO nullTypeMessage = new ChatMessageDTO(
                null, SENDER_ID, SENDER_NAME, "Test", null, null
        );
        doNothing().when(userValidationService).validateSender(SENDER_ID);

        // When
        chatMessageService.processIncomingMessage(CHAT_ID, nullTypeMessage);

        // Then
        verify(redisChatStore).saveMessage(eq(CHAT_ID), messageCaptor.capture());
        assertThat(messageCaptor.getValue().type()).isEqualTo(MessageType.CHAT);
    }

    @Test
    @DisplayName("Validation failure: exception is caught, message NOT persisted")
    void processIncomingMessage_validationFails_doesNotSaveOrBroadcast() {
        // Given
        doThrow(new com.subh.exception.ChatServiceException("Unknown sender"))
                .when(userValidationService).validateSender(SENDER_ID);

        // When
        chatMessageService.processIncomingMessage(CHAT_ID, incomingMessage);

        // Then — no Redis save and no broadcast
        verify(redisChatStore, never()).saveMessage(any(), any());
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    @DisplayName("Redis failure: exception is caught gracefully")
    void processIncomingMessage_redisFails_doesNotCrash() {
        // Given
        doNothing().when(userValidationService).validateSender(SENDER_ID);
        doThrow(new RuntimeException("Redis connection refused"))
                .when(redisChatStore).saveMessage(any(), any());

        // When — should not throw
        chatMessageService.processIncomingMessage(CHAT_ID, incomingMessage);

        // Then — validation was attempted, broadcast was not reached
        verify(userValidationService).validateSender(SENDER_ID);
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }
}
