# VibeCraft Plugin

VibeCraft is a Paper plugin that provides an in-game Claude terminal/chat workflow and a client-mod bridge via plugin messages.

## Requirements

- Paper 1.21.4
- Java 21
- Optional: VibeCraftMod client for rich mod-side UI

## Build

From this folder:

```powershell
./gradlew.bat build
```

Output jar:

- build/libs/VibeCraft-1.0-SNAPSHOT.jar

## Install

1. Copy the built jar into your server plugins folder.
2. Start/restart the server.
3. Ensure the command is present: /claude (aliases: /c, /ai).

## Core Features

- In-game `/claude` terminal style UI.
- Session and history handling per player.
- Repo onboarding and selection helpers.
- Optional mod integration over `vibecraft:events` and `vibecraft:input` channels.
- UI schema export/merge support for mod-driven screens.
- Plugin-scoped UI settings and styling for multi-plugin coexistence.
- Schema-driven buttons can invoke client internal actions via `invoke_internal`.
- Schema overlays can define text, bars, icons, and item slots on the client.

## Commands

- `/claude`
- `/claude <message>`
- `/claude --path <dir> <message>`
- `/claude repo show`
- `/claude repo set <path>`
- `/claude repo new <name>`
- `/claude reset`
- `/claude book`

## Configuration

`plugins/VibeCraft/config.yml` includes paths such as:

- `claude-path`
- `server-plugins-dir`
- `restart-flag-path`
- `vibecraft-dir`
- `server-dir`

## Mod Bridge Notes

- Plugin registers outgoing channel: `vibecraft:events`
- Plugin registers incoming channel: `vibecraft:input`
- Non-mod clients can still use chat/terminal behavior; they just ignore mod events.
- Schema payloads should include `plugin`/`namespace` so client state stays isolated per plugin.
- History/settings payloads can include optional ordering metadata (`seq`, `sequence`, `revision`, or `version`) for conservative client-side replay protection.

## Schema Authoring Notes

- Use `invoke_internal` when a schema button should call a built-in client function.
- Keep `plugin` set on overlay definitions and screen actions so the client can route them correctly.
- Prefer plugin-scoped setting keys for anything that should not bleed into another plugin's UI.
- Unknown widgets/actions fall back safely, so schemas can be extended incrementally.

## Troubleshooting

If plugin does not enable:

1. Confirm Java 21 runtime for server.
2. Confirm Paper 1.21.4 compatibility.
3. Check `plugin.yml` still defines command `claude`.
4. Check server log for startup path/config errors.

If mod UI is missing:

1. Verify client has VibeCraftMod + Fabric setup.
2. Verify plugin channels are registered and no packet-size errors are reported.
3. Confirm schema payloads include the correct plugin/namespace for the screen being opened.
