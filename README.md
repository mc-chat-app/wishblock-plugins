# Minecraft plugins made with Wishblock

Each plugin here was made by typing **one sentence** into [Wishblock](https://www.wishblock.net/?ref=github).
Wishblock's AI wrote the Paper plugin, compiled it, loaded it on a test server and filmed it working,
all in a few minutes. Nobody wrote or edited the code by hand. It is published as the AI wrote it.

| Plugin | What was typed | What it does |
| --- | --- | --- |
| [Racecars](racecars/) | *Add racecars I can drive and a racetrack* | `/racetrack` builds an oval track with a timed start line; `/racecar` gives you a 3D car you drive with WASD |
| [Wordle](wordle/) | *Make a wordle gui* | `/wordle` opens a clickable Wordle board with green/yellow/gray tiles and a letters book |
| [Lava Parkour](lava-parkour/) | *Make a lava parkour where the platforms melt after you jump* | `/lavaparkour` builds a lava course whose platforms melt behind you |

<p>
<img src="racecars/demo.gif" width="300" alt="Driving a racecar around a racetrack in Minecraft">
<img src="wordle/demo.gif" width="300" alt="Playing Wordle in a Minecraft chest menu">
</p>

## Make your own

1. Open **[app.wishblock.net](https://app.wishblock.net/?ref=github)** and sign in with Discord or Apple. You get a free Minecraft: Java Edition server.
2. Type what your game is missing, the way you'd say it to a friend: *"add a pet dragon"*, *"make a /home command"*.
3. A few minutes later it's on your server's test world. Ask for changes the same way: *"make the car turn faster"*.

Each plugin's page has a **Make this** link that starts Wishblock with that sentence already typed.

## Using these on your own server

- **Lava Parkour** runs on any Paper 26.2 server: download the jar from [Releases](../../releases) and put it in `plugins/`.
- **Racecars** and **Wordle** come with their own textures. On Wishblock those reach players automatically; on another server
  the plugin would ask players for a resource pack it can't serve, so there's no jar for them yet. The source is here to read and build on.

## License

MIT, see [LICENSE](LICENSE).

*Wishblock is not made by, endorsed by, or associated with Mojang or Microsoft.*
