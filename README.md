# EventTrail

EventTrail is a server-side audit, logging and investigation mod for Minecraft servers. It records player and world
activity, keeps the history in a database, and gives staff inspect and lookup tools.

EventTrail records and investigates history. It does not roll back or restore the world; use normal server and world
backups for that.

## Status: pre-release, not yet audited

EventTrail has not been released. Its code has not yet been through its security and release audit, and it is not
for public servers. Nothing is released (no download, no tag, no CurseForge file) until that audit gives the
go-ahead.

To report a security problem, see [SECURITY.md](SECURITY.md). Please do not post it in a public issue.

## Target

- Minecraft 1.21.1
- NeoForge 21.1.221 or newer within 21.1.x (the declared range; tested so far on 21.1.221, 21.1.247 and 21.1.248)
- Java 21
- Mod ID `eventtrail`

EventTrail runs on the server only; players do not install it. Other Minecraft versions and loaders are future work.

## Installation (private test servers only)

Put the main EventTrail jar in the dedicated server's `mods` folder; do not install development helper or test
jars. On first start EventTrail creates `config/eventtrail-server.properties` and, with the default SQLite backend,
`world/eventtrail/eventtrail.db`. Review the permissions and database settings before giving staff access.

## What it records

- block breaks and placements, with the block state and available block-entity data;
- items players put into and take out of containers;
- container opens and closes, when the server can prove the exact menu and target;
- entity kills and player deaths;
- item drops and pickups; and
- explosions.

Container recording covers vanilla containers and some storage mods. It records only transfers it can attribute to a
player; automation is not attributed to a player. Support for other mods is version-specific and is still being
tested; it is not a claim of compatibility with every storage mod.

## Commands

```text
/eventtrail inspect
/eventtrail lookup ...
/eventtrail page <num>
/eventtrail status
/eventtrail maintenance status
```

`/et` is a convenience alias for the same commands when no other mod has registered it. There is no rollback, undo,
player-search or teleport command.

- `/eventtrail inspect` turns inspect mode on or off. While it is on, right-click a block to see its history. Crouch
  and right-click a block face to inspect the air position next to it. `/eventtrail page <num>` shows more pages.
- `/eventtrail status` is read-only. It reports whether EventTrail is recording, the storage state and the audit
  write backlog, without showing connection secrets.
- `/eventtrail maintenance status` is read-only and reports retention and database clean-up progress.

### Lookup

A lookup starts with a scope and then a time window; both are required, in that order:

- `radius:<blocks>` searches around where you stand when you run the command, up to `lookup.maxRadiusBlocks`;
- `world:<dimension>` searches one dimension (needs the world lookup permission);
- `global` searches all dimensions (needs the global lookup permission);
- `time:<duration>` is a whole number followed by `s`, `m`, `h`, `d` or `w`.

Optional filters, in any order: `player:`, `action:`, `block:`, `item:`, `entity:`, `mod:` and `per-page:` (1 to
50; the default is 5). Each option may be given once. Values in one comma list are ORed; different filters are
ANDed.

The actions are `break`, `place`, `container_open`, `container_closed`, `container_deposit`,
`container_withdrawal`, `kill`, `death`, `drop`, `pickup` and `explosion`. Leaving out `action:` matches every
action. Deposits and withdrawals are shown as **Added Item** and **Removed Item**.

`block:` matches the block of break, place and explosion rows, and a name in quotes also matches the container of
open, close, deposit and withdrawal rows by the name it was logged with. `item:` matches the item of drop, pickup,
deposit and withdrawal rows and the weapon of a kill. `entity:` matches the victim, the killer or the "via" entity of
kill and death rows. Each takes one value or a comma list:

- an ID without quotes is exact: `minecraft:oak_log` is that ID only, and `oak_log` is that ID in any mod, so it
  never finds `minecraft:stripped_oak_log`;
- a name in `"..."` or `'...'` is the whole name, ignoring case: the thing's normal name or the name it was logged
  with, custom names included, so `item:"Diamond"` also finds dirt renamed "Diamond";
- `*` is the only wildcard, in IDs and names: `block:*_log`, `item:"diamond*"`.

`mod:` takes mod IDs (lowercase, as the mod names itself, with `minecraft` for the game) and matches an entry when
any block, item or entity it names is from that mod; entries of a removed mod still match. Tab lists the installed
mod IDs. `name:` was removed; use `block:`, `item:` or `entity:` with the name in quotes.

```text
/eventtrail lookup radius:50 time:2h
/eventtrail lookup world:minecraft:overworld time:1d player:Steve
/eventtrail lookup global time:30m action:break per-page:50
/eventtrail lookup radius:25 time:4h block:minecraft:oak_log,birch_log
/eventtrail lookup radius:50 time:1d action:break,place block:"Oak Iron Chest"
/eventtrail lookup global time:1h item:"Lucky Pick" mod:minecraft
/eventtrail lookup global time:1h action:container_deposit,container_withdrawal
```

Coordinates in lookup results can be clicked to teleport, for players with `command.eventtrail.teleport`. The link
is short-lived and bound to the player who received it.

## Permissions

```text
command.eventtrail.inspect
command.eventtrail.lookup
command.eventtrail.page
command.eventtrail.status
command.eventtrail.lookup.world
command.eventtrail.lookup.global
command.eventtrail.lookup.extended
command.eventtrail.teleport
```

`command.eventtrail` only makes the command root visible; it grants nothing else. `command.eventtrail.status` also
covers `/eventtrail maintenance status`. `command.eventtrail.lookup.extended` matters only when
`lookup.maxDaysWithoutExtendedPermission` is set above 0: it then allows longer time windows, and grants nothing
else.

EventTrail uses one permission source for players, chosen at server start. By default (`permissions.source=auto`)
it uses the first that applies:

1. LuckPerms, when it is installed. An unset node is denied, and operator status does not bypass it.
2. Another NeoForge permission handler, when LuckPerms is not installed and NeoForge's active handler is not its
   default one (`neoforge:default_handler`). That handler decides through NeoForge's permission API.
3. FTB Ranks, when neither of the above applies and FTB Ranks is installed. Only a value set on exactly the node
   being checked counts; other nodes fall back to the operator check.
4. The operator check: operators are allowed, other players are not.

`permissions.source` can also be `luckperms`, `neoforge`, `ftbranks` or `operator` to use only that source. If the
chosen source is not installed, or the value is not known, EventTrail uses the operator check and logs a warning; it
never falls back to another permission mod on its own. A change applies after a restart.

The server console and RCON are always allowed. Command blocks, functions, fake players and other non-player sources
are denied. At start-up EventTrail logs one line naming the permission source and what chose it, for example:

```text
EventTrail permissions: LuckPerms (unset nodes are denied), chosen by auto
```

FTB Ranks also gates commands by itself, under names built from the command path (such as `command.eventtrail` and
`command.eventtrail.lookup`). A value an FTB Ranks rank sets on such a name, or on a parent name, allows or denies
that command for that player whatever EventTrail's source says. To keep LuckPerms in charge on a server with both,
leave every `command.eventtrail` name, and broad entries such as `command` or `command.*`, out of the FTB Ranks rank
files.

## Storage

- SQLite, the default, with no database server to set up;
- MySQL Server 8.4 or newer; and
- PostgreSQL.

MariaDB is not supported. The JDBC drivers and connection pool are inside the EventTrail jar.

When several Minecraft servers share one MySQL or PostgreSQL database, give each a different `database.serverId`.
If a chosen MySQL or PostgreSQL backend cannot start, EventTrail does not switch to SQLite on its own. TLS to MySQL
and PostgreSQL is supported; when it is on, EventTrail verifies the database server's certificate and host name.

SQLite keeps its data under the world folder (`eventtrail/eventtrail.db`). Keep that database on a local disk, used
by EventTrail only. Do not delete its `-wal`, `-shm` or `-journal` files or the `eventtrail.db.eventtrail.lock`
file.

Accepted entries wait briefly in a bounded in-memory queue before they are written. When the database cannot
take them yet, EventTrail keeps them in a disk spool under the world folder (`eventtrail/spool/`) and writes them
later, after a restart too. The spool is on by default (`database.spool.enabled`; its size cap is
`database.spool.maxMegabytes`). Actions are dropped only when the disk spool is full, when the drive it is on has
less than 512 MiB free, or when they arrive faster than the disk can take them for long enough to fill the memory set
aside for them. Each case has its own WARN. On a server with little memory, the memory set aside is lowered at
startup with a WARN; the disk spool keeps its configured size. Entries that had reached neither the spool nor the
database when the server stops abruptly can be lost.

### When recording stops

The console message `AUDIT LOGGING DISABLED - [CODE]` means EventTrail is not recording new events, and events
missed meanwhile are not filled in later. Run `/eventtrail status` for the reason and the safe next step. Keep the
code and the whole database, files included, before you change anything; do not create an empty database in its
place or switch backends to get past it. Recording is back only when EventTrail says so and `/eventtrail status`
confirms it.

## Configuration

The server's settings are in `config/eventtrail-server.properties`, created on first start. Main settings:

- `retention.days` (90; 0 turns off clean-up of old entries);
- `lookup.maxRadiusBlocks` (500);
- `lookup.maxDaysWithoutExtendedPermission` (0: no limit);
- `inspect.pageSize` (5);
- `permissions.source` (not written by default; `auto` when absent);
- `database.backend` (`sqlite`, `mysql` or `postgresql`), `database.serverId`, and each backend's connection and
  TLS settings; and
- the `recording.*.enabled` switches, all on by default, which turn off single kinds of entry.

Keep database passwords only in the server's own configuration file.

## Building from source

You need a Java 21 JDK. The Gradle wrapper in this repository downloads Gradle itself.

Windows:

```text
gradlew.bat build
```

Linux and macOS:

```text
./gradlew build
```

The first build downloads Gradle, NeoForge and the Minecraft development files, so it needs internet access and takes several minutes. The mod jar is written to `neoforge/build/libs/` as `EventTrail-1.21.1-NeoForge-<mod version>.jar`, and that folder holds only this jar.

## Running a development server

Run `gradlew.bat runServer` (Windows) or `./gradlew runServer` (Linux and macOS). The server runs in `run/server/`. On the first run, accept the Minecraft EULA by setting `eula=true` in `run/server/eula.txt`, then run the task again. Its world name, port, and other server settings belong to the ignored local `run/server/server.properties`. EventTrail is loaded from the Gradle project, so do not also copy a built jar into `run/server/mods/`. For LuckPerms testing, place a NeoForge 1.21.1 LuckPerms jar in `run/server/mods/`.

For IntelliJ IDEA, open the repository as a Gradle project and use the `runServer` task for local debugging.

## Licence

EventTrail is licensed under the [Apache License 2.0](LICENSE). Bundled third-party components keep their own
licences.
