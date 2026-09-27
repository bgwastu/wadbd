package net.wastu.wadbd.data

import java.security.MessageDigest
import android.util.Base64

data class AdbKey(
    val id: Int,
    val raw: String,
    val base64Key: String,
    val comment: String,
    val fingerprint: String,
    val isAuthorized: Boolean = true
) {
    val user: String
    val host: String

    init {
        val parts = comment.split("@")
        user = parts.getOrNull(0) ?: comment
        host = parts.getOrNull(1) ?: "Unknown"
    }

    companion object {
        fun parse(line: String, id: Int, isAuthorized: Boolean = true): AdbKey? {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return null

            val parts = trimmed.split("\\s+".toRegex(), limit = 2)
            val base64Key = parts[0]
            val comment = parts.getOrElse(1) { "Unknown" }

            val fingerprint = computeFingerprint(base64Key)
            return AdbKey(
                id = id,
                raw = trimmed,
                base64Key = base64Key,
                comment = comment,
                fingerprint = fingerprint,
                isAuthorized = isAuthorized
            )
        }

        fun computeFingerprint(base64Key: String): String {
            return try {
                val decoded = Base64.decode(base64Key, Base64.DEFAULT)
                val md = MessageDigest.getInstance("SHA-256")
                val hash = md.digest(decoded)
                hash.joinToString(":") { "%02X".format(it) }
            } catch (e: Exception) {
                "Invalid Key"
            }
        }
    }
}

data class ActiveSession(
    val remoteAddress: String,
    val remotePort: Int,
    val localAddress: String,
    val localPort: Int,
    val peerName: String = "",
    val durationSeconds: Long = 0
) {
    val cleanIp: String
        get() = remoteAddress.replace("[", "").replace("]", "")
}
