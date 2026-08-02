package com.subh.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subh.dto.ChatMessageDTO;
import com.subh.dto.ChatMessageDTO.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RedisChatStore}.
 *
 * <p>Validates sorted set operations (ZADD, ZRANGEBYSCORE) and the
 * serialization/deserialization pipeline using mocked RedisTemplate.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisChatStore Unit Tests")
class RedisChatStoreTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Captor
    private ArgumentCaptor<String> keyCaptor;

    @Captor
    private ArgumentCaptor<Double> scoreCaptor;

    @Captor
    private ArgumentCaptor<String> valueCaptor;

    private RedisChatStore redisChatStore;
    private ObjectMapper objectMapper;

    private static final String CHAT_ID = "room-abc";
    private static final String EXPECTED_KEY = "chat:room:room-abc:messages";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        redisChatStore = new RedisChatStore(redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("saveMessage stores JSON in sorted set with epoch millis score")
    void saveMessage_addsToSortedSetWithCorrectScoreAndExpiry() {
        // Given
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(redisTemplate.expire(any(), any(Duration.class))).thenReturn(true);

        Instant now = Instant.now();
        ChatMessageDTO message = new ChatMessageDTO(
                UUID.randomUUID(), "user-1", "Alice", "Hello!", now, MessageType.CHAT
        );

        // When
        redisChatStore.saveMessage(CHAT_ID, message);

        // Then — ZADD was called with correct key, score, and serialized value
        verify(zSetOperations).add(keyCaptor.capture(), valueCaptor.capture(), scoreCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo(EXPECTED_KEY);
        assertThat(scoreCaptor.getValue()).isEqualTo((double) now.toEpochMilli());

        // Verify the JSON value is valid and contains the message content
        String json = valueCaptor.getValue();
        assertThat(json).contains("Hello!");
        assertThat(json).contains("Alice");

        // Then — backstop TTL was set
        verify(redisTemplate).expire(eq(EXPECTED_KEY), eq(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("getLast24Hours returns deserialized messages from sorted set")
    void getLast24Hours_returnsDeserializedMessages() throws JsonProcessingException {
        // Given
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        ChatMessageDTO msg1 = new ChatMessageDTO(
                UUID.randomUUID(), "user-1", "Alice", "First", Instant.now().minus(2, ChronoUnit.HOURS), MessageType.CHAT
        );
        ChatMessageDTO msg2 = new ChatMessageDTO(
                UUID.randomUUID(), "user-2", "Bob", "Second", Instant.now().minus(1, ChronoUnit.HOURS), MessageType.CHAT
        );

        Set<String> rawSet = new LinkedHashSet<>();
        rawSet.add(objectMapper.writeValueAsString(msg1));
        rawSet.add(objectMapper.writeValueAsString(msg2));

        when(zSetOperations.rangeByScore(eq(EXPECTED_KEY), anyDouble(), eq(Double.MAX_VALUE)))
                .thenReturn(rawSet);

        // When
        List<ChatMessageDTO> result = redisChatStore.getLast24Hours(CHAT_ID);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).content()).isEqualTo("First");
        assertThat(result.get(1).content()).isEqualTo("Second");
    }

    @Test
    @DisplayName("getLast24Hours returns empty list when no messages exist")
    void getLast24Hours_noMessages_returnsEmptyList() {
        // Given
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.rangeByScore(anyString(), anyDouble(), anyDouble()))
                .thenReturn(null);

        // When
        List<ChatMessageDTO> result = redisChatStore.getLast24Hours(CHAT_ID);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getLast24Hours returns empty list for empty set")
    void getLast24Hours_emptySet_returnsEmptyList() {
        // Given
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.rangeByScore(anyString(), anyDouble(), anyDouble()))
                .thenReturn(Set.of());

        // When
        List<ChatMessageDTO> result = redisChatStore.getLast24Hours(CHAT_ID);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("pruneStaleMessages removes entries older than 24 hours")
    void pruneStaleMessages_removesOldEntries() {
        // Given
        when(redisTemplate.keys(anyString()))
                .thenReturn(Set.of(EXPECTED_KEY));
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.removeRangeByScore(anyString(), anyDouble(), anyDouble()))
                .thenReturn(3L);

        // When
        redisChatStore.pruneStaleMessages();

        // Then
        verify(zSetOperations).removeRangeByScore(
                eq(EXPECTED_KEY),
                eq(0.0),
                anyDouble()
        );
    }

    @Test
    @DisplayName("pruneStaleMessages handles no active rooms gracefully")
    void pruneStaleMessages_noActiveRooms_doesNothing() {
        // Given
        when(redisTemplate.keys(anyString())).thenReturn(Set.of());

        // When
        redisChatStore.pruneStaleMessages();

        // Then — no ZREMRANGEBYSCORE calls
        verify(redisTemplate, never()).opsForZSet();
    }
}
