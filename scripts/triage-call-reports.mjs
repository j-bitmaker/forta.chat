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
    else if (key === "speaker on") result.speakerOn = value.toLowerCase().includes("yes");
    else if (key === "bt sco on") result.btScoOn = value.toLowerCase().includes("yes");
    else if (key === "recent invites") result.recentInvites = parseInt(value, 10) || 0;
    else if (key === "expired invites") result.expiredInvites = parseInt(value, 10) || 0;
  }

  return Object.keys(result).length > 0 ? result : null;
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

  // Stuck-after-call: audioMode is MODE_RINGTONE or MODE_IN_COMMUNICATION
  // OR text mentions "после звонка/вызова/кнопки громкости/не даёт звонить/продолжается"
  if (
    diagnostics &&
    (diagnostics.audioMode === "MODE_RINGTONE" ||
      diagnostics.audioMode === "MODE_IN_COMMUNICATION")
  ) {
    clusters.add("stuck-after-call");
  }
  if (
    text.includes("после звонка") ||
    text.includes("после вызова") ||
    text.includes("кнопки громкости") ||
    text.includes("не даёт звонить") ||
    text.includes("продолжается")
  ) {
    clusters.add("stuck-after-call");
  }

  // No audio
  if (
    text.includes("не слышу") ||
    text.includes("нет звука") ||
    text.includes("no audio") ||
    text.includes("silent") ||
    text.includes("no sound")
  ) {
    clusters.add("no-audio");
  }

  // Accept button issues
  if (
    text.includes("кнопка") ||
    text.includes("accept") ||
    text.includes("button") ||
    text.includes("не нажимается") ||
    text.includes("не работает кнопка")
  ) {
    clusters.add("accept-button");
  }

  // Connect fail
  if (
    text.includes("не подключается") ||
    text.includes("connect") ||
    text.includes("failed") ||
    text.includes("timeout")
  ) {
    clusters.add("connect-fail");
  }

  // Speaker toggle
  if (
    text.includes("динамик") ||
    text.includes("speaker") ||
    text.includes("toggle")
  ) {
    clusters.add("speaker-toggle");
  }

  // No ringtone
  if (
    text.includes("гудок") ||
    text.includes("ringtone") ||
    text.includes("notification sound")
  ) {
    clusters.add("no-ringtone");
  }

  // Background incoming
  if (
    text.includes("фон") ||
    text.includes("background") ||
    text.includes("screen off")
  ) {
    clusters.add("background-incoming");
  }

  // Video issues
  if (text.includes("видео") || text.includes("video")) {
    clusters.add("video");
  }

  // Quality issues
  if (
    text.includes("качество") ||
    text.includes("quality") ||
    text.includes("lag") ||
    text.includes("latency")
  ) {
    clusters.add("quality");
  }

  // Default to "other" if no clusters matched
  if (clusters.size === 0) {
    clusters.add("other");
  }

  return Array.from(clusters).sort();
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
