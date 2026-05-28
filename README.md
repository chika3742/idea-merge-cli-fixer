# idea-merge-cli-fixer

An IntelliJ IDEA plugin that adds a single CLI command, `idea mergex`,
designed to be used as a `git mergetool`.

## Why

As of IntelliJ IDEA **2026.1.2**, the bundled `idea merge` command has
three issues that make it unsuitable as a `git mergetool` driver
(JetBrains may address them in a future release &mdash; please verify
against your current IDE version before relying on this plugin):

1. **It does not wait.** `idea merge` returns control to the shell before
   the merge dialog is closed, so `git mergetool` proceeds with an
   unresolved file.
2. **It nags about non-project files.** When the file being merged sits
   outside any open project, the IDE displays a "you are editing a
   non-project file" confirmation dialog on every save.
3. **EDT errors.** Threading bugs occasionally surface as event-dispatch
   thread violations.

`idea mergex` is a sibling command that:

- Blocks until the merge dialog is closed.
- Whitelists writes to the merged file while the merge is active (no
  non-project-file dialog).
- Uses the modern coroutine-based application starter to stay on the
  correct thread.
- Coexists with the bundled `idea merge` &mdash; it is added, not
  replaced.

## Requirements

- IntelliJ IDEA **2026.1.2** or newer (build `261.*`).
- JDK 21 (for building).
- An `idea` (or `idea.sh` / `idea.bat`) launcher on `PATH`.

## Build

```sh
./gradlew buildPlugin
```

The plugin zip is written to `build/distributions/`.

## Install

In IntelliJ IDEA:

1. Open **Settings &rarr; Plugins**.
2. Click the gear icon and choose **Install Plugin from Disk&hellip;**.
3. Select the zip in `build/distributions/`.
4. Restart the IDE.

## Configure `git mergetool`

Add the following to `~/.gitconfig` (or run the equivalent
`git config --global` commands):

```ini
[merge]
    tool = mergex
[mergetool "mergex"]
    cmd = idea mergex $LOCAL $REMOTE $BASE $MERGED
    trustExitCode = true
[mergetool]
    keepBackup = false
```

After this, `git mergetool` will block on the IDE's merge dialog and
honour its exit code:

| Exit code | Meaning                                     |
|-----------|---------------------------------------------|
| 0         | Merge resolved (Apply, or take Left/Right). |
| 1         | Cancelled.                                  |
| 2         | Invalid arguments or fatal error.           |

## Verifying the install

### End-to-end via `git mergetool`

Create a synthetic conflict in a throwaway repository:

```sh
mkdir /tmp/mergex-demo && cd /tmp/mergex-demo
git init
echo base > file.txt && git add file.txt && git commit -m base
git checkout -b a && echo a > file.txt && git commit -am a
git checkout -                # back to default branch
echo b > file.txt && git commit -am b
git merge a || true           # produces a conflict
git mergetool
```

The IDE's merge dialog should appear and `git mergetool` should
block until you close it. After resolving and clicking **Apply**, the
process should exit `0` and `git status` should show `file.txt` as
resolved.

### Direct invocation

```sh
echo local  > /tmp/L
echo remote > /tmp/R
echo base   > /tmp/B
cp /tmp/B    /tmp/M
idea mergex /tmp/L /tmp/R /tmp/B /tmp/M
echo "exit=$?"
```

You can also drop the `BASE` argument to exercise the 2-way form:

```sh
idea mergex /tmp/L /tmp/R /tmp/M
```

## How it works

- `MergexStarter` extends `ApplicationStarterBase(3, 4)` and is
  registered under the extension point `com.intellij.appStarter` with
  the command name `mergex`. The IDE's CLI dispatcher routes
  `idea mergex &hellip;` to its `suspend fun executeCommand(&hellip;)`.
- The starter reads the file contents, locates the merged file in the
  VFS, opens a `MergeRequest` via `DiffRequestFactory.createMergeRequest`,
  and shows it with `DiffManager.showMerge` on the EDT. It then awaits
  the `MergeResult` callback through a `CompletableDeferred`, so the
  process blocks until the dialog closes.
- While the merge is active, `MergexSession` records the participating
  file paths. `MergexFileAccessProvider`, registered against
  `com.intellij.nonProjectFileWritingAccessExtension`, returns `true`
  from `isWritable` for those paths, which suppresses the non-project
  file confirmation dialog. Outside an active merge the extension is a
  no-op.

## License

See `LICENSE`.
