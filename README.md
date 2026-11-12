# Rootjar

<p align="center">
  <img src="icon.svg" width="160" alt="Rootjar icon">
</p>

**A Minecraft server slot is usually cheaper than a VPS, so Rootjar can save money when the goal is to host multiple Minecraft servers.** It turns that one allowed jar into a panel for every server the slot can run.

**One Java jar that turns a single server slot into a panel for many servers.**

Rootjar is for hosts that only let you run one jar, such as a Pterodactyl egg with `SERVER_JARFILE=server.jar`.

```text
java -jar server.jar
```

It runs a web panel and supervises as many child servers as the machine's CPU, RAM, disk, and ports allow. Players connect directly to the child that hosts their server.

## What it is for

Use it when one allowed server process needs to run several Minecraft servers, a proxy, a limbo server, a Discord bot, or a small website.

- A Velocity proxy plus several Paper servers
- Two independent Paper servers
- A proxy plus NanoLimbo
- A Minecraft server plus a JDA Discord bot
- A simple HTML site

Each child has its own folder, jar, port, memory limit, and console.

## How it works

1. The host launches `server.jar` with Java 17 or newer.
2. On first start, Rootjar creates `config.yml` and a random admin password in `ADMIN_PASSWORD.txt`.
3. The web panel opens on its configured port. The usual port is `25568`.
4. You create a child in the panel. Rootjar downloads its server jar when one is available, creates its folder, and adds it to `config.yml`.
5. Rootjar starts each child as its own Java process, in `start_order`.
6. The panel controls the selected child's power, console, commands, and files.
7. A crashed child can restart after 5, 10, 30, 60, and then 300 seconds. A manual stop does not restart it.
8. Stopping Rootjar shuts down every child in `stop_order`, waits for its timeout, and force-stops anything that refuses to exit.

## First start

1. Set the host's jar file to `server.jar` and keep its Java 17+ startup command.
2. Set the host's stop command to `stop`.
3. Upload `server.jar` and start it once.
4. Read the username and password in `ADMIN_PASSWORD.txt`.
5. Open the panel, log in, and delete `ADMIN_PASSWORD.txt`.
6. Create servers from the panel. Each server needs its own free port.

To choose the password before first startup:

```text
java -jar server.jar --hash-password
```

Put the printed hash in `config.yml` as `password_hash`. A value of `placeholder` makes Rootjar generate a random password instead.

## Panel

### Dashboard

- Lists every server the signed-in user is allowed to see.
- Shows status, software type, port, uptime, players, CPU, RAM, disk, and network use.
- Refreshes live resource numbers about every 2 seconds.
- Reads CPU, RAM, and network data from `/proc` and `/sys` on Linux.

### Server creation

- Guided choices for server type, software, version, name, port, and memory.
- Spaces in a server name become underscores, so `Sky Block` is saved as `Sky_Block`.
- Supports Paper, Purpur, Velocity, Waterfall, BungeeCord, Travertine, NanoLimbo, JDA Discord bots, and a simple HTML site.
- Downloads the selected server jar automatically when an upstream source is available.
- Shows a preview before creating the server.

### Power and console

- Start, stop, and restart one server without affecting the others.
- Stream its console live and send commands from the panel.
- Search and copy console output.
- Mute a noisy child in the host console while keeping it visible in the panel.
- Keep the latest 10,000 console lines, with each line capped at 2,048 characters.

### Files

- Browse each server's own folder.
- Open, edit, save, rename, move, copy, and delete files.
- Create folders, upload files, and import a file directly.
- Zip and download files or folders.
- Search file names and file contents.
- Move files to Trash, restore them, or empty the trash.
- Prevent file requests from escaping that server's folder.

### Backups

- Create, list, download, upload, restore, and delete backups.
- Keep live and older snapshots separate.
- Retain from 1 to 50 backups.
- Create a pre-restore backup before restoring one.

### Plugins, mods, and versions

- Search Modrinth and install a selected version.
- View installed plugins or mods and check for updates.
- Enable, disable, or delete an installed addon.
- Back up the addons folder before making changes.
- Read plugin and mod metadata and compare supported Minecraft versions.
- Change a server's software version while backing up its old jar.
- Download and manage extra Java runtimes for servers that need another Java version.

### Configuration

- Edit `server.properties` and the server's other config files in the browser.
- Use a structured editor where Rootjar recognizes the file format.
- Change memory, jar, flags, port, and startup settings.
- Mark a server when its changed settings need a restart.

### Players, activity, and access

- Show the player count and send normal moderation commands.
- Record who changed a server and what they changed.
- Show CPU, RAM, and player history.
- Download logs.
- Create subusers and assign them to individual servers.
- Use Viewer, Operator, Owner, and Admin access.
- Hide panel IPs or console IPs per user.
- View and close signed-in sessions.
- Rate-limit and temporarily lock repeated failed logins.
- Use an HttpOnly, SameSite=Strict session cookie and CSRF checks.
- Optionally publish a read-only public status page.

### Schedules, notifications, and appearance

- Save timed restart or console-command tasks.
- Send notifications to a Discord-compatible HTTPS webhook.
- Switch the panel color theme.
- Toggle the Minecraft-style font.

## Host console

| Command | Action |
|---|---|
| `status` | Show every child's state, process ID, and uptime |
| `list` | Show the player count of every running child |
| `help` | Show the command list |
| `stop` | Stop every child, then stop Rootjar |
| `velocity plist` | Send `plist` to the child named `velocity` |
| `paper1.start` | Start one child |
| `paper1.stop` | Stop one child |
| `paper1.restart` | Restart one child |
| `paper1.mute` | Hide that child in the host console |
| `paper1.unmute` | Show that child in the host console again |

Unrecognized text goes to the first child whose console is enabled. Child names are reread for every command, so a server created in the panel can be controlled immediately. Command names and server names are not case-sensitive: `sky_block.restart` controls `Sky_Block`.

## Previous and Next Node buttons

The panel can show two optional links:

- **Previous Node**, fixed at the bottom left.
- **Next Node**, fixed at the bottom right.

An admin pastes each destination into Settings and enables its checkbox. Clicking a button opens that saved `http` or `https` address. These are navigation links only. Their settings are stored in `node-buttons.properties` beside `config.yml`.

## 1.2.0

- Each server can have its own storage limit, in GB, set when it is created or in Edit Server. `0` means unlimited, and an existing `config.yml` with no `storage_max_gb` key stays unlimited.
- The limit is enforced as a hard cap on the operations that add bytes: file upload, saving a file, copying, extracting a zip, and importing a zip. A refused write answers 409 with the numbers, and the panel shows the reason. Deleting, moving and trashing are never blocked, so an over-quota server can always be brought back under it.
- Backups are excluded from the count on purpose. A full disk must not prevent a safety backup.
- The storage meter on the server card and in the sidebar now shows `used / limit` when a limit is set, and scales the bar to that limit instead of to the largest server on the box.
- A start that would exceed available RAM no longer refuses outright. The panel asks "Start server anyway?" with the numbers, and retries with `force=1` if you agree. Auto-start and crash-restart start anyway and log a warning, so a panel restart never leaves a server unexpectedly down.
- The Activity tab and the Audit Log now page through history instead of showing a fixed window, with Newer and Older buttons and a page count.
- Fixed a flaky duplicate in the server activity feed. Two writes of one action that straddled a second boundary were shown twice, because dedupe compared only the whole second.

## 1.1.1

A fix release for one bug that shipped in 1.1.0.

- The player-count ping works now. It encoded VarInts by shifting five bits per byte instead of seven, so the protocol version went out as six bytes rather than five and every server rejected the handshake. Because the ping swallows its own failures and returns null, this was invisible in the panel: the count quietly fell back to console parsing, while strict servers such as NanoLimbo logged `Cannot decode packet PacketHandshake(0x0)` caused by `Bad VarInt`, once per child every 30 seconds.
- The reply reader had the same wrong shift, which would also have broken parsing of any status JSON longer than 127 bytes, meaning every real reply.
- VarInt encoding and decoding are now covered by known wire-format vectors, including `-1`, both integer extremes, a real port, and the exact handshake bytes the panel sends. Verified end to end against a live public server, which returned a player count, a maximum and a version string.

## 1.1.0

Everything below ships in the 1.1.0 build. It is 24 commits of work on top of 1.0.2. No config key was removed and every new one defaults to the previous behaviour, so an existing `config.yml` works unchanged.

### Addons and installs

- Plugins and mods install correctly from all three registries. Every search result now carries its own registry, which is what the version picker and the install route both route on. Before this, an install from any registry failed with "Unknown source".
- A version picker opens for Modrinth, Hangar and SpigotMC. It used to exist for Modrinth only.
- Dependencies are listed under the version picker as "Install dependencies?", each marked required or optional and individually tickable, and they install after the plugin itself. A Modrinth dependency is named by its project title rather than by an opaque id.
- SpigotMC searches containing a space work again. Two-word queries returned a 404.
- A registry that redirects to a web page instead of a jar is refused, with the redirect offered as a clickable link. This is what SpigotMC does for externally hosted plugins such as EssentialsX, which previously had a 240 KB HTML page written into `plugins/` and reported as a successful install.
- Hangar downloads are verified against the SHA-256 its API publishes, and the Paper file is chosen explicitly rather than by whichever platform the response happened to list first.
- A search failure names the registry that actually failed, instead of blaming Modrinth for every host.
- Modpacks install straight from a Modrinth project, using the same version picker.
- Premium SpigotMC resources are filtered out of results rather than offered and then refused.

### Server startup and JVM

- The Startup tab has checkboxes for pinning `-Xms` to `-Xmx` and for applying Aikar's G1GC flags. Both were previously editable only by hand in `config.yml`.
- Aikar's set is now complete. `-XX:+AlwaysPreTouch` and Aikar's two marker properties were missing, and the large-heap variant is selected automatically above 12 GB instead of every server getting the small-heap tuning.
- A new per-server switch adds `-XX:+ExitOnOutOfMemoryError`, so a server that exhausts its heap exits and is restarted rather than running half broken while the panel reports it as running.
- The command preview shows the command line the panel actually builds, so it can no longer drift from what runs. It previously always showed `-Xms` and never showed `nogui`.
- Saving the Startup tab no longer silently turns the managed Aikar flags off, and no longer puts the same flags on the command line twice.
- `eula.txt` is agreed to on start, so a freshly created server does not exit on its first run.
- Forge and NeoForge launch from the arguments file their installer generates. They were started with `-jar`, which cannot work for either.
- A start that would not fit in the available RAM is refused, with the numbers in the message. Inside a container the available figure comes from the cgroup limit, and both cgroup v2 and v1 are read.

### Console

- Opening a server shows the last 1,000 console lines instead of about 50.
- Opening a server no longer freezes the panel for several seconds. Those lines are inserted as one batch per frame rather than one at a time, and the view no longer forces a layout after every line.
- URLs in console output are clickable.
- The console background matches the rest of the panel.

### Player count and server state

- The player count is read from the server itself every 30 seconds, using the same status ping the multiplayer menu performs. It no longer depends on join and leave lines surviving whatever a plugin or proxy did to them, and it no longer stays at 0 until someone runs `list`.
- The console parsing of join and leave lines is fixed as well, so the count still moves immediately between pings.
- The sidebar state indicator reports the real state, with a colour per state. It used to read `CONNECTED` beside a permanently green dot for every server, including stopped, crashed and killed ones.
- The power buttons merge Start and Stop into one, show the action that applies, and grey out Restart and Kill when nothing is running. Greyed buttons stay clickable. An icon mode reuses the dashboard's icons and is remembered per browser.
- The RESOURCES badge reads Live only while the server is running, and Idle otherwise.
- A forced kill is reported as killed rather than as a crash.

### File manager

- Uploads are queued and shown inline in the toolbar, with a progress bar, a cancel button and per-file errors, and they are written atomically so a failed upload cannot leave a partial file behind.
- The right-click menu has icons, sends files to Trash, and offers a separate permanent Delete.
- Move is in the right-click menu, and its destination is picked by browsing folders. Move and the other actions apply to the whole selection.
- The right-click menu stays inside the window.
- An upload refused by a reverse proxy says so, instead of reporting a generic failure that looks like the panel's own size limit.

### Text and encoding

- The panel is served as UTF-8 and every request body is decoded as UTF-8, which fixed text that appeared as mojibake.
- The pixel font is self-hosted inside `index.html` rather than hotlinked to a third-party CDN, and it now includes the arrow glyphs the panel uses.

### Known limits

- Per-server "actual RAM usage" is read from `/proc`, so it is correct on Linux and reads 0 on Windows. Total RAM is correct on both.
- SpiGet publishes no dependency data, so SpigotMC addons cannot list dependencies. Hangar dependencies are resolved by name search, so an ambiguous name could match the wrong project.
- The `hilltty`, `etil` and `benchmarked` flag presets contain options removed from modern JVMs and will not start on JDK 17 and newer. The panel warns about each of them.

## 1.0.2

Everything below ships in the 1.0.2 build. It includes the work that was previously published as replacement `1.0.1` jars.

- Plugins can be searched on Modrinth, Hangar or SpigotMC, one source at a time, with manual version and loader filters. The version filter defaults to “Any version”, so plugins whose authors have not tagged the newest Minecraft release still appear.
- The Plugins and Mods tabs put search on top, and the installed list shows three entries with their icons before a View More button.
- A server can hold more than one port. Extra ports are reserved so nothing else claims them, they are shown on the Startup tab, and one of them can be bound to Geyser’s Bedrock port.
- The panel title can be renamed in Settings, and the version badge can be hidden there.
- “Automatic shutdown in” only appears for servers that have it enabled.
- File rows highlight on hover, file icons are solid and chosen by type, every control is themed, and the Minecraft font now covers the whole panel.
- Server names in Manage Server are shown without their underscores.

- Permission repair now records the current machine. A copied server folder, including one carrying an older marker, is repaired again instead of being skipped.
- Plugin and mod archives are stored separately and can no longer appear as, replace, or delete full server backups.
- Restoring an older backup keeps that archive while the automatic pre-restore backup is created.
- Backup actions target the selected archive when the same name exists in both current and older backup folders.
- The audit log records file, config, backup, plugin, and version changes.
- The panel shows its version, and failed backup writes no longer leave partial archives.
- A server name may contain spaces. Rootjar saves them as underscores, so `Sky Block` becomes the folder and command `Sky_Block`.
- The host console command `list` shows the player count for every running child server.
- Console commands and server names are not case-sensitive, so `sky_block.restart` controls `Sky_Block`.
- Safe mode moves one plugin or mod aside after repeated crashes, and it can also be started from the panel.
- Update all previews plugin and mod updates, then replaces only the selected jars after confirmation.
- Files can show the largest files and folders. Each server can save its own daily backup time.
- The config editor can review unsaved changes before saving.
- Discord bots do not require a port. Escape closes an open panel popup.
- Rootjar corrects `server-port` in `server.properties` before a Minecraft server starts.
- The Minecraft-style font includes lowercase letters.
- A stopped server can install a Modrinth `.mrpack`, including its server mods and override files.
- Crash diagnosis shows the latest error and names the plugin or mod identified in the log.
- Operators and the whitelist can be edited from the Config tab.
- The console recalls earlier commands with the up and down arrow keys.
- An admin can change the panel password from Settings.
- Rootjar creates a full backup before a modpack install or Update All.
- Modrinth modpacks skip client-only mods.
- Operator and whitelist names are saved with their Mojang UUIDs.
- Crash diagnosis can move aside the exact plugin or mod named in the log.
- The audit log can be searched by user, action, or details.
- The dashboard can switch between a compact server list and the original cards. The selected layout is remembered.
- Server names display spaces in the panel. `Sky_Block` appears as `Sky Block`.
- Each server can save a custom IP or domain. `stellarfall.net` is shown as `stellarfall.net:25569` instead of the numeric IP.
- The file editor highlights YAML, Python, JSON, JavaScript, HTML, CSS, XML, properties, TOML, and shell files. Its language menu can change the highlighting without renaming the file.
- Each server can set an idle shutdown time in minutes, hours, or days. It stops after that time with no players, and the countdown pauses while a player is online.
- Settings opens as a menu. General, Nodes, Ports and Limits, Password, Schedules, and Audit Log each open in their own editor.
- Operators and the whitelist are managed from Players. Select a player name, add them, then remove them or change an operator level.
- Start, Stop, Restart, and Kill highlight when the pointer is over them.
- Uptime, automatic shutdown, players, and status have separate spacing in the server sidebar. CPU no longer shows a fixed 200% limit.
- Changing a server's port in Edit Server writes the new value into `server.properties` immediately, so the old allocation is no longer rejected.
- The Java Runtime section can download any Temurin release from Java 8 to 25, offers **Use recommended**, and warns when the runtime is newer than the Minecraft version supports. New servers are pinned to an installed runtime matching their version when one exists.
- Java downloads now replace the JDK's relative symlinks with real copies, so Temurin archives containing `legal/` links no longer fail with "refusing symlink".
- A new Startup tab holds the Java runtime, JVM flag presets, the jar name, stop command, strategy and timeout, auto start, idle shutdown, and a read-only preview of the command Rootjar will run.
- JVM flag presets include Aikar's low, medium and high, Proxy Optimized, Safe-Mode and Folia Optimized, plus Custom.
- Additional presets are taken verbatim from their published sources: MeowIce/meowice-flags, brucethemoose/Minecraft-Performance-Flags-Benchmarks, hilltty/hilltty-flags, Obydux/Minecraft-GraalVM-Flags and etil2jz/etil-minecraft-flags. Selecting one shows a warning when it needs a JVM other than Temurin.
- Creating a server now selects an installed runtime matching the required Java, and downloads and pins it automatically when none is installed.
- Modpack installation has its own Modpacks section instead of living inside Mods.
- Replacing `server.jar` now refreshes `index.html` when the copy on disk is older, so the panel stops serving a stale UI after an upgrade. The previous copy is kept as `index.html.old`.
- File uploads show a progress window with the overall percentage, the current file, a cancel button and any per-file errors, instead of failing silently.
- The Activity tab now shows everything a server did: power actions, console commands, file and folder opens, reads, writes, uploads, downloads, deletes, renames, moves, copies, archives, extracts, trash and restore, config reads and saves, startup and Java changes, version changes, backups, plugins, mods, modpacks, player actions and access list edits. Events are labelled Pterodactyl-style (`server:sftp.write`) with a readable description and a relative time, and the tab can be searched. IP addresses are masked.
- Panel-level events (sign-in, failed sign-in, settings, users, schedules) remain in the Settings audit log, which now uses the same cards.
- Before a Minecraft server starts, Rootjar also aligns `query.port` with the assigned port and gives RCON its own free port, so copied values never collide.
- Before starting Paper or a fork, Rootjar recreates `world/datapacks/bukkit/pack.mcmeta` if a copied or restored world is missing it, which caused `Could not initialize Bukkit datapack`.
- The first-boot Panel Setup card lays its fields out properly instead of overlapping.
- Server names read with spaces everywhere in the panel, including the console header, the file breadcrumb and Edit Server. The stored id, folder name and console commands keep their underscores.
- Refreshing the player count no longer prints `There are 0 of a max of 20 players online` to the console. A `list` command you type yourself is still shown.

## Building

Build with JDK 17 or newer:

```bash
./build.sh
```

The panel page is the root `index.html`. The build copies it into the jar, and the first run writes it beside `server.jar` when it is missing.

`scripts/m1-accept.sh` runs the packaged jar against disposable test servers. It checks configuration rejection, console routing, start and stop order, signals, forced shutdown, and crash restart behavior.
