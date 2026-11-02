# MedConsults data sync

Pulls the data behind the **Medication Matrix** page (drug records, interaction pairs, class-hazard lists, alternative-finder tables) into versioned JSON, keeps it fresh with GitHub Actions, and gives your Android app a small client that downloads it, verifies it and caches it for offline use.

```
medconsults.ai page ──▶ scripts/extract.mjs ──▶ data/*.json + manifest.json ──▶ GitHub ──▶ Android app (cached on device)
```

## 1 · Extract the data (once, on your computer)

Needs Node 20 or newer.

```bash
npm install
npx playwright install chromium
node scripts/extract.mjs
```

It opens the page in a headless browser and exports every data table it finds. You do **not** need to know any variable names. It looks in three places: JSON files the page downloads, top-level variables, and (as a fallback) big tables held in memory, which catches data hidden inside a function wrapper or a module.

| File | What it is |
|---|---|
| `data/<name>.json` | one file per table (drugs, interactions, class lists …) |
| `data/manifest.json` | file list, SHA-256 of each file and a `dataVersion`; the app uses it to see what changed |
| `data/schema.json` | key names and a sample record for every table; for you, the app never downloads it |

Open `data/schema.json` to see what was found. Tables found in memory rather than by name are called `heap-…` and can be renamed or deleted. Page-level noise can be dropped with `--exclude NAME,NAME`.

If it says *No data structures found*, try `--heap`, then `--min 2`, then `--save-scripts` (saves the page's HTML and JS to `./source` so you can see where the data lives). Data that only loads after you click something is not seen.

Re-running is safe: if nothing changed it does nothing, and it refuses to overwrite good data with a result that is much smaller than last time (override with `--force`).

## 2 · Put it on GitHub

1. Create a repo, push this folder (including `data/`).
2. **Actions → Sync medication data → Run workflow.** It also runs every Monday and commits only when the data changed. If the push is rejected, enable *Settings → Actions → General → Workflow permissions → Read and write*.
3. Your data folder is now at `https://raw.githubusercontent.com/<user>/<repo>/main/data/`.

`raw.githubusercontent.com` is fine while developing. For a released app, publish `data/` with GitHub Pages or on your own domain instead (raw links are cached for a few minutes and rate-limited). GitHub also pauses scheduled workflows in public repos after 60 days without repo activity.

## 3 · Android

**Gradle** (`app/build.gradle.kts`): use your Kotlin version for the plugin, and bump the libraries if Android Studio suggests it.

```kotlin
plugins { id("org.jetbrains.kotlin.plugin.serialization") }

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
```

`AndroidManifest.xml`: `<uses-permission android:name="android.permission.INTERNET" />`

1. Copy `android/MedDataRepository.kt` and `android/MedDataSyncWorker.kt` into your app. Fix the `package` line and `DEFAULT_BASE_URL`.
2. Copy `data/*.json` (not `schema.json`) into `app/src/main/assets/med-data/`, so the app works offline on first launch.
3. In `Application.onCreate()`: `MedDataSyncWorker.schedule(this)`
4. Use it:

```kotlin
val repo = MedDataRepository(applicationContext)

// inside a coroutine
repo.sync()                                // optional: refresh right now, never throws
val tree = repo.readTree("drugs.json")     // generic JsonElement, works before you write classes
val source = repo.manifest()?.source       // "Content current as of August 2026 (v69)": show it in your UI

// once you have @Serializable classes (generate them from data/schema.json):
// val drugs: List<Drug> = repo.load("drugs.json")
```

Downloads go to a staging folder, are checked against the SHA-256 in the manifest, and only then replace the live files, so a dropped connection never leaves half-updated data.

## 4 · The checker and finder logic is not in the data

The tables are only half of what the page does. Screening a regimen against the pairs, flagging any two QT-prolonging or serotonergic drugs, and ranking alternatives by eGFR, Child-Pugh, pregnancy, pediatric band and so on is JavaScript on the page. For the app you can:

- **Port that logic to Kotlin.** Best experience, fully native and offline. Run `node scripts/extract.mjs --save-scripts` and the page's code lands in `./source`.
- **Embed the page in a WebView.** Fastest, a few lines, always identical to the website:

```kotlin
WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    loadUrl("https://medconsults.ai/medication-matrix-prescriber-edition.html")
}
```

## Before you publish the app

- Keep the page's *educational reference, not a prescribing authority* disclaimer and the *content current as of* date visible in the app.
- Features that screen interactions or suggest alternative drugs can count as clinical decision support / medical-device software in some regions (EU MDR, FDA) and fall under Google Play's health-app policies. Worth checking before release.
