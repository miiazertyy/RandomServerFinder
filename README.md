# 🎲 Random Server Finder

A Fabric client mod that adds a dice button to the bottom-left of the multiplayer screen. It opens a list of random public Minecraft servers you can join.

It uses **no API and no account**. It works from a bundled list of ~190,000 server addresses and
pings them itself, so everything it shows is verified online right now.

### Need help?
If you need help or wanna request a feature, [click here](https://github.com/miiazertyy/RandomServerFinder/issues/new) to make a New issue! There's never any dumb questions.

Requires [Fabric API](https://modrinth.com/mod/fabric-api).

> **Use it with [ViaFabricPlus](https://modrinth.com/mod/viafabricplus)**
>
> Random servers run every Minecraft version ever released, and vanilla refuses to connect to any but
> your own, so most results would show a red ✗ and be unjoinable.
> [ViaFabricPlus](https://modrinth.com/mod/viafabricplus) translates the protocol as you connect, so
> you can join a 1.8, 1.16 or 26.2 server without changing your client or restarting.
>
> Client-side only; nothing needed on the servers you join. Treat it as part of the setup.

<img src="https://i.imgur.com/86Ca3TM.png" width="200" alt="Dice button">
<img src="https://i.imgur.com/3mikX8z.png" width="600" alt="Random server finder menu">

---

## Features

- **Random servers, endlessly.** Scroll and it keeps finding more, no repeats.
- **Fluid scrolling.** Eases with real elapsed time, so it feels the same at 60 fps and 240 fps.
  Vanilla's list snaps to whole steps per notch; this doesn't.
- **List or card layout.** Compact rows, or a grid of tiles that gives each server icon room to be
  recognisable.
- **Live everything.** Real icon, MOTD, player count, player list and latency from an actual ping.
- **Filters** that persist between sessions: player count, version, MOTD text, icon, port, subnet
  and more. Active ones show as chips under the title.
- **Access check.** Asks a server whether it needs a paid account, or would turn you away with a
  whitelist or ban, before you waste a connection on it.
- **Join history.** A green ✔ marks servers you have already joined, and the History screen gets you
  back to one. Random results are unrepeatable, so a good find would otherwise be lost.
- **Parties.** One person hosts, friends follow them from server to server automatically.
- **Streamer mode.** Hides every address on screen. A random server is usually somebody's home
  machine, and putting its address on stream tends to get it griefed.
- **Searches in the background** while you sit on the title screen, so results are ready before you
  open it. Stops entirely once you are in a world.

Join by double-clicking, **Direct** to open Direct Connect with the address filled in, or **Save** to
add it to your normal server list.

---

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer) **0.19.3 or newer**.
2. Drop these in your `mods` folder:
   - this mod's jar, matching your Minecraft version
   - [Fabric API](https://modrinth.com/mod/fabric-api)
   - [ViaFabricPlus](https://modrinth.com/mod/viafabricplus), strongly recommended, see above

On Windows that folder is `%APPDATA%\.minecraft\mods`.

## Build

Needs a **JDK 21 or newer**. Nothing else. The Gradle wrapper fetches Gradle, Minecraft and a Java
21 toolchain itself. The first build decompiles Minecraft, so expect a few minutes.

```shell
.\gradlew.bat build          # Windows
./gradlew build              # macOS / Linux
```

The mod lands at `build/libs/randomserverfinder-1.0.0.jar` (ignore the `-sources` one).

Build for every supported Minecraft version at once, collected in `dist/` and named ready to upload:

```shell
powershell -ExecutionPolicy Bypass -File .\build-all.ps1
```

### How the versions are kept apart

Minecraft 26.1 stopped being obfuscated, which split the toolchain in two. 26.x uses the
non-remapping `net.fabricmc.fabric-loom` plugin with no mappings; 1.21.x and 1.20.x use
`net.fabricmc.fabric-loom-remap` with Yarn. On top of that, 26.x renamed nearly every class in the
game and replaced immediate-mode `render(DrawContext…)` with `extractRenderState(GuiGraphicsExtractor…)`,
while 1.20.x predates the `Click`/`KeyInput` input records and the render-pipeline argument on
texture draws.

So the source is split rather than littered with conditionals:

```text
src/shared/java   no Minecraft at all, compiles once for every version
src/mc26/java     the 26.x UI layer
src/mc121/java    the 1.21.9 – 1.21.11 UI layer
src/mc120/java    the 1.20.x UI layer
src/main/resources
```

`build.gradle` picks the plugin and the variant from the version number alone. Roughly half the mod,
the address list, filtering, login probe, config and join history, is in `shared` and is built
identically for all of them.

To try it without installing anything:

```shell
.\gradlew.bat runClient
```

---

## Notes

**Filters.** Player count takes a range (`3`, `>1`, `<=5`, `1-10`). Version and host are patterns
(`%1.21%`, `Paper%`). Subnets take a list with `!` to exclude (`1.0.0.0/8, !2.3.4.0/24`). Address
filters are applied before pinging, so they cost nothing.

**Some servers can't be found.** Anything behind a hostname-routed proxy (Aternos, Minehut, Falix,
anything on Cloudflare) is unreachable by IP and impossible to discover by scanning. Use **Direct**
if you already know the hostname.