# Contributing to ApexDrive

Thanks for helping. ApexDrive is a native Java Android HOME launcher for aftermarket
car head units. Read `AGENT_HANDOFF.md` first for the hardware quirks that make this
code different from a phone app.

## Build

You need JDK 17 and the Android SDK (platform 33, build-tools 33.0.2). Then:

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JVM unit tests, no device needed
./gradlew lintDebug
```

The wrapper pins Gradle 8.2, which is the version CI uses. Do not use a different
Gradle version locally. Create `local.properties` with `sdk.dir=...` if Android Studio
did not do it for you (the file is git-ignored).

## Branches and commits

* One branch per change, branched from `main`. Open a pull request; CI must pass.
* Small commits, one logical change each.
* [Conventional Commits](https://www.conventionalcommits.org/): `type(scope): subject`.
  Types: `feat`, `fix`, `perf`, `docs`, `build`, `ci`, `test`, `refactor`, `chore`.
  Subject: imperative, lower-case, under 72 characters. Use the body to explain *why*.

## Rules for code changes

* Keep `minSdk 21` / `targetSdk 28` working. See "Why targetSdk 28" in `README.md`
  before changing either.
* Do not hardcode a specific car, phone, or personal data.
* Never block the CarPlay launch path when detection fails (see `AGENT_HANDOFF.md`).
* Pure logic (parsing, conversions) should be testable on the JVM without Android
  classes. Add a test under `app/src/test/java` when you add such logic.

## Credit

Add yourself to [`CONTRIBUTORS.md`](CONTRIBUTORS.md) in your first pull request; the file
explains the format.

## Signing

Never commit a keystore or password. CI signs with a stable key supplied through
repository secrets; see "Signing" in `README.md`.

## License

ApexDrive is released under the [MIT License](LICENSE). By contributing you agree that
your contributions are licensed under the same terms.
