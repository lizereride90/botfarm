# BotFarm

one android app for the things that are supposed to keep running.

hosts discord bots and selfbots, gives you a terminal and a file manager, and
has an opencode screen on top. the environment underneath is a real termux
install, unpacked into the app's own folder. no root, and uninstalling takes
all of it back.

## what is in it

- **servers** — add a command, start it, stop it, force kill it, see the log,
  auto restart with backoff if it crashes
- **terminal** — a shell into the userland, any command
- **files** — browse the app folder, open project directories
- **code** — opencode cli behind a chat interface
- **settings** — environment, keep alive, permissions

## first run

1. all files access, so the file manager can reach your projects
2. battery set to unrestricted, otherwise android kills the bots
3. notifications, so you hear about a crash
4. one download, the termux userland

the foreground service keeps the process alive after you swipe the app away.
to actually stop it, disable the app in the background apps list in settings.

## how the userland works

the tarball is the official termux bootstrap plus a package set, built by
`tools/termux.py` and published as a release asset. it lands in
`/data/data/io.botfarm/files/usr`, which is byte for byte the same length as
the path termux compiles into its own binaries, so the build repoints them with
a straight swap instead of patching in a longer string.

no proot. those binaries are built against android's libc, so they run as
ordinary children of the app process. the app targets api 28 for the same
reason termux does: android 10 and up refuse to exec anything inside an app's
data directory once the app targets 29 or higher.

## build

```
gradle :app:assembleRelease
```

github actions does this on every push and attaches the apk to the `apk`
release. the userland tarball lives on the `userland` release.

## layout

```
app/src/main/java/io/nexus/farm/
  core/       env paths, process manager, setup
  service/    the foreground service
  ui/         theme, screens
tools/        the userland builder
```
