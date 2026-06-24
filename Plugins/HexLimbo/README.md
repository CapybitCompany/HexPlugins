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
  `forceOfflineMode`).
* MySQL 5.7+.
* Two backend servers registered in `velocity.toml` whose names match `servers.limbo` and
  `servers.target` in `config.yml`.

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
