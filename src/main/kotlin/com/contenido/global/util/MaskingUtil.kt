package com.contenido.global.util

object MaskingUtil {
    /**
     * 전화번호 마스킹: 01012345678 → 010-****-5678
     * 저장 형식(숫자만) 및 포맷 형식(하이픈 포함) 모두 처리
     */
    fun maskPhoneNumber(phoneNumber: String): String {
        val digits = phoneNumber.replace("-", "")
        return when (digits.length) {
            11 -> "${digits.substring(0, 3)}-****-${digits.substring(7)}"
            10 -> "${digits.substring(0, 3)}-***-${digits.substring(6)}"
            else -> "***-****-****"
        }
    }
}
