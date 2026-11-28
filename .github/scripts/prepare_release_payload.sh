#!/usr/bin/env bash
set -euo pipefail

# The full signed APK is the required release payload. Differential
# generation is an optimization and must never block that payload.
mkdir -p release-assets
APK_NAME="LastHavenPlus-${RELEASE_VERSION}-stable-arm64-v8a.apk"
COMPAT_APK_NAME="lasthavenplus-arm64.apk"
cp app/build/outputs/apk/release/app-release.apk "release-assets/$APK_NAME"
TARGET_APK="release-assets/$APK_NAME"
cp "$TARGET_APK" "release-assets/$COMPAT_APK_NAME"
TARGET_SHA="$(sha256sum "$TARGET_APK" | awk '{print $1}')"
COMPAT_SHA="$(sha256sum "release-assets/$COMPAT_APK_NAME" | awk '{print $1}')"
if [[ "$COMPAT_SHA" != "$TARGET_SHA" ]]; then
  echo "::error::Legacy updater compatibility APK does not match the versioned APK."
  exit 1
fi
TARGET_SIZE="$(stat -c '%s' "$TARGET_APK")"
COMPAT_SIZE="$(stat -c '%s' "release-assets/$COMPAT_APK_NAME")"
if [[ "$COMPAT_SIZE" != "$TARGET_SIZE" ]]; then
  echo "::error::Legacy updater compatibility APK size does not match the versioned APK."
  exit 1
fi
PATCH_JSON="null"
RELEASES_JSON="$RUNNER_TEMP/releases.json"
PATCH_TOOLS_READY=false

# Bound both API latency and bytes before parsing untrusted release metadata.
if timeout 45s gh api "repos/$GITHUB_REPOSITORY/releases?per_page=100" | \
  python3 .github/scripts/bounded_release_input.py --stdin - "$RELEASES_JSON" 4194304; then
  if jq -e 'type == "array"' "$RELEASES_JSON" >/dev/null 2>&1; then
    PREVIOUS_RELEASE="$(jq -c --arg current "$RELEASE_TAG" \
      '[.[] | select(type == "object" and .draft == false and .prerelease == false and (.tag_name | type == "string") and .tag_name != $current)][0] // empty' \
      "$RELEASES_JSON" 2>/dev/null || true)"
  else
    echo "::warning::Previous-release API returned malformed data; publishing the full APK only."
    PREVIOUS_RELEASE=""
  fi
else
  echo "::warning::Previous-release API lookup failed, timed out or exceeded 4 MiB; publishing the full APK only."
  PREVIOUS_RELEASE=""
fi

if [[ -n "$PREVIOUS_RELEASE" ]]; then
  PREVIOUS_TAG="$(jq -r '.tag_name // empty' <<< "$PREVIOUS_RELEASE" 2>/dev/null || true)"
  PREVIOUS_APK_ASSET_NAME="$(jq -r --arg version "${PREVIOUS_TAG#v}" '.assets[]? | select(.name == ("LastHavenPlus-" + $version + "-stable-arm64-v8a.apk")) | .name' <<< "$PREVIOUS_RELEASE" 2>/dev/null | head -n 1 || true)"
  if [[ -z "$PREVIOUS_APK_ASSET_NAME" ]]; then
    PREVIOUS_APK_ASSET_NAME="lasthavenplus-arm64.apk"
  fi
  PREVIOUS_APK_URL="$(jq -r --arg name "$PREVIOUS_APK_ASSET_NAME" '.assets[]? | select(.name == $name) | .browser_download_url' <<< "$PREVIOUS_RELEASE" 2>/dev/null | head -n 1 || true)"
  PREVIOUS_MANIFEST_URL="$(jq -r '.assets[]? | select(.name == "lasthavenplus-update.json") | .browser_download_url' <<< "$PREVIOUS_RELEASE" 2>/dev/null | head -n 1 || true)"

  if [[ "$PREVIOUS_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ &&
    -n "$PREVIOUS_APK_URL" && "$PREVIOUS_APK_URL" != "null" &&
    -n "$PREVIOUS_MANIFEST_URL" && "$PREVIOUS_MANIFEST_URL" != "null" ]]; then
    # Release asset downloads are streamed and hard-capped even if Content-Length is absent.
    if timeout 90s python3 .github/scripts/bounded_release_input.py "$PREVIOUS_APK_URL" "$RUNNER_TEMP/previous.apk" 268435456 &&
      timeout 30s python3 .github/scripts/bounded_release_input.py "$PREVIOUS_MANIFEST_URL" "$RUNNER_TEMP/previous-manifest.json" 1048576; then

      BASE_VERSION="$(jq -r '.version // empty' "$RUNNER_TEMP/previous-manifest.json" 2>/dev/null || true)"
      BASE_VERSION_CODE="$(jq -r '.versionCode // empty' "$RUNNER_TEMP/previous-manifest.json" 2>/dev/null || true)"
      BASE_APK_NAME="$(jq -r '.apk.name // empty' "$RUNNER_TEMP/previous-manifest.json" 2>/dev/null || true)"
      BASE_EXPECTED_SHA="$(jq -r '.apk.sha256 // empty' "$RUNNER_TEMP/previous-manifest.json" 2>/dev/null || true)"
      BASE_SHA="$(sha256sum "$RUNNER_TEMP/previous.apk" 2>/dev/null | awk '{print $1}' || true)"

      if [[ "$BASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ &&
        "$PREVIOUS_TAG" == "v$BASE_VERSION" &&
        "$BASE_APK_NAME" == "$PREVIOUS_APK_ASSET_NAME" &&
        "$BASE_VERSION_CODE" =~ ^[0-9]+$ &&
        "$BASE_EXPECTED_SHA" =~ ^[0-9a-fA-F]{64}$ &&
        "$BASE_SHA" =~ ^[0-9a-fA-F]{64}$ &&
        "$BASE_SHA" == "$BASE_EXPECTED_SHA" ]]; then

        BASE_CERT_OUTPUT="$("$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --print-certs "$RUNNER_TEMP/previous.apk" 2>/dev/null || true)"
        BASE_CERT_DIGEST="$(printf '%s\n' "$BASE_CERT_OUTPUT" | awk '/certificate SHA-256 digest:/ {sub(/^.*certificate SHA-256 digest: /, ""); print tolower($0); exit}')"

        if [[ "$BASE_CERT_DIGEST" == "b3bbf7520e77dfc384a60b1eee6c5f205b978f9b453849e2be523f642018d263" ]]; then
          # Install the optional patch tools only after a compatible
          # base release is confirmed. Both package operations are bounded.
          if command -v bsdiff >/dev/null 2>&1 && command -v bspatch >/dev/null 2>&1; then
            PATCH_TOOLS_READY=true
          elif timeout 120s sudo apt-get update -o Acquire::Retries=1 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 &&
            timeout 90s sudo apt-get install -y --no-install-recommends bsdiff; then
            PATCH_TOOLS_READY=true
          else
            echo "::warning::Could not install bsdiff within the time limit; publishing the full APK only."
          fi

          if [[ "$PATCH_TOOLS_READY" == true ]]; then
            PATCH_NAME="lasthavenplus-arm64-${PREVIOUS_TAG}-to-${RELEASE_TAG}.bsdiff"
            if timeout 120s bsdiff "$RUNNER_TEMP/previous.apk" "$TARGET_APK" "$RUNNER_TEMP/update.bsdiff" &&
              timeout 60s bspatch "$RUNNER_TEMP/previous.apk" "$RUNNER_TEMP/reconstructed.apk" "$RUNNER_TEMP/update.bsdiff" &&
              [[ "$(sha256sum "$RUNNER_TEMP/reconstructed.apk" | awk '{print $1}')" == "$TARGET_SHA" ]]; then
              if PATCH_SIZE="$(stat -c '%s' "$RUNNER_TEMP/update.bsdiff" 2>/dev/null)" &&
                [[ "$PATCH_SIZE" =~ ^[0-9]+$ ]] &&
                (( PATCH_SIZE > 0 && PATCH_SIZE * 10 < TARGET_SIZE * 9 )); then
                if cp "$RUNNER_TEMP/update.bsdiff" "release-assets/$PATCH_NAME" &&
                  PATCH_SHA="$(sha256sum "release-assets/$PATCH_NAME" 2>/dev/null | awk '{print $1}')" &&
                  [[ "$PATCH_SHA" =~ ^[0-9a-fA-F]{64}$ ]] &&
                  PATCH_JSON="$(jq -n \
                    --arg name "$PATCH_NAME" \
                    --arg fromVersion "$BASE_VERSION" \
                    --arg fromSha256 "$BASE_SHA" \
                    --arg sha256 "$PATCH_SHA" \
                    --argjson fromVersionCode "$BASE_VERSION_CODE" \
                    --argjson size "$PATCH_SIZE" \
                    '{name:$name, fromVersion:$fromVersion, fromVersionCode:$fromVersionCode, fromSha256:$fromSha256, sha256:$sha256, size:$size}')"; then
                  echo "Verified differential patch: $PATCH_SIZE bytes versus $TARGET_SIZE-byte full APK."
                else
                  rm -f "release-assets/$PATCH_NAME"
                  PATCH_JSON="null"
                  echo "::warning::Could not stage or describe the optional differential patch; publishing full APK only."
                fi
              else
                echo "Differential patch is not at least 10% smaller or its size could not be verified; publishing full APK only."
              fi
            else
              echo "::warning::Differential patch generation or reconstruction failed; publishing full APK only."
            fi
          fi
        else
          echo "Previous release signing certificate differs or is invalid; skipping differential patch."
        fi
      else
        echo "Previous release manifest or APK digest is invalid; skipping differential patch."
      fi
    else
      echo "::warning::Could not download the previous release within the time limit; publishing full APK only."
    fi
  else
    echo "Previous release has no compatible APK and manifest; publishing full APK only."
  fi
else
  echo "No previous stable release was found; publishing full APK only."
fi

# This manifest is always produced, including every delta fallback path.
jq -n \
  --arg version "$RELEASE_VERSION" \
  --argjson versionCode "$RELEASE_VERSION_CODE" \
  --arg apkName "$APK_NAME" \
  --arg apkSha "$TARGET_SHA" \
  --argjson apkSize "$TARGET_SIZE" \
  --argjson patch "$PATCH_JSON" \
  '{schemaVersion:1, version:$version, versionCode:$versionCode, apk:{name:$apkName, sha256:$apkSha, size:$apkSize}, patch:$patch}' \
  > release-assets/lasthavenplus-update.json

test -s "release-assets/$APK_NAME"
test -s "release-assets/$COMPAT_APK_NAME"
test -s release-assets/lasthavenplus-update.json
echo "Release payload ready: full APK and update manifest."
