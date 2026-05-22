package com.contenido.domain.interest.dto

import com.contenido.domain.interest.entity.Interest
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

data class InterestResponse(
    val id: Long,
    val slug: String,
    val label: String,
    val category: String,
    val displayOrder: Int,
) {
    companion object {
        fun from(i: Interest) = InterestResponse(
            id = i.id,
            slug = i.slug,
            label = i.label,
            category = i.category,
            displayOrder = i.displayOrder,
        )
    }
}

/**
 * PR147 — 내 관심사 upsert. set semantics — 들어온 ID 들이 최종 상태.
 * 빈 리스트는 모든 관심사 해제. null 은 차단 — 빈 의도와 미지정 의도를 구별.
 */
data class UpdateMyInterestsRequest(
    @field:NotNull
    @field:Size(max = 30)
    val interestIds: List<Long>,
)
