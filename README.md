# LastHavenPlus

LastHavenPlus is an Android launcher for the existing [LastHaven Wrath of the Lich King game](https://lasthavens.com/play). It provides a native launcher around the hosted game; it does not recreate or modify the game client.

- **Latest stable release:** [v2.4.0](https://github.com/iNeonyne/LastHavenPlus/releases/tag/v2.4.0)
- **Download:** [Latest Android release](https://github.com/iNeonyne/LastHavenPlus/releases/latest)
- **Game:** [lasthavens.com/play](https://lasthavens.com/play)

## Compatibility

| Item | Stable v2.4.0 |
|---|---|
| Android | Android 10 (API 29) and later |
| CPU architecture | ARM64 only (arm64-v8a) |
| Browser engine | Mozilla GeckoView 160 Stable |
| UI | Kotlin and Jetpack Compose |
| Application ID | com.neonyne.lasthavenlauncher |

The application ID and established release-signing identity are preserved so existing installations can receive compatible updates.

## Features

- Native launcher and in-game settings menu.
- GeckoView-based game hosting with a separate app-private browser profile.
- Local addon ZIP import, export and library management. Addons are not activated inside the game in v2.4.0.
- User-initiated update checks, verified full APK downloads and optional differential updates.
- Local diagnostics and basic storage/network usage information.

GeckoView uses its own browser profile. A fresh game login may be required after moving from the former browser engine. No supported Cefrium-to-GeckoView profile migration is provided.

## Installation and updates

Download the ARM64 APK from the [latest release](https://github.com/iNeonyne/LastHavenPlus/releases/latest). Android may ask you to allow this launcher to install updates from this source. The launcher checks for updates only when you request it; Android's package installer controls installation.

## Privacy and compatibility notes

- The launcher does not include Google Play services modules. GeckoView passkey/WebAuthn integration is therefore unavailable; use another sign-in method.
- The game and browser profile are stored in app-private data. Do not clear app data if you want to preserve the local profile.
- The hosted game and third-party components remain subject to their own terms and licenses.

## Project roadmap

The [addon security trust model](docs/ADDON_SECURITY.md) explains the capabilities granted to enabled addons and the limits of launcher-side validation.

The [roadmap](ROADMAP.md) combines curated strategic priorities with a generated live inventory of GitHub Issues and pull requests. The inventory updates automatically as issues, labels and PRs change; the strategic sequence remains intentionally concise. GitHub remains the source of truth for detailed scope and implementation status.

## Third-party notices

Original LastHavenPlus material is reserved to its copyright holder. Third-party components retain their own license terms and required notices. See [LICENSE](LICENSE), [NOTICE](NOTICE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). These notices do not grant rights to the hosted game or third-party assets.
