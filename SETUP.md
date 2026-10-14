# Health Sync Setup

Health Sync is a personal Android app that exports aggregated Health Connect
data to a Google Sheets spreadsheet with `Daily` and `Workouts` tabs.

Google Sheets access uses the signed-in Google account's OAuth permission. No
backend server or shared cloud credentials are used.

## What It Exports

The app reads from Health Connect and writes:

- `Daily`: one row per Stockholm calendar date with steps, exercise minutes,
  active/total calories, resting heart rate, sleep duration/start/end, and
  distance.
- `Workouts`: one row per workout with local date/start time, type, duration,
  and available average/maximum heart rate.

Daily summaries use Health Connect's aggregate API. Individual heart-rate
samples are not exported. Existing rows are updated by date/workout, missing
values remain blank, and data older than 90 days is removed. Daily summaries
are captured for the current date on sync; past daily rows are not backfilled.

For daily card comparison, the app prefers the wearable/app source when present
instead of mixing every Health Connect origin. Fitbit is preferred first, then
Google Fit/Health sources. This helps steps match the visible Fitbit/Google
Health app instead of adding phone and wearable step streams together.

## Privacy Notes

Do not commit any of these:

- APK files
- debug keystores or signing keys
- `local.properties`
- Gradle build output
- screenshots containing personal health data

The repository should contain source code, docs, and build configuration only.

## Requirements

- Android phone with Health Connect available.
- Fitbit, Google Fit, or another source syncing data into Health Connect.
- Google account for the spreadsheet.
- Android Studio or command-line Android build tools.
- JDK 17.

## Google Sheets API Setup

Before connecting an account:

1. Create or select a Google Cloud project.
2. Enable the **Google Sheets API** for that project.
3. Configure the OAuth consent screen. While the app is in testing, add the
   Google account you will use as a test user.
4. Create an **OAuth client ID** of type **Android** for package
   `com.healthsynceng`, using the SHA-1 certificate fingerprint of the key that
   signs the installed APK.
5. Rebuild and install the app signed by that key.

For a local debug build, the default debug keystore is typically
`%USERPROFILE%\\.android\\debug.keystore`. Use `keytool -list -v -alias
androiddebugkey -keystore "%USERPROFILE%\\.android\\debug.keystore"
-storepass android -keypass android` to get its SHA-1 fingerprint. If you use
another signing key or install a differently signed APK, configure that
certificate's fingerprint instead. OAuth configuration is kept in Google
Cloud, not embedded in the app.

## Download Public APK

The latest public APK is attached to the GitHub Release:

```text
https://github.com/manikverma675/health-sync/releases/latest/download/HealthSync.apk
```

This APK is ready for sideloading on Android 8.0+ phones. Health Connect export
still depends on the phone having Health Connect available and the user granting
Health Connect permissions.

To install:

1. Download `HealthSync.apk` on your Android phone.
2. Open the APK.
3. If Android blocks it, enable **Install unknown apps** for the browser or file
   manager you used.
4. Install and open **Health Sync**.

## Build Locally

From the Android project directory:

```bash
cd android
gradle assembleDebug
```

The APK is generated at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

If you use Android Studio, open the `android` directory and run the `app`
configuration.

## Install And Use

1. Install the APK on your Android phone.
2. Open Health Sync.
3. Tap **Connect Health Connect**.
4. Grant the Health Connect categories you want exported.
5. Tap **Connect Google Sheets** and choose the Google account to use.
6. The app creates a spreadsheet named **Health Sync** with `Daily` and
   `Workouts` tabs.
7. Tap **Sync Now**.
8. Optional: tap **Start Auto Sync**.

## Auto Sync

Auto sync uses Android WorkManager.

- It requests a background sync about every 15 minutes.
- Android may delay it for battery or system scheduling reasons.
- It runs as foreground data-sync work while exporting.
- It updates today's summary and recent workout rows, retaining up to 90 days.
- When enabled, the app also runs one immediate sync.
- The app may ask for notification permission and battery optimization
  exemption so Android is less likely to kill sync in the background.
- If the app is reinstalled, the Google account and Health Connect permissions must
  be connected again.

## File Interpretation

When using Claude or another assistant with the spreadsheet:

- Use `Daily` for daily totals and `Workouts` for exercise sessions.
- Dates and workout times use Stockholm local time.
- Blank cells mean unavailable data, not zero.
- Workout heart-rate values are aggregates; individual samples are not exported.

## GitHub Actions

The included workflow builds a debug APK on pushes to `main` and manual runs.
The APK artifact is uploaded to the workflow run.
