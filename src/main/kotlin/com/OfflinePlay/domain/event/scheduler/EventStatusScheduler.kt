package com.contenido.domain.event.scheduler

import com.contenido.domain.event.repository.EventRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Component
class EventStatusScheduler(
    private val eventRepository: EventRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60000) // 1분(60,000ms)마다 실행
    @Transactional
    fun updateEventStatuses() {
        val now = LocalDateTime.now()

        val ongoingCount = eventRepository.updateStatusToOngoing(now)
        val closedCount = eventRepository.updateStatusToClosed(now)

        if (ongoingCount > 0 || closedCount > 0) {
            log.info("이벤트 상태 업데이트 완료: 진행중 전환 {}건, 마감 전환 {}건", ongoingCount, closedCount)
        }
    }
}