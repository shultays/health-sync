# Health Sync - Google Fitbit AI Coach Killer

Health Sync is a small Android app for exporting aggregated Health Connect data
to Google Sheets.

It is designed for personal use with AI assistants: your phone reads Health
Connect, updates a private spreadsheet, and your assistant can read the `Daily`
and `Workouts` tabs.

## Why This Exists

Many fitness apps lock useful coaching summaries behind premium dashboards.
Health Sync takes a different route:

- your phone owns the export
- Health Connect remains the source of truth
- Google Sheets stores compact daily and workout summaries
- an AI assistant can explain the data with your own instructions

This is not a medical device and it does not clone every proprietary Fitbit or
Google insight. It does give users a practical, open workflow for evidence-based
daily coaching without needing a custom backend or paid health-dashboard layer.

## How It Works

1. Fitbit or another health app syncs data into Health Connect.
2. Health Sync requests Health Connect read permissions.
3. You connect your Google account and the app creates a Health Sync spreadsheet.
4. The app updates today's summary and recent workout rows in its tabs.
5. Optional background sync keeps the file updated.

No backend server or API key is stored in the app. Google Sheets access uses
the signed-in user's OAuth permission; Google Cloud OAuth setup is required.

## Download APK

Download the latest installable APK from GitHub Releases:

```text
https://github.com/manikverma675/health-sync/releases/latest/download/HealthSync.apk
```

This is a universal debug-signed APK intended for sideloading. It works on
Android 8.0+ phones, but Health Connect data export requires Health Connect to
be available on the device and permissions to be granted by the user.

## Data Model

The spreadsheet has two tabs:

- `Daily`: one row per Stockholm calendar day, with activity, calories, average
  heart rate, sleep, and distance summaries.
- `Workouts`: one row per workout, with local date/time, type, duration, and
  available average/maximum heart rate.

The app uses Health Connect aggregates rather than exporting individual
heart-rate samples or summing overlapping raw records. It updates existing
dates/workouts instead of creating duplicates and retains up to 90 days.
Missing values remain blank. Activity summaries are recorded for the current date. Sleep totals also
update recent past dates using the session end date.

Daily summaries prefer the wearable/app source when it is present, currently
Fitbit first, then Google Fit/Health sources. This prevents phone step records
and wearable step records from being mixed together when the goal is to match
the visible Fitbit/Google Health card.

Workout heart-rate values are aggregate averages and maxima; individual samples
are not exported.

## Claude Project Setup

Create a Claude Project for health coaching:

1. Open Claude.
2. Create a new Project, for example `Health Coach`.
3. In Project instructions, paste the prompt below.
4. Add your private Health Sync Google spreadsheet as a connected source.
5. Ask Claude questions grounded in the file, such as:
   - "What changed in my sleep last night?"
   - "Am I progressing toward 8,000 steps consistently?"
   - "Compare today's recovery signals with yesterday."

Use your own private spreadsheet URL. Do not commit it to this repository.

```text
You are reading my Health Connect export in a private Google Sheets spreadsheet. Use the Daily tab for daily trends and Workouts for exercise sessions.

Daily:
- Each row is a Stockholm calendar date. Use this for activity and recovery trends.
- Missing cells mean unavailable data; do not treat them as zero.
- Activity updates today's row; sleep totals also update recent past dates.

Workouts:
- Each row is a workout with local date/time, type, duration, and available average/maximum heart rate.
- Heart-rate values are workout aggregates; individual samples are not exported.

Use only available spreadsheet values. Blank cells mean unavailable data, not zero.
Use Stockholm dates for comparisons, and do not diagnose medical conditions.

Answer style:
- Start with the factual takeaway and cite dates/metrics used.
- Separate evidence from interpretation; if data is missing, say so.
- Be encouraging without exaggerating. For concerning patterns, suggest
  discussing them with a qualified clinician.
```

## Supported Data

The app requests all read record types supported by the bundled Health Connect
SDK, including:

- steps, distance, calories, exercise sessions, floors, speed, power, cadence
- heart rate, resting heart rate, HRV, respiratory rate, oxygen saturation
- sleep sessions and sleep stages
- weight, height, body fat, body water mass, bone mass, lean body mass
- nutrition and hydration
- menstrual health records where available
- mindfulness sessions

Only data that exists in Health Connect and is granted by the user can be
exported.

## Privacy

This repository should not contain personal health data or secrets.

Ignored by default:

- generated APK files
- signing keys and keystores
- `health_data.json` (legacy local exports)
- local Android config
- Gradle build output
- screenshots and OS metadata

## License

MIT License. See [LICENSE](LICENSE).

## Build

See [SETUP.md](SETUP.md) for setup, build, install, and usage instructions.

GitHub Actions can build a debug APK from source and upload it as a workflow
artifact. Tagged releases publish `HealthSync.apk` for direct download.

## Background Sync Reliability

Auto sync uses WorkManager and runs sync as foreground data-sync work so Android
is less likely to kill the job mid-export. When enabled, the app syncs once and
then updates the spreadsheet about every 15 minutes. Android can still delay
periodic work, but this keeps current-day data fresh.

### Export updates

Daily rows are matched by Stockholm date, so a new day gets its own row.
Only changed cells are written; an unchanged sync does not edit the spreadsheet.
Total kcal is no longer exported; existing sheets migrate on the next sync.
Steps update only when they exceed the saved count by more than 100 (150 stays
until at least 251). Missing daily values preserve previously exported values.

Daily exports Avg HR from ordinary heart-rate samples in place of Resting HR.
Activity kcal and heart-rate aggregates use all available Health
Connect sources independently of the source selected for steps. Activity kcal
keeps two decimal places. Historical activity rows are not backfilled; sleep totals are.

Total sleep h sums completed sleep-session durations by their end date in
Europe/Stockholm, including split nights and naps. Overlapping sessions count
once. Sleep start/end columns are removed on sync. Sleep history is read for
90 days with history access, otherwise 30 days. No sleep records means blank.

Daily HR columns (BPM): Avg HR is the Health Connect daily aggregate; Awake HR
is the sample average outside recorded sleep and exercise intervals; Exercise
HR is the duration-weighted mean of available workout averages for workouts
starting that day; Sleep HR averages samples within all sleep sessions ending
that day, including the previous evening. Context HR columns update for today.
Intervals include their start and exclude their end. Identical timestamp/BPM
samples count once. Missing qualifying readings remain blank. Awake HR is not
resting HR; incomplete session records can affect classification.
