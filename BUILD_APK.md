# Getting an installable APK

I could not build the APK for you: the environment this project was written in
has no Android SDK and blocks Google's Maven and SDK servers, so no Android
build of any kind can run there. Below are two ways to get one, and honest notes
on what is likely to go wrong the first time.

**Read "What to expect on the first build" at the bottom before you start.** The
project has been reviewed twice but never compiled, so plan for one or two
rounds of small fixes rather than a clean first run.

---

## Option A — GitHub Actions (no installs, ~10 minutes)

GitHub builds it on their machines for free and hands you an APK you can
download on the phone. This is the fastest path if you don't already have
Android Studio, and it is the one I'd suggest.

A workflow file is already included at `.github/workflows/build-apk.yml`, so
there is nothing to configure.

1. Create a free account at <https://github.com> if you don't have one.
2. Create a new **private** repository — call it `trueshot`. Don't add a README
   or a .gitignore; you want it empty.
3. Upload the project. Either:

   **Via the website** (no tools needed): on the empty repo page click
   *uploading an existing file*, then drag in the **contents** of the unzipped
   `trueshot` folder — not the folder itself.

   > The web uploader skips dotfolders, so `.github` won't make it. After the
   > first upload, click **Add file → Create new file**, type
   > `.github/workflows/build-apk.yml` as the filename (the slashes create the
   > folders), and paste in the contents of that file from the zip.

   **Or via git**, if you have it:
   ```bash
   cd trueshot
   git init && git add -A && git commit -m "TrueShot v1"
   git branch -M main
   git remote add origin https://github.com/YOUR-NAME/trueshot.git
   git push -u origin main
   ```

4. Open the **Actions** tab. A run called *Build APK* starts on its own. Give it
   three to five minutes.
5. **If it's green:** open the run, scroll to **Artifacts**, download
   `TrueShot-debug-apk`. You can do this in the phone's browser — sign in to
   GitHub on the phone and the download lands in your Downloads folder.
6. **If it's red:** open the run, click the failed step, and copy the error
   text. Paste it back to me and I'll fix it — build errors are quick to
   resolve once I can see them.

### Installing it on the Pixel

The APK is a **debug** build, signed with Android's standard debug key. It
installs fine; it just isn't a Play Store build.

1. Tap the downloaded `.apk` in Files or Chrome downloads.
2. Android will say installs from this source aren't allowed. Tap **Settings**,
   turn on **Allow from this source**, then go back and tap **Install**.
3. Open TrueShot and grant camera (and microphone, for video) access.

---

## Option B — Android Studio (best if you plan to keep working on it)

Slower to set up the first time (the SDK download is a few GB) but then builds
take seconds, and you get the error messages directly, which makes iterating far
less painful.

1. Install Android Studio: <https://developer.android.com/studio>
2. **File → Open**, pick the unzipped `trueshot` folder. Let it finish the
   first Gradle sync — several minutes, and it downloads the SDK it needs.
3. Plug in the Pixel with USB debugging on (*Settings → About phone*, tap
   **Build number** seven times, then *Settings → System → Developer options →
   USB debugging*).
4. Press **Run** (the green triangle). It builds, installs and launches.

To get a shareable APK file instead: **Build → Build Bundle(s) / APK(s) →
Build APK(s)**. It lands in `app/build/outputs/apk/debug/`.

---

## What to expect on the first build

Being straight with you about where the risk sits, so nothing comes as a
surprise.

### Build errors — possible, and easy

Here's exactly how far the code has been verified, so you know what's left.

**A real Kotlin compiler has now parsed every source file.** Gradle ships
`kotlin-compiler-embeddable`, so the K2 compiler was run over all seven files.
It produced 2,448 diagnostics — and every single one traces back to `android.*`
being unresolved, because there's no `android.jar` to compile against. After
filtering those and their cascades, **zero syntax or structural errors remain.**

That rules out a whole class of problems: unbalanced braces, malformed lambdas,
bad label syntax. It specifically clears the one construct I was least sure of —
the `fun takePhoto(...) = post { ... return@post ... }` pattern used throughout
`CameraEngine`, which parses correctly.

On top of that: two adversarial review passes found and fixed 19 real defects,
and every `R.string`/`R.drawable`/`R.id` reference, every view-binding property
and every import has been mechanically checked.

**What is still unverified is type-checking against the real Android SDK** — that
genuinely needs `android.jar`, which only a real build provides. So the
remaining risk is things like a constant spelled slightly differently in API 36,
or an argument type that doesn't match. Expect zero to three of these.

They're the cheap kind of problem: the compiler names the file and the line.
Send me the output and I'll turn it around.

### Runtime behaviour — needs your actual phone

Some things genuinely cannot be settled without a Pixel in hand:

| What | How you'll know | If it's wrong |
|---|---|---|
| Does the HAL honour the processing overrides? | The status pill at the top reads e.g. `Off: noise reduction, sharpening, tone curve`. It reports what your device *actually* accepted. | If it says `Device rejected processing overrides`, the JPEG path is limited — but the DNG is still fully raw. |
| Is the preview the right way up and not stretched? | Obvious immediately. | Tell me what you see and I'll correct the orientation maths. |
| Does preview + JPEG + RAW work together on your Pixel? | If not, you'll get "Camera could not be configured". | Spec says it's guaranteed on RAW-capable sensors, but vendors vary. Fixable by dropping to JPEG-only. |
| Landscape photos right way up? | Take one rotated 90°, check the gallery. | This was a confirmed bug that got fixed; worth verifying. |
| Capture speed | Roughly one shot per second. | That's the honest cost of writing 25 MB per frame. |

### The test that actually matters

Once it runs, shoot the same high-contrast scene twice — once in Google Camera,
once in TrueShot — and compare at 100%. Look at shadow detail, at grain
structure in flat areas, and at high-contrast edges for sharpening halos. That
comparison tells you whether this solves your problem far better than any
description of it does.

Then open the `.dng` in Lightroom or Snapseed. That file is the real prize: a
genuinely unprocessed capture, unlike Google Camera's DNG, which is written from
the already-merged HDR+ result.

---

## Something else to try first (costs nothing)

Before you invest in the build, it's worth confirming the premise on your own
phone. Install any Camera2-based third-party camera and take a shot. Because
third-party apps never get HDR+, the output will already look noticeably less
processed than Google Camera's.

If that difference alone is what you were after, you may not need this app at
all. If it's closer but still too sharpened or too smoothed, that residual is
exactly the ISP layer TrueShot turns off — and you'll know the build is worth
doing.
