# DeathReplay

A [Paper](https://papermc.io) plugin that records the last few seconds around every player and,
when someone dies, lets **admins replay the death** – who hit whom, with what, and how it actually went down.
Great for settling "that was a cheater!" disputes and for moderation in general.

Only operators, or players with the `deathreplay.use` permission, can play replays.
Regular players cannot start a replay or see any notifications.

> **Status:** early version (1.0.0). Please open an issue if you run into problems on your server version.

## Features

- Continuous ring-buffer recording (default: last 10 seconds) around every player
- Records the victim, nearby players, mobs and arrows, including armor, held items, swings and hits
- Replays are private: only the admin watching sees them, nothing changes in the real world
- Playback controls: pause, slow motion, seek, restart, and three cameras (victim, killer, free)
- Clickable `[▶ Play]` notification for staff on every death
- Replays are saved to disk (gzip) and survive restarts; old ones are cleaned up automatically
- Staff in vanish (`vanished` metadata, as set by SuperVanish / PremiumVanish / Essentials) never appear in replays

## Requirements

- Paper 1.21.x (not Spigot – the plugin uses Paper APIs)
- Java 21

## Installation

1. Download `DeathReplay.jar` from the [Releases](../../releases) page (or from the latest workflow run under *Actions*).
2. Put it into your server's `plugins/` folder and restart.
3. Edit `plugins/DeathReplay/config.yml` if you want to change the defaults.

## Permissions

| Permission        | Default | Description                                                          |
|-------------------|---------|----------------------------------------------------------------------|
| `deathreplay.use` | OP      | Use `/replay` and receive a clickable notification on every death    |

Give it to another admin, for example with LuckPerms:

    /lp user <name> permission set deathreplay.use true

## Commands

Alias: `/dreplay`

| Command                              | Description                                                   |
|--------------------------------------|---------------------------------------------------------------|
| `/replay` or `/replay list [player]` | List recent deaths (with clickable `[▶]`)                     |
| `/replay play <id>`                  | Start a replay (switches you to spectator mode)               |
| `/replay pause`                      | Pause / resume                                                |
| `/replay speed <0.1-4>`              | Playback speed                                                |
| `/replay seek <seconds>`             | Jump forwards / backwards (negative = back)                   |
| `/replay cam <victim\|killer\|free>` | Switch camera                                                 |
| `/replay restart`                    | Start again from the beginning                                |
| `/replay stop`                       | End the replay; you return to your original spot and game mode |
| `/replay delete <id>`                | Delete a saved replay                                         |

## Configuration

```yaml
record:
  duration-seconds: 10   # how far back the recording goes
  interval-ticks: 2      # 1 = smoother, uses more memory
  radius: 24             # how far around the victim other entities are recorded
  max-entities: 20       # max entities per snapshot
storage:
  max-replays: 50        # oldest replays are deleted first
  keep-days: 14          # replays older than this are deleted on startup
notify-staff: true       # clickable message for staff on every death
```

## How it works

- Every `interval-ticks` a snapshot is stored for each player: position, view direction, pose, items, armor,
  swings, hits, plus nearby players, mobs and arrows. Only the last `duration-seconds` are kept in a ring buffer.
- On death the buffer is saved as a replay (`plugins/DeathReplay/replays/<id>.replay`, gzip) and staff are notified.
- During playback, real entities are spawned that are visible **only** to the admin
  (`setVisibleByDefault(false)` + `Player#showEntity`). Players are shown as armor stands wearing the player's
  head (skin) and armor; mobs are the same mob type with AI disabled.
  Nothing in the real world is changed and no one else sees anything.
- Spectators (including admins watching a replay) are never recorded.

## Limitations

- Players are rendered as armor stands with head/armor, not as exact player models. Faithful player models would
  require packet-level fake players (e.g. PacketEvents or Citizens).
- Block changes are not recorded – the replay shows entity movement, fights and hits, not building or breaking.
- If another plugin cancels entity spawns (region protection, mob limiters), those entities won't show up in the replay.
- On peaceful difficulty, hostile mobs in a replay may be removed immediately by the server.
- Armor trims, enchantment glint and dyed armor colors are not preserved.

## Building

Requires Java 21 and Maven:

    mvn package

The plugin jar is written to `target/DeathReplay.jar`.
Every push is also built by GitHub Actions (`.github/workflows/build.yml`).

## Creating a release

Push a version tag; the release workflow builds the plugin and attaches the jar to a GitHub release:

    git tag v1.0.0
    git push origin v1.0.0

## Contributing

Issues and pull requests are welcome.

## License

[MIT](LICENSE)
