# The target APK remains minified by the release build type. Keep the separately
# packaged instrumentation harness unshrunk so connectedReleaseAndroidTest
# validates the real minified app rather than shrinking test-only discovery code.
-dontshrink
-dontoptimize
-dontobfuscate
