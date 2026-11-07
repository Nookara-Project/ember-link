# Ember Link: Minecraft to Ember integration (spec v1)

Status: implemented in 0.1.0, with the 0.1.1 backend-console amendment below.

## 0.1.1 backend-console amendment

This amendment supersedes the earlier statements that proxied backends never open a gateway
socket or cannot run `bonfire link`. A proxied Paper/Fabric backend now opens its own authenticated
gateway connection for its status and console only. It has a separate identity and is linked from
its own console as another instance in the same Ember server. Its identify features advertise
`chat:false` and `events:false`; it does not send direct player chat or events or display Ember
chat. Those still pass through Velocity and `bonfire:events` plugin messages. Velocity's
`bonfire:hello` remains player-connection based and is independent of the direct console socket.
The gateway protocol and plugin-message format did not change. See `BACKEND-CONSOLES.md`.

Product name: **Ember Link**. That is the name of the plugin/mod family and of the in-game command `/bonfire`.
The Ember channel type is **"Game server"** (internal type id `game`).

Two sides talk to each other:

- **V**: the Ember server (Nookara, or a self-hosted [ember-server](https://github.com/Nookara-Project/ember-server) instance): game gateway, storage, REST and the Ember UI.
- **J**: this repository: a shared Java core plus Paper/Spigot, Fabric and Velocity front-ends.

Sections 3 (gateway protocol) and 6.6 (plugin messaging) are the only interfaces between V and J.
If either side needs a protocol change, record it in section 10 first.

## 1. Architecture overview

One outbound WebSocket per "root" (a Velocity proxy, or a standalone Paper/Fabric server) to the Ember game gateway
(`wss://<instance>/chat/game-gateway`). The Minecraft side never opens a port.

- **Keypair identity instead of a shared secret.** Each server generates an Ed25519 keypair; the Ember side stores only the public key (section 2).
- **Velocity is the network event root.** Backends send deaths and advancements to the proxy over plugin messages (section 6.6). Since 0.1.1 a backend may also connect directly for its own console.
- **The Ember side pre-renders markdown into "segments"** (section 3.5), so the Java side never parses Ember markdown, mentions or emoji tokens.
- Console lines are not stored as chat messages; the Ember side keeps a short in-memory buffer per server.

---

## 2. Server identity, claiming and abuse controls

### 2.1 Identity (J creates, V verifies)

- On first start the core generates an **Ed25519** keypair (`KeyPairGenerator.getInstance("Ed25519")`, built into Java 15+).
  - It stores the keypair in `identity.properties` in the plugin's data folder:
    - `private-key` = base64 of the PKCS#8 DER
    - `public-key` = base64 of the X.509 SPKI DER
  - The file is created owner-read/write where the OS supports it (`PosixFilePermissions` `rw-------`). It is never logged.
  - `config.properties` and `identity.properties` are separate so people can share their config safely.
- **Public key on the wire:** `key` = standard base64 of the SPKI DER (44 bytes, so 60 base64 characters).
- **Instance id (`iid`):** `"g" + hex(sha256(spkiDer))[0..16]`, for example `g3fa91c0d2be47a10`. Both sides compute it, and V treats its own computation as the authority.
- **Pairing tag:** 6 Crockford-base32 characters, taken from the first 30 bits of `sha256(spkiDer)` bytes 8..11, shown as `XXX-XXX` (for example `K7Q-2M9`).
  - Crockford alphabet: `0123456789ABCDEFGHJKMNPQRSTVWXYZ`.
  - Both sides compute it. V uses it for lookup, and J prints it in the console.
- Deleting `identity.properties` makes a brand new server identity. The old link stays attached to the old (now dead) identity until someone unlinks it in Ember.

### 2.2 Authentication handshake

1. V sends `hello {nonce}`, where the nonce is 32 random bytes in base64url.
2. J sends `identify {key, sig, ...}`.
   - `sig` = base64 Ed25519 signature over the UTF-8 bytes of `"bonfire-gw-v1|" + nonce + "|" + iid`.
3. V checks the signature with `crypto.verify(null, Buffer.from(str), crypto.createPublicKey({key: der, format: 'der', type: 'spki'}), sigBuf)`.
   - On first sight of the key, V inserts a `game_instances` row (trust on first use).
   - Then V replies `ready`.

### 2.3 Unclaimed listing ("Searching for servers...") - a global list

An instance is **unclaimed** when it has no `game_links` row. The searching list shows **every unclaimed server worldwide** that passes these filters. There is no IP matching, because hosted servers would never match.

Listed only when **all** hold:
- connected to the gateway right now, **and** its last heartbeat/status was within the last **3 minutes** (stale sessions drop off);
- not banned, and its session has been up for at least 10 s (a server that connects and immediately drops never flashes into the list);
- the viewer has **Manage server** in the Ember server whose setup screen is open. Nobody else can call the endpoint.

**Optional filters** (the UI has a search box):
- **Pairing tag:** typing `K7Q-2M9` (case-insensitive, dash optional) narrows the list to that one server. This is how a busy list is cut down.
- **Text search** over the sanitised MOTD, platform and version.

**Pagination:** 20 per page, newest-connected first. Ordering is deterministic: `session started_at DESC, iid`.

**Never exposed:** IP, port, player names, plugin list, `public_address`. The only fields are icon, sanitised MOTD, platform, MC version, players online/max (numbers only) and pairing tag.

**Content sanitising** (V, at identify/status time, before anything is stored or shown):
- **MOTD:**
  - at most 2 lines and 20 segments, 120 visible characters total;
  - control and zero-width characters stripped, and runs of whitespace collapsed;
  - colours only as `#rrggbb`, and `obf` is dropped;
  - no `link`/`hover` (MOTDs never carry links);
  - Ember renders it as text, never HTML.
- **Icon:** PNG only (magic bytes checked), at most 48 KiB, decoded and re-encoded by sharp to exactly 64x64 PNG with metadata stripped. Anything that fails is replaced by the default pack icon.
- Staff can ban an instance (2.5), which removes it from the list at once.

**Owner opt-out:** `listing=private` in the J config hides the server from the list unless its exact pairing tag is typed. The default is `listing=public`.

### 2.4 Claim code and proof of ownership

1. The admin clicks a listed server, or "Link without the list".
2. V creates a **claim code**, which is bound to `(bonfire server, admin user, channel cid or null)`. It is **not** bound to the clicked instance.
   - The clicked instance is only a UI hint.
   - The server that *proves* itself by running the command in its own console is the one that gets linked. That defeats MOTD/icon spoofing: clicking an impostor's listing achieves nothing unless you run the code in the impostor's console.
   - When the linked instance differs from the clicked hint, the UI says "Linked <name>, which is the server whose console ran the code".
3. Code format: 8 Crockford-base32 characters shown as `XXXX-XXXX`, about 40 bits of entropy.
   - It is single use and expires after **10 minutes**. V keeps it in memory only, as `Map(codeNormalized -> {sid, userId, cid, hintIid, exp})`, so it is lost on restart.
4. Displayed command: `bonfire link XXXX-XXXX`, with a copy button and a countdown. The note under it reads: "Run this in the server console (or RCON). It won't work from in-game chat."
5. Console-only enforcement (J):
   - Paper/Spigot: sender `instanceof ConsoleCommandSender` or `RemoteConsoleCommandSender`.
   - Fabric: `source.getEntity() == null`, `!source.isPlayer()`, and the source has owner permission (level 4). This excludes command blocks (level 2) and functions.
   - Velocity: `source instanceof ConsoleCommandSource`.
   - Players get: "This command only works from the server console."
6. J sends `link {code}`. V checks, in order:
   1. The code exists and has not expired.
   2. The admin is still a member with Manage server in that Ember server.
   3. The instance is not linked. If it is, the error is `already_linked`, plus the linked server's name when that server is the same Ember server.
   4. The Ember server has fewer than 10 linked instances (`too_many_instances`).

   Then V inserts `game_links`, burns the code, writes an audit row (`kind=link`) and:
   - replies `link_result {ok:true, server_name}` (J prints it in the console);
   - sends `linked` to the session;
   - pushes `game_linked` to the claiming user's sockets;
   - if the code carried a `cid` and that channel is still `game` type with no instance, sets `config.instance = iid` and calls `channelsChanged`.
7. Uniqueness: `game_links.instance_id` is the **primary key**, so one Minecraft instance belongs to at most one Ember server, enforced by the DB. One Ember server may link several instances (cap 10).

### 2.5 Unlinking and revocation

- **From Ember:** Server settings > Game servers > Unlink (Manage server). V deletes `game_links`, then for every `game` channel using that `iid` it sets `config.instance = null` and `config.purpose = null`. Those channels become "setup needed", so only admins see them.
  - V sends `unlinked {by:'bonfire', username}` to the session if connected. J prints it and keeps running as unclaimed.
  - V posts a system line in each affected chat-purpose channel *before* clearing: "This Minecraft server was unlinked by @user."
- **From the console:** `bonfire unlink` prints "Run `bonfire unlink confirm` within 30 s to unlink from <Ember server>". On confirm, J sends `unlink {}`, and V does the same as above with `by:'console'`.
- **Revocation of the identity:** unlinking frees the instance (it can be claimed again with a new code). `bonfire unlink confirm --new-identity` (console) also deletes `identity.properties` and reconnects with a fresh keypair. The old row stays unclaimed and is purged after 30 days unseen.
- **Site ban:** `game_instances.banned = 1`, set by hand/SQL for now. Effects: close 4004, never listed, can't be claimed.
- **Ember server deleted:** FK cascade removes `game_links`. On the next `ready` the instance is unclaimed again.

### 2.6 Abuse controls (V enforces)

| Control | Limit |
|---|---|
| New identities per IP hash | 5 per 24 h (count `game_instances.created_ip_hash`), else close 4008 |
| New identities globally | 200 per hour; beyond that new keys get 4008 and staff are logged a warning (protects the global list from floods) |
| Concurrent gateway sockets per IP hash | 10, else 4008 |
| Unclaimed instances per IP hash in the unfiltered global list | the 20 most recently connected. Hosting providers put many customers on one IP, so the cap is generous. A pairing-tag search always bypasses the cap. |
| Identify timeout | 15 s after `hello`, else 4002 |
| `GET /unclaimed` | 30 req/min/user; page size 20; search query at most 60 characters |
| Claim codes created | 10 / hour / user, `rate_limited` |
| `link` attempts | 5 / 10 min / instance, then `link_result {ok:false, error:'rate_limited'}` |
| Unclaimed rows | deleted when unclaimed and `last_seen` > 30 days |
| Offensive MOTD/icon | sanitised and capped (2.3); site staff ban the instance |

IP hashes are used **only** for these limits, never for listing. They come from `auth.hashIp` of the real IP: for the gateway, read `cf-connecting-ip` when the socket comes from a trusted proxy; otherwise use the first `x-forwarded-for` entry.

MOTD spoofing is addressed by 2.4: the click never decides what gets linked. Two servers claiming the same identity: only one session per `iid` is allowed, and the newer one replaces the older (4006). This matters only if someone copied the private key, which is the same as server compromise.

---

## 3. Gateway protocol (v1)

### 3.1 Transport

- URL: `wss://velora.ikelene.net/chat/game-gateway?v=1`. It is configurable in the J config as `gateway-url`.
- Text frames only. Each frame is one JSON object: `{"op": "<name>", "d": {...}, "id": <int, optional>}`.
  - `id` is set on requests that expect a correlated reply (`link`). The reply carries the same `id`.
- Max frame **64 KiB** in both directions. V closes oversized frames with 1009; J truncates log lines and splits batches to stay below.
- Text encoding is UTF-8. Timestamps are epoch milliseconds. UUIDs are dashed lowercase strings.
- WebSocket ping/pong: ws.js pings every socket on its heartbeat interval. The JDK WebSocket answers pings automatically (J must not override `onPing` without sending a pong). The app-level heartbeat below is still required, because Cloudflare closes idle sockets after 100 s.

### 3.2 Connection lifecycle

```
V: hello      {v:1, nonce, heartbeat_ms:30000}
J: identify   {...}                                  (within 15 s)
V: ready      {...}     | close 4003/4004/4007/4008
J: status     {...}                                  (immediately, then on change + every 60 s)
J: heartbeat  {seq}     V: heartbeat_ack {seq}       (every heartbeat_ms)
...
```

**Reconnect** (J): the backoff is 1, 2, 4, 8, 16, 32, then 60 s (cap), each with ±20 % jitter, reset after a session has lived 60 s.

| Close code | J reaction |
|---|---|
| 1000 / 1001 / 1006 / 1011 / 4000 / 4005 / 4006 | normal backoff |
| 4003 (bad signature) | log an error, retry every 10 min |
| 4004 (banned) | log an error, retry every 6 h |
| 4007 (protocol version unsupported) | log "update Ember Link", retry every 6 h |
| 4008 (IP limits) | retry every 10 min |

A missed `heartbeat_ack` for 2 intervals means J closes and reconnects.

**Server shutdown:** J sends `event {kind:'server_stop'}` and then closes with 1000. V then marks the instance offline immediately instead of waiting out the 60 s grace period.

### 3.3 Close codes (V to J)

`4000` generic, `4001` malformed frame/op, `4002` identify timeout, `4003` bad signature, `4004` banned, `4005` rate limited (sustained), `4006` replaced by a newer session, `4007` unsupported version, `4008` IP limits, `1009` frame too big.

### 3.4 Messages J to V

```jsonc
// identify
{"op":"identify","d":{
  "v":1, "key":"<base64 SPKI>", "sig":"<base64>", "iid":"g3fa91c0d2be47a10",
  "platform":"velocity",            // paper | spigot | fabric | velocity | forge
  "software":"Velocity 3.5.0-SNAPSHOT (git-abc)", "mc_version":"1.21.11",   // proxy: highest supported
  "mod_version":"1.0.0", "listing":"public",   // public | private (private = only found by exact pairing tag)
  "motd":[{"text":"i have a gambling addiction","color":"#22ff84"}],
  "icon_sha256":"<hex or null>", "max_players":100, "online_mode":true,
  "public_address":"mc.ikelene.net",   // from config, may be ""
  "features":{"chat":true,"events":true,"console":true,"console_exec":true}
}}
// heartbeat
{"op":"heartbeat","d":{"seq":17}}
// icon (only after ready.icon_needed=true; PNG <= 64x64, <= 48 KiB base64)
{"op":"icon","d":{"png":"<base64>"}}
// status (full snapshot, send on change, debounce 2 s, plus every 60 s)
{"op":"status","d":{
  "online":true, "version":"Fabric 26.2", "motd":[...segments],
  "players":{"online":3,"max":20,"list":[{"uuid":"...","name":"Nat","display":"[Admin] Nat","server":"survival"}]},  // list cap 100; "server" only from a proxy; vanished players never included (6.9)
  "tps":19.9,                           // optional, Paper/Fabric only
  "uptime_s":5123,
  "backends":[                          // proxy only, else omit
    {"name":"survival","online":true,"version":"1.21.11","motd":[...],"players":{"online":2,"max":20}},
    {"name":"modded","online":false}
  ]
}}
// chat (a player chat message; proxy adds "server")
{"op":"chat","d":{"uuid":"...","name":"Nat","display":"[Admin] Nat","text":"hello","server":"survival","bedrock":false}}   // display optional (6.10)
// event
{"op":"event","d":{
  "kind":"join",   // join | leave | death | advancement | switch | server_start | server_stop | backend_up | backend_down
  "uuid":"...","name":"Nat","display":"[Admin] Nat","server":"survival","from":"lobby",      // from: switch only; display optional
  "text":"Nat was slain by Zombie",                                   // death: vanilla death message, plain text
  "advancement":{"id":"minecraft:story/mine_diamond","title":"Diamonds!","description":"Acquire diamonds","frame":"task"}  // task|goal|challenge
}}
// console.log (batched; flush every 250 ms or 50 lines; only while V has said console_stream on)
{"op":"console.log","d":{"lines":[{"t":1760190000000,"level":"INFO","logger":"Server","msg":"Nat joined the game"}]}}
// console.result (answer to console.exec)
{"op":"console.result","d":{"req":"x7f2","ok":true,"output":["There are 3 of a max of 20 players online: ..."],"truncated":false}}
// console.command (a command run LOCALLY on this server, i.e. not one that came from Ember via console.exec)
// sent even when console_stream is off, so the audit log is complete; source: console | rcon
{"op":"console.command","d":{"t":1760190000000,"source":"console","by":"CONSOLE","command":"whitelist add Steve"}}
// rcon example: {"source":"rcon","by":"RCON 203.0.113.0","command":"list"}  (address only when console.redact-ips=false; else "RCON")
// link (from `bonfire link <code>`); reply = link_result with same id
{"op":"link","id":5,"d":{"code":"7KQ2M9XA"}}
// unlink (from `bonfire unlink confirm`)
{"op":"unlink","d":{}}
```

Field limits (V clamps; J should respect them):

- `text` 512 characters (MC chat max is 256)
- `name` 32
- `server`/`from` 40
- log `msg` 2000, `logger` 60
- `console.command`: `command` 1000, `by` 40; at most 20 per 10 s per instance (excess dropped, counted in a skipped marker)
- `output` max 100 lines, 2000 characters each
- MOTD max 20 segments, 256 characters total

### 3.5 Messages V to J

```jsonc
{"op":"hello","d":{"v":1,"nonce":"...","heartbeat_ms":30000}}
{"op":"ready","d":{
  "iid":"g3fa91c0d2be47a10","pairing_tag":"K7Q-2M9",
  "linked":null | {"server_name":"Ikelene Test Server","sid":"sb04dff8970","linked_by":"Ikelene","linked_at":1760190000000},
  "icon_needed":false,
  "relay":{"chat":true,"events":true},     // true when a chat-purpose channel uses this instance
  "console_stream":false,                  // true when a console channel uses this instance
  "limits":{"frames_per_s":30,"chat_per_10s":20}
}}
{"op":"heartbeat_ack","d":{"seq":17}}
// relay / console_stream changed (channel created / repurposed / deleted, link / unlink)
{"op":"config","d":{"relay":{"chat":true,"events":true},"console_stream":true}}
// an Ember message to show in game
{"op":"chat","d":{
  "id":"1234","channel":"mc-chat",
  "author":{"username":"Ikelene","color":"#ff7ac6","bot":false,"webhook":false},
  "segments":[{"text":"hey "},{"text":"@Nat","color":"#8ab4ff"},{"text":" look","b":true},{"text":"[image]","link":"https://...","u":true}],
  "reply":{"author":"Nat","text":"first 80 chars..."} | null
}}
{"op":"console.exec","d":{"req":"x7f2","command":"list","by":"Ikelene"}}
{"op":"link_result","id":5,"d":{"ok":true,"server_name":"Ikelene Test Server"}}   // or {"ok":false,"error":"invalid_code|expired|already_linked|rate_limited|not_allowed|too_many_instances"}
{"op":"linked","d":{"server_name":"...","sid":"...","linked_by":"Ikelene"}}
{"op":"unlinked","d":{"by":"bonfire|console","username":"Ikelene"}}
{"op":"error","d":{"code":"rate_limited|bad_op|too_big|not_linked","message":"...","fatal":false}}
```

**Segments** (shared shape for MOTD, chat and output):

`{text, color?:"#rrggbb", b?, i?, u?, s?, obf?, code?, link?:"https://...", hover?:"text"}`

- J maps segments to Adventure components (Paper/Velocity), to vanilla `Component`/`MutableComponent` with `Style` (Fabric), or to legacy `§` codes (plain Spigot without Adventure).
- `code` renders gray. `link` adds a click-open-URL plus underline. `hover` adds a hover text.

**How V builds segments** (in game.js, from the stored message body):

- `**b**` `*i*`/`_i_` `__u__` `~~s~~` `` `code` `` and code blocks become plain code segments.
- `||spoiler||` becomes `[spoiler]` with hover = the hidden text.
- `@name` becomes colored `#8ab4ff`.
- `<@&rid>` becomes `@RoleName` in the role color.
- `<:name:eid>` and `<a:name:eid>` become `:name:`.
- `#channel` stays as text.
- `[text](url)` becomes a link segment with the text.
- Bare URLs become link segments.
- Each attachment adds a ` [image]` / ` [video]` / ` [file]` link segment.
- Embeds-only messages become `[embed] <title or description first 80 chars>`.
- Headers, lists and `-#` are flattened to plain text.
- Newlines become spaces.
- The total is capped at 400 visible characters, ending in "...".

### 3.6 Rate limits

- **V, per session:** token bucket of 30 frames/s with burst 60. Excess frames are dropped and V sends `error {code:'rate_limited'}` (at most one per 5 s). If excess continues for 10 s, V closes with 4005.
- **V, per instance:** `chat` + `event` posts are limited to 20 per 10 s. Excess is dropped, and V posts one system line "Some Minecraft messages were skipped (too many at once)" at most every 60 s.
- **V, console:** `console.log` frames are limited to 10/s. The ring buffer keeps the last **1000 lines** per instance. Lines beyond 40/s per instance are dropped, and V inserts a `[... N lines skipped]` marker.
- **J:** bounded outbound queue of 500 frames while disconnected. When full, J drops the oldest `console.log` first, then `status`, and never drops `link`/`unlink`. On reconnect J sends one fresh `status` (stale statuses are not replayed). Chat/events older than 2 min are discarded, not replayed.
- **Console exec** (V, REST): 10 commands per 10 s per user, command max 1000 characters, and a leading `/` is stripped. The result times out after 10 s with `{ok:false, error:'timeout'}`; the command may still have run, and the UI says so.

---

## 6. Java side (J)

### 6.1 Repository and build

JDK 25 and Gradle 9.5 (see README). Package `net.ikelene.bonfire.*`.

```
core/       java-library, release 17
paper/      java,         release 17
fabric/     fabric-loom,  release 25
velocity/   java,         release 21
tools/      mock gateway + scenario scripts
```

**Java targets and why:**

| Module | Target | Reason |
|---|---|---|
| core | 17 | Shared code runs on every platform. Stays at 17 so the plugin covers Paper 1.20.4 and older Spigot. |
| paper | 17 | Same. Paper 1.21.11 itself needs Java 21 at runtime, which is fine. |
| velocity | 21 | Velocity 3.5 requires Java 21. |
| fabric | 25 | MC 26.2 and Fabric 26.2 require Java 25. |

**Dependencies:**

- **WebSocket:** `java.net.http.HttpClient` + `java.net.http.WebSocket` (JDK, no dependency).
- **JSON: Gson, `compileOnly 'com.google.code.gson:gson:2.10.1'`, not shaded.** All three platforms already ship Gson at runtime (Minecraft server, Paper/Spigot, Velocity), and Forge does too. That beats shading a second JSON library. Use only the Gson 2.10 API.
- **Crypto:** JDK `Ed25519`.
- **paper:** `compileOnly 'io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT'` (repo `https://repo.papermc.io/repository/maven-public/`).
  - Only Bukkit/Spigot API classes may be used in normal code.
  - Paper-only classes (Adventure `Component`, `AsyncChatEvent`, `Bukkit.createCommandSender`) are used **only** inside `PaperExtras`. That class is loaded when `Class.forName("io.papermc.paper.event.player.AsyncChatEvent")` succeeds.
  - `plugin.yml`: `api-version: '1.20'`, `folia-supported: false`.
- **velocity:** `compileOnly` + `annotationProcessor 'com.velocitypowered:velocity-api:3.5.0-SNAPSHOT'` (same repo).
- **fabric:** `minecraft`, `fabric-loader`, `fabric-api` (needs fabric-message-api, lifecycle events, command API, networking).
- **Packaging:** each platform jar includes core's classes directly (`jar { from(project(':core').sourceSets.main.output) }`; Loom: the same into the remapped/final jar). No shadow plugin, no relocation needed, since the package is unique.
- **Outputs:** `BonfireLink-Paper-<v>.jar`, `BonfireLink-Fabric-<v>+26.2.jar`, `BonfireLink-Velocity-<v>.jar`.

### 6.2 Core API (platform-agnostic)

Core owns:

- config and identity
- the gateway client (connect, handshake, heartbeat, backoff, outbound queue, rate limit)
- segment conversion (legacy `§` <-> segments; segments -> plain)
- the console log appender
- the link/unlink/status command logic
- the plugin-message codec (6.6)

Platforms implement:

```java
public interface Platform {
  String platformId();                 // "paper" | "spigot" | "fabric" | "velocity"
  String software();                   // e.g. "Paper 1.21.11-67"
  String mcVersion();
  Path dataDir();
  Logger logger();                     // java.util.logging or slf4j adapter
  StatusSnapshot status();             // built on the platform's main thread when needed (see threading)
  byte[] serverIconPng();              // server-icon.png from the working dir, or null
  List<Segment> motd();
  void broadcast(BonfireChat msg);     // show an Ember message to all players (main thread)
  CompletableFuture<CommandResult> runConsoleCommand(String cmd);   // capture output if possible
  void runSync(Runnable r);            // schedule on main/server thread
  Mode mode();                         // STANDALONE | PROXIED | PROXY
}
```

Core classes:

- `BonfireCore` (lifecycle `start()`/`stop()`)
- `Config`, `Identity`, `GatewayClient`, `Protocol` (records for each op)
- `Segments`
- `ConsoleCapture` (Log4j2 appender)
- `Commands` (shared text and logic for `/bonfire ...`)
- `EventBus`: platform calls `onChat(uuid, name, text, server)`, `onJoin`, `onLeave`, `onDeath(uuid, name, text, server)`, `onAdvancement(...)`, `onSwitch`, `onServerStart`, `onServerStop`, `onBackendUp/Down`

**Threading:**

- The WebSocket runs on JDK threads. Anything that touches game state hops to the main thread via `runSync`.
- `status()` is collected on the main thread every 60 s and on join/leave (debounced 2 s).
- Never block the main thread on network I/O.

**Console capture:**

- Attach a Log4j2 `AbstractAppender` to the root `LoggerConfig` at start, using `org.apache.logging.log4j.core`. Log4j2 core is present on Paper, Spigot, Fabric and Velocity; it is a `compileOnly` dependency in core.
- The appender is active only while `console_stream` is on.
- It must ignore events from logger names starting with `net.ikelene.bonfire.gateway`, so errors don't feed back into the stream.
- Messages are formatted (`event.getMessage().getFormattedMessage()`) with ANSI and `§` codes stripped.
- If `console.redact-ips=true` (default), IPv4/IPv6 addresses become `[ip]`. For example, `Nat[/1.2.3.4:5678] logged in` becomes `Nat[/[ip]] logged in`.

### 6.3 Config (`config.properties`, `java.util.Properties` with comments, written with defaults on first start)

Location: Paper `plugins/BonfireLink/`, Velocity `plugins/bonfire-link/`, Fabric `config/bonfire-link/`.

```properties
# Ember Link - connects this server to an Ember (velora.ikelene.net/chat) server.
gateway-url=wss://velora.ikelene.net/chat/game-gateway
# auto | standalone | proxied   (proxy = Velocity, always)
mode=auto
# public  = shows in Ember's global "Searching for servers" list (MOTD, icon, version, player count only)
# private = hidden unless someone types this server's pairing tag
listing=public
# Shown on the join-info card (players connect here). Empty = set it in Ember instead.
public-address=
chat.enabled=true
chat.use-luckperms-prefix=true
# Format of Ember messages in game. {name} {message} {channel}
chat.format=&8[&6Bonfire&8] &f{name}&7: &f{message}
events.enabled=true
console.enabled=true
console.exec-enabled=true
console.redact-ips=true
console.report-local-commands=true
# always treated as vanished (names or UUIDs, comma-separated)
events.hidden-players=
# Velocity only
proxy.cross-server-chat=true
proxy.cross-server-format=&8[&7{server}&8] &f<{name}> {message}
proxy.network-messages=true
proxy.excluded-servers=
# Names shown in Ember for backends, e.g. survival=Survival,creative=Creative
proxy.server-names=
```

- `{name}` is colored with the author color from the frame.
- `&` codes are translated by core.
- `{message}` is the segments rendered as components, not text, so a player can't inject formatting.

### 6.4 Commands (`/bonfire`)

| Command | Who | What |
|---|---|---|
| `bonfire link <code>` | console only | sends `link`, prints the `link_result` |
| `bonfire unlink` / `bonfire unlink confirm [--new-identity]` | console only | 2.5 |
| `bonfire status` | console, or players with permission `bonfire.status` / op level 2 | connection state, linked server name, iid, pairing tag, mode, last error, relay/console flags |
| `bonfire reload` | console only | reload config.properties and reconnect |

- On Velocity the command is a proxy command (`/bonfire` on the proxy). Backends in proxied mode register only `bonfire status`, and it says "Proxied: events go through the proxy".
- **Startup console banner** when not linked: `[Ember Link] Not linked yet. In Ember, add a "Game server" channel and pick this server. Pairing tag: K7Q-2M9`.
- When linked: `[Ember Link] Linked to <server name>.`

### 6.5 Per-platform behaviour

**Paper/Spigot (standalone).**

| Need | Implementation |
|---|---|
| Chat | `AsyncPlayerChatEvent` at `MONITOR`, `ignoreCancelled=true`. With Paper, use `AsyncChatEvent` in PaperExtras instead and take the plain text via `PlainTextComponentSerializer`. |
| Join / quit | `PlayerJoinEvent` / `PlayerQuitEvent` (MONITOR) |
| Death | `PlayerDeathEvent.getDeathMessage()` (strip `§`; skip if null or empty) |
| Advancement | `PlayerAdvancementDoneEvent`. Only when `advancement.getDisplay() != null && display.shouldAnnounceChat()` and the world gamerule `announceAdvancements` is true. Title from `display.getTitle()` / `display.getDescription()`, frame from `display.getType()`. |
| Start / stop | `onEnable` after the server finishes loading (scheduler `runTask`, i.e. first tick), `onDisable` |
| Broadcast | Paper: Adventure; Spigot: legacy `Bukkit.broadcastMessage`, built from segments as `§` text (links become plain) |
| Console exec | Paper: `Bukkit.createCommandSender(component -> collect)` captures output. Spigot fallback: `Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)` on the main thread, returning `output:["(output went to the console log)"]`. |
| TPS | Paper `Bukkit.getTPS()[0]` via PaperExtras |
| Local commands (6.8) | `ServerCommandEvent` at MONITOR, `ignoreCancelled=true`: `ConsoleCommandSender` reports `source:'console'`, `RemoteConsoleCommandSender` reports `source:'rcon'`. Commands run by `console.exec` use `dispatchCommand`/`createCommandSender`, which do not fire this event, so they aren't reported twice. |
| Vanish (6.9) | Bukkit metadata `player.getMetadata("vanished")`: any value `asBoolean()` true. This is the convention used by SuperVanish, PremiumVanish and EssentialsX. Check at join after a 2-tick delay, since vanish plugins set it during join. |
| Prefix (6.10) | LuckPerms API when the `LuckPerms` plugin is enabled |

**Paper in proxied mode:** register only the death, advancement and join (for the `presence` message) listeners, and send them as plugin messages (6.6). No gateway.

**Fabric 26.2 (standalone)** (use Mojang names; verify exact class/method names against the 26.2 sources):

| Need | Implementation |
|---|---|
| Chat | `ServerMessageEvents.CHAT_MESSAGE` (Fabric message API) using `message.signedContent()` |
| Join / leave | `ServerPlayConnectionEvents.JOIN` / `DISCONNECT` |
| Death | `ServerLivingEntityEvents.AFTER_DEATH` for `ServerPlayer`, using `player.getCombatTracker().getDeathMessage().getString()` |
| Advancement | a mixin on `PlayerAdvancements.award(AdvancementHolder, String)`: at RETURN, when the advancement became done (`getOrStartProgress(holder).isDone()` changed from false to true), the display is present with `shouldAnnounceChat()`, and gamerule announceAdvancements is on |
| Start / stop | `ServerLifecycleEvents.SERVER_STARTED` / `SERVER_STOPPING` |
| Broadcast | `server.getPlayerList().broadcastSystemMessage(component, false)` |
| Console exec | `server.getCommands().performPrefixedCommand(source, cmd)` with a `CommandSourceStack` at owner permission whose `CommandSource` collects `sendSystemMessage` output |
| Commands | `CommandRegistrationCallback` |
| Local commands (6.8) | a mixin at HEAD of `Commands.performPrefixedCommand(CommandSourceStack, String)`. Report when the stack's underlying `CommandSource` (accessor mixin) is the `MinecraftServer` (`source:'console'`) or an `RconConsoleSource` (`source:'rcon'`). Ember Link's own capture source is a different class, so its commands are skipped. |
| Vanish (6.9) | when the `vanish` mod (DrexHD, tested with `vanish-1.6.15+26.2`) is loaded, call its API `me.drex.vanish.api.VanishAPI.isVanished(Entity)` via reflection. Verify the class name against the jar. With any other or no vanish mod, nobody counts as vanished. |
| Prefix (6.10) | LuckPerms API when the `luckperms` mod is loaded (tested with LuckPerms-Fabric 5.5.57) |
| Proxied detection | `FabricLoader.getInstance().isModLoaded("fabricproxy-lite")` (tested with FabricProxy-Lite 2.12.0) |
| fabric.mod.json | `"depends": {"fabricloader": ">=0.19.3", "fabric-api": "*", "minecraft": "~26.2", "java": ">=25"}`, `"environment": "server"`. Server-only; clients don't need it. |

Servers may run **styled-chat** and **vanish**. Read chat from the Fabric message event, which fires before styled-chat decoration. Vanish is covered in 6.9.

**Velocity 3.5 (proxy mode).**

| Need | Implementation |
|---|---|
| Chat | `PlayerChatEvent`. **Observe only**: never change the result, since 1.19.1+ signed chat can't be modified on the proxy. `server` = `player.getCurrentServer()` name. |
| Cross-server chat | on the same event, for every other backend not in `proxy.excluded-servers`, send `proxy.cross-server-format` as a system message to the players on those backends. Players on the same backend already see the native message. This replaces SimpleProxyChat. |
| Network joins / leaves / switches | `PostLoginEvent` (join), `DisconnectEvent` (leave, only if the player was fully logged in), `ServerConnectedEvent` (switch, with `getPreviousServer()`). With `proxy.network-messages=true`, also broadcast `Nat joined the network` / `Nat moved to survival` / `Nat left` to all players (SimpleProxyChat parity). |
| Backend status | every 30 s `RegisteredServer.ping()` for each server in `proxy.getAllServers()` (timeout 3 s) gives online, version, players, MOTD. Transitions emit `backend_up` / `backend_down` events (after 2 consecutive failures). |
| Broadcast | `proxy.getAllPlayers()`, except players on excluded backends |
| Console exec | `proxy.getCommandManager().executeAsync(captureSource, cmd)`. `captureSource` is a `CommandSource` with `getPermissionValue` = TRUE that collects `sendMessage(Component)`. |
| Icon / MOTD | `server-icon.png` in the Velocity dir, `proxy.getConfiguration().getMotd()` |
| Plugin messaging | register `MinecraftChannelIdentifier.create("bonfire","events")` and `("bonfire","hello")` (6.6) |
| Local commands (6.8) | `CommandExecuteEvent` where `getCommandSource() instanceof ConsoleCommandSource`, reported as `source:'console'` (Velocity has no RCON). Skip Ember Link's own capture source. |
| Vanish (6.9) | hidden when the player has permission `bonfire.hidden` on the proxy (needs a permissions plugin there) **or** their current backend's last `presence` message said `vanished:true` |
| Prefix (6.10) | the LuckPerms API if LuckPerms is installed on the proxy; otherwise the `prefix` from the backend's `presence` message |

Geyser/Floodgate players on the proxy: set `bedrock=true` when `FloodgateApi.getInstance().isFloodgatePlayer(uuid)`. Load it via reflection only if the floodgate plugin is present; never a hard dependency.

### 6.6 Proxy and backend plugin messaging

| Channel | Direction | Payload (UTF-8 JSON) |
|---|---|---|
| `bonfire:hello` | proxy to backend (sent through a player's `ServerConnection` on `ServerPostConnectEvent`) | `{"v":1,"proxy":"bonfire-link","version":"1.0.0"}` |
| `bonfire:events` | backend to proxy (sent through any online player's connection; deaths/advancements always have one) | `{"v":1,"kind":"death"|"advancement","uuid":"...","name":"Nat","text":"Nat fell from a high place","advancement":{...}}` |
| `bonfire:events` kind `presence` | backend to proxy, through **that player's** connection: right after the hello on join, again when their vanish state or prefix changes (vanish polled every 5 s; prefix via the LuckPerms `UserDataRecalculateEvent`), and on server switch | `{"v":1,"kind":"presence","uuid":"...","vanished":false,"prefix":"[Admin]"}` (`prefix` is plain text per 6.10, or null) |

Rules:

- **The proxy is authoritative for the backend name.** It uses `((ServerConnection) event.getSource()).getServerInfo().getName()` and never a name from the payload.
- **The proxy drops spoofed messages:** in `PluginMessageEvent`, if the source is a `Player` (client) and the channel is `bonfire:*`, set `ForwardResult.handled()` and ignore it. If the source is a `ServerConnection` and the channel is `bonfire:events`, handle it and set `handled()` so it is never forwarded to the client.
- **Backend mode** is decided once at startup:
  - `mode=standalone`: always standalone.
  - `mode=proxied`: always proxied.
  - `mode=auto`:
    - Paper: proxied if `config/paper-global.yml` has `proxies.velocity.enabled: true` or `spigot.yml` has `settings.bungeecord: true`.
    - Fabric: proxied if FabricProxy-Lite is loaded.
    - Otherwise standalone.
  - `bonfire:hello` never changes the mode. It only sets "proxy confirmed" (shown in `bonfire status`). If a proxied backend has had players online for 5 min without a hello, it logs a warning once ("Is Ember Link installed on the proxy?").
  - A proxied backend sends events only after a hello has been seen on that player's connection. This avoids leaking the channel to clients when the proxy plugin is missing.
- Fabric registration: `PayloadTypeRegistry.playS2C().register(EventsPayload.TYPE, codec)` for `bonfire:events` and `PayloadTypeRegistry.playC2S().register(HelloPayload.TYPE, codec)` for `bonfire:hello`. Send with `ServerPlayNetworking.send(player, payload)`, receive with `ServerPlayNetworking.registerGlobalReceiver`. The codec is a string field (`ByteBufCodecs.STRING_UTF8`) holding the JSON.
- Paper: `getServer().getMessenger().registerOutgoingPluginChannel(this, "bonfire:events")` and `registerIncomingPluginChannel(this, "bonfire:hello", listener)`. Velocity reads with `event.getData()` and writes with `ServerConnection.sendPluginMessage`.
- **Wire format on every side: a VarInt length prefix followed by that many UTF-8 bytes of JSON.** This matches Fabric's `ByteBufCodecs.STRING_UTF8`. Paper and Velocity use core's `PluginMessageCodec.encode/decode`, which writes and reads the VarInt by hand. Never send bare JSON bytes.
- Max payload is 8 KiB. Larger ones are dropped.

### 6.7 Formatting rules

- **Ember to MC:** render the `segments` from V and wrap them with `chat.format`.
  - A reply adds a gray prefix line `> Nat: "first 80 chars"` above the message. Use plain ASCII, no arrow or symbol characters.
  - Bots get a `[BOT]` gray tag. Webhook or bridged authors get `[APP]` / `[Discord]` / `[Fluxer]`.
- **MC to Ember:** J sends plain text with `§` codes removed. V does all Ember-side escaping. J does not try to escape markdown. Player text that looks like markdown is allowed to format.
- **Display names:** J always sends the real username as `name`. It also sends `display` = `"<prefix> <name>"` when a LuckPerms prefix exists (6.10). V uses `display` for the Ember author name, capped at 80 characters, and uses `name`/`uuid` for the head avatar.

### 6.8 Locally-run console commands

- J reports every command typed into the server console, or sent over RCON, with `console.command` (3.4). This happens even when `console_stream` is off, so V's audit is complete.
- J never reports commands that came from `console.exec`.
- Config `console.report-local-commands=true` (default) controls it.
- When `console.redact-ips=true`, the RCON `by` value is just `"RCON"`. Otherwise it is `"RCON <ip>"`.
- Commands are queued like other frames while disconnected. They are not dropped by the 2-minute staleness rule; at most 200 are kept.

### 6.9 Vanished players are hidden everywhere

A vanished player is treated as not online, for Ember purposes:
- **Excluded** from `status.players.list` and from `players.online` counts (including per-backend counts on a proxy). The proxy can only adjust counts for players it knows about.
- **No `join`/`leave`/`switch` events** for them, and no proxy network join/leave/switch broadcasts in game either.
- **No `death`/`advancement` events** for them, since those would reveal them.
- Their chat **is** relayed, because they chose to speak; vanish mods let staff chat visibly.
- Vanishing or unvanishing mid-session sends **nothing** (no fake leave/join messages). The next `status` simply adds or removes them.
- **Proxy join timing:** a network join is announced only after the player's first backend sends `presence`, or 3 s after `ServerPostConnectEvent` if none arrives (then they are treated as not vanished). A leave is suppressed if their last known state was vanished.
- Detection per platform: see the Vanish rows in 6.5. Undetectable vanish mods are a known gap. Fallback config: `events.hidden-players=` (comma-separated names or UUIDs, always treated as vanished).

### 6.10 LuckPerms prefixes

- This is a soft dependency: `compileOnly 'net.luckperms:api:5.4'`. It is used only when the LuckPerms class `net.luckperms.api.LuckPermsProvider` resolves **and** the plugin/mod is enabled. Every call is wrapped in try/catch, and the plain name is the fallback.
- Read with `LuckPermsProvider.get().getUserManager().getUser(uuid).getCachedData().getMetaData().getPrefix()`. If the user isn't loaded, there is no prefix.
- Converting the prefix to Ember plain text (core `Prefixes.toPlain`):
  - strip legacy `&x`/`§x` codes, including `&#rrggbb`, `§x§r§r...` hex and `{#rrggbb}`;
  - strip MiniMessage tags (`<red>`, `<#ff00ff>`, `<gradient:...>`, `</...>`, `<bold>`, as LPC-Minimessage does);
  - collapse whitespace and trim;
  - cap at 32 characters;
  - drop it entirely if nothing printable is left.
- Config `chat.use-luckperms-prefix=true` (default).
- The proxy has no LuckPerms today, so on Velocity the prefix comes from the backends' `presence` messages.

---

## 10. Protocol change log

- v1 (this document), revised 2026-10-11: global list, `listing` public|private, `console.command` op, `display` field, `presence` plugin message, vanish rules. Add entries here, with date and reason, before either side implements a deviation.
