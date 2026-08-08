package com.subh.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ChatRoomDTO(
        UUID id,

        @NotBlank(message = "Room name is required")
        String name,

        @NotNull(message = "Creator ID is required")
        UUID createdBy,

        LocalDateTime createdAt,

        List<UUID> members
) {

    public static ChatRoomDTO fromEntity(UUID id, String name, UUID createdBy,
                                          LocalDateTime createdAt, List<UUID> members) {
        return new ChatRoomDTO(id, name, createdBy, createdAt, members);
    }
}
