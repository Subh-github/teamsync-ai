package com.subh.controller;

import com.subh.dto.ApiResponse;
import com.subh.dto.ChatMessageDTO;
import com.subh.dto.ChatRoomDTO;
import com.subh.service.ChatRoomService;
import com.subh.service.RedisChatStore;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for chat room management and message history retrieval.
 *
 * <p>Provides HTTP endpoints for operations that don't require real-time
 * WebSocket connectivity:</p>
 * <ul>
 *   <li>Chat room CRUD (create, get, list by user)</li>
 *   <li>Room membership management (add members)</li>
 *   <li>Message history retrieval (last 24hrs from Redis)</li>
 * </ul>
 *
 * <p>All responses are wrapped in {@link ApiResponse} for consistency
 * across the microservice ecosystem.</p>
 *
 * @see com.subh.controller.ChatWebSocketController for real-time messaging
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatRestController {

    private final RedisChatStore redisChatStore;
    private final ChatRoomService chatRoomService;

    // ── Message History ─────────────────────────────────────────────────

    /**
     * Retrieves the last 24 hours of messages for a chat room.
     *
     * <p>Messages are fetched from Redis sorted sets using
     * {@code ZRANGEBYSCORE}. Returns an empty list if the room
     * has no recent messages or doesn't exist in Redis.</p>
     *
     * @param chatId the chat room identifier
     * @return 200 with ordered list of messages (oldest first)
     */
    @GetMapping("/{chatId}/history")
    public ResponseEntity<ApiResponse<List<ChatMessageDTO>>> getHistory(
            @PathVariable String chatId) {
        log.debug("Fetching message history for room {}", chatId);
        List<ChatMessageDTO> messages = redisChatStore.getLast24Hours(chatId);
        return ResponseEntity.ok(ApiResponse.success(messages));
    }

    /**
     * Retrieves the N most recent messages for a chat room.
     *
     * @param chatId the chat room identifier
     * @param count  maximum number of messages to retrieve (default 50)
     * @return 200 with ordered list of recent messages (oldest first)
     */
    @GetMapping("/{chatId}/history/recent")
    public ResponseEntity<ApiResponse<List<ChatMessageDTO>>> getRecentMessages(
            @PathVariable String chatId,
            @RequestParam(defaultValue = "50") int count) {
        log.debug("Fetching {} recent messages for room {}", count, chatId);
        List<ChatMessageDTO> messages = redisChatStore.getRecentMessages(chatId, count);
        return ResponseEntity.ok(ApiResponse.success(messages));
    }

    // ── Room CRUD ───────────────────────────────────────────────────────

    /**
     * Creates a new chat room.
     *
     * <p>The creator is automatically added as the first member.
     * The room ID and creation timestamp are generated server-side.</p>
     *
     * @param dto the room creation request (name and createdBy required)
     * @return 201 with the created room details
     */
    @PostMapping("/rooms")
    public ResponseEntity<ApiResponse<ChatRoomDTO>> createRoom(
            @Valid @RequestBody ChatRoomDTO dto) {
        log.info("Creating chat room '{}' by user {}", dto.name(), dto.createdBy());
        ChatRoomDTO created = chatRoomService.createRoom(dto);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Room created successfully", created));
    }

    /**
     * Retrieves a chat room by its ID, including member list.
     *
     * @param roomId the UUID of the room
     * @return 200 with room details and member UUIDs
     */
    @GetMapping("/rooms/{roomId}")
    public ResponseEntity<ApiResponse<ChatRoomDTO>> getRoom(
            @PathVariable UUID roomId) {
        log.debug("Fetching room details for {}", roomId);
        ChatRoomDTO room = chatRoomService.getRoomById(roomId);
        return ResponseEntity.ok(ApiResponse.success(room));
    }

    /**
     * Adds a user to a chat room.
     *
     * @param roomId the UUID of the room
     * @param userId the UUID of the user to add
     * @return 200 with success message
     */
    @PostMapping("/rooms/{roomId}/members")
    public ResponseEntity<ApiResponse<Void>> addMember(
            @PathVariable UUID roomId,
            @RequestParam UUID userId) {
        log.info("Adding user {} to room {}", userId, roomId);
        chatRoomService.addMember(roomId, userId);
        return ResponseEntity.ok(ApiResponse.success("Member added successfully", null));
    }

    /**
     * Lists all chat rooms that a user is a member of.
     *
     * @param userId the UUID of the user
     * @return 200 with list of rooms and their member UUIDs
     */
    @GetMapping("/rooms/user/{userId}")
    public ResponseEntity<ApiResponse<List<ChatRoomDTO>>> getRoomsByUser(
            @PathVariable UUID userId) {
        log.debug("Fetching rooms for user {}", userId);
        List<ChatRoomDTO> rooms = chatRoomService.getRoomsByUser(userId);
        return ResponseEntity.ok(ApiResponse.success(rooms));
    }
}
