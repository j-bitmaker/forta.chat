import Capacitor
import Foundation
import PushKit
import CallKit
import UIKit

/// PushKit (VoIP push) registration + token surfacing for the JS layer.
///
/// Why a custom plugin instead of `@capacitor/push-notifications`:
/// PushKit pushes (`type == .voIP`) are routed by the OS through
/// `PKPushRegistry`, never through `UNUserNotificationCenter`. They are
/// the only push class Apple allows for delivering "ring this device
/// right now" payloads — and Apple **requires** that the app reports a
/// CallKit incoming call before `completion()` is invoked, otherwise
/// the OS bans the app from receiving future VoIP pushes (iOS 13+).
///
/// Architecture:
///
///   1. `load()` registers `PKPushRegistry` for `.voIP` on the main queue.
///      iOS replies asynchronously with `didUpdate pushCredentials` —
///      the VoIP token, distinct from the regular APNs device token.
///   2. We surface that token to JS via `voipTokenReceived` so
///      `push-service.ts` can register a SECOND Matrix pusher with
///      `app_id: 'fortaios.voip'`. The regular `fortaios` pusher (Step
///      4 / apns-push.md) keeps using FCM for non-call traffic.
///   3. When a VoIP push arrives (`didReceiveIncomingPushWith`), we:
///      a. extract the call payload fields (call_id, sender_display_name,
///         room_id, msg_type),
///      b. report a CallKit incoming call via the
///         `@capgo/capacitor-incoming-call-kit` plugin's CXProvider — by
///         invoking the plugin instance's own `showIncomingCall` method
///         with the options JS would send, because the plugin's request
///         types are internal to its SPM module,
///      c. emit `voipPushReceived` to JS for telemetry / handoff.
///   4. JS-side: the IncomingCallKit plugin's listener fires `callAccepted`
///      / `callDeclined` once the user interacts; the iOS adapter in
///      `native-call-bridge.ios.ts` translates those into the existing
///      bridge events and the call-service handles the rest.
///
/// Thread safety:
/// PushKit callbacks come back on the queue we registered with — `.main`
/// here. We call into the CallKit plugin and resolve `notifyListeners`
/// on the main thread, which is what Capacitor's bridge expects.
///
/// Apple compliance:
/// We *only* report a CallKit call from VoIP push. Apple's PushKit
/// guidelines forbid using `.voIP` for anything else; non-call pushes go
/// through the regular APNs pipeline (FCM / IOSPushIntentPlugin) instead.
@objc(IOSVoIPPushPlugin)
public class IOSVoIPPushPlugin: CAPPlugin {
    /// `jsName` of `@capgo/capacitor-incoming-call-kit`'s plugin, the owner
    /// of the CallKit `CXProvider` this app reports calls on.
    private static let callKitPluginName = "IncomingCallKit"

    private var registry: PKPushRegistry?

    public override func load() {
        let registry = PKPushRegistry(queue: .main)
        registry.delegate = self
        registry.desiredPushTypes = [.voIP]
        self.registry = registry
    }

    /// Allow JS to read the current VoIP token on demand. Returns
    /// `{ token: null }` when iOS has not yet handed us credentials —
    /// JS should additionally subscribe to `voipTokenReceived` to be
    /// notified when the token first arrives or is rotated.
    @objc func getToken(_ call: CAPPluginCall) {
        guard let registry = self.registry,
              let data = registry.pushToken(for: .voIP) else {
            call.resolve(["token": NSNull()])
            return
        }
        call.resolve(["token": Self.hexString(from: data)])
    }

    private static func hexString(from data: Data) -> String {
        return data.map { String(format: "%02x", $0) }.joined()
    }

    /// Report the call on the CallKit plugin's provider from native code.
    ///
    /// The plugin's `IncomingCallRequest` and `showIncomingCall(_:completion:)`
    /// are internal to its module, so the only cross-module entry is its
    /// Capacitor method, fed a `CAPPluginCall` with the same options JS sends
    /// from `native-call-bridge.ios.ts` (`handle` and `extra.roomId` carry the
    /// room, which the accept/decline events hand back to JS). The plugin
    /// keys calls by `callId`, so the JS report that follows once the bridge
    /// is up resolves to the same entry instead of ringing twice.
    ///
    /// Returns `false` when the plugin is not loaded, which leaves CallKit
    /// untold — the caller logs it; there is no provider to fall back to.
    private func reportToCallKit(
        callId: String,
        callerName: String,
        roomId: String,
        hasVideo: Bool
    ) -> Bool {
        let selector = NSSelectorFromString("showIncomingCall:")
        guard let plugin = bridge?.plugin(withName: Self.callKitPluginName),
              plugin.responds(to: selector) else {
            return false
        }
        let options: [String: Any] = [
            "callId": callId,
            "callerName": callerName,
            "handle": roomId,
            "hasVideo": hasVideo,
            "extra": ["roomId": roomId],
            "ios": ["handleType": "generic"],
        ]
        let call = CAPPluginCall(
            callbackId: "voip-push-\(callId)",
            methodName: "showIncomingCall",
            options: options,
            success: { _, _ in },
            error: { error in
                CAPLog.print("[IOSVoIPPush] showIncomingCall failed: \(error?.message ?? "unknown")")
            }
        )
        _ = plugin.perform(selector, with: call)
        return true
    }
}

// MARK: - PKPushRegistryDelegate

extension IOSVoIPPushPlugin: PKPushRegistryDelegate {
    /// Initial token issue and subsequent rotations both land here.
    public func pushRegistry(
        _ registry: PKPushRegistry,
        didUpdate pushCredentials: PKPushCredentials,
        for type: PKPushType
    ) {
        guard type == .voIP else { return }
        let token = Self.hexString(from: pushCredentials.token)
        notifyListeners("voipTokenReceived", data: ["token": token])
    }

    /// Token invalidation. Surface to JS so it can clean up the matching
    /// Matrix pusher; the Matrix homeserver will otherwise keep firing
    /// silent VoIP pushes into the void.
    public func pushRegistry(
        _ registry: PKPushRegistry,
        didInvalidatePushTokenFor type: PKPushType
    ) {
        guard type == .voIP else { return }
        notifyListeners("voipTokenInvalidated", data: [:])
    }

    /// Incoming VoIP push. Apple REQUIRES that we report a CallKit
    /// incoming call here, on the same run-loop tick, BEFORE invoking
    /// `completion()`. Otherwise iOS bans the app from receiving future
    /// VoIP pushes for the rest of the install.
    public func pushRegistry(
        _ registry: PKPushRegistry,
        didReceiveIncomingPushWith payload: PKPushPayload,
        for type: PKPushType,
        completion: @escaping () -> Void
    ) {
        guard type == .voIP else {
            completion()
            return
        }

        let dict = payload.dictionaryPayload
        let callId = (dict["call_id"] as? String)
            ?? (dict["event_id"] as? String)
            ?? UUID().uuidString
        let callerName = (dict["sender_display_name"] as? String) ?? "Unknown"
        let roomId = (dict["room_id"] as? String) ?? ""
        // Sygnal puts the original Matrix event type in `msg_type`. Bastyon
        // distinguishes voice vs video calls via `m.call.invite.video`
        // (see Sygnal config request doc).
        let hasVideo = (dict["msg_type"] as? String) == "m.call.invite.video"

        // Tell CallKit now, from native code. This used to be a
        // NotificationCenter post that nothing observed, so the call was
        // only reported once JS had booted and called `showIncomingCall`
        // itself — seconds after `completion()` on a cold start, which iOS
        // counts as "never reported": it kills the app and stops delivering
        // VoIP pushes to this install.
        if !reportToCallKit(callId: callId, callerName: callerName, roomId: roomId, hasVideo: hasVideo) {
            CAPLog.print("[IOSVoIPPush] \(Self.callKitPluginName) plugin not loaded; CallKit not told about \(callId)")
        }

        // Surface to JS for telemetry / bug-report attachment + so the
        // app can warm up the Matrix client / decryption pipeline before
        // the user accepts the call.
        notifyListeners("voipPushReceived", data: [
            "callId": callId,
            "roomId": roomId,
            "callerName": callerName,
            "hasVideo": hasVideo,
        ])

        // The plugin calls `reportNewIncomingCall` on the next main-queue
        // turn (`DispatchQueue.main.async` inside `showIncomingCall`), so the
        // completion is queued behind it: CallKit hears about the call before
        // PushKit is told we are done.
        DispatchQueue.main.async {
            completion()
        }
    }
}
