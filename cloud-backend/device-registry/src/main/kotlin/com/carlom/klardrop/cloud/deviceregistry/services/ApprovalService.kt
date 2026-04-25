package com.carlom.klardrop.cloud.deviceregistry.services

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class EnrollmentApprovalChallenge(
    val challengeId: String,
    val userId: String,
    val requesterDeviceId: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val approved: Boolean = false
)

class ApprovalService {
    private val challenges = ConcurrentHashMap<String, EnrollmentApprovalChallenge>()

    fun createChallenge(userId: String, requesterDeviceId: String, ttlSeconds: Long = 300): EnrollmentApprovalChallenge {
        val now = Instant.now()
        val challenge = EnrollmentApprovalChallenge(
            challengeId = UUID.randomUUID().toString(),
            userId = userId,
            requesterDeviceId = requesterDeviceId,
            createdAt = now,
            expiresAt = now.plusSeconds(ttlSeconds)
        )
        challenges[challenge.challengeId] = challenge
        return challenge
    }

    fun approveChallenge(userId: String, challengeId: String): Boolean {
        val existing = challenges[challengeId] ?: return false
        if (existing.userId != userId || existing.expiresAt.isBefore(Instant.now())) {
            return false
        }
        challenges[challengeId] = existing.copy(approved = true)
        return true
    }

    fun consumeApprovedChallenge(userId: String, challengeId: String): Boolean {
        val existing = challenges.remove(challengeId) ?: return false
        return existing.userId == userId && existing.approved && existing.expiresAt.isAfter(Instant.now())
    }
}
