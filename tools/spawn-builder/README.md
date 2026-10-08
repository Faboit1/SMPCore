# SiftBuild: the spawn platform builder

A one-off plugin that built the SiftVanilla spawn: a floating 41 x 41 stone platform centred on 0, 0. It has:

- a stone brick wall railing;
- a smooth stone floor with polished andesite frames;
- a chiseled centre stone where players arrive;
- a dark 7 x 4 polished deepslate pad on the north side for the AFK zone (x -3..3, z -19..-16);
- invisible light blocks every 6 blocks, so mobs never spawn on it.

It is installed only for the build and removed afterwards. It is not part of SiftCore.

```sh
tools/spawn-builder/build.sh <server-dir> <jdk-25-home>   # produces tools/spawn-builder/SiftBuild.jar
```

Console commands (operators only):

| Command | Effect |
|---------|--------|
| `siftbuild probe` | Prints the highest terrain block under the platform's footprint |
| `siftbuild spawn <y>` | Builds the platform with its floor at `y`, clears 4 blocks of air above it, and sets the world spawn to `0.5, y+1, 0.5` |
| `siftbuild clear <y>` | Removes a platform built at `y` (every block it placed becomes air) |

Each chunk is edited on its own region thread, with explicit block states and no physics, so the build is safe
on Folia and Canvas.
