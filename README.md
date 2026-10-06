<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/72a50948-fead-45ec-bfa7-75673af1c55a

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` in that file to your Gemini API key (see `.env.example` for an example)
5. Remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device
7. If you have already published your app in AI Studio, please [request upload key reset](https://support.google.com/googleplay/android-developer/answer/9842756#zippy=%2Crequest-an-upload-key-reset) in Google Play Console.

## CI/CD & Releases (GitHub Actions)

Free CI/CD via [GitHub Actions](https://github.com/features/actions) (`.github/workflows/android.yml`):

- **Every push / PR:** builds a debug APK and runs unit tests.
- **Push a tag `v*`:** builds a **signed release APK + AAB** and publishes them to a GitHub Release.

```bash
git tag v1.0.0
git push origin v1.0.0   # release job runs and attaches artifacts
```

Signing uses the repo secrets `KEYSTORE_BASE64`, `STORE_PASSWORD`, and `KEY_PASSWORD`. The key/credentials live locally in `my-upload-key.jks` / `signing-credentials.txt` (git-ignored) — back them up, they are required to ship updates under the same identity.
