# MC-Agents

[简体中文说明](README_zh.md)

**MC-Agents** is a [Fabric](https://fabricmc.net/) server-side mod that connects your Minecraft server to large language models (LLMs). It provides in-game commands for AI chat, automatic [Minecraft Wiki](https://minecraft.wiki/) retrieval, per-player conversation memory, and optional control of Carpet-style fake players via structured AI directives.

---

## Requirements

| Component | Version / notes |
|-----------|-----------------|
| **Minecraft** | 1.21.4 (`~1.21.4` in `fabric.mod.json`) |
| **Java** | 17 or newer (Gradle build targets Java 17) |
| **Fabric Loader** | ≥ 0.18.6 |
| **Fabric API** | Required (see `gradle.properties` for the pinned version, e.g. `0.119.4+1.21.4`) |

### Optional but recommended for bot control

- **[Carpet Mod](https://github.com/gnembon/fabric-carpet)** (or another mod that exposes the same `player <name> rejoin` / `player <name> kill` behaviour).  
  MC-Agents does **not** declare Carpet as a Gradle dependency; bot join/leave is implemented by dispatching those commands on the server. Without Carpet (or equivalent), control directives will fail when executed.

---

## Supported languages (in-game UI)

Client-facing strings use Minecraft’s language files:

- **English** — `en_us`
- **Simplified Chinese** — `zh_cn`

Set the game language in the client to switch between them. AI replies follow the behaviour described in `ControlBot.md` (default: same language as the user’s latest message).

---

## LLM API compatibility

The mod speaks **OpenAI-compatible Chat Completions** over HTTP:

- **Default endpoint** — `https://api.openai.com/v1/chat/completions` (configurable).
- **Protocol** — JSON `POST` with `Authorization: Bearer <api_key>`, `Content-Type: application/json; charset=utf-8`, body includes `model`, `stream`, and `messages` (roles: `system`, `user`, `assistant`).
- **Streaming** — Server-Sent Events (`data: …` chunks) are used first; on HTTP **400**, the client falls back to a non-streaming request.
- **Models** — Any provider that implements the same schema (OpenAI, many proxies, compatible gateways) should work. DeepSeek-style model names are normalized internally (`deepseek-chat` / `deepseek-reasoner` aliases).

### Context window size

Total context length is **resolved via [OpenRouter](https://openrouter.ai/)** (`GET https://openrouter.ai/api/v1/models`) using `openrouter_api_key` if set, otherwise **`api_key`**, to match the configured `model` and drive token budgeting and progress display. Ensure at least one of these keys can access OpenRouter’s model list for your chosen model id.

---

## Configuration

On first server start, a file is created next to `server.properties` (server root):

**`macagent.txt`** (key `=` value, lines starting with `#` are comments):

| Key | Description |
|-----|-------------|
| `api_url` | Chat Completions URL (default: OpenAI). |
| `api_key` | Bearer token for `api_url`. |
| `openrouter_api_key` | Optional separate key for OpenRouter `/models`; if empty, `api_key` is used. |
| `model` | Model id (default in code: `gpt-4o-mini`). |

Reload without restart: **`/agent reload`** (permission level **2**).

> **Security:** Keep `macagent.txt` out of public backups and version control; it contains secrets.

---

## System prompt and bot records

- **Control bot behaviour** is defined by **`src/main/java/com/mcagents/prompt/ControlBot.md`** (loaded from the server working directory: the mod searches upward from the world folder for that path).
- **Bot name ↔ tag** mappings are stored in **`macagent/agent_records.json`** (JSON array of `{ "bot_name", "tag" }`). The AI receives a truncated copy of this library in the system prompt for join/leave decisions.

Legacy paths may be migrated automatically (`agentsdata`, world `data/`, etc.) — see `Agent.java` / `Recordbot.java`.

---

## Commands (players)

| Command | Description |
|---------|-------------|
| `/agent ask <prompt>` | Send a prompt to the LLM with per-player conversation history. Supports **compound prompts** (multiple lines, or separators like `然后`, `；`, `+`, etc.) executed **sequentially**. |
| `/agent <prompt>` | Same as `/agent ask <prompt>`. Literal subcommands (`search`, `new`, `record`, …) are matched first, so this only applies when the first token is not a reserved keyword. |
| `/agent search <query>` | Fetch Minecraft Wiki excerpts (EN/zh) and ask the model to summarize. |
| `/agent new` | Clear the current player’s conversation state. |
| `/agent record <bot_name> <tag>` | Append a bot record. |
| `/agent record remove …` | Remove records (by bot+tag, by bot name, or all). |
| `/agent botlist` | List recorded bots. |
| `/agent reload` | Reload config (ops / permission level 2). |

`ask`, `search`, and `new` require a **player** executor (not console-only in the current implementation).

---

## Features (summary)

- **OpenAI-compatible chat** with **SSE streaming**, optional non-streaming fallback, and handling of `reasoning` / `thinking`-style fields where present.
- **Per-player chat history** with rough token estimation and a **context usage bar** when max context is known.
- **Minecraft Wiki integration** — MediaWiki API against **minecraft.wiki** and **zh.minecraft.wiki** (auto pick by Latin vs CJK in the query; fallback to the other wiki if needed).
- **Knowledge-style `/agent ask`** — For wiki-like questions, the server runs a **keyword-extraction** call (`WIKI_QUERY: …`), **searches** Minecraft Wiki, injects excerpts, then the model answers **once** from that context (no extra “answer first, wiki supplement” round-trip from the server).
- **`[MC_WIKI_SEARCH]` lines** — If the model outputs them, they are stripped from chat display and from committed history; the server does **not** enqueue chained wiki follow-up requests.
- **Compound prompts** — “Conversation complete” and **context usage** messages are shown **once**, after the **last** sub-prompt finishes.
- **Carpet bot control** — If the model outputs lines such as `[CONTROL_BOT] join <tag_or_name>` or `[CONTROL_BOT] leave …`, the server parses them and runs Carpet `player` subcommands (`rejoin` / `kill`), with **rate limits** and **cross-player conflict avoidance** for the same bot name. Targets resolve by **tag** from `agent_records.json` or direct **bot name** (3–16 chars, `[A-Za-z0-9_]`).
- **Sanitization** — Control directives and wiki directive lines are hidden from normal displayed text; prompt-leak patterns are reduced for “thinking” streams.

---

## Data layout (typical)

```
<server root>/
  macagent.txt              # API config
  macagent/
    agent_records.json      # bot_name / tag records
```

---

## Building

```bash
./gradlew build
```

The remapped mod JAR is under `build/libs/`. CI (`.github/workflows/build.yml`) runs `./gradlew build` on release events.

---

## Project metadata

- **Mod id:** `modid` (internal identifier; consider renaming for public release).
- **Maven group / version:** see `gradle.properties` (`maven_group`, `mod_version`).
- **Author:** Cherry Fu (see `fabric.mod.json`).

---

## License

See the `LICENSE` file included in the repository (referenced by the Gradle `jar` task).
