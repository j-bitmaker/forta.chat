import { describe, expect, it } from "vitest";
import {
  buildDigest,
  classifyClusters,
  classifyVendor,
  isCallRelated,
  isBrokenAecVendor,
  parseCallDiagnostics,
  parseDescription,
  parseEnvironment,
  parseAudioTimeline,
  timelineShowsAudioNeverEngaged,
} from "./triage-call-reports.mjs";

describe("parseEnvironment", () => {
  it("extracts platform and version from environment table", () => {
    const body = `## Environment
| Field | Value |
|-------|-------|
| Platform | android |
| Version | 1.11.4 |
| Build | 123 |
| WebView | 150.0.7049.111 |
| OS | 15 |
| Device | Xiaomi 2201117TG |
| Network | 4g |
| Tor | disabled |
| Matrix | ready |`;

    const env = parseEnvironment(body);
    expect(env.platform).toBe("android");
    expect(env.appVersion).toBe("1.11.4");
    expect(env.webView).toBe("150.0.7049.111");
    expect(env.os).toBe("15");
    expect(env.device).toBe("Xiaomi 2201117TG");
    expect(env.network).toBe("4g");
    expect(env.tor).toBe("disabled");
    expect(env.matrixReady).toBe(true);
  });

  it("returns empty object when no environment section", () => {
    const body = "No environment here";
    const env = parseEnvironment(body);
    expect(Object.keys(env).length).toBe(0);
  });
});

describe("parseCallDiagnostics", () => {
  it("extracts call diagnostics from the table", () => {
    const body = `## Call diagnostics
| Field | Value |
|-------|-------|
| Audio mode | MODE_NORMAL |
| Speaker on | no |
| BT SCO on | no |
| Recent invites | 3 |
| Expired invites | 0 |`;

    const diag = parseCallDiagnostics(body);
    expect(diag).not.toBeNull();
    expect(diag!.audioMode).toBe("MODE_NORMAL");
    expect(diag!.speakerOn).toBe(false);
    expect(diag!.btScoOn).toBe(false);
    expect(diag!.recentInvites).toBe(3);
    expect(diag!.expiredInvites).toBe(0);
  });

  it("returns null when no call diagnostics section", () => {
    const body = "## Description\nNo diagnostics";
    const diag = parseCallDiagnostics(body);
    expect(diag).toBeNull();
  });

  it("detects MODE_RINGTONE in audio mode", () => {
    const body = `## Call diagnostics
| Field | Value |
|-------|-------|
| Audio mode | MODE_RINGTONE |
| Speaker on | no |
| BT SCO on | no |
| Recent invites | 0 |
| Expired invites | 0 |`;

    const diag = parseCallDiagnostics(body);
    expect(diag!.audioMode).toBe("MODE_RINGTONE");
  });
});

describe("parseDescription", () => {
  it("extracts description text", () => {
    const body = `## Description
The app is not letting me accept incoming calls.

## Environment
| Field | Value |`;

    const desc = parseDescription(body);
    expect(desc).toContain("not letting me accept");
  });

  it("returns empty string when no description", () => {
    const body = "## Environment\n| Field | Value |";
    const desc = parseDescription(body);
    expect(desc.length).toBe(0);
  });
});

describe("classifyVendor", () => {
  it("recognizes Xiaomi and variants", () => {
    expect(classifyVendor("Xiaomi 12X")).toBe("Xiaomi");
    expect(classifyVendor("Redmi Note 11")).toBe("Xiaomi");
    expect(classifyVendor("Poco X3")).toBe("Xiaomi");
  });

  it("recognizes Honor and variants", () => {
    expect(classifyVendor("Honor 50")).toBe("Honor");
    expect(classifyVendor("HiHonor Magic 3")).toBe("Honor");
  });

  it("recognizes Samsung", () => {
    expect(classifyVendor("Samsung Galaxy S21")).toBe("Samsung");
    expect(classifyVendor("SM-G950F")).toBe("Samsung");
  });

  it("recognizes other vendors", () => {
    expect(classifyVendor("OPPO A12")).toBe("OPPO");
    expect(classifyVendor("Realme 8")).toBe("Realme");
    expect(classifyVendor("Huawei P40")).toBe("Huawei");
    expect(classifyVendor("Google Pixel 6")).toBe("Google");
    expect(classifyVendor("OnePlus 9")).toBe("OnePlus");
  });

  it("returns unknown for n/a or empty", () => {
    expect(classifyVendor("n/a")).toBe("unknown");
    expect(classifyVendor("")).toBe("unknown");
  });

  it("capitalizes first token for unknown vendors", () => {
    expect(classifyVendor("Nokia Device X")).toBe("Nokia");
  });
});

describe("isBrokenAecVendor", () => {
  it("recognizes broken AEC vendors", () => {
    expect(isBrokenAecVendor("Xiaomi 12X")).toBe(true);
    expect(isBrokenAecVendor("Realme 8")).toBe(true);
    expect(isBrokenAecVendor("Honor 50")).toBe(true);
    expect(isBrokenAecVendor("Infinix Note 12")).toBe(true);
    expect(isBrokenAecVendor("ZTE Blade V10")).toBe(true);
  });

  it("correctly identifies Samsung as NOT broken", () => {
    expect(isBrokenAecVendor("Samsung Galaxy S21")).toBe(false);
  });

  it("correctly identifies Google as NOT broken", () => {
    expect(isBrokenAecVendor("Google Pixel 6")).toBe(false);
  });

  it("returns false for empty device", () => {
    expect(isBrokenAecVendor("")).toBe(false);
  });
});

describe("isCallRelated", () => {
  it("recognizes English call keywords", () => {
    expect(
      isCallRelated("No audio during call", "I cannot hear anything")
    ).toBe(true);
    expect(isCallRelated("Speaker not working", "")).toBe(true);
    expect(isCallRelated("Ringtone missing", "")).toBe(true);
  });

  it("recognizes Russian call keywords", () => {
    expect(isCallRelated("звонок не работает", "")).toBe(true);
    expect(isCallRelated("вызов не подключается", "")).toBe(true);
    expect(isCallRelated("не слышно", "гудок не издает звука")).toBe(true);
  });

  it("excludes pure voice-message complaints without call keywords", () => {
    expect(
      isCallRelated(
        "Voice message issue",
        "голосовое сообщение не воспроизводится"
      )
    ).toBe(false);
  });

  it("includes voice-message complaint if call keywords are present", () => {
    expect(
      isCallRelated(
        "Call and voice message",
        "голосовое сообщение не работает после звонка"
      )
    ).toBe(true);
  });

  it("returns false for non-call issues", () => {
    expect(isCallRelated("Battery drains quickly", "")).toBe(false);
    expect(isCallRelated("Login fails", "Cannot authenticate")).toBe(false);
  });
});

describe("classifyClusters", () => {
  it("identifies stuck-after-call when audioMode is MODE_RINGTONE", () => {
    const diag = { audioMode: "MODE_RINGTONE", recentInvites: 0, expiredInvites: 0 };
    const clusters = classifyClusters("Issue after call", "", diag);
    expect(clusters).toContain("stuck-after-call");
  });

  it("identifies stuck-after-call when audioMode is MODE_IN_COMMUNICATION", () => {
    const diag = { audioMode: "MODE_IN_COMMUNICATION", recentInvites: 0, expiredInvites: 0 };
    const clusters = classifyClusters("Issue after call", "", diag);
    expect(clusters).toContain("stuck-after-call");
  });

  it("identifies stuck-after-call from Russian text", () => {
    const clusters = classifyClusters("Problem", "после звонка не работает микрофон", null);
    expect(clusters).toContain("stuck-after-call");
  });

  it("identifies no-audio clusters", () => {
    const clusters = classifyClusters("No sound", "не слышу собеседника", null);
    expect(clusters).toContain("no-audio");
  });

  it("identifies speaker-toggle clusters", () => {
    const clusters = classifyClusters("Speaker issue", "динамик не работает", null);
    expect(clusters).toContain("speaker-toggle");
  });

  it("identifies no-ringtone clusters", () => {
    const clusters = classifyClusters("Missing tone", "гудок не издает звука", null);
    expect(clusters).toContain("no-ringtone");
  });

  it("returns other when no clusters match", () => {
    const clusters = classifyClusters("Some issue", "description", null);
    expect(clusters).toContain("other");
  });

  it("handles Samsung device with no-audio (outside vendor gate)", () => {
    // Samsung is NOT in the broken AEC vendor gate, so reporting audio issues is interesting
    const clusters = classifyClusters("Samsung no audio", "no sound at all", null);
    expect(clusters).toContain("no-audio");
  });
});

describe("buildDigest", () => {
  it("generates digest with cluster counts", () => {
    const reports = [
      {
        number: 1,
        title: "Call issue 1",
        description: "no sound",
        environment: { device: "Xiaomi 12X", appVersion: "1.11.4" },
        diagnostics: null,
        reporterHash: "abc123",
        isCallRelated: true,
        vendor: "Xiaomi",
        device: "Xiaomi 12X",
        appVersion: "1.11.4",
        clusters: ["no-audio"],
      },
      {
        number: 2,
        title: "Call issue 2",
        description: "после вызова не работает",
        environment: { device: "Realme 8", appVersion: "1.11.4" },
        diagnostics: { audioMode: "MODE_RINGTONE", speakerOn: false, btScoOn: false, recentInvites: 0, expiredInvites: 0 },
        reporterHash: "def456",
        isCallRelated: true,
        vendor: "Realme",
        device: "Realme 8",
        appVersion: "1.11.4",
        clusters: ["stuck-after-call"],
      },
    ];

    const digest = buildDigest(reports);
    expect(digest).toContain("Total reports: 2");
    expect(digest).toContain("no-audio: 1");
    expect(digest).toContain("stuck-after-call: 1");
    expect(digest).toContain("Xiaomi: 1");
    expect(digest).toContain("Realme: 1");
  });

  it("counts devices outside vendor gate with audio problems", () => {
    const reports = [
      {
        number: 1,
        title: "Samsung no audio",
        description: "no sound during call",
        environment: { device: "Samsung Galaxy S21", appVersion: "1.11.4" },
        diagnostics: null,
        reporterHash: "abc123",
        isCallRelated: true,
        vendor: "Samsung",
        device: "Samsung Galaxy S21",
        appVersion: "1.11.4",
        clusters: ["no-audio"],
      },
    ];

    const digest = buildDigest(reports);
    expect(digest).toContain("Outside Vendor Gate");
    expect(digest).toContain("1");
    expect(digest).toContain("vendor gate may need to be widened");
  });

  it("tracks repeat reporters", () => {
    const reports = [
      {
        number: 1,
        title: "Issue 1",
        description: "desc",
        environment: {},
        diagnostics: null,
        reporterHash: "sameHashAAA",
        isCallRelated: false,
        vendor: "unknown",
        device: "",
        appVersion: "unknown",
        clusters: ["other"],
      },
      {
        number: 2,
        title: "Issue 2",
        description: "desc",
        environment: {},
        diagnostics: null,
        reporterHash: "sameHashAAA",
        isCallRelated: false,
        vendor: "unknown",
        device: "",
        appVersion: "unknown",
        clusters: ["other"],
      },
    ];

    const digest = buildDigest(reports);
    expect(digest).toContain("Repeat Reporters");
    expect(digest).toContain("sameHashAAA");
    expect(digest).toContain("2 reports");
  });

  it("includes version counts", () => {
    const reports = [
      {
        number: 1,
        title: "Issue 1",
        description: "",
        environment: { appVersion: "1.11.4" },
        diagnostics: null,
        reporterHash: null,
        isCallRelated: false,
        vendor: "unknown",
        device: "",
        appVersion: "1.11.4",
        clusters: ["other"],
      },
      {
        number: 2,
        title: "Issue 2",
        description: "",
        environment: { appVersion: "1.11.3" },
        diagnostics: null,
        reporterHash: null,
        isCallRelated: false,
        vendor: "unknown",
        device: "",
        appVersion: "1.11.3",
        clusters: ["other"],
      },
    ];

    const digest = buildDigest(reports);
    expect(digest).toContain("App Versions");
    expect(digest).toContain("1.11.4");
    expect(digest).toContain("1.11.3");
  });
});

describe("parseAudioTimeline", () => {
  const withTimeline = [
    "## Call diagnostics",
    "| Field | Value |",
    "|-------|-------|",
    "| WebRTC engine | native |",
    "| Audio mode | MODE_RINGTONE |",
    "",
    "<details><summary>Audio timeline</summary>",
    "",
    "| t (ms) | event | detail |",
    "|--------|-------|--------|",
    "| 0 | start | voice |",
    "| 14 | mode | MODE_IN_COMMUNICATION |",
    "| 1500 | mode_reapply | +1500ms |",
    "| 9200 | stop | — |",
    "</details>",
  ].join("\n");

  it("reads entries oldest-first with relative times", () => {
    expect(parseAudioTimeline(withTimeline)).toEqual([
      { atMs: 0, event: "start", detail: "voice" },
      { atMs: 14, event: "mode", detail: "MODE_IN_COMMUNICATION" },
      { atMs: 1500, event: "mode_reapply", detail: "+1500ms" },
      { atMs: 9200, event: "stop", detail: "" },
    ]);
  });

  it("returns an empty list for reports without the block", () => {
    expect(parseAudioTimeline("## Description\nno audio at all")).toEqual([]);
  });

  it("also picks up the engine row from the diagnostics table", () => {
    expect(parseCallDiagnostics(withTimeline)?.webrtcEngine).toBe("native");
  });
});

describe("timelineShowsAudioNeverEngaged", () => {
  it("flags a call that started but never entered call audio", () => {
    // The snapshot would just say MODE_NORMAL — indistinguishable from a
    // healthy finished call. The timeline shows the mode was never set.
    expect(
      timelineShowsAudioNeverEngaged([
        { atMs: 0, event: "start", detail: "voice" },
        { atMs: 5000, event: "stop", detail: "" },
      ]),
    ).toBe(true);
  });

  it("flags a call whose teardown had to be forced", () => {
    expect(
      timelineShowsAudioNeverEngaged([
        { atMs: 0, event: "start", detail: "voice" },
        { atMs: 12, event: "mode", detail: "MODE_IN_COMMUNICATION" },
        { atMs: 300000, event: "watchdog", detail: "orphaned router" },
      ]),
    ).toBe(true);
  });

  it("does not flag a healthy call", () => {
    expect(
      timelineShowsAudioNeverEngaged([
        { atMs: 0, event: "start", detail: "voice" },
        { atMs: 12, event: "mode", detail: "MODE_IN_COMMUNICATION" },
        { atMs: 8000, event: "stop", detail: "" },
      ]),
    ).toBe(false);
  });

  it("does not flag reports that carry no timeline at all", () => {
    expect(timelineShowsAudioNeverEngaged([])).toBe(false);
  });
});
