# Signing and releasing TrueShot to Google Play

Everything here is done once, except the build-and-upload loop at the end.

---

## 0. Before anything else: change the application ID

Open `app/build.gradle.kts` and change:

```kotlin
applicationId = "com.trueshot.camera"
```

to something under a domain you control — `com.yourdomain.trueshot`, for
example. **This is permanent.** Once an app is published under an application
ID, that ID is yours forever and cannot be changed; the only way out is to
publish a brand-new listing and lose your install base and reviews.

`namespace` (the line just above it) is the Kotlin package and does not have to
match, but keeping them the same avoids confusion.

---

## 1. Create an upload keystore

Play uses **Play App Signing**: Google holds the key that actually signs what
users download, and you hold an *upload* key that proves releases come from you.
If you lose the upload key you can ask Google to reset it. If you had opted out
of Play App Signing and lost your app signing key, your app would be permanently
un-updatable — so stay opted in, which is the default for new apps.

From the project root:

```bash
keytool -genkeypair -v \
  -keystore upload-keystore.jks \
  -storetype JKS \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias upload
```

It will ask for a password (twice) and some identity fields. The identity
fields are not shown to users; your name and country are fine.

**`-validity 10000` (about 27 years) is not arbitrary** — Play requires an
upload key valid well beyond 2033.

### Back it up now, not later

- Keep `upload-keystore.jks` and its passwords somewhere durable and private —
  a password manager is ideal.
- **Do not commit it.** `.gitignore` already excludes `*.jks` and
  `keystore.properties`, but check before your first push.

---

## 2. Wire the keystore into the build

Copy the template and fill it in:

```bash
cp keystore.properties.example keystore.properties
```

```properties
storeFile=upload-keystore.jks
storePassword=your-store-password
keyAlias=upload
keyPassword=your-key-password
```

`app/build.gradle.kts` already reads this file and configures the release
signing config from it. If the file is missing, release builds are simply
produced unsigned rather than failing — convenient for CI that does not need
to sign, but it does mean a missing file fails silently at upload time rather
than build time. Check for it if Play rejects your bundle.

---

## 3. Build the release bundle

Play takes an **Android App Bundle** (`.aab`), not an APK.

```bash
./gradlew clean bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`

Verify it is signed with the key you expect:

```bash
# Needs build-tools on your PATH, e.g.
# $ANDROID_HOME/build-tools/36.0.0/apksigner
apksigner verify --print-certs \
  app/build/outputs/bundle/release/app-release.aab
```

### Test the release build on a real Pixel first

Release builds have minification and resource shrinking on, and this is a
camera app touching a lot of platform APIs. Test the *release* build, not just
debug:

```bash
# Install the bundle as it will actually be delivered
bundletool build-apks \
  --bundle=app/build/outputs/bundle/release/app-release.aab \
  --output=trueshot.apks \
  --ks=upload-keystore.jks --ks-key-alias=upload \
  --connected-device
bundletool install-apks --apks=trueshot.apks
```

Walk through: take a photo, check both files land in `DCIM/TrueShot`, rotate to
landscape and take another (orientation is the easiest thing to get wrong),
record a clip, flip to the front camera, background and resume the app.

---

## 4. Set up the Play Console listing

1. Create a Google Play Developer account — **US$25, one time**.
   Individual accounts must complete identity verification, and since 2023 new
   individual accounts also need to run a **closed test with at least 12 testers
   for 14 continuous days** before they can apply for production access.
   Budget for this: it is the step that surprises people.
2. **Create app** → name, default language, "App", "Free".
3. Work through the **Dashboard** checklist. The ones that need thought for
   this app:

   **App access** — no login required; say so.

   **Content rating** — fill in the questionnaire. A camera utility with no
   user-generated content sharing rates as "Everyone" / PEGI 3.

   **Target audience** — not directed at children. Declaring otherwise pulls
   you into Families policy, which you do not want.

   **Data safety** — this is the one that matters here, and TrueShot's answer
   is unusually simple. The app collects and transmits **nothing**. It has no
   network permission at all (check `AndroidManifest.xml` — there is no
   `INTERNET` permission), no analytics, no crash reporting SDK, no ads.
   Photos and videos are written to the device's own media store and never
   leave it. Answer "No" to data collection and "No" to data sharing.

   Say this plainly in your store description too — it is a genuine
   differentiator against most camera apps.

   **Privacy policy** — Play requires a public URL even when you collect
   nothing. A single static page saying "TrueShot collects no data, has no
   network access, and stores photos only on your device" is sufficient. Host
   it on GitHub Pages, a Notion public page, or your own site.

   **Permissions** — you declare `CAMERA` and `RECORD_AUDIO`. Neither is a
   "sensitive permission" requiring a declaration form. You do **not** declare
   any storage permission (the app writes through MediaStore on API 29+), which
   keeps this section short.

4. **Store listing** — icon (512×512 PNG), feature graphic (1024×500), and at
   least 2 phone screenshots. Take the screenshots on a Pixel: a side-by-side
   of stock Pixel output against TrueShot output makes the pitch better than
   any copy you could write.

---

## 5. Upload

**Production** → **Create new release** → upload the `.aab` → write release
notes → **Review release** → **Start rollout**.

If your developer account is new, do the 12-tester closed test first
(**Testing** → **Closed testing**) — production will be locked until that
requirement is satisfied.

First review typically takes a few days; new accounts can take longer.

---

## 6. Shipping updates

Every upload needs a higher `versionCode` than the last. In
`app/build.gradle.kts`:

```kotlin
versionCode = 2          // must increase, every single time
versionName = "1.0.1"    // shown to users; any format you like
```

Then `./gradlew clean bundleRelease` and upload again.

---

## Target API level — keep an eye on this

Google Play requires new apps and updates to target a recent Android version,
and the bar moves every August.

**As of 31 August 2026, new apps and updates must target API 36 (Android 16).**
This project is already set to `compileSdk = 36` / `targetSdk = 36`, so you are
current. An extension to 1 November 2026 can be requested from the Play Console
if you ever need one.

Expect to bump this annually. Check the current requirement before each major
release:

- <https://developer.android.com/google/play/requirements/target-sdk>
- <https://support.google.com/googleplay/android-developer/answer/11926878>

---

## Sources

- [Meet Google Play's target API level requirement — Android Developers](https://developer.android.com/google/play/requirements/target-sdk)
- [Target API level requirements for Google Play apps — Play Console Help](https://support.google.com/googleplay/android-developer/answer/11926878)
