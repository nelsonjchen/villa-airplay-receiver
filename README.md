# Villa AirPlay Receiver

An Android TV AirPlay receiver fork tuned for the Sony TV at Villa. Based on
[jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server)
v0.0.31 (`c8defdd`), using [UxPlay](https://github.com/FDH2/UxPlay).
The app label and advertised AirPlay/mDNS name are **Villa AirPlay Receiver**.

## Before and after

**Before: roughly 1–2 seconds behind the Mac. After: much more responsive,
with audio sync the user found acceptable.** Tested on a **2016 Sony BRAVIA
XBR-65X750D**; this is tuning for that TV, not a claim about all Android TVs.

| | Original high-resolution setup | Tuned 1080p setup |
| --- | --- | --- |
| Experience | Noticeable 1–2 second lag | User reported substantially better responsiveness |
| Receiver-local timing | Roughly 800–1,000 ms callback-to-output residence | Typical window median around 73 ms; typical window p95 around 157 ms |
| Stability | Growing arrival delay | 30-minute run without sustained delay buildup or logged codec errors |
| Audio sync | No quantified baseline | User judged the flash/tone check close enough to be acceptable |

The main improvement came with **1080p/up-to-60-FPS configuration and decoder
scheduling changes**. Their separate contributions were not isolated. The
numbers above measure time inside the receiver, **not screen-to-screen delay**;
physical latency remains unmeasured and occasional spikes remain.

A subsequent output-polling experiment showed a smaller, tentative improvement
(67→57 ms typical window median). That is a separate comparison, described
below—not the full before/after improvement.

## Target TV

Tested on the **Sony BRAVIA XBR-65X750D**, a **65-inch 4K TV from the 2016
model year** ([Sony specifications](https://www.sony-latin.com/es/electronics/support/televisions-projectors-lcd-tvs-android-/xbr-65x750d/specifications)).
The exact retail model was verified through Sony's System Information screen
opened over ADB; Android's generic model property only reports **BRAVIA 4K GB**.

The target runs **Android 9**, firmware **PKG6.7220.0365NAA**, on the
**BRAVIA_ATV2** platform with a **32-bit ARM (`armeabi-v7a`)** app runtime.
Video uses the MediaTek hardware decoder **OMX.MTK.VIDEO.DECODER.AVC**.
This fork's tuning has not been validated on other TVs.

The working receiver configuration advertises **1920×1080, up to 60 FPS**.
The latest Mac mirroring session actually encoded **1662×1080 H.264**, matching
its mirrored desktop aspect ratio. Screen video uses **TCP**.

## Changes from upstream

- **Drain finished output before feeding input.** Return decoded buffers before
  asking MediaCodec for another input slot, so finished pictures are not left
  waiting while more compressed data is submitted.
- **Shorter individual input waits.** Reduced the dequeue wait from 20 ms to
  1 ms, increasing retry counts to preserve nominal total allowances of 200 ms
  during playback and 1 second for the first frame. Output is checked between
  attempts.
- **Independent output polling, opt-in.** `lab_output_poll=true` checks decoded
  output every 2 ms without adding an input queue. Codec access is serialized;
  cancellation and codec identity checks protect resets and shutdown. Enabled
  on the target TV, but **false by default in source**.
- **Timing instrumentation.** Logs callback-to-output residence, time before
  codec submission, input waits, pending frames, presentation scheduling, and
  GL texture/draw timing. These are local pipeline measurements, not physical
  screen-to-screen latency.
- **Villa branding and application identity.** Builds use
  `com.mindflakes.villaairplay`; debug builds retain diagnostics and target
  `armeabi-v7a`. A lab-only discovery identity override supports experiments.
- **Opt-in comparison paths.** Bounded input worker, direct display surface,
  and Sony vendor output configuration were explored. They did not establish
  an improvement and remain disabled on the target TV. Worker lifecycle tests
  are included; polling is disabled when worker mode is selected.

The 1080p limit is a retained TV preference, not a universal hard-coded limit.
Low-latency decoder options already existed upstream; we do not claim those
as a new Villa feature or assume the hardware honors every accepted option.

## Results and limits

The retained 1080p configuration completed a 30-minute run with no logged codec
errors, input-full drops, crashes, or sustained delay buildup. Across steady
windows, median p50 residence was about 73 ms and median p95 about 157 ms.
Occasional spikes remained. Resolution and the earlier scheduling changes were
not independently isolated.

A later short comparison of independent polling showed:

| Receiver-local statistic | Before polling | Polling enabled |
| --- | ---: | ---: |
| Median of window p50 values | 67 ms | 57 ms |
| Median of window p95 values | 147 ms | 129 ms |

Content was not controlled and reconnecting changed the session, so this is
suggestive evidence, not a proven causal improvement. These are medians of
window percentiles, not pooled frame percentiles. CPU/power overhead and
physical display latency have not been measured. Long-tail delays remain.

HEVC decoding exists upstream, but **no successful HEVC mirroring session has
been demonstrated on this setup**. Advertising HEVC or 4K did not make the Mac
select it. UDP video interoperability remains an investigation, not an
implemented feature. DRM-protected content is unsupported.

## Package migration

The application ID is `com.mindflakes.villaairplay` (from mindflakes.com).
Internal Kotlin/JNI namespaces retain the upstream names. Debug-only diagnostics
use the build type rather than a package-name suffix. The TV currently still has
the earlier `io.github.jqssun.airplay.lab` installation: the new ID installs as a
separate app and requires settings migration and re-pinning the launcher favorite.
No TV migration was performed as part of this source change.

## Build

For a normal source build, install the Android SDK and NDK requested by the
Gradle project, initialize native dependencies, then build:

```sh
git submodule update --init --recursive
./gradlew testDebugUnitTest assembleDebug
```

Local iteration used JDK 21 and `-PbaselineNative`, which bypasses native
compilation and uses pre-existing libraries in `app/baseline-native/`:

```sh
./gradlew -PbaselineNative testDebugUnitTest assembleDebug
```

Those libraries are ignored and **not supplied by this repository**. A fresh
clone should use the normal source build. The baseline-native build and existing
unit tests passed locally; a fresh full native build has not been validated for
this fork. Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Investigation and recovery

Local research notes, raw measurements, prior APKs, and settings backups live
separately in `~/code/airplay-receiver-lab`. Firmware, Apple binaries/disassembly,
raw device logs, and local build artifacts are not included here. Before changing
the TV, preserve its installed APK and preferences so a failed experiment can
be rolled back. The polling experiment can be disabled by restoring the prior
preferences and restarting the receiver.

## Development

The Villa fork was developed with **GPT-6 Astra** in Codex, including the
Sony TV investigation, latency experiments, implementation changes, and
documentation. Measurements and remaining limitations are described above.

## License and credits

GPLv3; see [LICENSE](LICENSE). Original Android receiver by
[jqssun](https://github.com/jqssun/android-airplay-server), protocol implementation
by [UxPlay](https://github.com/FDH2/UxPlay), with
[FFmpeg](https://ffmpeg.org) audio decoding and
[Next Player](https://github.com/anilbeesetti/nextplayer) video-player work.
Not affiliated with Apple or Sony.
