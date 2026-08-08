package com.subh.service;

import com.subh.dto.ChatRoomDTO;
import com.subh.entity.ChatRoom;
import com.subh.entity.ChatRoomMember;
import com.subh.exception.ChatServiceException;
import com.subh.exception.ResourceNotFoundException;
import com.subh.repository.ChatRoomMemberRepository;
import com.subh.repository.ChatRoomRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ChatRoomService}.
 *
 * <p>Validates room creation (including auto-membership), retrieval,
 * membership management, and error handling.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatRoomService Unit Tests")
class ChatRoomServiceTest {

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private ChatRoomMemberRepository chatRoomMemberRepository;

    @InjectMocks
    private ChatRoomService chatRoomService;

    @Captor
    private ArgumentCaptor<ChatRoom> roomCaptor;

    @Captor
    private ArgumentCaptor<ChatRoomMember> memberCaptor;

    private static final UUID ROOM_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String ROOM_NAME = "Project Alpha";

    @Nested
    @DisplayName("createRoom")
    class CreateRoom {

        @Test
        @DisplayName("Creates room and adds creator as first member")
        void createRoom_savesRoomAndAddsCreatorAsMember() {
            // Given
            ChatRoomDTO request = new ChatRoomDTO(null, ROOM_NAME, USER_ID, null, null);
            ChatRoom savedRoom = ChatRoom.builder()
                    .id(ROOM_ID)
                    .name(ROOM_NAME)
                    .createdBy(USER_ID)
                    .createdAt(LocalDateTime.now())
                    .build();

            when(chatRoomRepository.save(any(ChatRoom.class))).thenReturn(savedRoom);
            when(chatRoomMemberRepository.save(any(ChatRoomMember.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // When
            ChatRoomDTO result = chatRoomService.createRoom(request);

            // Then — room was saved
            verify(chatRoomRepository).save(roomCaptor.capture());
            assertThat(roomCaptor.getValue().getName()).isEqualTo(ROOM_NAME);
            assertThat(roomCaptor.getValue().getCreatedBy()).isEqualTo(USER_ID);

            // Then — creator was added as member
            verify(chatRoomMemberRepository).save(memberCaptor.capture());
            assertThat(memberCaptor.getValue().getRoomId()).isEqualTo(ROOM_ID);
            assertThat(memberCaptor.getValue().getUserId()).isEqualTo(USER_ID);

            // Then — response is populated
            assertThat(result.id()).isEqualTo(ROOM_ID);
            assertThat(result.name()).isEqualTo(ROOM_NAME);
            assertThat(result.members()).containsExactly(USER_ID);
        }
    }

    @Nested
    @DisplayName("getRoomById")
    class GetRoomById {

        @Test
        @DisplayName("Returns room with member list when found")
        void getRoomById_found_returnsRoomWithMembers() {
            // Given
            ChatRoom room = ChatRoom.builder()
                    .id(ROOM_ID).name(ROOM_NAME).createdBy(USER_ID)
                    .createdAt(LocalDateTime.now()).build();
            UUID member2 = UUID.randomUUID();

            when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
            when(chatRoomMemberRepository.findByRoomId(ROOM_ID)).thenReturn(List.of(
                    ChatRoomMember.builder().roomId(ROOM_ID).userId(USER_ID).build(),
                    ChatRoomMember.builder().roomId(ROOM_ID).userId(member2).build()
            ));

            // When
            ChatRoomDTO result = chatRoomService.getRoomById(ROOM_ID);

            // Then
            assertThat(result.id()).isEqualTo(ROOM_ID);
            assertThat(result.members()).containsExactly(USER_ID, member2);
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when room not found")
        void getRoomById_notFound_throwsException() {
            // Given
            when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> chatRoomService.getRoomById(ROOM_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(ROOM_ID.toString());
        }
    }

    @Nested
    @DisplayName("addMember")
    class AddMember {

        @Test
        @DisplayName("Adds member when room exists and user not already a member")
        void addMember_validRequest_addsMember() {
            // Given
            UUID newUserId = UUID.randomUUID();
            when(chatRoomRepository.existsById(ROOM_ID)).thenReturn(true);
            when(chatRoomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, newUserId)).thenReturn(false);
            when(chatRoomMemberRepository.save(any(ChatRoomMember.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // When
            chatRoomService.addMember(ROOM_ID, newUserId);

            // Then
            verify(chatRoomMemberRepository).save(memberCaptor.capture());
            assertThat(memberCaptor.getValue().getRoomId()).isEqualTo(ROOM_ID);
            assertThat(memberCaptor.getValue().getUserId()).isEqualTo(newUserId);
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when room doesn't exist")
        void addMember_roomNotFound_throwsException() {
            // Given
            when(chatRoomRepository.existsById(ROOM_ID)).thenReturn(false);

            // When / Then
            assertThatThrownBy(() -> chatRoomService.addMember(ROOM_ID, USER_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Throws ChatServiceException when user already a member")
        void addMember_alreadyMember_throwsException() {
            // Given
            when(chatRoomRepository.existsById(ROOM_ID)).thenReturn(true);
            when(chatRoomMemberRepository.existsByRoomIdAndUserId(ROOM_ID, USER_ID)).thenReturn(true);

            // When / Then
            assertThatThrownBy(() -> chatRoomService.addMember(ROOM_ID, USER_ID))
                    .isInstanceOf(ChatServiceException.class)
                    .hasMessageContaining("already a member");
        }
    }

    @Nested
    @DisplayName("getRoomsByUser")
    class GetRoomsByUser {

        @Test
        @DisplayName("Returns all rooms the user is a member of")
        void getRoomsByUser_returnsMemberRooms() {
            // Given
            UUID room2Id = UUID.randomUUID();
            ChatRoom room1 = ChatRoom.builder()
                    .id(ROOM_ID).name("Room 1").createdBy(USER_ID)
                    .createdAt(LocalDateTime.now()).build();
            ChatRoom room2 = ChatRoom.builder()
                    .id(room2Id).name("Room 2").createdBy(UUID.randomUUID())
                    .createdAt(LocalDateTime.now()).build();

            when(chatRoomMemberRepository.findByUserId(USER_ID)).thenReturn(List.of(
                    ChatRoomMember.builder().roomId(ROOM_ID).userId(USER_ID).build(),
                    ChatRoomMember.builder().roomId(room2Id).userId(USER_ID).build()
            ));
            when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room1));
            when(chatRoomRepository.findById(room2Id)).thenReturn(Optional.of(room2));
            when(chatRoomMemberRepository.findByRoomId(ROOM_ID)).thenReturn(List.of(
                    ChatRoomMember.builder().roomId(ROOM_ID).userId(USER_ID).build()
            ));
            when(chatRoomMemberRepository.findByRoomId(room2Id)).thenReturn(List.of(
                    ChatRoomMember.builder().roomId(room2Id).userId(USER_ID).build()
            ));

            // When
            List<ChatRoomDTO> result = chatRoomService.getRoomsByUser(USER_ID);

            // Then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).name()).isEqualTo("Room 1");
            assertThat(result.get(1).name()).isEqualTo("Room 2");
        }

        @Test
        @DisplayName("Returns empty list when user has no rooms")
        void getRoomsByUser_noRooms_returnsEmptyList() {
            // Given
            when(chatRoomMemberRepository.findByUserId(USER_ID)).thenReturn(List.of());

            // When
            List<ChatRoomDTO> result = chatRoomService.getRoomsByUser(USER_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }
}
