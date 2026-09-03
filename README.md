# DobriyAnonimj.github.io
A plugin to simplify working with server resource packs.

MultiResourcePacks
Drop several resource packs into one folder — the server merges them into a single
pack and hands it to every player who joins. No port forwarding, no file hosting,
no external services.
Works on Bukkit / Spigot / Paper, from 1.12 up to the latest release, with one
and the same jar.
---
Why
Minecraft lets a server push exactly one resource pack, and it has to live behind a
public URL. So the usual routine is: merge packs by hand in a file manager, upload
the zip somewhere, paste the link into `server.properties`, recalculate the SHA-1,
and repeat all of it every time a pack changes.
This plugin removes the whole routine. You put packs in a folder. That's it.
Features
Unlimited packs, merged automatically. Zip archives and unpacked folders,
mixed freely.
Smart merging, not blind overwriting. `sounds.json`, language files, fonts,
atlases, item models with `CustomModelData`, blockstates — all merged key by key,
so packs from different authors coexist instead of erasing each other.
No open port required. The pack is served over the Minecraft port itself,
which is already reachable. Nothing to forward, nothing to configure.
Kick toggle for players who refuse the pack — one command, no restart, the
setting survives a reboot.
Version-proof. No NMS class names, no version-specific code paths. One jar
for every server generation.
Installation
Put `MultiResourcePacks-1.1.0.jar` into your `plugins/` folder.
Start (or restart) the server.
Open `plugins/MultiResourcePacks/resourcepacks/` and drop your packs there.
Run `/rp reload`.
Check `/rp status` — you should see `Раздача: через порт Minecraft (25565)`
(or your port). That means everything is working and there is nothing left to set up.
How the no-port trick works
Every new TCP connection starts with a few identifying bytes. A game client sends a
Minecraft handshake packet; the resource pack downloader sends a plain HTTP request
beginning with `GET `. The plugin peeks at those first four bytes and routes the
connection accordingly: HTTP requests it answers itself, everything else it passes
through to Minecraft untouched.
Two details that matter in practice:
HTTP connections get Minecraft's own handlers stripped off them, because the
server's 30-second read timeout would otherwise abort a long download halfway.
The zip is streamed with a zero-copy file region, so a 200 MB pack does not turn
into 200 MB of heap.
If for any reason the interception cannot be installed, the plugin falls back to a
standalone HTTP port on its own and says so in the console.
Pack priority
Packs are applied in alphabetical order, and later ones win conflicts. Numbering
keeps it obvious:
```
resourcepacks/
├── 01\_base.zip
├── 02\_mobs.zip
├── 03\_gui.zip
└── 04\_custom\_items/        ← unpacked folders work too
```
What gets merged instead of overwritten
File	Merge rule
`pack.mcmeta`	rebuilt; `pack\_format` = highest found, plus a wide `supported\_formats` range
`assets/\*/sounds.json`	merged key by key
`assets/\*/lang/\*.json`	merged key by key
`assets/\*/font/\*.json`	`providers` arrays concatenated
`assets/\*/atlases/\*.json`	`sources` arrays concatenated
`assets/\*/models/\*\*.json`	`overrides` concatenated and sorted by `custom\_model\_data`
`assets/\*/blockstates/\*.json`	`variants` merged, `multipart` concatenated
`assets/\*/items/\*.json` (1.21.4+)	`entries` / `cases` concatenated
everything else	highest-priority pack wins; conflicts are logged
Duplicate entries are removed, so merging the same pack twice changes nothing.
The output zip is byte-for-byte reproducible, which means the SHA-1 only changes
when the packs actually change — clients re-download only when there is something
new to download.
Commands
Permission: `multirp.admin` (operators by default).
Command	Description
`/rp status`	URL, size, pack count, hosting mode, download counter
`/rp list`	packs in the folder, in priority order
`/rp reload`	rebuild the pack and reload the config
`/rp send <player|all>`	send the pack manually
`/rp url`	the current pack link
`/rp folder`	full path to the packs folder
`/rpkick on`	kick players who refuse the pack
`/rpkick off`	stop kicking
`/rpkick`	show the current state
`/rp kick on|off` does the same as `/rpkick`. Aliases: `/resourcepacks`, `/mrp`,
`/respack`.
The kick toggle is stored in `state.properties` and survives restarts, so you can
turn it on for an event and off afterwards without touching any files.
Configuration
Defaults work out of the box; the interesting knobs are below.
```yaml
packs-folder: resourcepacks   # relative to the plugin folder, or an absolute path
send-delay-ticks: 20          # delay before offering the pack on join
auto-reload-seconds: 0        # 0 = off; otherwise watch the folder and rebuild

pack:
  format: auto                # auto = highest pack\_format among your packs
  description: "\&aServer resource pack"
  wide-compatibility: true    # adds supported\_formats, removes the "incompatible" warning
  compression: 6              # 0..9

hosting:
  mode: auto                  # auto | minecraft-port | separate-port | external
  port: 25580                 # only for separate-port
  public-address: auto        # auto = detected on startup; or your IP / domain
  public-port: auto

kick:
  enabled: false
  on-declined: true
  on-failed-download: true
  on-timeout: false
  timeout-seconds: 90
```
Behind BungeeCord / Velocity
Proxies forward the Minecraft protocol, not HTTP, so players connecting through a
proxy cannot reach the pack on the proxy's port. Point them at the backend directly:
```yaml
hosting:
  public-address: 10.0.0.5    # address of THIS server, reachable by players
  public-port: 25566
```
Or host the file yourself and set `external-url`. The plugin detects proxy mode and
warns about this in the console on startup.
Building from source
Requires a JDK (8 or newer). Nothing is downloaded — the Bukkit API is replaced by
compile-only stubs in `stubs/`, and Netty is used purely through reflection.
```
./build.sh          # build
./build.sh test     # build and run the test suite
build.bat           # Windows
```
The jar lands in `out/`. Stubs never end up inside it.
Troubleshooting
Players see "Failed to download resource pack".
Run `/rp url` and open that link in a browser from outside your network. If it does
not open, the address in `hosting.public-address` is wrong, or a proxy is in the way
(see the section above).
The console says it switched to a separate port.
Some hosting providers put an anti-DDoS layer in front of the Minecraft port that
only speaks the game protocol. Either open the port named in the message, or host
the pack yourself through `external-url`.
Two packs both change the same texture.
Only one can win — the one later in alphabetical order. Rename the packs to set the
order you want. Conflicts are listed in the console after every rebuild.
A pack seems to be ignored.
It probably has `pack.mcmeta` buried inside an extra folder. The plugin unwraps that
automatically, but check `/rp list` to confirm the pack is picked up at all.
Author
Dobriy Anonim
Bug reports and ideas are welcome. Released under the MIT license — use it, change
it, ship it, just keep the credit line.
