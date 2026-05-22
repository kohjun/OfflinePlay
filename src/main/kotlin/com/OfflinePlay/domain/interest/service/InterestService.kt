package com.contenido.domain.interest.service

import com.contenido.domain.interest.dto.InterestResponse
import com.contenido.domain.interest.dto.UpdateMyInterestsRequest
import com.contenido.domain.interest.entity.UserInterest
import com.contenido.domain.interest.repository.InterestRepository
import com.contenido.domain.interest.repository.UserInterestRepository
import com.contenido.global.exception.UserNotFoundException
import com.contenido.domain.user.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * PR147 — 관심사 카탈로그 + 내 관심사 set 갱신.
 *
 *  - 카탈로그 조회는 비로그인 허용 (SecurityConfig 의 permitAll 분기).
 *  - 내 관심사 PATCH 는 set semantics — 들어온 ID 들이 최종 상태. delta 만 INSERT/DELETE.
 *  - 잘못된 interestId 가 들어오면 silently 무시 (FK 가 차단해도 race condition 등 안전).
 */
@Service
@Transactional(readOnly = true)
class InterestService(
    private val userRepository: UserRepository,
    private val interestRepository: InterestRepository,
    private val userInterestRepository: UserInterestRepository,
) {

    fun listAll(): List<InterestResponse> =
        interestRepository.findAllByOrderByCategoryAscDisplayOrderAsc()
            .map(InterestResponse::from)

    fun listMine(userId: Long): List<InterestResponse> {
        userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        val mine = userInterestRepository.findByUserId(userId).map { it.interestId }
        if (mine.isEmpty()) return emptyList()
        return interestRepository.findByIdIn(mine)
            .sortedWith(compareBy({ it.category }, { it.displayOrder }))
            .map(InterestResponse::from)
    }

    @Transactional
    fun updateMine(userId: Long, request: UpdateMyInterestsRequest): List<InterestResponse> {
        userRepository.findById(userId).orElseThrow { UserNotFoundException() }

        // 들어온 id 중 실재하는 카탈로그만 통과 — 잘못된 id 는 무시.
        val targetIds = if (request.interestIds.isEmpty()) emptySet()
        else interestRepository.findByIdIn(request.interestIds.distinct())
            .map { it.id }
            .toSet()

        val currentIds = userInterestRepository.findByUserId(userId).map { it.interestId }.toSet()
        val toAdd = (targetIds - currentIds).toList()
        val toRemove = (currentIds - targetIds).toList()

        if (toRemove.isNotEmpty()) {
            userInterestRepository.deleteByUserIdAndInterestIdIn(userId, toRemove)
        }
        if (toAdd.isNotEmpty()) {
            userInterestRepository.saveAll(toAdd.map { UserInterest(userId = userId, interestId = it) })
        }

        return listMine(userId)
    }
}
