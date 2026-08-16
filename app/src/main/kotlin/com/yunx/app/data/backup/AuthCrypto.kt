package com.yunx.app.data.backup

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * 网盘认证备份的 AES 加密/解密：
 * - 密钥：PBKDF2WithHmacSHA256（16 字节随机盐 + 10000 次迭代）从用户密码派生；
 * - 加密：AES/GCM/NoPadding（128 位 tag，认证加密，密文被篡改会解密失败）；
 * - 格式：Base64(魔数 "YUNX_AUTH_V1" + salt(16) + iv(12) + ciphertext)。
 * 密码错误 / 文件被篡改 → 解密抛异常（AEADBadTagException），上层提示「密码错误，解密失败」。
 */
object AuthCrypto {

    private const val MAGIC = "YUNX_AUTH_V1"
    private const val ITERATIONS = 10000
    private const val KEY_LENGTH = 256
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val GCM_TAG_BITS = 128

    /** 加密明文 JSON，返回 Base64 密文（含魔数头部） */
    fun encrypt(plain: String, password: String): String {
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val magic = MAGIC.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(magic.size + salt.size + iv.size + ciphertext.size)
        System.arraycopy(magic, 0, payload, 0, magic.size)
        System.arraycopy(salt, 0, payload, magic.size, salt.size)
        System.arraycopy(iv, 0, payload, magic.size + salt.size, iv.size)
        System.arraycopy(ciphertext, 0, payload, magic.size + salt.size + iv.size, ciphertext.size)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    /** 解密 Base64 密文；密码错误/文件损坏抛异常 */
    fun decrypt(data: String, password: String): String {
        val payload = Base64.decode(data.trim(), Base64.NO_WRAP)
        val magic = MAGIC.toByteArray(Charsets.UTF_8)
        if (payload.size < magic.size + SALT_SIZE + IV_SIZE ||
            !payload.copyOfRange(0, magic.size).contentEquals(magic)
        ) {
            throw IllegalArgumentException("不是有效的加密备份文件")
        }
        val salt = payload.copyOfRange(magic.size, magic.size + SALT_SIZE)
        val iv = payload.copyOfRange(magic.size + SALT_SIZE, magic.size + SALT_SIZE + IV_SIZE)
        val ciphertext = payload.copyOfRange(magic.size + SALT_SIZE + IV_SIZE, payload.size)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /** 判断内容是否为加密备份（检查魔数头部） */
    fun isEncrypted(data: String): Boolean = runCatching {
        val payload = Base64.decode(data.trim(), Base64.NO_WRAP)
        val magic = MAGIC.toByteArray(Charsets.UTF_8)
        payload.size >= magic.size && payload.copyOfRange(0, magic.size).contentEquals(magic)
    }.getOrDefault(false)

    private fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        } finally {
            spec.clearPassword()
        }
    }
}