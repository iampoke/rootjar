# Agent instructions

Standing rules for anyone working on this project. Read this before editing.

## Documentation

**Update `handoff.md` after every change.** Any edit to the source, the UI, the config schema, the
endpoints or the build gets a matching entry in `handoff.md` in the same commit. Record what changed,
where it lives, how it was verified, and any gotcha that cost time to find. `handoff.md` is the only
durable memory this project has, so a change that is not written down is a change the next session
will redo or break.

`NEXT-BATCH-PLAN.md` is the historical phase 0-9 plan. It is complete and is not a to-do list.

## Ground rules

- **Zero third-party dependencies.** JDK only. No Maven/Gradle build, no JSON or YAML library, no
  JUnit, no `node_modules`. JSON is concatenated with `jsonEscape()` and read back with `extractJson()`
  and friends; `config.yml` goes through `MiniYaml`/`ConfigSaver`; tests live in
  `src/test/java/rootjar/ChecksMain.java` as a plain `main()`.
- **`index.html` is the whole frontend** - one file, CSS then HTML then a single `<script>`. The build
  copies the root `index.html` into the jar; `Frontend` seeds a disk copy on first run. Keep the root
  file and the jar bundle identical.
- **CRLF line endings.** Normalise anything written with bare LF.
- **Escape everything.** Strings reaching JSON go through `jsonEscape()`; strings reaching the DOM go
  through `escapeHtml()`. This is tested with hostile filenames on purpose.
- **Path safety.** File endpoints resolve through the existing safe-resolve helper and reject anything
  that escapes the child's own folder. Archive extraction must reject zip-slip entries.
- Compiled `-source 17 -target 17` so the jar runs on Java 17+, even though the local JDK is newer.

## Verify before claiming done

```
bash build.sh            # compiles main + test, runs ChecksMain, builds build/server.jar
node scripts/verify-js.js  # parses the inline <script> in index.html
```

Both must be green. Add a `ChecksMain` check for any new backend logic. `make.bat` also builds, but it
writes to the project root and ends in `pause`, so it blocks a non-interactive shell - prefer `build.sh`.

## Layout notes

- Source project: `W:\childserversjar\mother-jar-m1-source\rootjar` (this folder, a git repo on `master`).
- `W:\childserversjar\trash` is the **retired** production deployment (old jar, old `index.html`,
  `nanolimbo/`, `velocity/`). It is history, not a build target. Do not deploy into it.
- A running jar is locked on Windows. Stop the process before rebuilding, and check the jar's byte
  count and timestamp - the build scripts print success even when the jar step failed.
- Kill stray processes with `taskkill /F /PID <pid>` found via `netstat -ano`. Never `pkill -f java`;
  it takes out unrelated processes.
