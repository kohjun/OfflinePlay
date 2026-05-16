package com.contenido.domain.review.service

import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.review.dto.CreateReviewRequest
import com.contenido.domain.review.dto.EventReviewSummary
import com.contenido.domain.review.dto.ReviewResponse
import com.contenido.domain.review.dto.UpdateReviewRequest
import com.contenido.domain.review.entity.Review
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.domain.ticket.entity.TicketStatus
import com.contenido.domain.ticket.repository.TicketRepository
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.EventNotFoundException
import com.contenido.global.exception.ReviewAlreadyExistsException
import com.contenido.global.exception.ReviewNotAllowedException
import com.contenido.global.exception.ReviewNotFoundException
import com.contenido.global.exception.UnauthorizedException
import com.contenido.global.exception.UserNotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 이벤트 후기 (별점 + 본문) 도메인 진입점.
 *
 * 작성 권한:
 *  - 해당 이벤트에 status=USED 인 티켓을 보유한 사용자만 (체크인 완료자).
 *  - 한 (event, author) 페어는 review 1건 — UNIQUE 제약 + 사전 검사 두 겹.
 *  - 본인 수정 / 본인+ADMIN 삭제. CREATOR 가 자기 이벤트 후기를 삭제하는 권한은 부여하지 않는다 —
 *    악의적 별점 조작 차단.
 */
@Service
@Transactional(readOnly = true)
class ReviewService(
    private val reviewRepository: ReviewRepository,
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val ticketRepository: TicketRepository,
) {

    @Transactional
    fun createReview(userId: Long, eventId: Long, request: CreateReviewRequest): ReviewResponse {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        val event = eventRepository.findById(eventId).orElseThrow { EventNotFoundException() }

        // 1. USED 티켓 보유 검증 — 체크인 완료자만.
        val hasUsedTicket = ticketRepository.existsByEventAndBuyerAndStatusIn(
            event = event, buyer = user, statuses = listOf(TicketStatus.USED),
        )
        if (!hasUsedTicket) throw ReviewNotAllowedException()

        // 2. 중복 후기 차단 (race condition 대비 UNIQUE 제약도 있음).
        if (reviewRepository.findByEventAndAuthor(event, user).isPresent) {
            throw ReviewAlreadyExistsException()
        }

        val saved = reviewRepository.save(
            Review(event = event, author = user, rating = request.rating, content = request.content),
        )
        return saved.toResponse()
    }

    @Transactional
    fun updateReview(userId: Long, reviewId: Long, request: UpdateReviewRequest): ReviewResponse {
        val review = reviewRepository.findById(reviewId).orElseThrow { ReviewNotFoundException() }
        if (review.author.id != userId) throw UnauthorizedException()
        review.update(rating = request.rating, content = request.content)
        return review.toResponse()
    }

    @Transactional
    fun deleteReview(userId: Long, reviewId: Long) {
        val review = reviewRepository.findById(reviewId).orElseThrow { ReviewNotFoundException() }
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        // 본인 또는 ADMIN 만 삭제 가능 — CREATOR 가 자기 이벤트 별점을 임의로 못 지우게 막는다.
        if (review.author.id != userId && user.role != UserRole.ADMIN) {
            throw UnauthorizedException()
        }
        reviewRepository.delete(review)
    }

    fun listEventReviews(eventId: Long, page: Int, size: Int): Page<ReviewResponse> {
        val event = eventRepository.findById(eventId).orElseThrow { EventNotFoundException() }
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 50))
        // PR51 — 자동 숨김된 후기는 사용자 조회에서 제외.
        return reviewRepository.findByEventAndHiddenAtIsNullOrderByCreatedAtDesc(event, pageable)
            .map { it.toResponse() }
    }

    /**
     * 본인이 작성한 후기 단건 — UI 에서 "이미 작성했는지 / 수정으로 진입할지" 판단용.
     * 없으면 null.
     */
    fun getMyReview(userId: Long, eventId: Long): ReviewResponse? {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        val event = eventRepository.findById(eventId).orElseThrow { EventNotFoundException() }
        return reviewRepository.findByEventAndAuthor(event, user)
            .orElse(null)?.toResponse()
    }

    /**
     * 이벤트 단건 hero 등에 노출할 후기 집계. 후기 0 건이면 averageRating=null + count=0.
     * EventService 가 응답을 만들 때 본 헬퍼를 호출 — 두 번의 가벼운 집계 쿼리.
     */
    fun summaryForEvent(eventId: Long): EventReviewSummary {
        val event = eventRepository.findById(eventId).orElse(null)
            ?: return EventReviewSummary(averageRating = null, reviewCount = 0L)
        val avg = reviewRepository.averageRatingByEventId(eventId)
        val count = reviewRepository.countByEvent(event)
        return EventReviewSummary(averageRating = avg, reviewCount = count)
    }

    private fun Review.toResponse() = ReviewResponse(
        id = id,
        eventId = event.id,
        authorId = author.id,
        authorNickname = author.nickname,
        rating = rating,
        content = content,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
