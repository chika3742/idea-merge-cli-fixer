# idea-merge-cli-fixer

An IntelliJ IDEA plugin that adds a single CLI command, `idea mergex`,
designed to be used as a `git mergetool`.

## Why

As of IntelliJ IDEA **2026.1.2**, the bundled `idea merge` command has
these issues that make it unsuitable as a `git mergetool` driver
(JetBrains may address them in a future release &mdash; please verify
against your current IDE version before relying on this plugin):

1. **It nags about non-project files.** When the file being merged sits
   outside any open project, the IDE displays a "you are editing a
   non-project file" confirmation dialog on every save.
2. **EDT errors.** Threading bugs occasionally surface as event-dispatch
   thread violations.

`idea mergex` is a sibling command that:

- Whitelists writes to the merged file while the merge is active (no
  non-project-file dialog).
- Uses the modern coroutine-based application starter to stay on the
  correct thread.
- Coexists with the bundled `idea merge` &mdash; it is added, not
  replaced.

## Requirements

- IntelliJ IDEA **2026.1.2** or newer (build `261.*`).
- JDK 21 (for building).
- Tested only on macOS 26.5.

## Basic install

Go to **Setting︎︎s -> Plugins** and search for "merge cli fixer" in Marketplace tab then install.

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

## Configure VCS

### Git

Add the following to `~/.gitconfig` (or run the equivalent
`git config --global` commands):

```ini
[merge]
    tool = idea
[mergetool "idea"]
    cmd = idea mergex --wait $LOCAL $REMOTE $BASE $MERGED
    trustExitCode = true
[mergetool]
    keepBackup = false
```

- Note that `--wait` option must be placed after `mergex`.
- Make sure Toolbox is configured to generate launch scripts.

After this, `git mergetool` will block on the IDE's merge dialog and
honour its exit code:

| Exit code | Meaning                                     |
|-----------|---------------------------------------------|
| 0         | Merge resolved (Apply, or take Left/Right). |
| 1         | Cancelled.                                  |
| 2         | Invalid arguments or fatal error.           |

### Jujutsu

Add the following to `~/.config/jj/config.toml`.

```toml
[ui]
merge-editor = "idea"

[merge-tools.idea]
program = "idea"
merge-args = ["mergex", "--wait", "$left", "$right", "$base", "$output"]
```

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

- `MergexStarter` implements the `ApplicationStarter` interface,
  registered under `com.intellij.appStarter` as `mergex`. It strips
  option flags (e.g. `--wait`), validates the 3&ndash;4 positional file
  arguments, and reports errors via modal dialogs.
- It reads the files, opens a `MergeRequest` with `DiffManager.showMerge`
  on the EDT, and awaits the `MergeResult` through a `CompletableDeferred`
  so the process blocks until the dialog closes.
- While a merge is active, `MergexSession` records the participating
  paths and `MergexFileAccessProvider` whitelists writes to them,
  suppressing the non-project-file confirmation dialog.

## License

See `LICENSE`.
