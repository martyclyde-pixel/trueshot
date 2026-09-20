# TrueShot

A camera app for Pixel phones that saves what the sensor saw, not what the
software decided you wanted.

---

## What problem this actually solves

It is worth being precise about this, because the honest version of the pitch
is narrower than "no processing" — and knowing which is which will save you
from chasing something the hardware won't give you.

There are **three** separate layers of processing on a Pixel, and a third-party
app can only reach two of them.

**Layer 1 — HDR+ / Night Sight.** Google Camera captures a burst of frames,
aligns and merges them, then applies aggressive local tone mapping. This is the
source of the signature "Pixel look": lifted shadows, compressed highlights,
that slightly HDR-ish flatness. **Third-party apps never get this pipeline.**
It is proprietary and not exposed through Camera2. So simply by not being
Google Camera, TrueShot already avoids the biggest offender.

This also explains something you may have already noticed: Google Camera's own
"RAW" DNG is *not* untouched sensor data. On Pixels it is written from the
HDR+ merged result, so the tone mapping is already baked in before the file is
saved. That is almost certainly the thing that made you want this app.

**Layer 2 — the vendor ISP.** Every Camera2 app, including this one, gets
frames through the image signal processor, which by default applies spatial
noise reduction, edge enhancement (sharpening, with halos), chromatic
aberration correction and a filmic S-curve tone map. Most third-party camera
apps leave these at their defaults and ship the vendor look unchanged.
**TrueShot explicitly turns all of them off.** This is the app's real work, and
it all lives in `CaptureTuning.kt` — a short, heavily commented file that is
worth reading if you want to change the look.

**Layer 3 — the sensor.** Below the ISP there is the Bayer data itself. The
`.dng` TrueShot writes is that data, straight from `RAW_SENSOR`, with nothing
applied.

### What is deliberately left ON

Not everything the ISP does is a "look". These stay enabled on purpose, because
switching them off would move the image *away* from the scene, not toward it:

| Kept | Why |
|---|---|
| Lens shading correction | The corners genuinely receive less light. Correcting vignetting is optics, not styling. |
| Hot pixel correction | Dead photosites are a defect, not detail. |
| Optical stabilisation | A physical lens element. Free sharpness, zero fidelity cost. |
| Auto exposure / auto white balance | Without them you get a manual camera, which is a different app. |
| Electronic stabilisation, **video only** | Handheld footage is unwatchable without it. Off for stills. |

### The honest caveat

"Exactly what I saw" and "unprocessed" are not quite the same thing, and it is
better to know this before you build than after.

Your eye has vastly more dynamic range than a phone sensor, and it does its own
local adaptation continuously. A single un-tone-mapped exposure of a
high-contrast scene will have blown highlights or crushed shadows where your eye
saw detail in both. That is not a bug in this app — it is the reason HDR+ exists
in the first place.

So what TrueShot promises is the achievable version: **no cosmetic
reinterpretation, and a viewfinder that tells the truth.** The preview request
carries the exact same tuning as the capture request, so what you frame is what
lands in the gallery. Expect files that look flatter and grainier than stock
Pixel output. That flatness is the point — it is headroom you can edit, rather
than decisions already made for you.

---

## What each shutter press produces

With the default settings, one tap writes two files to `DCIM/TrueShot`, sharing
a basename so they stay paired:

- `TS_20260919_143022_881.jpg` — full-resolution JPEG at quality 100, captured
  with noise reduction, edge enhancement and the filmic tone curve disabled, and
  a plain gamma 2.2 ramp in their place.
- `TS_20260919_143022_881.dng` — the raw Bayer frame with full capture metadata,
  written with `DngCreator`. Roughly 25 MB. Opens in Lightroom, Darktable,
  RawTherapee, Capture One.

Video records to `.mp4` in the same folder, at 4K where the device supports it,
with the same neutral tuning on the recording request and a bitrate 1.5× the
profile default (un-denoised frames compress worse, and it would be silly to
preserve real grain and then let the encoder smear it).

The status pill at the top of the viewfinder reports what the device *actually*
allowed — e.g. `Off: noise reduction, sharpening, tone curve`. Every override is
capability-checked, and if a device refuses one, the pill says so rather than
pretending.

---

## Building it

**Just want an APK to test on your phone? See [BUILD_APK.md](BUILD_APK.md)** —
it covers a GitHub Actions path that needs nothing installed locally.

You need **Android Studio Ladybug or newer** and a JDK 17+.

```bash
git clone <your repo>
cd TrueShot
./gradlew assembleDebug
```

Then install on a connected Pixel:

```bash
./gradlew installDebug
```

Or just open the folder in Android Studio and press Run.

> **Note:** this project was written and reviewed but has not been compiled or
> run on a device — the environment it was authored in had no access to the
> Android SDK. Expect to fix a small number of build-time nits on first open.
> The logic has been through two adversarial review passes; the Camera2 state
> machine, buffer lifecycle and orientation maths are the parts that were
> scrutinised hardest.

### Before you publish

1. **Change the application ID.** It is currently `com.trueshot.camera`
   (`app/build.gradle.kts`). Change it to something under a domain you control.
   It can never be changed once the app is live on Play.
2. Set up signing — see `RELEASE.md`.

---

## How it is put together

```
app/src/main/java/com/trueshot/camera/
├─ MainActivity.kt           UI, permissions, lifecycle, mode switching
├─ camera/
│  ├─ CaptureTuning.kt       ★ the actual point of the app — read this first
│  ├─ CameraEngine.kt        Camera2 device, session, capture state machine
│  ├─ CameraChooser.kt       Which camera to open, which output sizes
│  └─ VideoRecorder.kt       MediaRecorder + its MediaStore entry
├─ io/MediaStoreWriter.kt    Gallery writes (no storage permission needed)
└─ ui/AutoFitSurfaceView.kt  Preview that letterboxes to the sensor ratio
```

A few decisions worth knowing about if you extend it:

**Camera2 directly, not CameraX.** CameraX's `ImageCapture` did not support DNG
output until very recently, and CameraX Extensions is precisely the vendor
processing layer this app exists to avoid. Raw Camera2 is more code but gives
unambiguous control over every request key.

**Stream combination.** Photo mode configures preview (PRIVATE) + JPEG
(MAXIMUM) + RAW_SENSOR (MAXIMUM). That exact trio is in the Camera2
guaranteed-configuration table for any device advertising the RAW capability, so
it is safe on every Pixel main sensor. It is also slow — roughly one shot per
second — which is the honest cost of writing 25 MB per frame.

**SurfaceView, not TextureView.** Preview buffers go straight to the display
compositor with no intermediate GPU copy, and the sensor→display rotation comes
free from the surface transform hint.

**Threading.** Every public `CameraEngine` method hops onto the engine's own
`HandlerThread`, and all engine state is touched only from there. Camera2
callbacks land on that same thread, so there is no locking anywhere. The
`Listener` is therefore called on the camera thread and `MainActivity` posts to
main itself.

**RAW buffer lifecycle.** A `RawCapture` holds a native image buffer that
*must* be closed — leaking one permanently consumes a slot in the RAW
`ImageReader`, and exhausting the reader crashes the process. Saves run on a
plain `ExecutorService` rather than `lifecycleScope` for exactly this reason: a
cancelled coroutine would strand the buffer.

**The front camera is not mirrored.** Preview and saved file match, which is the
app's whole promise. It will feel unfamiliar if you are used to a mirrored
selfie preview.

---

## Known limitations

- **Logical cameras only.** The ultrawide and telephoto on a Pixel are physical
  sub-cameras and usually do not support RAW. Exposing them is a reasonable v2
  feature; `CameraChooser` is where it would go.
- **No zoom.** Deliberate for v1 — digital zoom is resampling, which is
  processing.
- **No HDR bracketing.** By design. If you want merged exposures you want HDR+,
  which is the thing this app exists to avoid.
- **Some HALs ignore overrides.** A device can advertise `NOISE_REDUCTION_MODE_OFF`
  and still apply mild denoise internally. The DNG is unaffected either way —
  that is the guaranteed-clean path.
- **DNG previews vary by gallery app.** Google Photos handles them; some file
  browsers only show a placeholder.

---

## License

Unlicensed — add one before publishing. MIT or Apache-2.0 are the usual choices
for something like this.
