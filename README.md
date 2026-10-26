# Rootjar

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
| `help` | Show the command list |
| `stop` | Stop every child, then stop Rootjar |
| `velocity plist` | Send `plist` to the child named `velocity` |
| `paper1.start` | Start one child |
| `paper1.stop` | Stop one child |
| `paper1.restart` | Restart one child |
| `paper1.mute` | Hide that child in the host console |
| `paper1.unmute` | Show that child in the host console again |

Unrecognized text goes to the first child whose console is enabled. Child names are reread for every command, so a server created in the panel can be controlled immediately.

## Previous and Next Node buttons

The panel can show two optional links:

- **Previous Node**, fixed at the bottom left.
- **Next Node**, fixed at the bottom right.

An admin pastes each destination into Settings and enables its checkbox. Clicking a button opens that saved `http` or `https` address. These are navigation links only. Their settings are stored in `node-buttons.properties` beside `config.yml`.

## Building

Build with JDK 17 or newer:

```bash
./build.sh
```

The panel page is the root `index.html`. The build copies it into the jar, and the first run writes it beside `server.jar` when it is missing.

`scripts/m1-accept.sh` runs the packaged jar against disposable test servers. It checks configuration rejection, console routing, start and stop order, signals, forced shutdown, and crash restart behavior.
