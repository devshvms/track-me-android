# The target APK remains minified by the release build type. The separately
# packaged instrumentation harness uses AndroidX Test runtime discovery, which
# includes dependencies invisible to static reachability analysis. Keep that
# harness unshrunk so connectedReleaseAndroidTest validates the real minified
# app instead of failing before it can launch a test.
-dontshrink
-dontoptimize
-dontobfuscate
