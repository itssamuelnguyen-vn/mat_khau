package com.samuelcruz.matkhau.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Tiện ích byte dùng chung. Mọi số ngẫu nhiên liên quan bảo mật đều đi qua SecureRandom.
 * (Spec 5.5: không bao giờ dùng Random / Math.random cho khóa, nonce, mật khẩu.)
 */
object Bytes {
    val secureRandom: SecureRandom = SecureRandom()

    fun random(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }

    /** Ghi đè bằng 0. Trên JVM không đảm bảo tuyệt đối (GC có thể đã copy), nhưng giảm thời gian bí mật nằm trong RAM. */
    fun wipe(vararg arrays: ByteArray?) {
        for (a in arrays) a?.fill(0)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }
}
