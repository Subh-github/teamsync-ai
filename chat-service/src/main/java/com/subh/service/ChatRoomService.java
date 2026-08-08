package com.subh.service;

import com.subh.dto.ChatRoomDTO;
import com.subh.entity.ChatRoom;
import com.subh.entity.ChatRoomMember;
import com.subh.exception.ChatServiceException;
import com.subh.exception.ResourceNotFoundException;
import com.subh.repository.ChatRoomMemberRepository;
import com.subh.repository.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Service layer for chat room CRUD operations.
 *
 * <p>Manages room lifecycle (create, retrieve, list) and membership
 * (add members, list members). All operations are transactional where
 * data consistency requires it.</p>
 *
 * <h3>Design Decisions</h3>
 * <ul>
 *   <li>Room creator is automatically added as a member on creation</li>
 *   <li>Duplicate member additions are rejected with a domain exception</li>
 *   <li>Room retrieval includes the list of member UUIDs for convenience</li>
 * </ul>
 *
 * @see ChatRoom
 * @see ChatRoomMember
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;

    /**
     * Creates a new chat room and adds the creator as the first member.
     *
     * <p>This is transactional to ensure both the room and the
     * creator's membership are persisted atomically.</p>
     *
     * @param dto the room creation request
     * @return the created room with populated ID, timestamp, and members
     */
    @Transactional
    public ChatRoomDTO createRoom(ChatRoomDTO dto) {
        ChatRoom room = ChatRoom.builder()
                .name(dto.name())
                .createdBy(dto.createdBy())
                .build();

        ChatRoom savedRoom = chatRoomRepository.saveAndFlush(room);
        log.info("Created chat room '{}' (id={}) by user {}", savedRoom.getName(),
                savedRoom.getId(), savedRoom.getCreatedBy());

        // Auto-add creator as the first member
        ChatRoomMember creatorMember = ChatRoomMember.builder()
                .roomId(savedRoom.getId())
                .userId(savedRoom.getCreatedBy())
                .build();
        chatRoomMemberRepository.save(creatorMember);

        return ChatRoomDTO.fromEntity(
                savedRoom.getId(),
                savedRoom.getName(),
                savedRoom.getCreatedBy(),
                savedRoom.getCreatedAt(),
                List.of(savedRoom.getCreatedBy())
        );
    }

    /**
     * Retrieves a chat room by its ID, including member list.
     *
     * @param roomId the UUID of the room to retrieve
     * @return the room details with member UUIDs
     * @throws ResourceNotFoundException if the room doesn't exist
     */
    @Transactional(readOnly = true)
    public ChatRoomDTO getRoomById(UUID roomId) {
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat room not found: " + roomId));

        List<UUID> memberIds = chatRoomMemberRepository.findByRoomId(roomId).stream()
                .map(ChatRoomMember::getUserId)
                .toList();

        return ChatRoomDTO.fromEntity(
                room.getId(),
                room.getName(),
                room.getCreatedBy(),
                room.getCreatedAt(),
                memberIds
        );
    }

    /**
     * Adds a user to a chat room.
     *
     * @param roomId the UUID of the room
     * @param userId the UUID of the user to add
     * @throws ResourceNotFoundException if the room doesn't exist
     * @throws ChatServiceException      if the user is already a member
     */
    @Transactional
    public void addMember(UUID roomId, UUID userId) {
        if (!chatRoomRepository.existsById(roomId)) {
            throw new ResourceNotFoundException("Chat room not found: " + roomId);
        }

        if (chatRoomMemberRepository.existsByRoomIdAndUserId(roomId, userId)) {
            throw new ChatServiceException("User " + userId + " is already a member of room " + roomId);
        }

        ChatRoomMember member = ChatRoomMember.builder()
                .roomId(roomId)
                .userId(userId)
                .build();
        chatRoomMemberRepository.save(member);

        log.info("Added user {} to room {}", userId, roomId);
    }

    /**
     * Lists all chat rooms that a user is a member of.
     *
     * <p>Queries the membership join table first, then fetches the
     * full room details for each membership. Each room includes
     * its complete member list.</p>
     *
     * @param userId the UUID of the user
     * @return list of rooms the user belongs to, each with member UUIDs
     */
    @Transactional(readOnly = true)
    public List<ChatRoomDTO> getRoomsByUser(UUID userId) {
        List<UUID> roomIds = chatRoomMemberRepository.findByUserId(userId).stream()
                .map(ChatRoomMember::getRoomId)
                .toList();

        return roomIds.stream()
                .map(this::getRoomById)
                .toList();
    }
}
