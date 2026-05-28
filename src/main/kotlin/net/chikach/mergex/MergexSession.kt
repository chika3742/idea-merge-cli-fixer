package net.chikach.mergex

import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide toggle that records which file paths are part of an active
 * `idea mergex` invocation. Used by [MergexFileAccessProvider] to whitelist
 * writes to those files for the duration of the merge.
 */
object MergexSession {
    private val active = AtomicBoolean(false)
    private val allowedPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun begin(paths: Collection<Path>) {
        allowedPaths.clear()
        paths.forEach { allowedPaths.add(it.toAbsolutePath().normalize().toString()) }
        active.set(true)
    }

    fun end() {
        active.set(false)
        allowedPaths.clear()
    }

    fun shouldAllow(file: VirtualFile): Boolean {
        if (!active.get()) return false
        val path = runCatching { Path.of(file.path).toAbsolutePath().normalize().toString() }
            .getOrElse { return false }
        return path in allowedPaths
    }
}
