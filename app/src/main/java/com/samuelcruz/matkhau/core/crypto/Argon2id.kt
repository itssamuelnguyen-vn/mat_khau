package com.samuelcruz.matkhau.core.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Tham số Argon2id. Được lưu trong header của vault để sau này có thể tăng độ mạnh
 * mà không phá vỡ vault cũ.
 */
data class KdfParams(
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    /** Giới hạn hợp lý khi đọc header từ file: chặn tham số quá yếu hoặc quá lớn (gây hết RAM). */
    fun isSane(): Boolean =
        memoryKiB in MIN_MEMORY_KIB..MAX_MEMORY_KIB &&
            iterations in 1..MAX_ITERATIONS &&
            parallelism in 1..MAX_PARALLELISM

    companion object {
        const val MIN_MEMORY_KIB = 8 * 1024        // 8 MiB
        const val MAX_MEMORY_KIB = 512 * 1024      // 512 MiB
        const val MAX_ITERATIONS = 20
        const val MAX_PARALLELISM = 8

        /** 64 MiB, 3 vòng, 1 luồng (gần với khuyến nghị thứ 2 của RFC 9106). */
        val DEFAULT = KdfParams(memoryKiB = 64 * 1024, iterations = 3, parallelism = 1)
    }
}

/**
 * Argon2id dùng cài đặt của Bouncy Castle (thư viện mật mã lâu đời, được kiểm chứng rộng rãi).
 * Không tự cài thuật toán (spec 5.4).
 */
object Argon2id {
    const val KEY_LENGTH = 32

    fun deriveKey(secret: ByteArray, salt: ByteArray, params: KdfParams): ByteArray {
        require(params.isSane()) { "Tham số Argon2 không hợp lệ" }
        require(salt.size >= 16) { "Salt quá ngắn" }
        return raw(secret, salt, params.memoryKiB, params.iterations, params.parallelism, KEY_LENGTH)
    }

    /** Hàm cấp thấp, chỉ dùng trực tiếp cho test vector RFC 9106. */
    fun raw(
        password: ByteArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int,
        outLength: Int,
        secret: ByteArray? = null,
        associatedData: ByteArray? = null,
    ): ByteArray {
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKiB)
            .withIterations(iterations)
            .withParallelism(parallelism)
        if (secret != null) builder.withSecret(secret)
        if (associatedData != null) builder.withAdditional(associatedData)

        val generator = Argon2BytesGenerator()
        generator.init(builder.build())
        val out = ByteArray(outLength)
        generator.generateBytes(password, out)
        return out
    }
}
