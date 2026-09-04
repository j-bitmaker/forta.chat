package com.forta.chat.plugins.calls

/**
 * WEE-56 — centralised per-vendor audio policy for the calls subsystem.
 *
 * Forta runs on a long tail of OEM ROMs whose audio HAL deviates from the
 * stock-Android contract the generic [AudioRouter] / [com.forta.chat.plugins.webrtc.NativeWebRTCManager]
 * paths assume. Before this class the vendor handling was scattered across two
 * ad-hoc checks that drifted independently:
 *
 *   - `NativeWebRTCManager.BROKEN_HW_AEC_VENDORS` (disable HW AEC/NS that mutes
 *     the mic on MIUI / EMUI / ColorOS / RealmeUI / XOS …)
 *   - `AudioRouter.modeReapplyScheduleMs()` (re-apply MODE_IN_COMMUNICATION after
 *     aggressive OEM resets)
 *
 * This object is the single source of truth the calls code consults for
 * vendor-conditional behaviour. It is intentionally **pure** (no Android
 * framework dependencies) so the decision logic is covered by plain JVM unit
 * tests without Robolectric / mockk, and so the imperative `AudioManager`
 * mutations in [AudioRouter] stay a thin shell over testable predicates.
 *
 * ## Design invariant (acceptance criterion A5)
 *
 * [CallVendor.GENERIC] is the default for any unmatched device, and every
 * vendor-specific tweak is **additive** to the proven WEE-54 generic path —
 * never a replacement. A device we do not recognise, or one whose vendor we
 * recognise but for which a given predicate returns the generic answer, runs
 * exactly the code it ran before WEE-56. This keeps the vast majority of
 * working devices untouched by construction.
 *
 * NOTE: the device-specific *routing* effects (HONOR one-way audio, HUAWEI
 * video-call silence) can only be validated on the physical Honor/Huawei
 * handsets from the bug cluster. The predicates here encode the policy and are
 * unit-tested; on-device QA is still required before merge.
 *
 * ## Runtime signal checking (WEE-110)
 *
 * The vendor list approach had a fundamental flaw: it required manual widening
 * (WEE-76 → WEE-87 → WEE-103) every time a new OEM's broken HW AEC was
 * discovered. Triage of 12 new audio complaints from devices OUTSIDE the list
 * (OnePlus, Vivo, Samsung, Motorola, etc.) showed the list alone was
 * insufficient.
 *
 * The solution: keep the vendor list as the DOMINANT, first-check input (to
 * never regress any device currently in the list), but add runtime signals
 * that can catch broken HW AEC on unlisted devices:
 *   - Whether AcousticEchoCanceler can be created and enabled on the session
 *   - Whether NoiseSuppressor can be created and enabled on the session
 *
 * A device outside the vendor list whose HW AEC fails to create/enable is
 * treated the same as a listed device — fall back to software processing.
 * This way the list stops being the only source of truth and becomes a
 * safety net for devices whose audio APIs are documented to throw.
 *
 * Queries happen at the [AudioRouter] call site inside try/catch; signals
 * that are unavailable or throw are passed as null, and fall back to the
 * vendor list answer.
 */
enum class CallVendor {
    HONOR,
    HUAWEI,
    REALME,
    XIAOMI,
    OPPO,
    SAMSUNG,
    GENERIC,
}

object VendorAudioPolicy {

    /**
     * Vendors whose **hardware** AEC/NS is known to mute the capture path or
     * lock the audio session, requiring the libwebrtc software AEC/NS instead.
     *
     * This is the authoritative list that [prefersSoftwareAudioProcessing] and
     * [requiresExplicitMicUnmuteOnStart] consult as the FIRST check — kept
     * here (not in the WebRTC manager) so the calls and webrtc packages share
     * one definition and a single JVM test locks it.
     *
     * Evidence from user reports: Xiaomi/MIUI, Realme/RealmeUI, Oppo/ColorOS,
     * Infinix/XOS, Tecno/HiOS, Huawei/EMUI, Honor/MagicOS, ZTE. Samsung / Pixel
     * / OnePlus ship working HW AEC and deliberately stay off this list (lower
     * CPU, better quality).
     *
     * The vendor list is now supplemented by runtime signal checking
     * ([requiresSoftwareAec] with `hwAecCreatable` signal) so a device outside
     * this list whose HW AEC fails to create is also covered.
     */
    private val BROKEN_HW_AEC_VENDORS = setOf(
        "xiaomi", "redmi", "poco",
        "realme",
        "oppo",
        "infinix", "itel",
        "tecno",
        "huawei", "honor", "hihonor",
        "zte",
    )

    /**
     * Coarse vendor classification used to pick an audio-routing strategy.
     *
     * Matching is case-insensitive against both `Build.MANUFACTURER` and
     * `Build.BRAND` because OEMs are inconsistent about which field carries the
     * recognisable name (e.g. Honor reports MANUFACTURER=HONOR but some Huawei-
     * era firmware still tags BRAND=Honor). HONOR is checked before HUAWEI on
     * purpose: post-2020 Honor is a separate company with its own MagicOS HAL,
     * so an "honor" / "hihonor" token must classify as [CallVendor.HONOR] and
     * not be swallowed by a broad "huawei" match.
     */
    fun detect(manufacturer: String?, brand: String?): CallVendor {
        val m = manufacturer?.trim()?.lowercase().orEmpty()
        val b = brand?.trim()?.lowercase().orEmpty()
        fun matches(token: String) = m.contains(token) || b.contains(token)

        return when {
            matches("honor") || matches("hihonor") -> CallVendor.HONOR
            matches("huawei") -> CallVendor.HUAWEI
            matches("realme") -> CallVendor.REALME
            matches("xiaomi") || matches("redmi") || matches("poco") -> CallVendor.XIAOMI
            matches("oppo") -> CallVendor.OPPO
            matches("samsung") -> CallVendor.SAMSUNG
            else -> CallVendor.GENERIC
        }
    }

    /**
     * Whether [AudioRouter.start] must force an explicit `setMicrophoneMute(false)`
     * after the comm-device routing, and re-release it on each OEM mode-reapply
     * tick — because the device's audio HAL is known to leave the process-global
     * microphone-mute flag asserted during call setup, stranding the local
     * capture path in silence so the peer hears nothing while video (a separate
     * track) plays normally.
     *
     * WEE-103: keyed off the SAME broken-HW-AEC family as
     * [prefersSoftwareAudioProcessing] (see [BROKEN_HW_AEC_VENDORS]) rather than a
     * hand-maintained [CallVendor] subset. The mic-mute-flag bug and the broken
     * HW-AEC bug share one root — the vendor audio HAL — so every device that
     * needs software AEC is also a device that can strand the capture path muted.
     * The previous gate keyed off the coarse [detect] enum ({HONOR, REALME, OPPO,
     * XIAOMI}), which kept missing OEMs that [detect] classifies as GENERIC:
     * Infinix/XOS (#1008) and ZTE/nubia (#1009) both ship broken HW AEC yet fell
     * through to the generic path with the mic left muted → both-ways silence on
     * 1.10.44. HUAWEI was also excluded by WEE-87 on the assumption it had "no
     * mic-mute-flag report" — #1009 (Huawei pure 70 ultra ↔ nubia, both-ways
     * silence + working video) falsifies that, and Huawei is in the broken-AEC
     * family too, so it is now covered.
     *
     * This is the same too-narrow-vendor-gate regression that bit WEE-76→WEE-87;
     * tying the predicate to the broken-AEC family (instead of re-enumerating
     * vendors by hand) ends that cycle — the next broken-AEC OEM is covered
     * automatically.
     *
     * Samsung / Pixel / OnePlus / unknown-working OEMs are NOT in
     * [BROKEN_HW_AEC_VENDORS], so the proven generic WEE-54 start path stays
     * byte-for-byte unchanged on the working majority (acceptance criterion A5).
     * Re-asserting the unmute is itself documented-safe — it can only release a
     * stuck capture path, never break a healthy one (the in-call Mute button
     * toggles the WebRTC track's `enabled`, not this AudioManager flag).
     *
     * @param manufacturer The device's Build.MANUFACTURER (or null)
     * @param brand The device's Build.BRAND (or null)
     * @param hwAecCreatable Whether AcousticEchoCanceler.create(sessionId) succeeded
     *        on this session. Null if the check was not performed or threw an exception.
     *        If false (HW AEC is not creatable), this device needs software processing
     *        and the mic unmute, regardless of vendor list membership.
     *
     * @return true if the device needs explicit mic unmute on start.
     */
    fun requiresExplicitMicUnmuteOnStart(
        manufacturer: String?,
        brand: String?,
        hwAecCreatable: Boolean? = null,
    ): Boolean = requiresSoftwareAec(manufacturer, brand, hwAecCreatable)

    /**
     * Whether the WebRTC factory should use **software** AEC/NS for this device
     * instead of the hardware path. See [BROKEN_HW_AEC_VENDORS].
     *
     * This is the root-cause handling for the HUAWEI "video works, no audio"
     * symptom (#874): EMUI hardware AEC mutes the capture stream, so the video
     * pipeline is fine while audio is dropped. We deliberately do NOT force a
     * PCMU/G.711 codec fallback (as one hypothesis suggested) — disabling Opus
     * would degrade quality for every Huawei user and risk one-way audio
     * against Opus-only web/desktop peers (an A5 regression). Falling back to
     * software audio processing fixes the capture mute without touching codec
     * negotiation.
     *
     * Matches manufacturer OR brand (case-insensitive, whitespace-trimmed),
     * mirroring the prior `NativeWebRTCManager` predicate. One intentional
     * tightening: that predicate returned false whenever `Build.MANUFACTURER`
     * was null, ignoring the brand; this one falls back to the brand, so a
     * device exposing the vendor only via `Build.BRAND` still gets the software
     * fallback. Real production builds populate both fields, so the difference
     * is observable only on emulator / malformed-ROM builds — and there the
     * brand-aware answer is the safer one.
     *
     * @param manufacturer The device's Build.MANUFACTURER (or null)
     * @param brand The device's Build.BRAND (or null)
     * @param hwAecCreatable Whether AcousticEchoCanceler.create(sessionId) succeeded.
     *        Null if not queried or threw an exception. If false, HW AEC cannot be
     *        created so software processing is required.
     *
     * @return true if the device should use software audio processing.
     */
    fun prefersSoftwareAudioProcessing(
        manufacturer: String?,
        brand: String?,
        hwAecCreatable: Boolean? = null,
    ): Boolean = requiresSoftwareAec(manufacturer, brand, hwAecCreatable)

    /**
     * Post-call defensive microphone unmute for **every** vendor
     * (#875 / #898 / #900 + the cross-app "Telegram voice message is empty
     * after a Forta call" report).
     *
     * `AudioManager.setMicrophoneMute` toggles a process-global flag. If any
     * path left it asserted when the call tears down, other apps
     * (Telegram / WhatsApp / system recorder) capture pure silence until
     * reboot. Clearing it on every teardown is universally safe — it can only
     * release a stuck capture path, never break a healthy one — so the policy
     * is vendor-independent and always true. Kept as a predicate (rather than a
     * bare call site) so the intent is documented and locked by a test.
     */
    fun shouldUnmuteMicOnStop(vendor: CallVendor): Boolean = true

    /**
     * Pure decision function: does this device need software audio processing
     * (software AEC/NS) instead of hardware?
     *
     * Decision tree:
     * 1. Check vendor list FIRST. If the vendor matches [BROKEN_HW_AEC_VENDORS],
     *    return true immediately (never regress). The list is dominant — devices
     *    we know are broken always get the software fallback.
     * 2. If the vendor is not in the list, check runtime signals:
     *    - If hwAecCreatable is false (HW AEC failed to create), return true.
     *      This catches unlisted devices whose HW AEC is broken at runtime.
     *    - If hwAecCreatable is null (signal unavailable or threw), fall back
     *      to the vendor list answer (which is false here, since step 1 didn't
     *      match).
     * 3. If no signal indicates a problem, return false (use hardware AEC).
     *
     * This approach keeps the vendor list as the first safety net (never
     * regressing devices we know are broken) but adds runtime detection so
     * devices outside the list can still be covered if their HW AEC fails.
     *
     * WEE-110: addresses the fact that the vendor list approach requires
     * manual widening every time a new OEM's broken HW AEC is discovered,
     * and triage showed 12 devices outside the list with similar issues.
     *
     * @param vendor Manufacturer + brand (normalized by the caller, or null)
     * @param hwAecCreatable Result of trying to create AcousticEchoCanceler on
     *        this session:
     *        - true: AcousticEchoCanceler.create(sessionId) succeeded
     *        - false: it returned null, or setEnabled threw
     *        - null: the check was not performed, threw an exception, or the
     *          audio APIs are unavailable. Falls back to vendor list.
     *
     * @return true if software AEC/NS should be used; false otherwise.
     */
    private fun requiresSoftwareAec(
        vendor: String?,
        brand: String?,
        hwAecCreatable: Boolean?,
    ): Boolean {
        // Step 1: Check vendor list first (dominant, never regress).
        if (matchesBrokenHwAecFamily(vendor, brand)) {
            return true
        }

        // Step 2: If not in vendor list, check runtime signals.
        // hwAecCreatable = false means HW AEC failed to create at runtime.
        if (hwAecCreatable == false) {
            return true
        }

        // Step 3: No signal indicates a problem — use hardware AEC.
        return false
    }

    /**
     * Case-insensitive substring membership test against [BROKEN_HW_AEC_VENDORS]
     * over both `Build.MANUFACTURER` and `Build.BRAND`. Shared by
     * [prefersSoftwareAudioProcessing] and [requiresExplicitMicUnmuteOnStart]
     * (WEE-103) so the two HAL-driven remedies key off one definition of the
     * broken-audio-HAL family and can never drift apart again.
     *
     * WEE-60: substring match, not strict equality. detect() above already uses
     * contains(); the prior `==` predicate missed OEMs that report a multi-word
     * Build.MANUFACTURER/BRAND (e.g. "Infinix Mobility Limited", "TECNO MOBILE
     * LIMITED", brand "Itel it2163") and kept the broken hardware AEC — muting
     * the capture path into one-way audio (#894 Xiaomi 12X, #921 Pixel-adjacent).
     */
    private fun matchesBrokenHwAecFamily(manufacturer: String?, brand: String?): Boolean {
        val m = manufacturer?.trim()?.lowercase().orEmpty()
        val b = brand?.trim()?.lowercase().orEmpty()
        if (m.isEmpty() && b.isEmpty()) return false
        return BROKEN_HW_AEC_VENDORS.any { v -> m.contains(v) || b.contains(v) }
    }
}
