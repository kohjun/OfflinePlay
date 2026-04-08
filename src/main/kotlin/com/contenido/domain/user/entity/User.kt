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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val role: UserRole = UserRole.PARTICIPANT,

    @Column(name = "phone_number", nullable = false, length = 20)
    var phoneNumber: String,

) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: LocalDateTime
        private set

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: LocalDateTime
        private set

    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null
        private set

    val isDeleted: Boolean
        get() = deletedAt != null

    fun softDelete() {
        deletedAt = LocalDateTime.now()
    }
}
