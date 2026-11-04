# BotFarm

one android app for the things that are supposed to keep running.

hosts discord bots and selfbots, gives you a terminal and a file manager, and
has an opencode screen on top. everything runs inside the app's own folder
through `proot`, so no root is involved and uninstalling takes all of it back.

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
4. one download, the linux userland

the foreground service keeps the process alive after you swipe the app away.
to actually stop it, disable the app in the background apps list in settings.

## build

```
gradle :app:assembleRelease
```

github actions does this on every push and attaches the apk.

## layout

```
app/src/main/java/io/nexus/farm/
  core/       env paths, proot command line, process manager, setup
  service/    the foreground service
  ui/         theme, screens
```
