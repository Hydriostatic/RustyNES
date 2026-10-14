# AYN Thor second-screen expansions (`.exp`)

An expansion is a zip renamed to `.exp` with a `manifest.json` (plus optional `images/`,
`icons/`, `fonts/`). It draws a live panel for one game on the Thor's lower screen
(1240 x 1080). Same format as the M64-DS expansions
(https://github.com/Hydriostatic/m64ds-expansions/blob/main/FORMAT.md), with these NES changes:

- Addresses are NES CPU-bus addresses: `0x0000-0x07FF` internal RAM, `0x6000-0x7FFF`
  cartridge RAM. `"$075A"` and `"0x075A"` are both accepted.
- Multi-byte numbers are little-endian (`u16`, `s16`, `u24`, `u32`); `u16be` / `u32be` for
  big-endian. `ptr` reads a 16-bit little-endian pointer; `chain` uses `"*"` the same way.
- Extra value types: `digits` (`count` bytes, one decimal digit each, most significant first;
  `"order": "le"` reverses), `bcd` (`bytes` bytes, two digits each), `bit` (`bit`: 0-7).
  Every number type takes optional `shift`, `mask`, `times`, `div`, `add`.
- `match`: `md5` / `sha1` / `sha256` / `crc` match the whole file or the file without its
  16-byte iNES header (so No-Intro and RetroAchievements hashes both work); `name` (substring)
  and `name_regex` match the ROM file name. Lists are accepted for every key.
- Templates accept `{name:03}` to zero-pad numbers.
- Pages supported: `screen` (tiles + bar) and `pixel_screen` (layers, `frames` animation,
  `fade` / `jigsaw` transition). `map_screen` (3D maps) is not supported on the NES build.

Build an example: `cd smb-text && zip -r ../smb-text.exp manifest.json`, then on the bottom
screen tap **Import expansion (.exp)…**. Long-press a panel (or tap **Save RAM dump**) to save
the 64 KB CPU bus to `Download/RustyNES/` for finding addresses.

The `smb-text` example has no game art; its RAM addresses come from the community Super Mario
Bros. RAM map and should be checked in-game.
