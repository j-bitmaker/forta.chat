#!/usr/bin/env node
/**
 * Triage GitHub bug reports from greenShirtMystery/forta-bugs.
 *
 * Classifies call-related issues to accelerate triage:
 * - Parse issue body for environment, diagnostics, and description
 * - Classify by vendor (broken AEC gate, known OEM quirks)
 * - Cluster by symptom (no-audio, stuck-after-call, accept-button, etc.)
 * - Generate digest with counts per cluster/vendor and reporter tracking
 *
 * Pure functions for all parsing/classification so tests don't need network.
 * CLI wrapper only when run directly; supports --json, --since, --limit flags.
 */

import { execSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

/**
 * Parse the Environment section of a bug report body.
 * Returns an object with platform, appVersion, webView, os, device, network, tor, matrixReady.
 *
 * Reads markdown table rows like `| Platform | android |`
 */
export function parseEnvironment(body) {
  const envMatch = body.match(
    /##\s*Environment\s*\|[\s\S]*?(?=##|$)/
  );
  if (!envMatch) {
    return {};
  }

  const table = envMatch[0];
  const result = {};

  const rows = table.split("\n");
  for (const row of rows) {
    const match = row.match(/\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|/);
    if (!match) continue;

    const key = match[1].trim().toLowerCase();
    const value = match[2].trim();

    if (key === "platform") result.platform = value;
    else if (key === "version") result.appVersion = value;
    else if (key === "webview") result.webView = value;
    else if (key === "os") result.os = value;
    else if (key === "device") result.device = value;
    else if (key === "network") result.network = value;
    else if (key === "tor") result.tor = value;
    else if (key === "matrix") result.matrixReady = value.toLowerCase().includes("ready");
  }

  return result;
}

/**
 * Parse the Call diagnostics section if present.
 * Returns null when the section is missing.
 * Otherwise returns { audioMode, speakerOn, btScoOn, recentInvites, expiredInvites }
 */
export function parseCallDiagnostics(body) {
  const diagMatch = body.match(
    /##\s*Call\s+diagnostics\s*\|[\s\S]*?(?=##|<details|$)/
  );
  if (!diagMatch) {
    return null;
  }

  const table = diagMatch[0];
  const result = {};

  const rows = table.split("\n");
  for (const row of rows) {
    const match = row.match(/\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|/);
    if (!match) continue;

    const key = match[1].trim().toLowerCase();
    const value = match[2].trim();

    if (key === "audio mode") result.audioMode = value;
    else if (key === "webrtc engine") result.webrtcEngine = value;
    else if (key === "speaker on") result.speakerOn = value.toLowerCase().includes("yes");
    else if (key === "bt sco on") result.btScoOn = value.toLowerCase().includes("yes");
    else if (key === "recent invites") result.recentInvites = parseInt(value, 10) || 0;
    else if (key === "expired invites") result.expiredInvites = parseInt(value, 10) || 0;
  }

  return Object.keys(result).length > 0 ? result : null;
}

/**
 * Parse the collapsed "Audio timeline" table a report carries when the device
 * ran a call on the native engine. Entries are `{ atMs, event, detail }`,
 * oldest first, times relative to the first entry.
 *
 * Returns an empty array when the block is absent — most reports predate it,
 * and iOS never emits one.
 */
export function parseAudioTimeline(body) {
  const block = body.match(
    /<summary>\s*Audio timeline\s*<\/summary>([\s\S]*?)<\/details>/i
  );
  if (!block) return [];

  const entries = [];
  for (const row of block[1].split("\n")) {
    const cells = row.match(/\|\s*([^|]*?)\s*\|\s*([^|]*?)\s*\|\s*([^|]*?)\s*\|/);
    if (!cells) continue;
    const atMs = Number.parseInt(cells[1], 10);
    if (Number.isNaN(atMs)) continue; // header and separator rows
    const detail = cells[3] === "\u2014" ? "" : cells[3];
    entries.push({ atMs, event: cells[2], detail });
  }
  return entries;
}

/**
 * True when the timeline shows audio was never actually handed to the call:
 * a start with no MODE_IN_COMMUNICATION, or a teardown that had to be forced.
 * Both are stronger evidence than the audio-mode snapshot, which cannot tell
 * "never entered call audio" from "already recovered by the time of the report".
 */
export function timelineShowsAudioNeverEngaged(entries) {
  if (!entries.length) return false;
  const started = entries.some((e) => e.event === "start");
  if (!started) return false;
  const engaged = entries.some(
    (e) => e.event === "mode" || e.event === "mode_reapply"
  );
  const forcedDown = entries.some(
    (e) => e.event === "force_stop" || e.event === "watchdog"
  );
  return !engaged || forcedDown;
}

/**
 * Extract the description text from the ## Description section.
 */
export function parseDescription(body) {
  const descMatch = body.match(
    /##\s*Description\s*\n([\s\S]*?)(?=##|$)/
  );
  if (!descMatch) {
    return "";
  }
  return descMatch[1].trim();
}

/**
 * Normalize a device model string to a vendor name.
 * Recognizes: Xiaomi, Realme, OPPO, Vivo, Huawei, Honor, Samsung, Tecno, Infinix, Itel, OnePlus, Motorola, Google, ZTE.
 * Returns the first capitalized token on unknown vendors, or "unknown" for empty/n/a values.
 */
export function classifyVendor(device) {
  if (!device || device.toLowerCase() === "n/a") {
    return "unknown";
  }

  const d = device.toLowerCase();

  // Exact checks with aliases
  if (d.includes("xiaomi") || d.includes("redmi") || d.includes("poco")) {
    return "Xiaomi";
  }
  if (d.includes("realme")) {
    return "Realme";
  }
  if (d.includes("oppo")) {
    return "OPPO";
  }
  if (d.includes("vivo")) {
    return "Vivo";
  }
  if (d.includes("huawei")) {
    return "Huawei";
  }
  if (d.includes("honor") || d.includes("hihonor")) {
    return "Honor";
  }
  if (d.includes("samsung") || d.includes("sm-")) {
    return "Samsung";
  }
  if (d.includes("tecno")) {
    return "Tecno";
  }
  if (d.includes("infinix")) {
    return "Infinix";
  }
  if (d.includes("itel")) {
    return "Itel";
  }
  if (d.includes("oneplus") || d.includes("one plus")) {
    return "OnePlus";
  }
  if (d.includes("motorola") || d.includes("moto")) {
    return "Motorola";
  }
  if (d.includes("google") || d.includes("pixel")) {
    return "Google";
  }
  if (d.includes("zte")) {
    return "ZTE";
  }

  // Extract first token and capitalize
  const first = device.split(/[\s\-_/]+/)[0].trim();
  if (!first) return "unknown";
  return first.charAt(0).toUpperCase() + first.slice(1).toLowerCase();
}

/**
 * Check if the device is in the broken AEC vendor gate.
 * Mirrors BROKEN_HW_AEC_VENDORS from VendorAudioPolicy.kt
 *
 * See: android/app/src/main/java/com/forta/chat/plugins/calls/VendorAudioPolicy.kt
 */
export function isBrokenAecVendor(device) {
  if (!device) return false;

  const d = device.toLowerCase();
  const vendors = [
    "xiaomi", "redmi", "poco",
    "realme",
    "oppo",
    "infinix", "itel",
    "tecno",
    "huawei", "honor", "hihonor",
    "zte",
  ];

  return vendors.some((v) => d.includes(v));
}

/**
 * Determine if an issue is call-related based on keywords in title and description.
 * Looks for call-related terms in Russian (звонок, вызов, гудок, слышно, динамик, микрофон)
 * and English (call, hear, speaker, ringtone, видеозвонок).
 *
 * Returns false for pure voice-message complaints ("голосовое сообщение") without call keywords.
 */
export function isCallRelated(title, description) {
  const text = `${title} ${description}`.toLowerCase();

  // Russian call keywords
  const ruCallKeywords = [
    "звонок",
    "звонк",
    "вызов",
    "дозвон",
    "гудок",
    "гудо",
    "видеозвонок",
  ];

  // English call keywords
  const enCallKeywords = [
    "call",
    "hear",
    "speaker",
    "ringtone",
    "microphone",
    "mic",
  ];

  // Combine all keywords
  const allKeywords = [...ruCallKeywords, ...enCallKeywords];

  // Check for call keyword presence
  const hasCallKeyword = allKeywords.some((kw) => text.includes(kw));

  if (!hasCallKeyword) {
    return false;
  }

  // Check for voice-message-only complaint (without call keywords)
  const voiceMessagePattern = /голосовое\s+сообщение/i;
  const hasVoiceMessage = voiceMessagePattern.test(text);

  // Only consider it a pure voice-message complaint if it has voice-message but no call keywords
  // Since we already checked for call keywords, we return true (is call-related)
  return !hasVoiceMessage || hasCallKeyword;
}

/**
 * Classify an issue into zero or more symptom clusters.
 * Returns an array of cluster IDs: "no-audio", "stuck-after-call", "accept-button",
 * "connect-fail", "speaker-toggle", "no-ringtone", "background-incoming", "video", "quality", "other".
 *
 * Uses title, description, and diagnostics to infer clusters.
 */
export function classifyClusters(title, description, diagnostics) {
  const text = `${title} ${description}`.toLowerCase();
  const clusters = new Set();
  const has = (...needles) => needles.some((n) => text.includes(n));

  // Reports are written by users on phones, so the vocabulary is messy:
  // "неслышим", "неслыши", "не слышно друг друга" all mean the same thing.
  // Matching on the stem "слыш" plus a negation catches the family without
  // enumerating every typo.
  // One-way audio is usually reported as a contrast rather than a plain
  // negation — "собеседник меня слышит я его нет" (#1292). The negation sits
  // on the other party, several words from the verb, so the stem+negation
  // pattern above never sees it. Bounded to one sentence so an unrelated
  // "нет" further down the report cannot pull a mention of hearing in.
  // NB: no \b around "нет" — JavaScript word boundaries are ASCII-only, so
  // \b never matches next to a Cyrillic letter. The character classes below
  // are the portable equivalent.
  //
  // Two guards against proximity alone: the gap has to be short, and the
  // window has to name the other party. "слышно нормально, а вот интернета у
  // меня дома нет" clears a 40-character gap and even contains a pronoun —
  // it is a report about the network. In the real one-way phrasings the two
  // halves sit right next to each other ("меня слышит я его нет", "я его
  // слышу, а он меня нет"), inside 20 characters. Anything longer than that
  // has room for a change of subject, which is exactly the failure mode.
  const NEAR_NEGATION = [
    /слыш[а-яё]*([^.!?\n]{0,20}?)(^|[^а-яё])нет([^а-яё]|$)/,
    /(^|[^а-яё])нет([^а-яё]|$)([^.!?\n]{0,20}?)слыш/,
  ];
  const OTHER_PARTY = /(меня|его|её|ее|их|вас|тебя|собеседник|абонент)/;
  const oneWayHearing = NEAR_NEGATION.some((re) => {
    const m = re.exec(text);
    if (!m) return false;
    // Everything the window spans, so the pronoun can sit on either side of
    // the verb: "меня слышит, я его нет" and "его не слышно" both qualify.
    return OTHER_PARTY.test(m[0]);
  });

  // Users put the adjective on either side of the noun: "тихий звук" and
  // "звук тихий" (#1068) are the same complaint.
  const quietAudio =
    /тих[а-яё]*\s+(звук|голос)/.test(text) ||
    /(звук|голос)\s+тих/.test(text);

  const negatedHearing =
    /(не|ни|бес)\s*слыш/.test(text) ||
    /неслыш/.test(text) ||
    oneWayHearing ||
    quietAudio ||
    has("плохо слышно", "тихо", "еле слышно");

  // Stuck after the call: the device is left in a call audio mode, which
  // breaks media volume until reboot. The diagnostics snapshot is the
  // strongest signal; the phrases cover reports filed before it existed.
  if (
    diagnostics &&
    (diagnostics.audioMode === "MODE_RINGTONE" ||
      diagnostics.audioMode === "MODE_IN_COMMUNICATION")
  ) {
    clusters.add("stuck-after-call");
  }
  if (
    has(
      "после звонка",
      "после вызова",
      "кнопки громкости",
      "регулировать громкость",
      "regulate the volume",
      "volume indicator",
      "не даёт звонить",
      "не дает звонить",
      "продолжается",
      "до перезагрузки",
      "before the next reboot",
      // A microphone the app never let go of is the same leak as a stranded
      // audio mode and has the same owner — the foreground service teardown
      // (#997, #1088).
      "микрофон продолжает",
      "доступ к микрофону",
      "микрофон используется",
      "микрофон занят",
      "microphone is still",
    )
  ) {
    clusters.add("stuck-after-call");
  }

  if (
    negatedHearing ||
    has("нет звука", "no audio", "silent", "no sound", "без звука")
  ) {
    clusters.add("no-audio");
  }

  // Echo and self-hearing are a distinct failure of the acoustic echo
  // canceller, not the same thing as silence — they point at a different fix.
  if (has("слышно самого себя", "слышу себя", "эхо", "echo", "посторонние звуки")) {
    clusters.add("quality");
  }

  // Accept button: the tap either drops the call, bounces back as an outgoing
  // one, or kills the app. All are the same race around answerCall.
  if (
    has(
      "зелёную",
      "зеленую",
      "зелёная",
      "зеленая",
      "accept",
      "не нажимается",
      "не работает кнопка",
    ) ||
    // "ответ" as a stem covers "при ответе" / "при ответах" (#1068), which
    // the verb forms missed — minus "ответствен*", which is a different word
    // that happens to start the same way and shows up in frustrated reports.
    // Still gated on a drop word, so a plain mention of an answer is not enough.
    ((has("принять", "отвечаю", "отвечая") || /ответ(?!ствен)/.test(text)) &&
      has("сброс", "сбрасывает", "обрывается", "закрывается", "не могу"))
  ) {
    clusters.add("accept-button");
  }

  if (
    has(
      "не подключается",
      "связи нет",
      "не проходят звонк",
      "звонки не работают",
      "не работает дозвон",
      "невозможно произвести звонок",
      "вылезает ошибка",
      // Reported from the caller's side, or as the absence of the handshake
      // rather than of the call (#997, #1088).
      "не могут дозвониться",
      "не может дозвониться",
      "не могу дозвониться",
      "соединение не происходит",
      "не происходит соединение",
      "connect",
      "failed",
      "timeout",
    )
  ) {
    clusters.add("connect-fail");
  }

  // Speaker: "громкая связь" is how users say speakerphone.
  if (
    has("динамик", "speaker", "toggle", "громкая связь", "громкой связи", "громкую связь")
  ) {
    clusters.add("speaker-toggle");
  }

  if (has("гудок", "гудк", "ringtone", "notification sound", "нет звонка", "не звонит")) {
    clusters.add("no-ringtone");
  }

  // Incoming call only works while the app is on screen — the FCM path.
  if (
    has(
      "в фоне",
      "фоновом режиме",
      "приложение закрыто",
      "не открыто",
      "только при запущенном",
      "background",
      "закрытом приложении",
      "свернут",
    )
  ) {
    clusters.add("background-incoming");
  }

  // Duplicate rings: two apps on one account, or a phantom re-ring.
  if (
    has(
      "дважды",
      "два раза",
      "дубл",
      "повторн",
      "4шт",
      "хотя на самом деле звонка не было",
      // Several ringers stacked on screen at once — the same phantom, seen
      // as overlapping UI rather than as a repeat (#1088).
      "звонков параллельно",
      "параллельно от одного",
    ) ||
    // "наслоение" on its own is ordinary Russian for problems piling up, so
    // it only counts when the sentence is about calls.
    (has("наслоение") && has("звонк", "вызов", "звонок")) ||
    (has("bastyon", "бастион", "бастиона") && has("звон", "вызов"))
  ) {
    clusters.add("duplicate-ring");
  }

  if (has("видео", "video", "камер", "camera")) {
    clusters.add("video");
  }

  if (has("прерыв", "качество", "quality", "заикается", "робот")) {
    clusters.add("quality");
  }

  return clusters.size > 0 ? Array.from(clusters) : ["other"];
}

/**
 * Build a markdown digest from a list of classified reports.
 * Includes:
 * - Total counts
 * - Counts per cluster and vendor
 * - Count of reports from devices outside the broken-AEC vendor gate but reporting audio problems
 * - List of repeat reporters (same reporter hash more than once)
 */
export function buildDigest(reports) {
  const lines = [];

  lines.push("# Call Report Triage Digest");
  lines.push("");

  // Total counts
  lines.push(`## Summary`);
  lines.push(`- Total reports: ${reports.length}`);
  const callRelated = reports.filter((r) => r.isCallRelated).length;
  lines.push(`- Call-related: ${callRelated}`);
  lines.push("");

  // Cluster counts
  const clusterCounts = {};
  for (const report of reports) {
    for (const cluster of report.clusters) {
      clusterCounts[cluster] = (clusterCounts[cluster] || 0) + 1;
    }
  }

  if (Object.keys(clusterCounts).length > 0) {
    lines.push("## Clusters");
    for (const [cluster, count] of Object.entries(clusterCounts).sort()) {
      lines.push(`- ${cluster}: ${count}`);
    }
    lines.push("");
  }

  // Vendor counts
  const vendorCounts = {};
  for (const report of reports) {
    const vendor = report.vendor || "unknown";
    vendorCounts[vendor] = (vendorCounts[vendor] || 0) + 1;
  }

  if (Object.keys(vendorCounts).length > 0) {
    lines.push("## Vendors");
    for (const [vendor, count] of Object.entries(vendorCounts).sort()) {
      lines.push(`- ${vendor}: ${count}`);
    }
    lines.push("");
  }

  // Version counts
  const versionCounts = {};
  for (const report of reports) {
    const version = report.appVersion || "unknown";
    versionCounts[version] = (versionCounts[version] || 0) + 1;
  }

  if (Object.keys(versionCounts).length > 0) {
    lines.push("## App Versions");
    for (const [version, count] of Object.entries(versionCounts).sort()) {
      lines.push(`- ${version}: ${count}`);
    }
    lines.push("");
  }

  // Outside-gate audio problems count
  const outsideGateAudio = reports.filter((r) => {
    const hasAudioCluster = r.clusters.some((c) =>
      ["no-audio", "stuck-after-call", "no-ringtone"].includes(c)
    );
    const device = r.device || "";
    const isOutsideGate = !isBrokenAecVendor(device);
    return hasAudioCluster && isOutsideGate;
  }).length;

  if (outsideGateAudio > 0) {
  // Objective signals beat prose: the audio mode captured at report time says
  // whether the device was still stuck when the user wrote in, and the invite
  // count says whether push ever reached it. Both are independent of wording.
  const modes = {};
  let withInvites = 0;
  let withDiagnostics = 0;
  for (const r of reports) {
    if (!r.diagnostics) continue;
    withDiagnostics += 1;
    const mode = r.diagnostics.audioMode || "unknown";
    modes[mode] = (modes[mode] || 0) + 1;
    if ((r.diagnostics.recentInvites || 0) > 0) withInvites += 1;
  }
  const abnormal = Object.entries(modes)
    .filter(([m]) => m !== "MODE_NORMAL" && m !== "unknown")
    .reduce((sum, [, n]) => sum + n, 0);

  lines.push("");
  lines.push("## Audio mode at report time");
  lines.push(`Reports carrying diagnostics: ${withDiagnostics}`);
  for (const [mode, count] of Object.entries(modes).sort((a, b) => b[1] - a[1])) {
    lines.push(`- ${mode}: ${count}`);
  }
  lines.push(
    `_${abnormal} device(s) were still in a call audio mode when the report was filed — the phone's media volume stays broken until that clears._`,
  );

  lines.push("");
  lines.push("## Push delivery");
  lines.push(`- Reports where FCM invites had been received: ${withInvites}`);
  lines.push(
    "_A report about missed incoming calls with zero invites points at push delivery; with invites, at how the invite was handled._",
  );

    lines.push("## Outside Vendor Gate");
    lines.push(
      `Devices reporting audio problems but NOT in broken-AEC vendor gate: ${outsideGateAudio}`
    );
    lines.push(
      "_This indicates the vendor gate may need to be widened._"
    );
    lines.push("");
  }

  // Repeat reporters
  const reporterCounts = {};
  for (const report of reports) {
    if (report.reporterHash) {
      reporterCounts[report.reporterHash] =
        (reporterCounts[report.reporterHash] || 0) + 1;
    }
  }

  const repeaters = Object.entries(reporterCounts).filter(
    ([_, count]) => count > 1
  );

  if (repeaters.length > 0) {
    lines.push("## Repeat Reporters");
    lines.push(`Found ${repeaters.length} reporter(s) with multiple reports:`);
    for (const [hash, count] of repeaters.sort((a, b) => b[1] - a[1])) {
      lines.push(`- ${hash}: ${count} reports`);
    }
    lines.push("");
  }

  return lines.join("\n");
}

/**
 * CLI: Fetch and classify issues from GitHub.
 * Supports flags:
 * - --json: machine-readable JSON output
 * - --since=YYYY-MM-DD: filter by created date
 * - --limit=N: max issues to fetch (default 500)
 */
function cli() {
  const args = process.argv.slice(2);
  let jsonOutput = false;
  let since = null;
  let limit = 500;

  for (const arg of args) {
    if (arg === "--json") {
      jsonOutput = true;
    } else if (arg.startsWith("--since=")) {
      since = arg.slice("--since=".length);
    } else if (arg.startsWith("--limit=")) {
      limit = parseInt(arg.slice("--limit=".length), 10) || 500;
    }
  }

  try {
    // Fetch issues via gh CLI
    const cmd = `gh issue list -R greenShirtMystery/forta-bugs --state open --limit ${limit} --json number,title,body,createdAt,labels`;
    const output = execSync(cmd, { encoding: "utf-8" });
    const issues = JSON.parse(output);

    const reports = [];
    for (const issue of issues) {
      const env = parseEnvironment(issue.body);
      const desc = parseDescription(issue.body);
      const diag = parseCallDiagnostics(issue.body);

      // Extract reporter hash from HTML comment if present
      const reporterHashMatch = issue.body.match(
        /<!--\s*reporter:([0-9a-f]{16})\s*-->/
      );
      const reporterHash = reporterHashMatch ? reporterHashMatch[1] : null;

      // Filter by date if --since provided
      if (since && new Date(issue.createdAt) < new Date(since)) {
        continue;
      }

      const callRelated = isCallRelated(issue.title, desc);
      const vendor = classifyVendor(env.device || "");
      const device = env.device || "";
      const clusters = callRelated
        ? classifyClusters(issue.title, desc, diag)
        : [];

      reports.push({
        number: issue.number,
        title: issue.title,
        description: desc,
        environment: env,
        diagnostics: diag,
        reporterHash,
        isCallRelated: callRelated,
        vendor,
        device,
        appVersion: env.appVersion,
        clusters,
      });
    }

    if (jsonOutput) {
      console.log(JSON.stringify(reports, null, 2));
    } else {
      const digest = buildDigest(reports);
      console.log(digest);
    }
  } catch (error) {
    if (error instanceof Error) {
      console.error(
        "Error: Failed to fetch issues. Is `gh` CLI installed and authenticated?"
      );
      console.error(error.message);
    }
    process.exit(1);
  }
}

// CLI entry point
const isMain =
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);

if (isMain) {
  cli();
}
