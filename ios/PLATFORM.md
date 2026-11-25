# iOS platform decisions (M3)

## Data directory
- The core data dir is `<App Group group.org.tapetape.v2>/tapetape-v2`.
- On first launch, an existing `Application Support/tapetape-v2` is moved there. This works because blob paths are stored relative to the data dir.
- The main app and the share extension each open their own `Core` on the same directory.
- **Kept out of device backups.** The directory holds the core DB, which includes the mnemonics.
  - At every launch, and right after a backup restore, `AppGroup.protect` sets `isExcludedFromBackup` on `<App Group>/tapetape-v2`.
  - It also sets `NSFileProtectionCompleteUntilFirstUserAuthentication` on the directory and every item inside it. New files inherit that class from the directory.
  - So iCloud and Finder/iTunes backups contain no TapeTape data. Users move to a new phone with the in-app backup (Settings → Chats and Media → Export Backup, then "Restore from Backup" during onboarding), as with the old app.
  - "Until first user authentication" lets background refresh and the share extension open the DB once the phone has been unlocked after boot.

## Lifecycle
- On going to background:
  - `stop()` all profiles,
  - schedule `BGAppRefresh`,
  - update the badge.
- On returning to foreground: `start()` all profiles.

## Notifications
- Notifications are local only, posted from `Event::Notification`.
- Suppression rule: show only if `!muted || (mention && settings.mentions)`.
- In the foreground:
  - Messages for the chat currently open on screen are not posted.
  - No banners and no sound (old behaviour). The badge updates, and the notification goes into Notification Center (`.list`) so messages for other profiles can still be found there.
- Content:
  - 1:1: the title is the sender and the body is the preview.
  - Group: the title is the group name and the body is "sender: preview".
  - Reactions use `reaction_by_other`.
- The badge is the unread total across all non-muted profiles.
- Tapping a notification switches to its profile and opens the chat.
- A chat's notifications are removed once that chat is marked read.

## Background fetch
- `BGAppRefreshTask` with id `org.tapetape.v2.refresh`, scheduled at earliest 15 minutes ahead. It calls `Core.fetchAll()` and posts the returned notifications.
- Wake-up times (the last 10) are kept for the Connectivity page.

## Notification Service Extension: not built
- There is no remote push in v2: the relay has no APNs integration.
- The old app's NSE only ran when a push arrived, so with no push it would never run.
- Background delivery is covered by BGAppRefresh. Revisit this if the relay gains push.

## Share extension (`TapeTapeShare`)
- Built on `SLComposeServiceViewController`, with:
  - placeholder "Message",
  - a "Forward to…" row that defaults to Saved Messages,
  - a searchable chat picker,
  - a "One moment…" alert while sending.
- Opens `Core` on the App Group dir, uses the selected profile, and never calls `start()`.
- To send: `send_text` / `send_media`, then `send_pending()`.
- Images are re-encoded to fit `Account.max_attachment_size()`. Larger files are refused with `relay_too_large`. This replaces the old hard-coded 12 MB English message.

## Universal links
- Associated domain: `applinks:i.tapetape.org`. Links go through the same handler as a scanned QR code: contact invite, or group invite with `g` / `gn`.
- For the links to open the app, the server must serve an `apple-app-site-association` file that lists the v2 app ID.

## Widget: not built
- The previous app had no reachable "Add to Widget" entry point.
- The widget only shows auto-filled items, and the doc does not define what they are. Users never curate it.
- Left out of M3 until product decides what it should show.

## Appearance
- The app is light only, the same as the previous iOS app (ARCHITECTURE §1.1).
- How it is enforced:
  - `UIUserInterfaceStyle = Light` in Info.plist,
  - `.preferredColorScheme(.light)` on the root view,
  - a light keyboard in the bridged text fields.

## Release and signing
| Item | Value |
|---|---|
| App bundle id | `org.tapetape.v2` (display name "TapeTape v2", version `MARKETING_VERSION` 2.0.0, build `CURRENT_PROJECT_VERSION`) |
| Share extension bundle id | `org.tapetape.v2.share` (it must stay a child of the app id) |
| Core framework bundle id | `org.tapetape.v2.core` (embedded framework, not signed separately on the portal) |
| App Group | `group.org.tapetape.v2`, on the app **and** the share extension. The core data dir lives here. |
| App entitlements (`TapeTape/TapeTape.entitlements`) | `com.apple.security.application-groups`, `com.apple.developer.associated-domains` (`applinks:i.tapetape.org`) |
| Share extension entitlements (`TapeTapeShare/TapeTapeShare.entitlements`) | `com.apple.security.application-groups` |
| Capabilities to enable on the portal | App Groups (both ids); Associated Domains (app). No Push Notifications: the app uses local notifications only. |
| Background modes | `fetch`, with BGTask id `org.tapetape.v2.refresh` |
| Privacy strings | Camera, Photo Library (read and add), Microphone, and `NSFaceIDUsageDescription` (device auth before backup and recovery-phrase export) |
| Universal links | The server at `i.tapetape.org` must add `<TEAMID>.org.tapetape.v2` to `apple-app-site-association`. |
| Signing | `CODE_SIGN_STYLE = Automatic`. Set `DEVELOPMENT_TEAM` (project.yml `settings.base`, or on the command line). |

The Release build links `core/out/ios/TapeTapeCore.xcframework` (`ios-arm64` and `ios-arm64-simulator`). Rebuild it with the core's iOS script before archiving.
- The core xcframework has no x86_64 simulator slice, so `project.yml` sets `EXCLUDED_ARCHS[sdk=iphonesimulator*] = x86_64`.
- Without that setting, a Release simulator build tries to link x86_64 as well and fails.

Archive commands:
```
xcodegen generate
xcodebuild archive -project TapeTape.xcodeproj -scheme TapeTape -configuration Release \
  -destination 'generic/platform=iOS' -archivePath build/TapeTape.xcarchive \
  DEVELOPMENT_TEAM=<TEAMID>
xcodebuild -exportArchive -archivePath build/TapeTape.xcarchive -exportPath build/export \
  -exportOptionsPlist ExportOptions.plist   # method app-store-connect, teamID <TEAMID>
```
- Without a team, use `CODE_SIGNING_ALLOWED=NO` instead of `DEVELOPMENT_TEAM`. That still produces a complete, unsigned archive, and that is how M5 checked it.
- A team id without an account or profiles fails with "No profiles for 'org.tapetape.v2'". Add `-allowProvisioningUpdates` with a signed-in Xcode account, or install App Store profiles for both bundle ids.
- DEBUG-only code is compiled out of Release: the `-TapeTapePacketGallery` packet gallery and the test launch arguments.

## Large attachments backend (`TAPETAPE_FILES_BACKEND`)
- Build setting `TAPETAPE_FILES_BACKEND` is declared in `project.yml` (target `TapeTape`, `settings.base`). It defaults to empty.
- It reaches the app as the Info.plist key `TapeTapeFilesBackend` (`TapeTape/Info.plist`, generated from `project.yml`: `TapeTapeFilesBackend: $(TAPETAPE_FILES_BACKEND)`).
- URL shape: `https://<backend-domain>`, for example `https://tapetape.org`. No path and no trailing slash.
- **Where to set it** (any one of these):
  - `project.yml`: `TAPETAPE_FILES_BACKEND: https://tapetape.org`, then run `xcodegen generate`.
  - On the command line: `xcodebuild … TAPETAPE_FILES_BACKEND=https://tapetape.org`.
  - In an xcconfig: `TAPETAPE_FILES_BACKEND = https:/$()/tapetape.org`. The `$()` is needed because `//` starts a comment in xcconfig files.
- **What the app does when the value is non-empty:**
  - `AppModel.loadSelectedAccount()` calls `setRelayFilesBackend(url:)` on every profile whose stored backend differs.
  - That function runs at launch, after creating, importing or restoring a profile, after a profile switch, and after a delete, so new and restored profiles get the backend too.
  - The value is stored per profile in the core, so the share extension uses it as well.
- **When the value is empty:** nothing is changed, and each profile keeps whatever the core has stored (default off).
- **DEBUG only:** the launch argument `-TapeTapeFilesBackend <url>` overrides the build setting. Passing `""` switches the backend off; the UI tests use that.
