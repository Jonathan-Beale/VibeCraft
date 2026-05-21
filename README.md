# VibeCraft Plugin

VibeCraft is a Paper 1.21.4 plugin that provides an in-game interface for interacting with an AI coding assistant (Claude CLI or Hermes). It manages AI sessions, streams responses into a custom inventory-based terminal UI and the player's chat, relays events to the VibeCraftMod client via plugin channels, and automates build/deploy workflows for registered plugin projects.

## Requirements

- Paper 1.21.4
- Java 21
- Optional: VibeCraftMod client mod for rich mod-side UI

## Build

```bash
./gradlew build       # Linux
./gradlew.bat build   # Windows
```

Output jar: `build/libs/VibeCraft-1.0-SNAPSHOT.jar`

## Install

1. Copy the built jar into the server's `plugins/` folder.
2. Start or restart the server.
3. Verify the command is present: `/claude` (aliases: `/c`, `/ai`).
4. The plugin requires operator permission; only ops can run `/claude`.

## Core systems

### AI provider

`ClaudeSession` drives all AI interaction. It supports two providers, selected by `ai-provider` in `config.yml`:

- **`claude`** — Invokes the Claude CLI as a subprocess (`--print --dangerously-skip-permissions --verbose --output-format stream-json`). On the first message per session a custom system prompt is injected; subsequent messages use `--continue` to resume the existing session. The stream-json output is parsed event-by-event into `TerminalEvent` instances.
- **`hermes`** — Invokes the Hermes CLI (`hermes chat -q "<message>" -Q`). Hermes output is not streamed; the full response is collected and emitted as a single `ClaudeText` event.

Session timeout is 120 seconds for both providers.

### System prompt

When starting a new Claude session, a detailed system prompt is injected automatically. It instructs the assistant to:

- Act autonomously: build, deploy, reload, and restart without asking the user.
- Use the correct per-plugin reload flow (plugin-specific RCON command for EnchantForge, restart-flag for VibeCraft).
- Follow a YAML-first / ECS architecture for all plugin development.
- Use RCON (`mc.sh` / `mc.ps1`) for live debugging.
- Use the `AskUserQuestion` tool for multiple-choice decisions routed through the mod UI.

### Terminal UI (`ClaudeTerminalUI`)

`/claude` with no arguments opens a 54-slot inventory used as a scrollable chat terminal. The upper 45 slots display conversation messages as item stacks (Paper for user messages, Written Book for AI responses, Gray Dye for thinking blocks, colored stained glass for tool calls, Comparator for bash output, Command Block for system messages). The bottom row (slots 45–53) contains controls:

| Slot | Control |
|------|---------|
| 45 | Scroll newer (▲) |
| 46 | Scroll older (▼) |
| 48 | Compose — closes inventory, intercepts the next chat message |
| 50 | Reset session |
| 52 | Current repo (info only) |

All clicks and drags inside the inventory are cancelled by `TerminalInputListener`. When compose mode is active, the player's next chat message is intercepted and sent to the AI; typing `/cancel` returns to the terminal.

### Chat mode

When the terminal is not open, AI events appear directly in chat:

- `stream_start` — action bar spinner
- `tool_call` — action bar update (color-coded by tool) and optional chat line
- `bash_output` — optional chat lines
- `thinking` — optional chat preview (first 120 chars)
- `claude_text` — up to 4 preview lines; a clickable `[▼ N more lines]` link triggers `/claude book`
- `stream_end` — clears action bar, shows quick-action bar (Reply / Terminal / Reset)

Each category is gated by a per-player setting (`chat.user_messages`, `chat.claude_text`, `chat.tools`, `chat.bash`, `chat.thinking`).

### Session and history

`ClaudeSession` tracks whether a Claude session exists on disk per working directory. On first use of a directory a fresh system-prompted session is started; on subsequent uses `--continue` resumes it.

`SessionHistory` persists conversation entries to `<data>/sessions/<uuid>-<provider>.hist` (TSV, one entry per line). Entry types: `USER`, `CLAUDE`, `HERMES`, `THINKING`, `TOOL`, `BASH`, `SYSTEM`. History is replayed into the terminal on first open and sent to the mod on join.

Session logs are also written to `<data>/sessions/<playerName>-<date>.log` as plain text.

### Mod bridge

On enable, the plugin registers:

- **Outgoing:** `vibecraft:events` — JSON events pushed to VibeCraftMod
- **Incoming:** `vibecraft:input` — JSON messages received from VibeCraftMod

`ModInputListener` handles incoming messages routed to `plugin: "vibecraft"`:

| Incoming type | Action |
|---------------|--------|
| `message` field present | Submit text as an AI query |
| `request_history` | Send UI schema + history + settings to the client |
| `set_setting` | Persist a player setting by key/value |
| `clear_history` | Clear history file and notify mod |

`ClaudeCommand` sends these event types over `vibecraft:events`:

| Outgoing type | Content |
|---------------|---------|
| `stream_start` / `stream_end` | Session lifecycle markers |
| `user_message` | Player's message text |
| `thinking` | Incremental thinking delta |
| `tool_call` | Tool name + detail |
| `bash_output` | Lines array |
| `claude_text` | Lines array (legacy-section-encoded) |
| `question` | Prompt string + options array |
| `ui_schema` | Merged schema object |
| `history` | Legacy entry array (for older mod clients) |
| `history_chunk` | Chunked entry array with `done` flag |
| `settings` | All player settings as key-value pairs |

Plugin message payloads are capped at 30 000 bytes. Oversize payloads are split line-by-line or truncated with a binary-search fit. Events that cannot be made to fit are dropped with a warning.

On player join (after a 40-tick delay), the plugin sends the full UI schema, history, and settings to the mod. `PlayerJoinListener` also cleans up in-memory state on quit.

### UI schema and `PluginUIRegistry`

At startup the plugin saves `ui/main.json` from its jar to the data folder. `PluginUIRegistry.buildMergedSchema()` reads VibeCraft's own `ui/main.json`, then scans every other enabled plugin for `<pluginDataFolder>/ui/main.json`. Screens and overlays from other plugins are merged in with a `plugin` field injected. The current `ai-provider` is added as a top-level `provider` field. The merged schema is sent as `ui_schema` on join and on `request_history`.

The bundled `ui/main.json` defines a single screen (`vibecraft:claude`) containing a toolbar, history panel, question-options widget, text input, and a help modal. It also declares keybinds and two settings tabs (Settings and Colors).

### Player settings (`PlayerSettings`)

Per-player settings are stored in `<data>/player-settings/<uuid>.yml`. Settings keys and defaults:

| Key | Default |
|-----|---------|
| `chat.user_messages` | `true` |
| `chat.claude_text` | `true` |
| `chat.tools` | `false` |
| `chat.bash` | `false` |
| `chat.thinking` | `false` |
| `hud.lines` | `1` |
| `ui.thoughts_visible` | `true` |
| `ui.color_scheme` | `terminal` |
| `color.user` | `55FF55` |
| `color.claude` | `55FFFF` |
| `color.tool` | `FFAA00` |
| `color.output` | `888888` |
| `color.system` | `AAAAAA` |
| `color.question` | `FFFF55` |

Settings are synced to the mod as part of `sendHistoryAndSettingsToMod()`.

### Player data (`PlayerDataStore`)

`players.yml` in the data folder tracks each player's default repository path and the set of directories for which a Claude session exists on disk.

### Build script management (`BuildScriptManager`)

When a player sets or creates a repo, `BuildScriptManager` writes Windows batch scripts to the server directory:

- `build-<Name>.bat` — builds the plugin with Gradle, copies the jar to the plugins folder, and mirrors `src/main/resources/` (excluding `plugin.yml`) to the plugin's data directory using `robocopy`.
- `build-all.bat` — calls every registered per-plugin script in sequence.

Scripts are regenerated on startup and whenever a repo is added or changed.

### Restart watcher (`RestartWatcher`)

A repeating task (every 20 ticks) checks for the existence of the file at `restart-flag-path`. If found, the file is deleted and `Bukkit.getServer().restart()` is called. The Claude system prompt instructs the AI to create this file to trigger restarts after VibeCraft Java changes.

### Project scaffolding

`/claude repo new <name>` creates a new Paper plugin project in the workspace directory. The scaffold copies VibeCraft's Gradle wrapper, writes a minimal `build.gradle.kts` and `plugin.yml`, and generates an ECS skeleton: `EntityManager`, `ArchetypeLoader`, an `ExampleComponent` record, an `ExampleSystem`, and an example YAML archetypes file under `src/main/resources/data/`.

### Markdown renderer (`MarkdownRenderer`)

AI response text is rendered into Adventure `Component` lists. Supported syntax: `# ## ###` headings, `---`/`===` horizontal rules, `>` blockquotes, `- *` bullet lists, `1.` numbered lists, `**bold**`, `*italic*`, `` `code` ``. Lines longer than 4 000 characters are split.

### Mock mode

`/claude mock [--path <dir>] <message>` runs a scripted sequence of synthetic `TerminalEvent` instances (StreamStart, several Thinking chunks, ToolCall, BashOutput, ClaudeText, StreamEnd) without invoking the Claude CLI. This is used to test streaming UX and mod event handling.

## Commands

```
/claude                              Open inventory terminal
/claude <message>                    Send a message to the AI
/claude --path <dir> <message>       Override working directory for this message
/claude mock [--path <dir>] <msg>    Synthetic stream test (no CLI invocation)
/claude repo show                    Show the current default repo
/claude repo set <path>              Set the default repo (must be inside workspace)
/claude repo new <name>              Scaffold a new plugin project in the workspace
/claude reset                        Clear session history and start fresh
/claude book                         Re-open the last AI response in a book GUI
```

All subcommands require operator permission.

## Configuration (`config.yml`)

| Key | Purpose |
|-----|---------|
| `claude-path` | Full path to the Claude CLI executable |
| `hermes-path` | Full path to the Hermes CLI executable |
| `ai-provider` | `claude` or `hermes` |
| `server-plugins-dir` | Destination for deployed plugin jars |
| `restart-flag-path` | Path the AI writes to trigger a server restart |
| `vibecraft-dir` | VibeCraft source directory (used for workspace root and scaffolding) |
| `server-dir` | Server root directory (build scripts are written here) |
| `default-repo` | Server-wide fallback repo for players who haven't chosen one |

## Troubleshooting

If the plugin does not enable:

1. Confirm Java 21 runtime.
2. Confirm Paper 1.21.4 compatibility.
3. Check that `plugin.yml` still declares the `claude` command.
4. Check the server log for startup errors.

If mod UI is missing or stale:

1. Verify the client has VibeCraftMod installed with Fabric.
2. Verify plugin channels are registered (`vibecraft:events` / `vibecraft:input`).
3. Send `request_history` from the mod to force a full schema + history push.
4. Check the server log for dropped oversize payload warnings.
