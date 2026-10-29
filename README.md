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

## 1.0.1

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

## Building

Build with JDK 17 or newer:

```bash
./build.sh
```

The panel page is the root `index.html`. The build copies it into the jar, and the first run writes it beside `server.jar` when it is missing.

`scripts/m1-accept.sh` runs the packaged jar against disposable test servers. It checks configuration rejection, console routing, start and stop order, signals, forced shutdown, and crash restart behavior.
