package com.subh.repository;

import com.subh.entity.ChatRoomMember;
import com.subh.entity.ChatRoomMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, ChatRoomMemberId> {

    List<ChatRoomMember> findByRoomId(UUID roomId);

    List<ChatRoomMember> findByUserId(UUID userId);

    boolean existsByRoomIdAndUserId(UUID roomId, UUID userId);
}
