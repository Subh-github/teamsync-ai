package com.subh.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.subh.dto.ChatMessageDTO;
import com.subh.dto.ChatMessageDTO.MessageType;
import com.subh.dto.ChatRoomDTO;
import com.subh.exception.ResourceNotFoundException;
import com.subh.service.ChatRoomService;
import com.subh.service.RedisChatStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web layer tests for {@link ChatRestController}.
 *
 * <p>Uses {@code @WebMvcTest} to test only the controller slice with
 * mocked service dependencies. Validates HTTP status codes, response
 * structure (ApiResponse wrapper), and request validation.</p>
 */
@WebMvcTest(ChatRestController.class)
@DisplayName("ChatRestController Web Layer Tests")
class ChatRestControllerTest {

    @Autowired
    private MockMvc mockMvc;



    @MockitoBean
    private RedisChatStore redisChatStore;

    @MockitoBean
    private ChatRoomService chatRoomService;

    private static final UUID ROOM_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Nested
    @DisplayName("GET /api/chat/{chatId}/history")
    class GetHistory {

        @Test
        @DisplayName("Returns 200 with message list")
        void getHistory_returnsMessages() throws Exception {
            // Given
            List<ChatMessageDTO> messages = List.of(
                    new ChatMessageDTO(UUID.randomUUID(), "user-1", "Alice", "Hello",
                            Instant.now(), MessageType.CHAT),
                    new ChatMessageDTO(UUID.randomUUID(), "user-2", "Bob", "Hi there",
                            Instant.now(), MessageType.CHAT)
            );
            when(redisChatStore.getLast24Hours("room-123")).thenReturn(messages);

            // When / Then
            mockMvc.perform(get("/api/chat/room-123/history"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data", hasSize(2)))
                    .andExpect(jsonPath("$.data[0].content").value("Hello"))
                    .andExpect(jsonPath("$.data[1].content").value("Hi there"));
        }

        @Test
        @DisplayName("Returns 200 with empty list when no messages")
        void getHistory_noMessages_returnsEmptyList() throws Exception {
            // Given
            when(redisChatStore.getLast24Hours("room-empty")).thenReturn(List.of());

            // When / Then
            mockMvc.perform(get("/api/chat/room-empty/history"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }
    }

    @Nested
    @DisplayName("POST /api/chat/rooms")
    class CreateRoom {

        @Test
        @DisplayName("Returns 201 with created room")
        void createRoom_validRequest_returns201() throws Exception {
            // Given
            ChatRoomDTO response = new ChatRoomDTO(
                    ROOM_ID, "Project Alpha", USER_ID,
                    LocalDateTime.now(), List.of(USER_ID)
            );
            when(chatRoomService.createRoom(any())).thenReturn(response);

            String requestBody = """
                    {
                        "name": "Project Alpha",
                        "createdBy": "%s"
                    }
                    """.formatted(USER_ID);

            // When / Then
            mockMvc.perform(post("/api/chat/rooms")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.name").value("Project Alpha"))
                    .andExpect(jsonPath("$.data.id").value(ROOM_ID.toString()));
        }

        @Test
        @DisplayName("Returns 400 when room name is blank")
        void createRoom_blankName_returns400() throws Exception {
            String requestBody = """
                    {
                        "name": "",
                        "createdBy": "%s"
                    }
                    """.formatted(USER_ID);

            // When / Then
            mockMvc.perform(post("/api/chat/rooms")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Returns 400 when createdBy is null")
        void createRoom_nullCreator_returns400() throws Exception {
            String requestBody = """
                    {
                        "name": "Test Room"
                    }
                    """;

            // When / Then
            mockMvc.perform(post("/api/chat/rooms")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/chat/rooms/{roomId}")
    class GetRoom {

        @Test
        @DisplayName("Returns 200 with room details")
        void getRoom_found_returns200() throws Exception {
            // Given
            ChatRoomDTO room = new ChatRoomDTO(
                    ROOM_ID, "Project Alpha", USER_ID,
                    LocalDateTime.now(), List.of(USER_ID)
            );
            when(chatRoomService.getRoomById(ROOM_ID)).thenReturn(room);

            // When / Then
            mockMvc.perform(get("/api/chat/rooms/{roomId}", ROOM_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.name").value("Project Alpha"));
        }

        @Test
        @DisplayName("Returns 404 when room not found")
        void getRoom_notFound_returns404() throws Exception {
            // Given
            when(chatRoomService.getRoomById(ROOM_ID))
                    .thenThrow(new ResourceNotFoundException("Chat room not found: " + ROOM_ID));

            // When / Then
            mockMvc.perform(get("/api/chat/rooms/{roomId}", ROOM_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message", containsString("not found")));
        }
    }

    @Nested
    @DisplayName("POST /api/chat/rooms/{roomId}/members")
    class AddMember {

        @Test
        @DisplayName("Returns 200 on successful member addition")
        void addMember_success_returns200() throws Exception {
            // Given
            UUID newUserId = UUID.randomUUID();
            doNothing().when(chatRoomService).addMember(ROOM_ID, newUserId);

            // When / Then
            mockMvc.perform(post("/api/chat/rooms/{roomId}/members", ROOM_ID)
                            .param("userId", newUserId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Member added successfully"));
        }
    }

    @Nested
    @DisplayName("GET /api/chat/rooms/user/{userId}")
    class GetRoomsByUser {

        @Test
        @DisplayName("Returns 200 with user's rooms")
        void getRoomsByUser_returnsRooms() throws Exception {
            // Given
            List<ChatRoomDTO> rooms = List.of(
                    new ChatRoomDTO(ROOM_ID, "Room 1", USER_ID, LocalDateTime.now(), List.of(USER_ID)),
                    new ChatRoomDTO(UUID.randomUUID(), "Room 2", USER_ID, LocalDateTime.now(), List.of(USER_ID))
            );
            when(chatRoomService.getRoomsByUser(USER_ID)).thenReturn(rooms);

            // When / Then
            mockMvc.perform(get("/api/chat/rooms/user/{userId}", USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data", hasSize(2)));
        }
    }
}
