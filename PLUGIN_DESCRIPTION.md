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

### Jujutsu

Add the following to `~/.config/jj/config.toml`.

```toml
[ui]
merge-editor = "idea"

[merge-tools.idea]
program = "idea"
merge-args = ["mergex", "--wait", "$left", "$right", "$base", "$output"]
```

- Note that `--wait` option must be placed after `mergex`.
- Make sure Toolbox is configured to generate launch scripts.
