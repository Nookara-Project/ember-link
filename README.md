# Ember Link (Minecraft)

The plugin / mod that connects a Minecraft server to Ember: synced chat, join / death / advancement lines, a live
status card and a console channel. One outbound WebSocket per root (a Velocity proxy, or a standalone Paper / Spigot /
Fabric server); no open ports. Full design: [docs/SPEC.md](docs/SPEC.md); wire protocol: [ember-server PROTOCOL.md](https://github.com/Nookara-Project/ember-server/blob/main/PROTOCOL.md) section 18.

| module | jar | runs on |
|---|---|---|
| `core` | (shaded into the others) | shared logic: config, identity, gateway, commands |
| `paper` | `bonfire-paper-<v>.jar` | Paper / Spigot 1.20.4+ (Java 17) |
| `velocity` | `bonfire-velocity-<v>.jar` | Velocity 3.5 (Java 21) |
| `fabric` | `bonfire-fabric-<v>.jar` | Fabric 26.2 + Fabric API (Java 25) |

## Velora or a self-hosted Ember instance

Out of the box it connects to Velora. To use a self-hosted [ember-server](https://github.com/Nookara-Project/ember-server) instance, run in the server console:

```
bonfire server https://chat.example.com     # any instance URL (http:// for a LAN test box); stored as server= in config.properties
bonfire server                              # shows where it connects
bonfire server velora                       # back to Velora
```

It reconnects to `wss://chat.example.com/game-gateway?v=1`, prints its pairing tag and waits until an admin picks it
under **Connected game servers** (or links it with `bonfire link XXXX-XXXX`). Behind Velocity, run the command on the
proxy and on each backend (backends connect on their own for their console; they warn when they point somewhere else
than the proxy).

## Building

JDK 25 (the Fabric module targets it) and Gradle 9.5:

```
gradle build          # jars in */build/libs, core tests in core
```

The build needs `repo.papermc.io` (Paper + Velocity APIs), `maven.fabricmc.net`, `meta.fabricmc.net` and Mojang's
`piston-meta.mojang.com` / `piston-data.mojang.com` / `libraries.minecraft.net` (Fabric Loom) besides Maven Central.
`gradle/wrapper/gradle-wrapper.jar` is not checked in; `gradle wrapper` regenerates it if you want `./gradlew`.

`core` builds and tests with Maven Central only. `tools/selfhosted-e2e/drive.js` runs the core against a real
throwaway [ember-server](https://github.com/Nookara-Project/ember-server) and checks connect, listing, linking and chat both ways
(clone ember-server next to this repo, or set `EMBER_SERVER=/path/to/ember-server`).

## License

AGPL-3.0, see [LICENSE](LICENSE).
