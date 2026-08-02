package com.subh.entity;

import lombok.*;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRoomMemberId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private UUID roomId;

    private UUID userId;
}
