# HexLimbo

Velocity authentication limbo plugin. Premium players are auto-authenticated through Velocity's
online-mode flow; cracked players register/login inside a configurable limbo server and receive a
stable fake UUID persisted in MySQL.

## Build

```
./gradlew :plugins:HexLimbo:build
```

Output: `Plugins/HexLimbo/build/libs/HexLimbo-1.0.0.jar` (shaded with BCrypt, HikariCP, MySQL
Connector/J, and SnakeYAML).

## Runtime requirements

* Velocity 3.3+ (uses per-player `PreLoginEvent.PreLoginComponentResult#forceOnlineMode` /
  `forceOfflineMode`). Tested with Velocity 3.4.0-SNAPSHOT.
* MySQL 5.7+.
* **No external limbo backend is needed.** HexLimbo starts and registers its own minimal void
  backend during proxy startup. Only `servers.target` (the lobby) needs to be declared in
  `velocity.toml`.

### Minecraft version support

The internal limbo speaks Minecraft protocol **769 (Minecraft 1.21.4)** and **only** that
version. The protocol version constant lives in
`hex.limbo.limbo.server.Protocol.MINECRAFT_PROTOCOL_VERSION` and is intentionally pinned to a
single version – multi-version support is explicitly out of scope for v1.

A handshake announcing a different protocol version is rejected with a Login Disconnect that
asks the user to install ViaVersion. There is no "proceed anyway" fallback.

Clients on other versions are expected to be translated to 1.21.4 by ViaVersion /
ViaBackwards running on the **proxy**, not on the backend.

### Velocity forwarding

The limbo backend supports Velocity's modern forwarding handshake. Configure it via
`limbo.forwarding`:

```yaml
limbo:
  forwarding:
    mode: "MODERN"   # MODERN | LEGACY | NONE
    secret: ""        # Velocity's forwarding secret (optional; see below)
```

* **MODERN** (default) – matches `player-info-forwarding-mode = "modern"` in `velocity.toml`.
  After Login Start the limbo sends a `velocity:player_info` Login Plugin Request advertising
  forwarding version 1. Velocity replies with a signed payload (32-byte HMAC-SHA256 + version +
  IP + UUID + username + properties); the limbo uses the forwarded UUID/username for Login
  Success. **This is the mode you want when running behind a normal Velocity proxy.**
* **LEGACY** – BungeeCord-style forwarding. The limbo treats this identically to NONE today; the
  username from Login Start is trusted.
* **NONE** – no forwarding handshake. Use this only when Velocity is also configured with no
  forwarding (rare).

`limbo.forwarding.secret`:

* **Leave empty** – the limbo still consumes the forwarded UUID/username but does **not**
  validate the HMAC. This is acceptable on `127.0.0.1` because the proxy and limbo share a
  loopback channel.
* **Set to the contents of Velocity's `forwarding.secret`** – the limbo additionally validates
  the HMAC and rejects any tampered response with a Login Disconnect. Recommended if you ever
  decide to bind `limbo.bind-host` to something other than loopback.

BungeeGuard is not implemented in v1 and is not supported by the internal limbo.

### Internal void backend

HexLimbo's backend is intentionally tiny:

* Plain Java sockets, no Netty, no third-party Minecraft protocol library.
* No compression, no encryption (loopback only by default).
* Configuration state runs the full 1.21.4 handshake in this exact order:

  1. **Feature Flags** (`update_enabled_features`, id `0x0C`) advertising `minecraft:vanilla`.
  2. **Select Known Packs** (id `0x0E`) announcing `minecraft:core 1.21.4`.
  3. *Client responds with Select Known Packs response (id `0x07` serverbound)* – the limbo
     parses it, logs every pack the client echoes back, and warns if `minecraft:core 1.21.4`
     was NOT acknowledged (because `hasData=false` registries depend on the client resolving
     NBT from the known pack).
  4. **Registry Data** (id `0x07` clientbound) – the encoding depends on what the client
     echoed in step 3:
     * If the client acknowledged `minecraft:core 1.21.4`, every entry is sent with
       `hasData=false`. The client resolves the NBT from its built-in known pack and the
       packets stay tiny.
     * If the client returned `count=0` (Velocity/ViaVersion has been observed to do this in
       production), the limbo falls back to `hasData=true` for the registries that ship inline
       NBT bodies: `minecraft:dimension_type/overworld`, `minecraft:worldgen/biome/plains`,
       `minecraft:chat_type/chat`, `minecraft:damage_type/generic_kill`. The other registries
       (trim, wolf/cat/painting variants, banner, enchantment, jukebox, instrument) carry
       token-only entries and are **skipped entirely** in fallback mode – sending
       `hasData=true` with a missing body would crash the client. A void player never
       references those registries, so skipping them is safe.
  5. **Update Tags** (id `0x0D`) – empty (`VarInt(0)`). The 1.21.4 client tolerates an empty
     tag list for a void where nothing references tags.
  6. **Finish Configuration** (id `0x03`).

  Skipping any of Feature Flags / Registry Data / Update Tags caused the client to silently
  disconnect right after Finish Configuration in earlier rounds. The order above matches the
  vanilla server flow.
* The bootstrap registries are listed in `MinimalRegistries.java`:
  `minecraft:dimension_type` (with `minecraft:overworld`), `minecraft:worldgen/biome` (with
  `minecraft:plains`), `minecraft:chat_type`, `minecraft:damage_type`, `minecraft:trim_pattern`,
  `minecraft:trim_material`, `minecraft:wolf_variant`, `minecraft:cat_variant`,
  `minecraft:painting_variant`, `minecraft:banner_pattern`, `minecraft:enchantment`,
  `minecraft:jukebox_song`, `minecraft:instrument`. Each registry holds a single entry from
  `minecraft:core` – enough to satisfy the client's startup health checks without making the void
  pretend to be a full vanilla world.
* Play state sends Login → Player Abilities (so the void doesn't insta-kill the player) → Set
  Center Chunk → a 5×5 patch of empty single-value-palette chunks around spawn → Synchronize
  Player Position → Game Event 13 ("start waiting for level chunks") to dismiss the loading
  screen. Keep Alives every 15 s. No blocks, no inventory, no entities.
* Action-bar text is **off by default** in v1 – the NBT TextComponent encoding hasn't been
  validated against every 1.21.4 client build and a malformed packet kicks the player. Re-enable
  with `limbo.actionbar-enabled: true` after testing.
* Future bot defence / captcha checks have hooks ready in `LimboSession` and
  `LimboSessionRegistry` but are not wired up in v1.

### Debug logging

Set `limbo.debug-protocol: true` to surface INFO-level structured logs at every backend
handshake step:

* HANDSHAKE protocol + next-state
* LOGIN_START username / uuid
* FORWARDING request sent + response (mode, ip, uuid, name)
* LOGIN_SUCCESS sent
* LOGIN_ACKNOWLEDGED received
* CONFIG: Known Packs sent / response received
* CONFIG: each `Registry Data <name> entries=<n>` packet sent
* CONFIG: Finish Configuration sent / received / ACK
* CONFIG: any unknown packet id received
* PLAY: opening sequence sent
* I/O failure mid-stage: the current state is included in the WARN/INFO message

Use this the first time you wire a new proxy/client combination and turn it back off afterwards
– the logs are chatty.

`/hexlimbo limbo` now reports both `active-sessions` (players past Login Success) and
`tcp-connections` (raw accepted sockets), useful for telling a connection-storm apart from a
post-login-stall.

## Velocity API limitations discovered

These constraints shaped the design:

1. **Mixing premium and cracked players at the proxy.** The only supported way to mix is the
   per-player result on `PreLoginEvent` (`forceOnlineMode()` / `forceOfflineMode()`). The
   `velocity.toml` global flag is not the runtime source of truth once a plugin sets a result.
2. **UUID rewriting.** `GameProfileRequestEvent#setGameProfile()` is the only hook that can change
   a player's UUID. After this event the UUID is locked.
3. **Backend UUID consistency requires modern forwarding** (`modern` or `bungeeguard`). Otherwise
   the backend re-derives its own UUID and our stable fake UUID is lost.
4. **Premium name protection is enforced by Mojang's challenge.** When we return
   `forceOnlineMode()`, a client that does not own the premium account fails encryption and
   Velocity kicks it before `LoginEvent` fires.
5. **`PlayerChatEvent` is deprecated** with no stable replacement for signed chat in Velocity 3.3.
6. **`PreLoginEvent` / `LoginEvent` / `GameProfileRequestEvent` are `@AwaitingEvent`-style** – they
   wait on the `EventTask` returned from a `@Subscribe`, so DB and Mojang HTTP calls run through
   Velocity's async event mechanism instead of the netty I/O thread.
7. **No native Velocity library loader.** All third-party deps are shaded into the plugin JAR and
   relocated under `hex.limbo.libs.*`.

## Quick setup on the proxy

1. Drop `HexLimbo-1.0.0.jar` into Velocity's `plugins/` directory.
2. Make sure your real lobby/target backend is declared in `velocity.toml`:
   ```toml
   [servers]
   lobby = "127.0.0.1:25565"
   try = ["lobby"]
   ```
   Do NOT add a `limbo` entry – HexLimbo registers its own backend at startup.
3. Edit `plugins/hexlimbo/config.yml`:
   * `servers.target` – the lobby/server registered above.
   * `limbo.bind-port` – a free TCP port on the proxy host (default `25580`).
   * `database.*` – your MySQL credentials.
4. Run ViaVersion / ViaBackwards on the proxy if you want to support clients other than 1.21.4.
5. Start Velocity. Look for:
   ```
   HexLimbo internal void backend ready on 127.0.0.1:25580 (protocol 769 / Minecraft 1.21.4).
   Registered HexLimbo internal backend with Velocity as 'hexlimbo-limbo' at 127.0.0.1:25580.
   ```
6. Verify with `/hexlimbo limbo` from console or an op'd player.

A cracked player connecting now lands in the void and can `/register` / `/login`. After
authentication they are forwarded to `servers.target`.

## Database fail-fast

`database.fail-fast: true` (the default) refuses to start when MySQL is unavailable: every
incoming login is kicked with a "service unavailable" message. This is the production-safe choice
for an auth plugin – we never want to let players in without the credential store.

Set `database.fail-fast: false` to use an in-memory fallback for development. A loud warning is
emitted and accounts will NOT persist across restarts.

HexLimbo uses MySQL Connector/J. For MySQL users with `sha256_password` or
`caching_sha2_password`, keep `database.allow-public-key-retrieval: true` when `database.use-ssl:
false`, or enable SSL and set `allow-public-key-retrieval: false`.

## Premium check semantics (tri-state)

The Mojang public profile API is the source of truth, but it isn't always reachable. The resolver
returns one of three states:

| Status        | PreLogin result                                  | /register result                                                     |
|---------------|--------------------------------------------------|----------------------------------------------------------------------|
| `PREMIUM`     | `forceOnlineMode()` (Mojang challenge)           | Cracked registration is denied (premium-name protection)             |
| `NOT_PREMIUM` | `forceOfflineMode()`                             | Allowed                                                              |
| `UNKNOWN`     | Login denied (or `forceOfflineMode()` if open)   | Registration denied (or allowed if open)                             |

`UNKNOWN` includes network errors, timeouts, 5xx, 429, and malformed bodies. Override with
`premium.fail-open-on-check-error: true` only if you accept the risk that a cracked client could
grab a premium name during a Mojang outage.

Only `PREMIUM` and `NOT_PREMIUM` results are cached. `UNKNOWN` is never cached so the next caller
retries upstream.

## Hot reload

`/hexlimbo reload` re-reads `config.yml` and `messages.yml` and atomically swaps them into the
live `RuntimeContext`. Listeners and commands read through the context on every invocation, so
new values take effect immediately for:

* Server names (`servers.limbo`, `servers.target`)
* Login timeout, admin-bypass permission, allowed commands while unauthenticated
* All messages
* Min password length, max failed attempts, lockout seconds, max accounts per IP
* `session.enabled` and `session.duration-minutes` (read fresh on every session create/lookup)
* `premium.enabled`, `premium.fail-open-on-check-error`

Reload also performs these targeted side-effects:

* If `session.purge-interval-minutes` changed, the periodic purge task is cancelled and
  rescheduled with the new interval.
* If any field under `premium:` changed (`cache-ttl-seconds`, `cache-max-entries`,
  `http-timeout-ms`, `enabled`, `fail-open-on-check-error`), a fresh `CachedPremiumResolver` (and
  underlying `MojangPremiumResolver`) is built and swapped into the `PremiumResolverHandle` that
  every listener and command holds. The previous cache is cleared.

**NOT reloaded** (require a proxy restart, with a warning logged when edited):

* Database connection (host, port, user, password, pool size, fail-fast)
* `security.rate-limit-per-minute` sliding-window size
* `security.ip-hash-pepper` (changing it would invalidate every stored IP hash and every existing
  session lookup)
* `limbo.server-name` – Velocity holds the server registration; renaming would require unregister
  + re-register, which is not safe while players are inside.
* `limbo.bind-host`, `limbo.bind-port` – the TCP socket is bound once at startup.
* `limbo.spawn.*` – the spawn coordinates are baked into the chunk handshake sent on join.
* `limbo.actionbar-enabled`, `limbo.actionbar-text` – also restart-only in v1; live updates would
  race against the per-connection action-bar task.

On `/hexlimbo reload`, **any** change under `limbo.*` is ignored and a warning is logged. The
running TCP backend continues with the old values until you restart the proxy.

## Admin bypass

The configured `admin-bypass-permission` (default `hexlimbo.bypass`) opts a player out of the
entire HexLimbo flow:

* No limbo routing – they go straight to the configured target server.
* No login timeout, no `/login` required.
* No chat block, no command block.
* No server-connection gate – they can switch to any backend.

This is implemented in `LoginListener` and respected by every gate.

## Database schema

Tables are created automatically:

* `hex_limbo_accounts` – one row per registered identity.
* `hex_limbo_sessions` – session-based auto-login, keyed by (uuid, ip-hash). Periodically purged
  by a scheduled task every `session.purge-interval-minutes` (default 10 minutes).
* `hex_limbo_audit_log` – best-effort async log of login/register/admin events.

## Player commands

* `/register <password> <password>`
* `/login <password>` (alias `/l`)
* `/logout` – cracked accounts only. Premium accounts are authenticated by Mojang every session
  and have no password to re-enter, so `/logout` is intentionally a no-op for them; the player is
  told to reconnect instead.
* `/changepassword <oldPassword> <newPassword>` (alias `/cpw`)
* `/premium`
* `/limbo help`

## Admin commands (permission `hexlimbo.admin`)

* `/hexlimbo reload`
* `/hexlimbo info <player>`
* `/hexlimbo resetpassword <player> <newPassword>`
* `/hexlimbo forcelogout <player>` – sessions are always invalidated. Online cracked players are
  routed to the limbo server; online premium players are kicked (they have no password to
  re-enter); offline targets just have their persisted sessions cleared. All three result strings
  live in `messages.yml` under `admin.forcelogout.*`.
* `/hexlimbo unregister <player>`
* `/hexlimbo sessions <player>` – reports valid session count and latest expiry for the player.
* `/hexlimbo migrate <player>`
* `/hexlimbo debug <player>`
* `/hexlimbo limbo` – reports the internal void backend's server-name, bind host/port, ready flag,
  active connection count and last start error (if any). All output strings live under
  `messages.yml > admin.limbo.*`.

All admin subcommands that touch the database dispatch through the auth executor; only the
in-memory `debug` runs synchronously.

## Message keys

Every user- and admin-facing string the plugin emits lives in `messages.yml`. The most
frequently customised groups:

* `disconnect.*` – kick reasons.
* `error.*` – inline error messages shown to the player.
* `register.*`, `login.*`, `logout.*`, `changepassword.*`, `premium.*`, `limbo.*` – player command
  output.
* `admin.usage.*`, `admin.account-not-found`, `admin.reload.*`, `admin.resetpassword.*`,
  `admin.forcelogout.*`, `admin.unregister.*`, `admin.sessions.*`, `admin.migrate.*`,
  `admin.debug.*`, `admin.info.*` – `/hexlimbo` command output. The `admin.info.field` and
  `admin.debug.field` templates accept two arguments (field name, value).

The only intentionally hardcoded user-visible string is the startup fallback used by
`FailFastKickListener` when `RuntimeContext` could not be created (i.e. `messages.yml` itself
failed to load).

## Security notes

* Passwords are stored as BCrypt hashes (cost factor 12) with an automatically-generated per-row
  salt embedded inside the hash string. Passwords are never logged.
* Player IP addresses are SHA-256 hashed with a configurable pepper before storage; the raw IP
  never reaches the database.
* Login/register endpoints are rate-limited per username with a sliding window.
* After `security.max-failed-attempts` failed logins the account is locked for
  `security.lockout-seconds` seconds.

## Reserved for future versions

TOTP-based staff 2FA and a registration captcha are intentionally **not** shipped in v1. They were
present in earlier drafts as scaffolding only – the code path didn't actually enforce them, which
is worse than leaving them out. They will return as fully wired features in a later release.

## Async model

* `PreLoginListener`, `GameProfileListener`, and `LoginListener` return `EventTask.async(...)`
  from their `@Subscribe` methods. Velocity runs the task through its own async event mechanism,
  so Mojang HTTP and MySQL work never happens on the netty I/O thread.
* Every command (`/register`, `/login`, `/logout`, `/changepassword`, `/premium`, and every
  `/hexlimbo` admin subcommand except `debug`) dispatches its DB and BCrypt work onto the plugin's
  own `authExecutor` – a fixed pool of 4 daemon threads.
* Audit-log writes run on a separate `auditExecutor` (2 daemon threads).
* Both executors are shut down cleanly in `ProxyShutdownEvent`.
* Every async block catches `RuntimeException` and either logs + replies with the
  `error.internal` message, or denies the gating event safely. Exceptions are not swallowed.
