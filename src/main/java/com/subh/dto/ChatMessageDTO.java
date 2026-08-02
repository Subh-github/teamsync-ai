package com.subh.dto;

import java.time.Instant;
import java.util.UUID;

public record ChatMessageDTO(
        UUID messageId,
        String senderId,
        String senderName,
        String content,
        Instant timestamp,
        MessageType type
) {

    public enum MessageType {
        CHAT,
        JOIN,
        LEAVE
    }
}
