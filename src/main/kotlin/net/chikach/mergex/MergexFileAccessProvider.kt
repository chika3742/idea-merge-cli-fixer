package net.chikach.mergex

import com.intellij.openapi.fileEditor.impl.NonProjectFileWritingAccessExtension
import com.intellij.openapi.vfs.VirtualFile

/**
 * Whitelists writes to files participating in an active `idea mergex` session.
 *
 * Returns `false` (the default) for everything else, so this extension has no
 * effect on normal IDE editing of non-project files outside a merge.
 */
class MergexFileAccessProvider : NonProjectFileWritingAccessExtension {
    override fun isWritable(file: VirtualFile): Boolean = MergexSession.shouldAllow(file)
}
