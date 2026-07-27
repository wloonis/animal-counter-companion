# Default ProGuard / R8 rules for Animal Counter.
#
# Release minify is currently disabled (isMinifyEnabled = false) in
# app/build.gradle.kts, so these rules are not applied to the debug
# build. They are kept as a stub for future hardening of release builds.
#
# When minification is enabled, keep the following in mind:
#   - Jetpack Compose ships its own consumer rules; no special keep rules
#     are required for the compiler plugin.
#   - The app uses DataStore Preferences, HttpURLConnection (stdlib) and
#     org.json (stdlib) — all stable under R8 shrinking.
#   - Keep the manifest-referenced components (Activity, Service,
#     BroadcastReceiver) which are already preserved by the manifest
#     merger rules.

# If you enable minification, uncomment the generic safety nets below:
# -keep class com.animalcounter.** { *; }
# -dontwarn org.jetbrains.annotations.**