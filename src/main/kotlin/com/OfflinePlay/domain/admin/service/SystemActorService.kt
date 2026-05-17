package com.contenido.domain.admin.service

import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * PR69 — 자동 background job (scheduler 등) 이 audit log / archive 의 actor 로 사용할
 * "system actor" user row 를 책임진다.
 *
 * 정책:
 *  - lookup by [SYSTEM_ACTOR_EMAIL]. V9 migration 이 prod 에 1 row 를 seed 한다.
 *  - test profile / local 환경처럼 V9 가 적용되지 않은 환경에서도 동작하도록, row 가 없으면
 *    한 번에 한해 same constants 로 생성한다. UNIQUE(email) 가 다중 생성을 막아 멱등.
 *  - password 는 bcrypt 가 매칭 못 하는 sentinel 문자열 → 정상 로그인 경로로 인증 불가.
 *  - role 은 [UserRole.PARTICIPANT] — `hasRole('ADMIN')` 등 권한 흐름에서 system actor 가
 *    실제 권한을 갖지 않게 한다.
 *  - 본 service 는 system actor 를 다른 곳에서 "ADMIN 처럼" 활용하지 않도록 [getSystemActor]
 *    호출자도 archive / audit 기록 용도로만 사용해야 한다.
 */
@Service
class SystemActorService(
    private val userRepository: UserRepository,
) {

    companion object {
        const val SYSTEM_ACTOR_EMAIL: String = "system@contenido.local"
        const val SYSTEM_ACTOR_NICKNAME: String = "System"
        const val SYSTEM_ACTOR_PHONE: String = "00000000000"
        /**
         * bcrypt prefix (`$2a$` / `$2b$` / `$2y$`) 와 다른 sentinel — 일반 로그인 흐름에서
         * `BCryptPasswordEncoder.matches` 가 무조건 false 를 반환하게 만든다.
         */
        const val SYSTEM_ACTOR_PASSWORD_PLACEHOLDER: String = "__SYSTEM_ACTOR_NO_LOGIN__"
    }

    @Transactional
    fun getSystemActor(): User {
        userRepository.findByEmail(SYSTEM_ACTOR_EMAIL).orElse(null)?.let { return it }
        // V9 seed 가 적용되지 않은 환경 (test / local 등). 같은 상수로 한 번 생성하면 이후 호출은
        // 위 lookup 으로 처리된다. UNIQUE(email) 가 race 를 막는다.
        val created = User(
            email = SYSTEM_ACTOR_EMAIL,
            password = SYSTEM_ACTOR_PASSWORD_PLACEHOLDER,
            nickname = SYSTEM_ACTOR_NICKNAME,
            phoneNumber = SYSTEM_ACTOR_PHONE,
        )
        // role 은 default PARTICIPANT — 의도적으로 ADMIN 으로 격상하지 않는다.
        return userRepository.save(created)
    }

    fun getSystemActorId(): Long = getSystemActor().id
}
