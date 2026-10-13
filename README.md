<p align="center">
  <img src=".github/kordon-labs-logo.svg" alt="Kordon Labs" width="420">
</p>

<p align="center">
  VPN, proxy and bot protection for Minecraft proxies.<br>
  <a href="https://kordonlabs.com">Website</a> ·
  <a href="https://panel.kordonlabs.com/register">Get started for free</a> ·
  <a href="https://t.me/kordonlabs">News</a>
</p>

---

**Kordon** keeps VPNs, proxies, bots and suspicious clients off your Minecraft server, while real players join
without delay. This repository contains the source code of the Kordon plugin for **BungeeCord** and **Velocity**.
The plugin connects your proxy to the [Kordon](https://kordonlabs.com) service, where you manage everything from the
web panel. No config files.

> **Kordon has a free plan with no time limit:** 1,000 checks a day, enough for a small server.
> Paid plans add higher limits, more servers, a team, and advanced filters.
> See [pricing](https://kordonlabs.com/#pricing) or [create an account](https://panel.kordonlabs.com/register).

## Features

- **Checks every login** before the player reaches your server: VPNs, proxies, Tor, hosting and residential proxies.
- **Kordon's shared database:** an address caught on any server protected by Kordon is blocked on yours too.
- **Bot attack protection:** detects bursts of bot connections.
- **Country and nickname filters**, mobile carrier and network (AS) rules, and maintenance mode.
- **Whitelist and blacklist** of nicknames, IPs and IP ranges, managed in the panel or in-game.
- **Live view of your server:** who is online, where they connect from, their client and version, and one-click
  kicks from the panel.
- **Login log and statistics:** block reasons, attacks, new players, clients and join domains.
- **Observe mode:** see who would be blocked without blocking anyone.
- **Custom messages** for blocked players and staff notifications in chat.
- **Fail-safe:** if Kordon cannot be reached, the plugin follows your setting and either lets everyone in or nobody.

Availability of some features depends on your plan.

## Compatibility

| Platform | Versions |
|---|---|
| BungeeCord, Waterfall and forks (XCord, FlameCord, Travertine) | BungeeCord API 1.16 and newer |
| Velocity | 3.4 and newer, including 4.x |

Requires **Java 21** or newer (Velocity 4 requires Java 25). One `Kordon.jar` works on every supported proxy.
Install it on the proxy, not on the backend servers. Geyser/Floodgate players are supported.

## Installation

1. [Sign up](https://panel.kordonlabs.com/register) and add your server in the Kordon panel.
2. Download `Kordon.jar` from the server's **Plugin** page. This file is personal: it already contains your server
   key, so there is nothing to configure.
3. Put it into the proxy's `plugins` folder and restart the proxy.

Alternatively, use the generic `Kordon.jar` and link it from the **proxy console** with `/kordon link <key>`. The key
is on the same **Plugin** page. The plugin verifies the key, saves it to `plugins/Kordon/secret.key` and connects
right away, without a restart.

When everything is ready, the console shows `Kordon connected to server "..."` and the panel marks the plugin as
online within a minute.

> Treat the server key like a password: do not share the downloaded jar or the key. If it leaks, issue a new key in
> the panel and the old one stops working immediately.

To update, stop the proxy, replace `Kordon.jar` and start it again. The key stays in `plugins/Kordon/secret.key`.

## Commands

Every command requires `kordon.command` plus the permission listed below. The console has all permissions.

| Command | Permission | Description |
|---|---|---|
| `/kordon`, `/kordon help` | `kordon.command` | List available commands |
| `/kordon status` | `kordon.command` | Connection status, mode, key and online count |
| `/kordon reload` | `kordon.command.reload` | Fetch the settings from the panel now |
| `/kordon lookup <nick\|UUID\|IP>` | `kordon.command.lookup` | What Kordon knows about a player or address |
| `/kordon whitelist add <nick\|IP\|range> [duration] [note]` | `kordon.command.whitelist` | Let in without VPN and country checks |
| `/kordon whitelist remove <value>` | `kordon.command.whitelist` | Remove from the whitelist |
| `/kordon blacklist add <nick\|IP\|range> [duration] [note]` | `kordon.command.blacklist` | Block, and disconnect if online |
| `/kordon blacklist remove <value>` | `kordon.command.blacklist` | Remove from the blacklist |
| `/kordon link <key>` | `kordon.command.link` | Link the proxy to your Kordon server |
| `/kordon unlink` | `kordon.command.link` | Remove the saved key |

- Aliases: `wl` / `bl`, `rm` / `del`.
- Duration: `1h`, `1d`, `7d`, `30d` or `never` (default).
- `--ip` targets the current IP of an online player, `--name` forces a nickname.
- Enter the key in the proxy console, not in-game, so it does not end up in chat or command logs.
- In-game commands can be turned off in the panel.

## Permissions

| Permission | Grants |
|---|---|
| `kordon.command` | Required for any `/kordon` command; status and help |
| `kordon.command.lookup` | Player lookup |
| `kordon.command.lookup.ip` | See IP addresses and look up by IP |
| `kordon.command.whitelist` | Manage the whitelist |
| `kordon.command.blacklist` | Manage the blacklist |
| `kordon.command.reload` | Reload settings from the panel |
| `kordon.command.link` | Link and unlink the proxy (give it to the owner only) |
| `kordon.notify` | Staff notifications in chat |

## Data sent to Kordon

For each login: nickname, UUID, IP address, protocol version, skin (online mode), whether the player is a Bedrock
player (Floodgate), the address the player connected to, and later the backend server, client brand and language.
About the proxy: platform and version, plugin version, machine name, online count and response time.

The plugin does not collect chat, player commands or any other client data.

## Developer API

Other plugins can react to Kordon decisions. Add `Kordon.jar` as a `compileOnly` dependency.

| Event | Fired when | You can |
|---|---|---|
| `KordonLoginCheckedEvent` | Kordon has decided whether to let a player in | Read the verdict and reasons, change the kick message, or cancel a block to let the player in |
| `KordonRemoteKickEvent` | Kordon is about to disconnect a player on a panel command or a blacklist entry | Change the message or cancel the kick |

Packages: `com.kordonlabs.plugin.bungee.api` (BungeeCord, `softDepends: [Kordon]`) and
`com.kordonlabs.plugin.velocity.api` (Velocity, `"dependencies": [{"id": "kordon", "optional": true}]`).
Events are fired off the proxy's main threads; keep handlers fast and thread-safe.

```java
@Subscribe
public void onChecked(KordonLoginCheckedEvent event) {
    if (event.isBlocked() && isTrusted(event.getPlayer().getUniqueId())) {
        event.setCancelled(true);
    }
}
```

## Building from source

Requires JDK 21 or newer.

```bash
./gradlew build
```

The plugin is written to `kordon-universal/build/libs/Kordon.jar`. To point a build at another Kordon API address, pass `-PkordonApiUrl=https://...`.

| Module | Contents |
|---|---|
| `kordon-common` | Platform-independent core: login checks, panel connection, sessions, commands |
| `kordon-bungee` | BungeeCord and Waterfall integration |
| `kordon-velocity` | Velocity integration |
| `kordon-universal` | Packages everything into a single `Kordon.jar` |

## License

Kordon is licensed under the [GNU General Public License v3.0](LICENSE).
