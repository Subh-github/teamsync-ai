package com.subh.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subh.dto.ChatMessageDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Redis-backed chat message store using sorted sets.
 *
 * <p>Each chat room has a dedicated sorted set keyed by
 * {@code chat:room:{chatId}:messages}. The score is the message
 * timestamp in epoch milliseconds, enabling efficient range queries
 * for "last 24 hours" retrieval via {@code ZRANGEBYSCORE}.</p>
 *
 * <h3>Why sorted sets instead of per-key TTL?</h3>
 * <p>A plain {@code SET key value EX ttl} approach would require
 * {@code SCAN} to reconstruct a room's recent messages — expensive
 * and non-deterministic under load. Sorted sets give us O(log N)
 * inserts and clean range queries by time.</p>
 *
 * <h3>TTL Strategy</h3>
 * <ul>
 *   <li>Backstop TTL on the entire sorted set key (24hr, refreshed on each write)</li>
 *   <li>Hourly pruning via {@link #pruneStaleMessages()} removes entries
 *       older than 24 hours from active sets</li>
 * </ul>
 *
 * @see com.subh.config.RedisConfig
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisChatStore {

    private static final String KEY_PREFIX = "chat:room:";
    private static final String KEY_SUFFIX = ":messages";
    private static final Duration MESSAGE_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Saves a chat message to the room's sorted set in Redis.
     *
     * <p>The message is serialized to JSON and stored with its timestamp
     * (epoch millis) as the score. A backstop TTL of 24 hours is set on
     * the entire key, refreshed on every write.</p>
     *
     * @param chatId  the chat room identifier
     * @param message the message to persist
     */
    public void saveMessage(String chatId, ChatMessageDTO message) {
        String key = buildKey(chatId);
        double score = message.timestamp().toEpochMilli();
        String json = serialize(message);

        redisTemplate.opsForZSet().add(key, json, score);
        redisTemplate.expire(key, MESSAGE_TTL);

        log.debug("Saved message {} to room {} with score {}", message.messageId(), chatId, score);
    }

    /**
     * Retrieves all messages from the last 24 hours for a given room.
     *
     * <p>Uses {@code ZRANGEBYSCORE} to efficiently query messages within
     * the time window. Returns an empty list if the room has no recent messages.</p>
     *
     * @param chatId the chat room identifier
     * @return ordered list of messages from the last 24 hours (oldest first)
     */
    public List<ChatMessageDTO> getLast24Hours(String chatId) {
        String key = buildKey(chatId);
        long cutoff = Instant.now().minus(24, ChronoUnit.HOURS).toEpochMilli();

        Set<String> raw = redisTemplate.opsForZSet()
                .rangeByScore(key, cutoff, Double.MAX_VALUE);

        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }

        return raw.stream()
                .map(this::deserialize)
                .toList();
    }

    /**
     * Retrieves the N most recent messages for a given room.
     *
     * <p>Uses {@code ZREVRANGE} to get the latest messages, then reverses
     * to chronological order.</p>
     *
     * @param chatId the chat room identifier
     * @param count  the maximum number of messages to retrieve
     * @return ordered list of the most recent messages (oldest first)
     */
    public List<ChatMessageDTO> getRecentMessages(String chatId, int count) {
        String key = buildKey(chatId);

        Set<ZSetOperations.TypedTuple<String>> raw = redisTemplate.opsForZSet()
                .reverseRangeWithScores(key, 0, count - 1L);

        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }

        // Reverse to chronological order (oldest first)
        List<ChatMessageDTO> messages = new java.util.ArrayList<>(raw.stream()
                .map(tuple -> deserialize(tuple.getValue()))
                .toList());
        Collections.reverse(messages);
        return messages;
    }

    /**
     * Hourly pruning job that removes messages older than 24 hours.
     *
     * <p>Iterates all known room keys matching the {@code chat:room:*:messages}
     * pattern and runs {@code ZREMRANGEBYSCORE} to remove stale entries.
     * This is a defense-in-depth measure alongside the backstop TTL.</p>
     */
    @Scheduled(fixedRate = 3_600_000) // every hour
    public void pruneStaleMessages() {
        long cutoff = Instant.now().minus(24, ChronoUnit.HOURS).toEpochMilli();
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*" + KEY_SUFFIX);

        if (keys == null || keys.isEmpty()) {
            log.debug("No active room keys found for pruning");
            return;
        }

        int totalRemoved = 0;
        for (String key : keys) {
            Long removed = redisTemplate.opsForZSet().removeRangeByScore(key, 0, cutoff);
            if (removed != null && removed > 0) {
                totalRemoved += removed.intValue();
                log.debug("Pruned {} stale messages from {}", removed, key);
            }
        }

        if (totalRemoved > 0) {
            log.info("Pruned {} stale messages across {} rooms", totalRemoved, keys.size());
        }
    }

    /**
     * Builds the Redis key for a room's message sorted set.
     *
     * @param chatId the chat room identifier
     * @return the fully qualified Redis key
     */
    private String buildKey(String chatId) {
        return KEY_PREFIX + chatId + KEY_SUFFIX;
    }

    /**
     * Serializes a {@link ChatMessageDTO} to JSON string.
     *
     * @param message the message to serialize
     * @return JSON string representation
     * @throws com.subh.exception.ChatServiceException if serialization fails
     */
    private String serialize(ChatMessageDTO message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize ChatMessageDTO: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to serialize chat message", e);
        }
    }

    /**
     * Deserializes a JSON string back to a {@link ChatMessageDTO}.
     *
     * @param json the JSON string to deserialize
     * @return the deserialized message DTO
     * @throws com.subh.exception.ChatServiceException if deserialization fails
     */
    private ChatMessageDTO deserialize(String json) {
        try {
            return objectMapper.readValue(json, ChatMessageDTO.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize ChatMessageDTO: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to deserialize chat message", e);
        }
    }
}
