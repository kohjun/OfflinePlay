package com.contenido.domain.event.scheduler

import com.contenido.domain.event.repository.EventRepository
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

// 테스트 컨텍스트에선 @Scheduled 가 ThreadPoolTaskScheduler 의 non-daemon thread 를 잡고
// 컨텍스트 종료 시점에 fixedDelay 가 깨어나면서 종료가 지연된다. 다중 @SpringBootTest 클래스가
// 누적되면 JVM 종료가 분 단위로 매달리는 원인. 운영/local 에선 정상 작동.
@Profile("!test")
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