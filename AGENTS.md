# 播棋 Seed Pits · project guidance

Kalah (6 pits × 4 stones) for PICO OS 6 Shared Space, PICO Spatial SDK BOM 6.1.9, package `io.embodit.seedpits`, v0.1.0.
Design package: factory `apps/seed-pits/design-package.json` (scope = profile A12 main loop; everything in `v2[]` is out).
Started from Cloud Air (kejing-dev/CloudAir: Main.kt two containers, Motion timeline, SoundPlayer, theme ContextWrapper)
with the Kalah rules / α-β engine written fresh in Xiangqi's LocalEngine shape.

## Surfaces

- **Board volume** (default container `SeedBoard`, Volumetric 0.72 × 0.28 × 0.32 m from the manifest, fixed world scale,
  gravity aligned, no material background, no caption bar, uniform resize). `board.glb` (0.66 × 0.24 × 0.03 m walnut,
  12 spherical pits, 2 elliptical stores, carved counterclockwise arrow) on `tiltRoot` (0 / 15°, pivot on the near edge),
  48 river stones (`stone_0..3.glb`, 12 clones each), unlit textured marks, 14 digit tiles, an upright seat plate over the
  far edge. No buttons in the volume. Pit colliders are 6.4 × 5.3 × 6.4 cm boxes named `pit:<cell>`.
- **Control window** (`SeedControl`, Planar 560 × 440 dp, `Placement.right(SeedBoard)`): native TabBar with four pages
  对局 / 新局 / 规则 / 设置. Confirmations (resign / restart / exit) open in place. Exit = `finishAndRemoveTask` + `exitProcess`.

## Rules that stay in force

- No Material / Material3: SpatialUI + `SeedTheme` (PicoTheme, 16 roles, ink labels). Every Text goes through `T(...)`,
  which logs `OVERFLOW <id>` on clipping.
- Rules live in `domain/rules/Kalah.kt` only (cells 0–5 yours a–f, 6 your store, 7–12 theirs A–F, 13 their store).
  A move is committed and saved before the animation (`GameFlow.sow`); the board reports steps and `Done`; a watchdog
  finishes a sow the board never reports. Panel / tile counts read the animation cursor `SowResult.countsAt(step)`.
- Per-frame motion lives in `BoardScene.onFrame` (driven by `BoardFrameSystem`), never in Compose.
- Every `openWindowContainer` goes through `WindowCtl` + `LevelGate`. In Shared Space the head provider reports
  `NOT_IN_FULL_SPACE`, so the gate opens at once (`launch/nohead-unsupported`). Head pose is never used to aim or select.
- Debug commands call the same `GameFlow` functions as real taps (profile D29).

## Build / test / run (kj-mbp-m5)

```bash
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
./gradlew :app:assembleDebug :app:testDebugUnitTest      # 28 JVM tests: rules, perft, engine strength, save, undo, slots, D35, level gate
python3 tools/build_board.py      # board.glb + stone_0..3.glb (pure Python)
python3 tools/make_sounds.py      # assets/sounds/*.wav (+ --check for peaks)
python3 tools/build_icon.py --export icon.png   # layered PICO icon + adaptive vector icon
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n io.embodit.seedpits/.platform.LaunchActivity
```
Emulators are shared: borrow with `python3 -I bin/emu_borrow.py borrow seed-pits` in the factory, return after.

## Debug broadcast (debug builds)

```
adb shell am broadcast -a io.embodit.seedpits.DEBUG -p io.embodit.seedpits --es cmd <cmd> --es a <arg>
```
`tap a–f|A–F` · `sow a–f` (tap twice) · `play` ([播]) · `cancel` · `pos 4,4,4,4,4,4,0,4,4,4,4,4,4,0/S` · `new <1–5>,me|them|random`
· `undo` · `redo` · `hint on|off` · `more` · `resign` · `restart` · `exit` · `yes` · `no` · `again` · `menu` · `continue`
· `welcome` · `page play|new|rules|settings` · `lang system|zh|en` · `sound on|off` · `volume 0|1|2` · `reduced on|off`
· `digits on|off` · `tilt 0|15` · `freeze on|off` (hold S06 / S07 / S08) · `finish` · `recall` · `open` · `tex F|off`
(D10 test glyph on tiles and plate) · `light <intensity>` · `reset` (first-run flags) · `goto S01|S02|S03|S04|S06|S07|S08|S09|S11|S12|S13`
· `state` · `evidence`. Log tag `SeedPits`: `GAME`, `PREVIEW`, `SOW id=… / play / played / done / watchdog`, `AI think / pick`,
`AGAIN`, `ALERT`, `UNDO`, `REDO`, `HINT`, `CONFIRM`, `WINDOW`, `TAP target=`, `OVERFLOW`, `EXIT`, `DUMP`, `EVIDENCE`.

## QA screenshot list (states in scope)

| state | how |
|---|---|
| S00 Loading | `pm clear` + cold start, shot within 0.5 s |
| S01 Welcome | first run (or `reset`, then relaunch): rules page + [开始] |
| S02 Setup | `goto S02`; with a save: start a game, `menu` |
| S03 Idle | `goto S03` (coach line in the first game); after `undo`: [重做] in the main slot |
| S04 Preview | `goto S04` (pit d → store, extra turn); capture preview: `pos 1,0,3,3,3,3,0,4,4,4,4,5,4,6/S` then `tap a` |
| S05 Sowing | `sow c`, shoot 0.3 s later |
| S06 Thinking | `goto S06` (Master, they start, held by freeze) |
| S07 Again | `goto S07` (held) |
| S08 Alert | `goto S08` (held); also `tap` an empty pit / `tap` during S06 |
| S09 Result | `goto S09` (win); lose / draw via `pos`; resign: `resign` + `yes` |
| S11 / S12 | `goto S11` / `goto S12`, both languages |
| S13 Confirm | `goto S13` (resign), `restart`, `exit` |
| S16 Restored | force-stop during S05 or S06, relaunch |
| modifiers | `lang en`, `reduced on`, `sound off`, `hint on`, `digits off`, `tilt 0`, `tex F` |

## Known gaps (v0.1.0)

- Hover preview (T3 / N1) not built: two taps is the path. Seat plate is the Canvas fallback (N2), not an AttachmentPanel.
- Sounds use SoundPool with left / right pan by pit position, not Spatial SDK positional audio.
- Not tried on a headset (none connected): controller ray / pinch, system window handle, sound loudness.
- v2 (design `v2[]`): hot-seat, puzzles, replay, game codes, second skin, pause menu, one-tap sow, fast toggle.
