package net.chikach.mergex

import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestFactory
import com.intellij.diff.merge.MergeResult
import com.intellij.ide.CliResult
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.eel.fs.EelFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.Icon
import kotlin.system.exitProcess

/**
 * `idea mergex` &mdash; a blocking, git-mergetool-friendly variant of the
 * bundled `idea merge` command.
 *
 * Argument layout matches `git mergetool` conventions:
 * ```
 *   idea mergex <LOCAL> <REMOTE> <BASE> <MERGED>   # 3-way
 *   idea mergex <LOCAL> <REMOTE> <MERGED>          # 2-way (synthesizes empty BASE)
 * ```
 *
 * Exit codes: `0` resolved, `1` cancelled, `2` invalid arguments / fatal error.
 *
 * Implements the public [ApplicationStarter] interface directly. The former
 * base class `ApplicationStarterBase` is marked internal to the IntelliJ
 * Platform and must not be used from plugins, so the small amount of glue it
 * provided (argument checking, the external-command-line path, and the
 * direct-launch path) is replicated here.
 *
 * The command is typically launched via `open -Wna "IntelliJ IDEA.app" --args
 * mergex …`, so its stdout/stderr are not attached to the terminal that ran
 * `git mergetool`. Errors and the usage message are therefore surfaced to the
 * user through modal dialogs (mirroring `ApplicationStarterBase`), with stderr
 * kept only for the IDE log.
 */
internal class MergexStarter : ApplicationStarter {

    private val commandName: String = "mergex"

    private val usageMessage: String =
        "Usage: idea mergex <LOCAL> <REMOTE> [<BASE>] <MERGED>"

    /** The command logic runs on a background thread; UI work is dispatched to the EDT explicitly. */
    override val requiredModality: Int = ApplicationStarter.NOT_IN_EDT

    /** The merge dialog requires a UI, so this starter cannot run headless. */
    override val isHeadless: Boolean = false

    override fun canProcessExternalCommandLine(): Boolean = true

    private fun checkArguments(args: List<String>): Boolean {
        val positional = positionalArgs(args).size
        return positional == 3 || positional == 4
    }

    /**
     * Direct-launch path: invoked on the EDT when no running instance handled
     * the command. We must not block the EDT (the merge dialog needs it), so we
     * launch the work on the application coroutine scope and terminate the JVM
     * ourselves once it completes.
     */
    override fun main(args: List<String>) {
        service<MergexCoroutineScopeService>().scope.launch {
            val exitCode = if (!checkArguments(args)) {
                showUsageDialog()
                2
            } else {
                executeCommand(args, currentDirectory = null).exitCode
            }
            exitProcess(exitCode)
        }
    }

    /**
     * External-command-line path: invoked (off the EDT) on the already-running
     * instance when `git mergetool` launches the IDE a second time.
     */
    override suspend fun processExternalCommandLine(args: List<String>, currentDirectory: String?): CliResult {
        if (!checkArguments(args)) {
            showUsageDialog()
            return CliResult(2, usageMessage)
        }
        return executeCommand(args, currentDirectory)
    }

    private suspend fun executeCommand(args: List<String>, currentDirectory: String?): CliResult {
        return try {
            runMerge(positionalArgs(args), currentDirectory)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            t.printStackTrace(System.err)
            showErrorDialog(t)
            CliResult(1, t.message ?: t.javaClass.simpleName)
        }
    }

    private suspend fun runMerge(positional: List<String>, currentDirectory: String?): CliResult {
        val cwd: Path = currentDirectory?.let(Paths::get) ?: Paths.get("").toAbsolutePath()

        val localPath: Path
        val remotePath: Path
        val basePath: Path?
        val mergedPath: Path

        when (positional.size) {
            4 -> {
                localPath  = resolve(cwd, positional[0])
                remotePath = resolve(cwd, positional[1])
                basePath   = resolve(cwd, positional[2])
                mergedPath = resolve(cwd, positional[3])
            }
            3 -> {
                localPath  = resolve(cwd, positional[0])
                remotePath = resolve(cwd, positional[1])
                basePath   = null
                mergedPath = resolve(cwd, positional[2])
            }
            else -> return invalidArgs("expected 3 or 4 positional arguments, got ${positional.size}")
        }

        for ((label, path) in listOf("LOCAL" to localPath, "REMOTE" to remotePath, "MERGED" to mergedPath)) {
            if (!Files.exists(path)) return invalidArgs("$label file not found: $path")
        }
        if (basePath != null && !Files.exists(basePath)) {
            return invalidArgs("BASE file not found: $basePath")
        }

        val localBytes  = readBytes(localPath)
        val remoteBytes = readBytes(remotePath)
        val baseBytes   = basePath?.let { readBytes(it) } ?: ByteArray(0)

        val mergedVf = withContext(Dispatchers.EDT) {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(mergedPath)
        } ?: return invalidArgs("could not locate MERGED file in VFS: $mergedPath")

        val tracked = listOfNotNull(localPath, remotePath, basePath, mergedPath)
        MergexSession.begin(tracked)
        try {
            val result = CompletableDeferred<MergeResult>()
            withContext(Dispatchers.EDT) {
                val project = findContainingOpenProject(mergedPath)
                val request = DiffRequestFactory.getInstance().createMergeRequest(
                    project,
                    mergedVf,
                    listOf(localBytes, baseBytes, remoteBytes),
                    "Merge ${mergedPath.fileName}",
                    listOf("Local", "Base", "Remote"),
                ) { mergeResult: MergeResult -> result.complete(mergeResult) }
                DiffManager.getInstance().showMerge(project, request)
            }
            return when (result.await()) {
                MergeResult.CANCEL -> CliResult(1, null)
                MergeResult.RESOLVED, MergeResult.LEFT, MergeResult.RIGHT -> {
                    // The merge dialog updates the document under a WriteAction but the disk
                    // flush is queued; without an explicit save the process can exit before
                    // FileDocumentManager flushes, losing the merged content.
                    flushMergedFile(mergedVf)
                    CliResult.OK
                }
            }
        } finally {
            MergexSession.end()
        }
    }

    private suspend fun flushMergedFile(vf: VirtualFile) {
        withContext(Dispatchers.EDT) {
            val fdm = FileDocumentManager.getInstance()
            val doc = fdm.getCachedDocument(vf) ?: fdm.getDocument(vf)
            if (doc != null) fdm.saveDocument(doc) else fdm.saveAllDocuments()
        }
    }

    /**
     * Extracts the positional file arguments, dropping the leading command name
     * and any option flags. The launcher script generated by JetBrains Toolbox
     * forwards switches such as `--wait` (needed to make the script block until
     * the IDE exits) into the argument list, so they must be filtered out before
     * counting the LOCAL/REMOTE/BASE/MERGED operands.
     */
    private fun positionalArgs(args: List<String>): List<String> =
        args.filterNot { it == commandName || it.startsWith("-") }

    private fun resolve(cwd: Path, arg: String): Path {
        val p = Paths.get(arg)
        return (if (p.isAbsolute) p else cwd.resolve(p)).toAbsolutePath().normalize()
    }

    private fun readBytes(path: Path): ByteArray =
        try {
            @Suppress("UnstableApiUsage")
            EelFiles.readAllBytes(path)
        } catch (e: NoSuchFileException) {
            throw IllegalStateException("File disappeared while reading: $path", e)
        }

    private fun findContainingOpenProject(file: Path): Project? {
        val openProjects = ProjectManager.getInstance().openProjects
        return openProjects.firstOrNull { project ->
            val base = project.basePath ?: return@firstOrNull false
            runCatching {
                file.startsWith(Paths.get(base).toAbsolutePath().normalize())
            }.getOrDefault(false)
        }
    }

    private suspend fun invalidArgs(message: String): CliResult {
        System.err.println("mergex: $message")
        showDialog("$message\n\n$usageMessage", Messages.getWarningIcon())
        return CliResult(2, message)
    }

    private suspend fun showUsageDialog() =
        showDialog(usageMessage, Messages.getInformationIcon())

    private suspend fun showErrorDialog(t: Throwable) =
        showDialog("mergex failed: ${t.message ?: t.javaClass.simpleName}", Messages.getErrorIcon())

    private suspend fun showDialog(message: String, icon: Icon) {
        // Messages renders the text as HTML, so angle-bracketed placeholders such
        // as <LOCAL> would otherwise be swallowed as unknown tags (and newlines
        // ignored). Escape the text and turn line breaks into <br> explicitly.
        val html = "<html>" + message
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>") + "</html>"
        withContext(Dispatchers.EDT) {
            Messages.showMessageDialog(html, "idea mergex", icon)
        }
    }
}
