package net.wastu.wadbd.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WadbdState(
    val isRootAvailable: Boolean = false,
    val isEnabled: Boolean = false,
    val port: Int = 5555,
    val isBootEnabled: Boolean = false,
    val bootPort: Int = 5555,
    val isRestricted: Boolean = false,
    val boundTargets: List<String> = emptyList(),
    val isNotificationEnabled: Boolean = true,
    val activeSessions: List<ActiveSession> = emptyList(),
    val authorizedKeys: List<AdbKey> = emptyList(),
    val pendingKeys: List<AdbKey> = emptyList()
)

class WadbdRepository {

    suspend fun loadState(): WadbdState = withContext(Dispatchers.IO) {
        val isRootAvailable = RootShell.isRootAvailable()
        val portStr = RootShell.exec("getprop service.adb.tcp.port").text.trim()
        val port = portStr.toIntOrNull() ?: -1
        val isEnabled = port > 0

        val bootPortStr = RootShell.exec("cat /data/adb/wadbd/enable_on_boot 2>/dev/null").text.trim()
        val bootPort = bootPortStr.toIntOrNull() ?: 5555
        val isBootEnabled = bootPortStr.isNotEmpty() && bootPort > 0

        val boundTargets = RootShell.execLines("cat /data/adb/wadbd/bind_ifaces 2>/dev/null")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        val isRestricted = boundTargets.isNotEmpty()

        val notifPref = RootShell.exec("cat /data/adb/wadbd/notify_conn 2>/dev/null").text.trim()
        val isNotificationEnabled = notifPref != "0"

        val sessions = loadActiveSessions(if (isEnabled) port else 5555)
        val authorizedKeys = loadAuthorizedKeys()
        val pendingKeys = loadPendingKeys()

        // TCP sockets do not expose the authenticated ADB RSA fingerprint.
        // Never infer key identity from DNS; show the verified peer IP instead.
        val enrichedSessions = sessions

        WadbdState(
            isRootAvailable = isRootAvailable,
            isEnabled = isEnabled,
            port = if (isEnabled) port else 5555,
            isBootEnabled = isBootEnabled,
            bootPort = bootPort,
            isRestricted = isRestricted,
            boundTargets = boundTargets,
            isNotificationEnabled = isNotificationEnabled,
            activeSessions = enrichedSessions,
            authorizedKeys = authorizedKeys,
            pendingKeys = pendingKeys
        )
    }

    suspend fun requestRoot(): Boolean = RootShell.requestRoot()

    suspend fun toggleAdb(enable: Boolean, port: Int = 5555): Boolean = withContext(Dispatchers.IO) {
        if (enable) {
            RootShell.exec("/data/adb/modules/wadbd/system/bin/wadbd on $port || wadbd on $port").isSuccess
        } else {
            RootShell.exec("/data/adb/modules/wadbd/system/bin/wadbd off || wadbd off").isSuccess
        }
    }

    suspend fun toggleBootPersistence(enable: Boolean, port: Int = 5555): Boolean = withContext(Dispatchers.IO) {
        if (enable) {
            RootShell.exec("/data/adb/modules/wadbd/system/bin/wadbd enable-on-boot $port || wadbd enable-on-boot $port").isSuccess
        } else {
            RootShell.exec("/data/adb/modules/wadbd/system/bin/wadbd disable-on-boot || wadbd disable-on-boot").isSuccess
        }
    }

    suspend fun toggleNotifications(enable: Boolean): Boolean = withContext(Dispatchers.IO) {
        val arg = if (enable) "on" else "off"
        RootShell.exec("/data/adb/modules/wadbd/system/bin/wadbd notify $arg || wadbd notify $arg").isSuccess
    }

    suspend fun bindTarget(target: String): Boolean = withContext(Dispatchers.IO) {
        RootShell.exec("/data/adb/modules/wadbd/system/bin/wadbd bind '$target' || wadbd bind '$target'").isSuccess
    }

    suspend fun unbindTarget(target: String): Boolean = withContext(Dispatchers.IO) {
        RootShell.exec("/data/adb/modules/wadbd/system/bin/wadbd unbind '$target' || wadbd unbind '$target'").isSuccess
    }

    suspend fun unbindAll(): Boolean = withContext(Dispatchers.IO) {
        RootShell.exec("/data/adb/modules/wadbd/system/bin/wadbd unbind-all || wadbd unbind-all").isSuccess
    }

    suspend fun kickSession(session: ActiveSession): Boolean = withContext(Dispatchers.IO) {
        // Try TCP socket termination via ss -K, with fallback to cycling adbd
        val res = RootShell.exec("ss -K dst '${session.remoteAddress}' dport = :${session.remotePort} 2>/dev/null")
        if (!res.isSuccess || res.code != 0) {
            // Fallback: quick restart of adbd
            RootShell.exec("stop adbd; sleep 0.2; start adbd")
        }
        true
    }

    suspend fun kickAllSessions(): Boolean = withContext(Dispatchers.IO) {
        val port = RootShell.exec("getprop service.adb.tcp.port").text.trim()
        RootShell.exec("stop adbd; sleep 0.2; start adbd")
        if (port.isNotEmpty() && port != "-1") {
            RootShell.exec("if [ -f /data/adb/wadbd/bind_ifaces ]; then /data/adb/modules/wadbd/system/bin/wadbd on $port; fi")
        }
        true
    }

    suspend fun loadActiveSessions(port: Int): List<ActiveSession> = withContext(Dispatchers.IO) {
        val lines = RootShell.execLines("ss -Htn state established '( sport = :$port )' 2>/dev/null")
        val sessions = mutableListOf<ActiveSession>()

        for (line in lines) {
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size >= 4) {
                val local = parts[2]
                val peer = parts[3]
                val localColon = local.lastIndexOf(':')
                val peerColon = peer.lastIndexOf(':')

                if (localColon != -1 && peerColon != -1) {
                    val localIp = local.substring(0, localColon)
                    val localPort = local.substring(localColon + 1).toIntOrNull() ?: port
                    val remoteIp = peer.substring(0, peerColon)
                    val remotePort = peer.substring(peerColon + 1).toIntOrNull() ?: 0

                    if (!remoteIp.contains("127.0.0.1") && !remoteIp.contains("::1")) {
                        sessions.add(
                            ActiveSession(
                                remoteAddress = remoteIp,
                                remotePort = remotePort,
                                localAddress = localIp,
                                localPort = localPort
                            )
                        )
                    }
                }
            }
        }
        sessions
    }

    suspend fun loadAuthorizedKeys(): List<AdbKey> = withContext(Dispatchers.IO) {
        val lines = RootShell.execLines("cat /data/misc/adb/adb_keys 2>/dev/null")
        val keys = mutableListOf<AdbKey>()
        var id = 0
        for (line in lines) {
            val parsed = AdbKey.parse(line, id, isAuthorized = true)
            if (parsed != null) {
                keys.add(parsed)
                id++
            }
        }
        keys
    }

    suspend fun revokeKey(key: AdbKey): Boolean = withContext(Dispatchers.IO) {
        // Rewrite adb_keys file without the revoked key
        val currentKeys = loadAuthorizedKeys()
        val remaining = currentKeys.filter { it.raw != key.raw }
        val content = remaining.joinToString("\n") { it.raw }
        
        val tempPath = "/data/local/tmp/new_adb_keys"
        RootShell.exec("cat <<'EOF' > $tempPath\n$content\nEOF")
        val res = RootShell.exec("cp $tempPath /data/misc/adb/adb_keys && chmod 640 /data/misc/adb/adb_keys && rm -f $tempPath")
        res.isSuccess
    }

    suspend fun importKey(rawKey: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) return@withContext false
        val res = RootShell.exec("echo '$trimmed' >> /data/misc/adb/adb_keys && chmod 640 /data/misc/adb/adb_keys")
        res.isSuccess
    }

    suspend fun revokeAllKeys(): Boolean = withContext(Dispatchers.IO) {
        val res = RootShell.exec("> /data/misc/adb/adb_keys && chmod 640 /data/misc/adb/adb_keys && stop adbd; sleep 0.2; start adbd")
        res.isSuccess
    }

    suspend fun loadPendingKeys(): List<AdbKey> = withContext(Dispatchers.IO) {
        // Parse unauthorized connection attempts from recent logcat
        val lines = RootShell.execLines("logcat -d -t 200 -s adbd AdbDebuggingManager 2>/dev/null | grep -iE 'key|auth|fingerprint'")
        val pending = mutableListOf<AdbKey>()
        var id = 100

        for (line in lines) {
            // Check for key strings in logcat
            if (line.contains("key") && line.contains("not in")) {
                val match = Regex("key\\s+([A-Za-z0-9+/=]+)").find(line)
                if (match != null) {
                    val base64 = match.groupValues[1]
                    val parsed = AdbKey.parse("$base64 Unauthorized Device", id, isAuthorized = false)
                    if (parsed != null && pending.none { it.fingerprint == parsed.fingerprint }) {
                        pending.add(parsed)
                        id++
                    }
                }
            }
        }
        pending
    }

    suspend fun allowPendingKey(key: AdbKey): Boolean = withContext(Dispatchers.IO) {
        importKey(key.raw)
    }

    suspend fun ignorePendingKey(key: AdbKey): Boolean = withContext(Dispatchers.IO) {
        // Drop any active socket and clear logcat buffer
        RootShell.exec("logcat -c")
        true
    }

}
