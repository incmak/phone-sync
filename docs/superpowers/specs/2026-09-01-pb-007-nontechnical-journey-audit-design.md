# PB-007 - Non-technical journey audit and local fixes

Status: approved by the owner's 2026-09-01 instruction to complete every backlog item that is not blocked by owner input or physical-device evidence.

## Goal

Make the emulator-reachable first-run journey honest and completable for a person who does not know Android permission names, relays, or network infrastructure, while recording the paired and physical-device states that still cannot be proved locally.

## Root cause found in the audit

Choosing **Pair nearby without internet** persists the nearby mode, but the following permission screen checks and requests only notification posting and notification-listener access. Native nearby pairing then calls Wi-Fi/NSD APIs without the Android 13+ `NEARBY_WIFI_DEVICES` runtime grant and returns `wifi_permission_denied`. The error screen can only retry the same denied operation or cancel, so the advertised no-infrastructure primary path is blocked. The manifest also prematurely declared `ACCESS_LOCAL_NETWORK`, which is the Android 17 contract for apps targeting SDK 37; removing that declaration is required to keep the current target-36 permission model coherent.

Android's current platform guidance requires apps targeting Android 13+ to request `NEARBY_WIFI_DEVICES` before the relevant nearby Wi-Fi APIs. For an app targeting SDK 36, Android 16 local-network protection also uses that grant; `ACCESS_LOCAL_NETWORK` does not become the runtime contract until target SDK 37. Sources: <https://developer.android.com/develop/connectivity/wifi/wifi-permissions> and <https://developer.android.com/privacy-and-security/local-network-permission>.

## Scope

- When nearby pairing was selected, show a third permission card that explains nearby-device access before invoking Android's permission prompt.
- Expose typed native check/request methods for the existing manifest-declared `NEARBY_WIFI_DEVICES` permission and keep relay-only onboarding free of that prompt.
- If the user permanently denies the nearby permission, route the existing card action to the app's Android settings instead of retrying a prompt that cannot appear.
- Give every permission-card action a meaningful accessibility label and at least a 48 dp target.
- Correct first-run claims that say every notification is mirrored or that “Twinotify” cannot see locally processed notifications; explain without infrastructure terms that only the paired phones can read content.
- Make the background-reliability instructions match the actual destination: Android app info, then **App battery usage**, with an explicit note that device wording varies.
- Preserve the existing layout, components, tokens, navigation order, permission timing, and reversible skip.
- Save the current-run emulator screenshots and a concise combined UX/accessibility audit tied to them.

## Non-goals

- No production relay hostname, operating policy, or default relay choice; those remain PB-005 owner decisions.
- No visual redesign, new onboarding step, new dependency, manual-code feature, or Android permission requested before the user chooses the nearby path.
- No claim that two-phone pairing, successful mirroring, unpair, TalkBack, one-handed use, OEM battery restrictions, launcher behavior, or physical notification shades passed. Those require the owner's two phones.
- No request for `ACCESS_LOCAL_NETWORK` while Twinotify still targets SDK 36; revisit that contract when target SDK 37 is planned.

## Acceptance criteria

1. Nearby onboarding explains and requests `NEARBY_WIFI_DEVICES` before entering nearby pairing, and Continue remains disabled until all permissions required by that selected path are granted.
2. Relay onboarding still requires only notification posting and notification access.
3. Returning from Android settings refreshes every displayed grant; retryable and permanent denial remain truthful and recoverable.
4. Permission actions expose descriptive labels, 48 dp targets, enabled state, and the surrounding rationale to assistive technology.
5. First-run privacy, selection, and battery-settings copy matches implemented behavior and the actual Android destination.
6. Focused tests fail before implementation and pass afterward; full TypeScript/Jest/lint and Android build gates pass.
7. On `emulator-5558`, the nearby runtime prompt appears from the explained card and a granted run advances past the previous `wifi_permission_denied` failure to a real QR/waiting state.
8. The audit records light, dark, 160% text, and reachable system-permission states, with physical-only gaps named rather than inferred.
