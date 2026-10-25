# Root jar (supervisor + web panel)

**This is a root server jar** - it runs inside a single Pterodactyl container and manages an entire Minecraft network. The main objective is to act as a secondary panel: you can create as many Minecraft servers as your resources allow within one container. Examples: 2 Paper servers, a proxy + Paper server, a proxy + NanoLimbo, or any combination of forks.

One `server.jar` that Pterodactyl launches. It starts the configured children you can create Paper, Purpur, Velocity or whatever you want, prefixes their output into the panel console, routes console commands to the right one, and stops everything gracefully. It also runs a built-in **web panel** with login, live console, file manager, config editor, Paper/Modrinth installers and per-user access control.

## First-run checklist

1. In the Pterodactyl panel set `SERVER_JARFILE=server.jar`. Leave the startup command as it is (needs Java 17+).
2. Set the egg's **stop command** to `stop` (the root console command for "stop everything").
3. Upload `server.jar` to the server's root folder. On first start it generates `config.yml` and a random
   admin password in `ADMIN_PASSWORD.txt` (delete that file after logging in).
4. Create two folders next to it: `velocity/` and `nanolimbo/`. Put `velocity.jar` and `NanoLimbo.jar` inside
   (or change `jar:` in config.yml to match your file names).
5. Configure the children themselves (the root does not touch these files):
   - `velocity/velocity.toml`: `bind = "0.0.0.0:25565"`
   - NanoLimbo settings: port `25566`, bind address `127.0.0.1`
6. Start the server, log into the web panel (port `25568` by default) with the credentials from
   `ADMIN_PASSWORD.txt`, then delete that file.

You can still pre-seed a password with `java -jar server.jar --hash-password` and paste it into
`config.yml` as `password_hash:`; a `placeholder` value now triggers the random-password flow.

## Web panel

- Login sessions are cookie-based (HttpOnly, SameSite=Strict), expire after `session_ttl_seconds`,
  and `/api/login` is rate limited + failure-locked per IP per `server:` config.
- Dashboard with per-server RAM/CPU, players, uptime; live console over SSE; file manager with
  upload/download-free editing, zip, rename, mkdir, delete; PaperMC and Modrinth installers;
  config quick-editor (`server.properties` and friends).
- **Delete Server** (admin only, three-step warning): removes the server from the panel and config.
  It does **not** delete any files: the folder stays on disk and inside the Pterodactyl panel's files.
- Users: admins can create subusers and grant per-server access; non-admins only see their servers.

## Console commands (type them in the server console)

| You type | What happens |
|---|---|
| `velocity plist` | sends `plist` to Velocity (any `<id> <command>` works) |
| velocity.start / .stop / .restart | controls one child (same for any server id) |
| velocity.mute / .unmute | mutes or unmutes that child's log lines on the main console; the web panel still shows them |
| `status` | shows every child's state, pid and uptime |
| `stop` | stops ALL children gracefully, then exits the root |
| `help` | lists these |
| anything else | goes to the first child with `console_enabled: true` |

Child ids are re-read on every line, so servers created in the web panel are console-routable immediately.

## Shutdown timing (important)

Order: by `stop_order` (Velocity first in the default config). Each child is asked to stop per its
`stop_strategy`, given `stop_timeout_seconds`, then killed, then up to 5 s for the OS to reap it.
Worst case with defaults, if both ignore every stop request: (30 + 5) + (15 + 5) = **55 s**. Keep the
panel's stop timeout above that, or lower the timeouts in config.yml.

The same shutdown runs for the `stop` command, SIGTERM/SIGINT, and any JVM exit; the root exits with code 0.

## Things to know

- A child counts as `running` once it has stayed alive for 5 s.
- Crashed children restart with backoff `5, 10, 30, 60, 300` s (last value repeats). After 10 minutes of stable
  running, or any manual start/restart, the backoff starts over. `stop`/`<id>.stop` never restarts.
- Child heap below 256M is rejected *when Aikar flags are on* (the JVM itself refuses to start); turn off
  `use_aikar_flags` or raise the heap.
- The last 10,000 lines (max 2,048 characters each) of every child are kept in memory for the live console.
- Duplicate ids / ports / start-stop orders in config.yml are auto-fixed and re-saved on boot.
- RAM/CPU/network stats are read from `/proc` and `/sys` (Linux containers, the intended deployment).

## Building and testing (needs only a JDK 17+)

`./build.sh` compiles, builds `build/server.jar` (with the panel UI bundled), runs the unit checks, and
builds the dummy test server. On Windows, `make.bat` does compile + package. The panel UI lives in the
repo-root `index.html`; it is copied into the jar at build time and written out next to the jar on first run,
so you can edit `index.html` and restart to apply changes without rebuilding.

`scripts/m1-accept.sh` runs the real jar against dummy servers (config rejection, prefixes, routing,
start/stop order, SIGTERM, the SIGKILL path and crash backoff; about 3 minutes).

# TLDR; Create many minecraft servers from 1 jar file if you're limited to 1 server. The only requirements are multiple ports you need per server.
