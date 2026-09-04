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

  it("does NOT treat MODE_IN_CALL as a stuck audio mode", () => {
    // MODE_IN_CALL belongs to the telephony stack, not to us — we only ever
    // set MODE_IN_COMMUNICATION. The open reports filed from it are a video
    // feature request (#955) and a localisation bug (#855), so counting it as
    // stuck-after-call would inflate the largest cluster with noise.
    const diag = { audioMode: "MODE_IN_CALL", recentInvites: 0, expiredInvites: 0 };
    const clusters = classifyClusters("Нет перевода в списке чатов", "", diag);
    expect(clusters).not.toContain("stuck-after-call");
  });

  it("identifies one-way audio phrased as a contrast (#1292)", () => {
    // "he hears me, I don't hear him" — the negation is attached to the other
    // party, several words away from the verb, so the negated-hearing stem
    // pattern alone misses it and the report used to land in "other".
    const clusters = classifyClusters(
      "Проблема со звуком",
      "При звонках собеседник меня слышит я его нет",
      null,
    );
    expect(clusters).toContain("no-audio");
  });

  it("identifies quiet incoming audio with the adjective after the noun (#1068)", () => {
    const clusters = classifyClusters(
      "Проблема",
      "На входящем звонке звук тихий, хотя на полную громкость стоит",
      null,
    );
    expect(clusters).toContain("no-audio");
  });

  it("identifies a call dropped on answer phrased with the noun (#1068)", () => {
    const clusters = classifyClusters("Проблема", "При ответе происходит сброс звонка", null);
    expect(clusters).toContain("accept-button");
  });

  it("does not call every mention of an answer an accept-button failure", () => {
    const clusters = classifyClusters("Вопрос", "Жду ответа от поддержки", null);
    expect(clusters).not.toContain("accept-button");
  });

  it("identifies a microphone the app never released (#997, #1088)", () => {
    // Same class of leak as a stranded audio mode, and the same fix: the
    // foreground service teardown. Reports saying it used to land in "other".
    const clusters = classifyClusters(
      "Проблема",
      "Входящие вызовы идут, но соединение не происходит. После этого микрофон продолжает использоваться приложением forta",
      null,
    );
    expect(clusters).toContain("stuck-after-call");
    expect(clusters).toContain("connect-fail");
    expect(clusters).not.toContain("other");
  });

  it("identifies an unreachable callee phrased from the caller's side (#1088)", () => {
    const clusters = classifyClusters("Проблема", "До меня не могут дозвониться", null);
    expect(clusters).toContain("connect-fail");
  });

  it("identifies stacked parallel rings from one peer (#1088)", () => {
    const clusters = classifyClusters(
      "Проблема",
      "Происходит наслоение интерфейса несколько звонков параллельно от одного и того же собеседника",
      null,
    );
    expect(clusters).toContain("duplicate-ring");
  });

  it("leaves a report with no symptom in it as other (#1023)", () => {
    // Not every report is classifiable, and inventing a cluster for pure
    // frustration would put work in a bucket that has none.
    const clusters = classifyClusters(
      "Проблема",
      "Хоть вы и пишете, что проблема решена, но она не решена",
      null,
    );
    expect(clusters).toContain("other");
  });

  it("does not read an unrelated negation near a positive hearing report as no-audio", () => {
    // "слышно" + a "нет" 40 characters later, about something else entirely.
    // The one-way pattern needs the other party in it, not just any negation.
    const clusters = classifyClusters(
      "Отзыв",
      "Слышно нормально, а вот интернета у меня дома нет",
      null,
    );
    expect(clusters).not.toContain("no-audio");
  });

  it("does not read 'ответственно' as a mention of answering a call", () => {
    const clusters = classifyClusters(
      "Отзыв",
      "Я ответственно заявляю, звонок сбрасывается, не могу понять почему",
      null,
    );
    expect(clusters).not.toContain("accept-button");
  });

  it("does not read a pile-up of unrelated problems as duplicate rings", () => {
    // "наслоение" is ordinary Russian for problems piling up; on its own it
    // says nothing about two ringers on screen.
    const clusters = classifyClusters(
      "Отзыв",
      "Странное наслоение проблем: то приложение виснет, то список не грузится",
      null,
    );
    expect(clusters).not.toContain("duplicate-ring");
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

describe("classifyClusters — real report wording", () => {
  // Every case below is taken from the wording of an actual open report, with
  // the device details stripped. The old keyword list missed all of them.

  it("catches negated hearing written as one word or with typos", () => {
    // "неслышим", "неслыши" — users type fast on a phone.
    expect(classifyClusters("", "После соединения при аудиозвонке мы друг друга неслышим", null))
      .toContain("no-audio");
    expect(classifyClusters("", "Почему не слышно друг друга при звонке?", null))
      .toContain("no-audio");
  });

  it("treats hearing yourself as a quality fault, not silence", () => {
    // Echo points at the acoustic echo canceller, silence at routing — the
    // two need different fixes, so they must not land in one bucket.
    const clusters = classifyClusters("", "При звонке слышно самого себя, посторонние звуки", null);
    expect(clusters).toContain("quality");
    expect(clusters).not.toContain("no-audio");
  });

  it("recognises 'громкая связь' as the speaker toggle", () => {
    expect(classifyClusters("", "Включая громкую связь не слышно собеседника", null))
      .toContain("speaker-toggle");
  });

  it("catches the accept-button race however it is described", () => {
    expect(
      classifyClusters("", "При попытке ответить на входящий нажатием зелёной кнопочки всё сбрасывается", null),
    ).toContain("accept-button");
    expect(
      classifyClusters("", "Нажимая принять вызов, звонок либо обрывается, либо приложение закрывается", null),
    ).toContain("accept-button");
  });

  it("catches calls that never connect", () => {
    expect(classifyClusters("", "Звонки проходят, а связи нет", null)).toContain("connect-fail");
    expect(classifyClusters("", "Не работает дозвон", null)).toContain("connect-fail");
  });

  it("flags duplicate rings, including the Bastyon coexistence case", () => {
    expect(classifyClusters("", "Я звонил товарищу 2 раза, а уведомлений об исходящем 4шт", null))
      .toContain("duplicate-ring");
    expect(
      classifyClusters("", "Когда установлены и Бастион и Форта, звонок приходит дважды", null),
    ).toContain("duplicate-ring");
  });

  it("does NOT read the word 'телефон' as a background-call report", () => {
    // The previous classifier matched the substring "фон", so every report
    // that merely said "телефон" was counted as a background-delivery bug —
    // 19 of the 22 in that cluster were false.
    const clusters = classifyClusters("", "Телефон не реагировал на нажатие кнопок громкости", null);
    expect(clusters).not.toContain("background-incoming");
  });

  it("still flags a genuine background-delivery report", () => {
    expect(
      classifyClusters("", "Когда приложение не открыто (в фоне) входящий вызов не поступает", null),
    ).toContain("background-incoming");
  });

  it("uses the audio-mode snapshot as independent evidence of being stuck", () => {
    expect(classifyClusters("", "звук пропал", { audioMode: "MODE_RINGTONE" }))
      .toContain("stuck-after-call");
  });
});
