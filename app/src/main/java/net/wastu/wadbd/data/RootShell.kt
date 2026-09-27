package net.wastu.wadbd.data

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RootShell {

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10)
        )
    }

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        Shell.isAppGrantedRoot() == true
    }

    suspend fun exec(cmd: String): CommandResult = withContext(Dispatchers.IO) {
        val result = Shell.cmd(cmd).exec()
        CommandResult(
            isSuccess = result.isSuccess,
            code = result.code,
            out = result.out,
            err = result.err
        )
    }

    suspend fun execLines(cmd: String): List<String> = withContext(Dispatchers.IO) {
        Shell.cmd(cmd).exec().out
    }
    suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
        Shell.rootAccess()
    }



}

data class CommandResult(
    val isSuccess: Boolean,
    val code: Int,
    val out: List<String>,
    val err: List<String>
) {
    val text: String get() = out.joinToString("\n")
}
