package com.contenido.domain.user.entity

import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

enum class UserRole {
    PARTICIPANT, CREATOR, ADMIN
}

@Entity
@Table(name = "users")
@EntityListeners(AuditingEntityListener::class)
class User(
    @Column(nullable = false, unique = true)
    val email: String,

    @Column(nullable = false)
    var password: String,

    @Column(nullable = false, length = 50)
    var nickname: String,

    @Column(name = "phone_number", nullable = false, length = 20)
    var phoneNumber: String
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: UserRole = UserRole.PARTICIPANT
        protected set // 이제 몸체에 있으므로 정상 작동합니다.

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: LocalDateTime
        protected set

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: LocalDateTime
        protected set

    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null
        protected set

    val isDeleted: Boolean
        get() = deletedAt != null

    fun softDelete() {
        deletedAt = LocalDateTime.now()
    }

    fun updateRole(newRole: UserRole) {
        this.role = newRole
    }
}