# ExpiryEye

ExpiryEye is an offline-first Android food inventory and expiration tracker. No account, backend, or API keys are required.

## Build

Open this directory in Android Studio with JDK 17 and Android SDK 35, then sync Gradle. Build with `gradle :app:assembleDebug` where Gradle is installed. This workspace currently has no Android SDK or Gradle executable, so compilation has not been verified here.

## Implemented

- Room persistence for food records and consumed history.
- Add, edit, delete, consume, search, and filter by storage location.
- Expiry urgency on the dashboard, including contextual use-soon suggestions.
- WorkManager reminders at 7, 3, 1, and 0 days before the entered date. Android notification permission is requested after the first item is added; delivery time can vary due to Android background scheduling.
- Configurable local reminder intervals at 14, 7, 3, 1, and 0 days.
- Offline recipe suggestions with ingredient-match counts and missing ingredients.
- Persistent shopping list, including adding missing recipe ingredients.
- Basic impact statistics derived from tracked and consumed items, estimating value only when prices were entered.
- Persisted dark theme preference.
- CSV inventory export and versioned JSON backup/restore, with record validation and explicit replace confirmation.
- A confirmed clear-data flow and Room migration for the shopping list.
- Three-screen first-run introduction with skip, explore, and add-first-item paths.
- CameraX barcode scanning through bundled, on-device ML Kit; online Open Food Facts lookup can prefill product name, brand, and category. Barcode reading itself works offline, and the expiry date always requires user entry.
- On-device receipt OCR through bundled ML Kit Latin text recognition. Product name, likely price, and simple leading quantity are suggestions only; the user edits/confirms each candidate and enters its expiration date before it is saved. OCR accuracy depends on receipt quality; the bundled recognizer targets Latin script.

## Still outstanding

Bulgarian localization, recipe favorites and dietary filters, extended statistics, broader automated tests, and release artwork/configuration. Expiry date, backup codec, and receipt parser unit tests have been added, but the application has not yet been compiled or tested.
