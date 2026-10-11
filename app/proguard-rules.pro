# Minification and shrinking are OFF in this project (`isMinifyEnabled = false` for the release
# build type in app/build.gradle.kts), so R8/ProGuard never runs and none of these rules are
# applied today. If minification is ever switched on, re-check Room's generated implementations,
# the Credential Manager / Google ID sign-in classes, and OkHttp before trusting the release build.
