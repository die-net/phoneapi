# S9 first-frame diagnostics

On the Galaxy S9+ (SM-G965U1, Android 10), a video stream started on a still screen shows nothing until the screen changes. PR #16 works around it with a screencap. Before deciding on that, we want to know where the first frame is lost. Please run everything below in one pass and send back `REPORT.md` plus `s9-results.tar.gz` (see the end).

What we already know, from AOSP source and the Android 10 emulator: stock Android 10 draws one frame into a new mirror display within about 40 ms, even on a still screen, and PhoneAPI's relay receives it. So the S9 is doing something different, and these tests find out what.

## Before you start

- **Tools on the computer:** `adb`, `uv`, `jq`, `python3`, and `ffprobe` (optional). The phone connects over USB.
- **Branch:** check out `diag/s9-first-frame` from https://github.com/die-net/phoneapi. It is main plus logging and four switches, and it is not meant to be merged. It adds `scripts/video-probe` and `scripts/frames-summary`.
- **Pairing:** the scripts read `.dev/pairing.json`, or `$PAPI_PAIRING` if that is set. Use the pairing your dev build already has.
- **Results directory:** `mkdir -p s9-results`. Every file named below goes there.
- **A "still screen":**
  - The phone shows the top-level Settings list.
  - The screen is on and unlocked.
  - Nobody has touched it for at least 5 seconds.
  - No notification is arriving. Do Not Disturb helps; turn it off again afterwards.
  - Start each run between :05 and :45 past the minute, because the status-bar clock redraws on the minute.
  - Set the screen timeout to at least 2 minutes for the session.
- **No other viewers:** close every `/viewer` page on every computer before Part P. An open viewer keeps the encoder running, and then nothing below is a cold start.

## RUN(label): how to do one stream run

Parts P and C use this procedure. Wait at least 5 seconds after the previous run, so the encoder has stopped.

```bash
adb logcat -c
scripts/video-probe LABEL 8 4 > s9-results/LABEL.jsonl
adb logcat -d -v threadtime PhoneApiStream:V PhoneApiHelper:V SurfaceFlinger:V VirtualDisplaySurface:V \
  BufferQueueProducer:V BufferQueueConsumer:V GLConsumer:V SurfaceTexture:V MediaCodec:V CCodec:V ACodec:V \
  AndroidRuntime:E '*:S' > s9-results/LABEL.log
```

`video-probe` opens the stream for 8 seconds. A second viewer joins at 4 seconds. It prints one JSON line per frame it receives, then a `summary` line.

## Part A: device facts (no stream running)

```bash
adb shell getprop > s9-results/A1-getprop.txt
adb shell dumpsys SurfaceFlinger > s9-results/A2-surfaceflinger.txt
adb shell 'wm size; wm density; dumpsys display | grep -E "mBaseDisplayInfo|mOverrideDisplayInfo"' > s9-results/A3-display.txt
adb shell 'dumpsys package net.die.phoneapi.dev | grep -E "versionName|versionCode|lastUpdateTime"' > s9-results/A4-package.txt
adb shell service call SurfaceFlinger 1004 > s9-results/A5-sf1004.txt 2>&1
```

- **A2:** this lists the windows on screen. Redact names in it if you need to, but keep its structure.
- **A5:** we expect a permission error. This only confirms that Samsung kept AOSP's permission check.

## Part B: stock `screenrecord` (no PhoneAPI)

This shows whether the S9 draws a first frame into a new mirror display at all, without any of our code involved. `--output-format=frames` sends the mirror to a GL texture, the same path PhoneAPI's relay uses. It writes one record per frame the system draws.

Change each `--size` to match the phone's aspect ratio from A3. `360x740` (B1 to B3) and `720x1480` (B4) fit both 1440x2960 and 1080x2220.

**B1.** Still screen, 1-second capture. Run it three times (`B1a`, `B1b`, `B1c`), with a still screen before each:

```bash
adb shell 'screenrecord --verbose --output-format=frames --size 360x740 /data/local/tmp/B1a.frames & p=$!; sleep 1; kill -INT $p; wait $p' > s9-results/B1a.txt 2>&1
adb pull /data/local/tmp/B1a.frames /tmp/ && scripts/frames-summary /tmp/B1a.frames >> s9-results/B1a.txt
```

**B2.** Still screen, 4-second capture: the same command with `B2` and `sleep 4`.

**B3.** Control: 4 seconds with a scroll at 1 second. This proves the tool records changes on this phone:

```bash
adb shell 'screenrecord --verbose --output-format=frames --size 360x740 /data/local/tmp/B3.frames & p=$!; sleep 1; input swipe 540 1800 540 900 300; sleep 3; kill -INT $p; wait $p' > s9-results/B3.txt 2>&1
adb pull /data/local/tmp/B3.frames /tmp/ && scripts/frames-summary /tmp/B3.frames >> s9-results/B3.txt
```

**B4.** Still screen, recorded straight into the encoder (the setup v0.2.3 used):

```bash
adb shell 'screenrecord --verbose --time-limit 4 --size 720x1480 /data/local/tmp/B4.mp4; ls -l /data/local/tmp/B4.mp4' > s9-results/B4.txt 2>&1
adb pull /data/local/tmp/B4.mp4 s9-results/ && ffprobe -v error -count_frames -select_streams v:0 \
  -show_entries stream=nb_read_frames,width,height -of json s9-results/B4.mp4 >> s9-results/B4.txt
```

`screenrecord` does not ask the encoder to repeat frames, so zero frames in B4 can also mean the encoder held its only frame. B1 is the deciding test; B4 is supporting evidence.

Afterwards: `adb shell rm /data/local/tmp/B*.frames /data/local/tmp/B4.mp4`

## Part P: your current PR #16 build

Before installing anything, save the build you have, so you can put it back afterwards:

```bash
adb pull "$(adb shell pm path net.die.phoneapi.dev | head -1 | tr -d '\r' | sed s/^package://)" pr16-build.apk
```

Then do **RUN(P1)** and **RUN(P2)**, each on a still screen. This build has no diagnostic logging; the probe output is what matters here.

## Part C: the diagnostics build

Build and install it over the dev build. This keeps the app's data and pairing, and restarts the helper:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
scripts/phoneapi-dev helper
scripts/phoneapi-dev server
scripts/phoneapi-dev papi GET /v1/device | head -1 | jq -r .helper   # should print "running"
```

If the install fails with a signature error, uninstall `net.die.phoneapi.dev` and run `scripts/phoneapi-dev setup` instead, which re-pairs.

**The switches.** They are system properties, read each time a stream starts, so changing one needs no restart:

| Property | Values | What it does |
|---|---|---|
| `debug.phoneapi.relay` | `1` (default) or `0` | `0` sends the mirror straight to the encoder, skipping the relay (like v0.2.3) |
| `debug.phoneapi.mirror.secure` | `0` (default) or `1` | `1` creates the mirror as a secure display, which is what scrcpy does on Android 10 |
| `debug.phoneapi.mirror.nudge_ms` | `0` (default) or a number of ms | After that many ms, shifts the mirror's projection by 2 px and back, which makes the system redraw the mirror |

Set all three before every run, so nothing carries over from the last one:

```bash
adb shell 'setprop debug.phoneapi.relay R; setprop debug.phoneapi.mirror.secure S; setprop debug.phoneapi.mirror.nudge_ms N'
```

| Labels | relay | secure | nudge_ms | Why |
|---|---|---|---|---|
| C0a, C0b, C0c | 1 | 0 | 0 | Same behaviour as main |
| C1a, C1b | 0 | 0 | 0 | Without the relay |
| C2a, C2b | 1 | 1 | 0 | Secure display |
| C3a, C3b | 1 | 0 | 300 | Does a nudge produce the frame? |
| C4 | 0 | 1 | 0 | Closest match to scrcpy |
| C5 | 1 | 0 | 0 | First stream after the app restarts (see below) |
| C6 | 1 | 0 | 0 | Control: the screen changes during the run (see below) |

**C5**, before its RUN:

```bash
adb shell am force-stop net.die.phoneapi.dev
scripts/phoneapi-dev helper && scripts/phoneapi-dev server
# repeat until it prints "running"
scripts/phoneapi-dev papi GET /v1/device | head -1 | jq -r .helper
```

Then leave a still screen for 5 seconds and do RUN(C5).

**C6:** start the RUN, and 2 seconds after the probe starts, run `adb shell input swipe 540 1800 540 900 300`. Note the time you did it.

**What the diag log lines mean.** The `.log` files include these lines, tagged `PhoneApiStream` (app) and `PhoneApiHelper` (helper):
- `diag: mirror start ... secure=... nudgeMs=...`, then `Mirroring display 0 ... via SurfaceControl`
- `diag: startMirror returned true at Nms`, `diag: requestSync at Nms`, `diag: redraw at Nms hasFrame=...`
- `diag: mirror frame K at Nms`: the relay got frame K from the system. This is the key line.
- `diag: updateTexImage failed on mirror frame K`: a frame arrived but could not be read.
- `diag: no mirror frame yet at Nms`: logged every 500 ms until a frame arrives.
- `diag: draw K`, `diag: encoder output K key=...`
- `diag: nudged the mirror projection after Nms`, or `diag: nudge skipped`

## Part D: put things back

```bash
adb shell 'setprop debug.phoneapi.relay ""; setprop debug.phoneapi.mirror.secure ""; setprop debug.phoneapi.mirror.nudge_ms ""'
adb install -r pr16-build.apk && scripts/phoneapi-dev helper
```

Also turn Do Not Disturb and the screen timeout back to how they were.

## What to send back

`tar czf s9-results.tar.gz s9-results` and a `REPORT.md` laid out as follows.

### Environment
- `ro.build.fingerprint`
- Display size and density from A3
- `debug.sf.enable_hwc_vds`, and every other `debug.sf.*` and `ro.surface_flinger.*` property from A1
- The A5 output
- The display resolution set under Settings > Display > Screen resolution

### Stock screenrecord

| Test | Seconds | Frames | First frame mean_luma | Notes |
|---|---|---|---|---|
| B1a | 1 | | | |
| B1b | 1 | | | |
| B1c | 1 | | | |
| B2 | 4 | | | |
| B3 (scroll) | 4 | | | |
| B4 (mp4) | 4 | nb_read_frames: | n/a | |

### Stream runs
Fill these from each run's `.jsonl` summary line and its `.log`. For Part P runs, the log columns are "n/a".

| Label | relay/secure/nudge | Viewer 1 first_key_ms | Viewer 1 keys/deltas | Viewer 2 first_key_ms | First `mirror frame 1` at (ms, or "none") | `updateTexImage failed` count | Last `no mirror frame yet` (ms) | Notes |
|---|---|---|---|---|---|---|---|---|
| P1 | PR #16 build | | | | n/a | n/a | n/a | |
| P2 | PR #16 build | | | | n/a | n/a | n/a | |
| C0a | 1/0/0 | | | | | | | |
| … one row per run, through C6 | | | | | | | | |

### Anything unexpected
- Errors or warnings from `SurfaceFlinger`, `VirtualDisplaySurface`, `BufferQueue*`, `GLConsumer`, or the codec in any `.log`
- Crashes
- Runs you had to repeat, and why
- Anything you did differently from these steps
