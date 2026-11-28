# Addon security trust model

This document describes what installed addons can actually do in LastHavenPlus and where the
launcher's checks stop. It is documentation only: it does not change runtime behavior. The source
files referenced below are the authoritative behavior.

## What an addon is

Addons are WoW 3.3.5a-style addon folders (`.toc` metadata plus `.lua`/`.js`/`.css` assets)
imported from user-provided ZIP files. They are stored in the app's private storage on the device,
start disabled after import, and are enabled or disabled per addon by the user in the Addon
Manager. Enabling or disabling takes effect when the game is reloaded.

## Where addon code runs

**Enabled addon entry-point scripts are injected into the game page itself, in the game page's own
realm.** The page-side bootstrap (`app/src/main/assets/extensions/addon-bridge/addon-bootstrap.js`)
adds each enabled addon's `.js` entry scripts as `<script>` elements and its `.css` styles directly
to the game document at `https://lasthavens.com` (or the alternate game origin
`https://alt.lasthavens.com`).

This means an enabled addon executes with **the same capabilities as the game page**:

- Read and write the game page's JavaScript-visible cookies.
- Read and write the game origin's LocalStorage and IndexedDB (saved-game data, session data the
  page stores there).
- Manipulate the game page's DOM and interact with the game's own page JavaScript.
- Make the same network requests the game page can make, including credentialed same-origin
  requests that carry the game session cookies.
- Observe data the game displays or stores in the page.

Addon code is **not** permission-sandboxed, **not** isolated from the game origin, and **not**
limited by an addon permission model. There is no capability prompt per addon feature.

## What installing an addon means

**Installing and enabling an addon is a trust decision.** An untrusted or malicious addon can do
everything listed above with your game account data: it can read game cookies and saved data, alter
game behavior, submit actions as your game session, or exfiltrate game-origin data to the game
origin or anywhere the page context is permitted to send it.

The launcher does not review, certify or rate addons. Import validation is structural, not a code
review. Only install addons from sources you trust, and treat an enabled addon as trusted code
running as your game session.

## What the launcher's checks do and do not do

The launcher protects transport and boundaries. It does **not** sandbox addon code from the game
origin.

| Check | What it protects | What it does not do |
|---|---|---|
| ZIP import validation (`AddonArchive.kt`) | Path traversal, malformed archives, oversized imports (128 MB archive / 256 MB expanded / 64 MB per file limits) | Nothing about what the code does once enabled |
| Library and runtime limits (`AddonLimits.kt`, `AddonRuntimePlanner.kt`) | Resource exhaustion: 384 MB library, 16 MB runtime bundle, 8 MB per runtime file, 1024 files, 128 addons | Nothing about code behavior |
| Dependency analysis (`AddonDependencyAnalyzer.kt`) | Deterministic load order; blocks missing dependencies and dependency cycles | Nothing about code behavior |
| Bridge sender policy (`AddonBridgePolicy.kt`) | The native-messaging bridge only accepts the launcher's own bundled built-in extension, bound to the live game session, at the strict game origin | Not a sandbox: the bridge serves addon-initiated requests from the game page to the launcher |
| Payload delivery (`AddonRuntimePayloadProvider.kt`, `payload-validation.js`) | Read-only, size-bounded delivery of validated library files, pinned by a revision hash | Does not inspect or restrict what the delivered code does in the page |

In short: the checks ensure that only well-formed, size-bounded files from the on-device library
reach the game page, and that launcher bridge services fail closed. Once an addon's code executes
in the game page, it has the page's capabilities; the launcher cannot and does not constrain it.

## Safe installation and removal practices

- **Only import addon ZIPs from sources you trust.** The import dialog in the Addon Manager warns
  about this before installation.
- **New imports start disabled.** Review an addon (for example by exporting or inspecting the ZIP)
  before enabling it.
- **Enable only the addons you need.** Every enabled addon adds trusted code to the game page.
- **Remove addons you no longer use.** Removal deletes the addon's files from the device and the
  addon will no longer be injected on the next game reload.
- **Removal is not an undo.** Deleting an addon removes its code, not changes it already made to
  game-origin data (cookies, LocalStorage, IndexedDB). The launcher deliberately never clears,
  copies or migrates game profile data; handling game-site data is a decision you make in the game
  or browser context, not something the launcher performs silently.

## Scope of this document

This describes the launcher's addon runtime as implemented on `beta`. It makes no claims about
network behavior beyond the page context, and no protections are implied that are not implemented
in the referenced sources.
