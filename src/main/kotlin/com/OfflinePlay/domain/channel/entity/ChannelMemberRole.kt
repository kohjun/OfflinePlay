package com.contenido.domain.channel.entity

enum class ChannelMemberRole {
    /** 채널 소유자(기획자 본인). 1채널당 정확히 1명. */
    OWNER,

    /** 채널 운영을 함께하는 팀원. 이벤트 기획·콘텐츠 작성을 도울 수 있다. */
    STAFF,
}
