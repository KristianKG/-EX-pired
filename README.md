# ExpiryEye

ExpiryEye is an offline-first Android food inventory and expiration tracker. No account, backend, or API keys are required.

## Build

Open this directory in Android Studio with JDK 17 and Android SDK 35, then sync Gradle. The checked-in Gradle Wrapper pins Gradle 8.9, so build without a separate Gradle installation using `gradlew.bat :app:assembleDebug` on Windows or `./gradlew :app:assembleDebug` on macOS/Linux. Set `ANDROID_HOME` or `sdk.dir` in the untracked `local.properties` file to the Android SDK location.

## Implemented

- Room persistence for food records and consumed history.
- Add, edit, delete, partially or fully consume, search, and filter by storage location. Records include purchase/opened dates and notes; discarding asks for a reason and removes the item from the active inventory.
- Expiry urgency on the dashboard, including contextual use-soon suggestions.
- WorkManager reminders at 7, 3, 1, and 0 days before the entered date. Android notification permission is requested after the first item is added; delivery time can vary due to Android background scheduling.
- Configurable local reminder intervals at 14, 7, 3, 1, and 0 days.
- Reminder taps open the corresponding food item, and a daily WorkManager check refreshes future reminders against the current local date and selected reminder intervals.
- Offline recipe suggestions with ingredient-match counts and missing ingredients.
- Persistent recipe favorites and filters for meal type, quick meals, vegetarian, high-protein, low-cost, and use-soon recipes. Matching dishes are ranked higher when they use inventory nearing its entered date.
- Persistent shopping list, including adding missing recipe ingredients.
- Week, month, and year impact summaries, a weekly consumption chart, and estimated value for items marked consumed when a price was entered.
- Persisted dark theme preference.
- CSV inventory export and versioned JSON backup/restore, with record validation and explicit replace confirmation.
- A confirmed clear-data flow and Room migration for the shopping list.
- Three-screen first-run introduction with skip, explore, and add-first-item paths.
- CameraX barcode scanning through bundled, on-device ML Kit; online Open Food Facts lookup can prefill product name, brand, and category. Barcode reading itself works offline, and the expiry date always requires user entry.
- On-device receipt OCR through bundled ML Kit Latin text recognition. Product name, likely price, and simple leading quantity are suggestions only; the user edits/confirms each candidate and enters its expiration date before it is saved. OCR accuracy depends on receipt quality; the bundled recognizer targets Latin script.
- Original adaptive launcher artwork, Android 12+ splash screen, and monochrome notification icon.
- Release minification/resource shrinking configuration and explicit Java/Kotlin JVM 17 targets. Device backup is disabled; users can make their own JSON backups.

## Still outstanding

The screen UI is currently English; only the launcher label has a Bulgarian resource. Other remaining work includes richer recipe content (images, servings, difficulty), daily summary, waste trends by category and time, broader database/UI tests, completing repository/Hilt architecture and optional image loading, and final Play Store metadata/signing setup. Users can add custom storage locations in Settings and select them when editing products. This app does not claim food is safe based only on appearance or smell, and it never changes entered dates automatically.

## Build verification

`gradlew.bat :app:assembleDebug` completed successfully on Windows with JDK 17 and Android SDK 35. `gradlew.bat :app:testDebugUnitTest` passed all 14 unit tests. Instrumented/device tests and a signed release bundle have not been run.
