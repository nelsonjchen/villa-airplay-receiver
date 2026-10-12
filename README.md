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

## Wake on playback

With launch-on-connect enabled, Villa requests a brief screen wake when the
first mirroring video data arrives, a video playback request starts, or an
audio-only session begins. It follows the wake-lock pattern observed in Sony's
reference receiver: SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP, acquired
with a 10-second safety timeout and immediately released. Discovery and initial
TCP connections do not trigger this wake request. A continuous mirror stream
only requests wake once, allowing the user to put the screen back to sleep.
This targets the Android 9 Sony; newer Android versions may impose additional
restrictions.

Validated on the target Sony: receiver running with display OFF, then a Mac
mirroring connection triggered the wake request and display ON; the user also
confirmed the screen turned on. Audio-only wake and screensaver dismissal have
not been separately tested. Cold startup on this TV can also stall in Android DEX verification before the
application runs; see the installation note below. Boot/deep-standby reliability
has not been established by an overnight test.

## Screen-off lifecycle

Turning the screen off ends the connected playback session, stops video/audio
rendering, cancels pending wake requests, and leaves the foreground receiver and
mDNS registration available. This follows Sony's separation of session cleanup
from receiver shutdown. Our current native bridge has no session-only disconnect
API, so Villa briefly stops and restarts the native listener on the same port;
it does not remove the Android mDNS registrations. Failure to rebind stops the
server rather than leaving a stale advertisement. This is an implementation
approximation, not a port of Sony's native session-finalization code.

Validated on the target TV with one live mirroring cycle: screen-off disconnected
the sender and left the foreground service running with the display off; selecting
Villa again reconnected and woke the display. The debug build and unit tests passed.
Deep standby and audio-only screen-off behavior have not been separately tested.

Picture-in-picture is disabled: leaving Villa does not open a floating player,
and playback controls have no PiP button. This is our requested OEM-style policy;
equivalent automatic PiP behavior was not established in Sony's reference player.

## Receiver lifecycle

- Leaving the player (Home or switching apps) ends the current session and releases
  audio focus, while the background receiver remains available. Configuration
  changes are excluded; observed Android screensaver broadcasts suppress this
  cleanup while dreaming. This approximates Sony's player-stop behavior and
  screensaver exception, not its proprietary screensaver state machine.
- Back retains Villa's existing local navigation. Sony forwards an AirPlay Back
  command; that protocol behavior has not been implemented.
- Playback requests media audio focus; session cleanup releases it. Focus changes
  are logged, without adding an automatic pause/duck policy not established in
  Sony's inspected callback.
- Default-network and link-property changes are debounced. The old listener and
  mDNS registrations are released and rebuilt; losing the network leaves an enabled
  foreground service waiting for its return. Manual Stop cancels recovery.
  This tracks Android's default network, not Sony's full per-interface manager.
- The service promotes itself before player/codec initialization and requests
  sticky restart. These changes cannot avoid stalls before application code runs.

On the target TV, live mirroring acquired audio focus; pressing Home disconnected
the sender, released focus, and left the foreground receiver running. The debug
build and unit tests passed. After package precompilation, a fresh process started
the foreground service and registered both mDNS services successfully. Physical
network-loss recovery, screensaver timing,
and reboot/deep-standby behavior still need device validation.

### Cold installation on the target TV

An ANR trace captured the main thread in Android's DEX verifier before application
startup. After installing a debug APK, precompile it before starting the service:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd package compile -m speed -f com.mindflakes.villaairplay
```

Use the appropriate adb server/device options for your setup. Compilation may take
several minutes on this 2016 TV. This is an installation step, separate from the
service's early foreground promotion; it must be repeated after an APK update.

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
use the build type rather than a package-name suffix. The target TV has been migrated from `io.github.jqssun.airplay.lab`, preserving
settings and the AirPlay identity. The old installation is disabled for rollback.
Changing IDs creates a separate app and requires re-pinning its launcher favorite.

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
