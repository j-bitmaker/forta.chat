/**
 * Adds gathered local ICE candidates to a session description.
 *
 * A browser's `localDescription` gains each candidate as it is gathered, and
 * matrix-js-sdk relies on that: when it sends an offer or answer it drops the
 * candidates it had queued, because they "will be sent in the offer/answer".
 * The native engine's SDP from createOffer/createAnswer carries no candidates,
 * so the native proxy rebuilds that view with this helper.
 *
 * A candidate goes at the end of its media section (by `sdpMid`, else by
 * `sdpMLineIndex`). Candidates of another ICE generation (their `ufrag`
 * differs from the section's `a=ice-ufrag`) and lines the section already has
 * are left out.
 */

const CANDIDATE_UFRAG = /\sufrag\s+(\S+)/;

interface MediaSection {
  start: number;
  end: number;
  mid: string | null;
  ufrag: string | null;
}

function attribute(lines: readonly string[], from: number, to: number, name: string): string | null {
  const prefix = `a=${name}:`;
  for (let i = from; i < to; i++) {
    if (lines[i].startsWith(prefix)) return lines[i].slice(prefix.length).trim();
  }
  return null;
}

export function addLocalCandidatesToSdp(sdp: string, candidates: readonly RTCIceCandidateInit[]): string {
  if (!sdp || candidates.length === 0) return sdp;

  const eol = sdp.includes("\r\n") ? "\r\n" : "\n";
  const lines = sdp.split(eol);
  const endsWithEol = lines.at(-1) === "";
  if (endsWithEol) lines.pop();

  const starts = lines.flatMap((line, i) => (line.startsWith("m=") ? [i] : []));
  if (starts.length === 0) return sdp;
  const sessionUfrag = attribute(lines, 0, starts[0], "ice-ufrag");
  const sections: MediaSection[] = starts.map((start, index) => {
    const end = starts[index + 1] ?? lines.length;
    return {
      start,
      end,
      mid: attribute(lines, start, end, "mid"),
      ufrag: attribute(lines, start, end, "ice-ufrag") ?? sessionUfrag,
    };
  });

  const additions = new Map<number, string[]>();
  for (const c of candidates) {
    const body = c.candidate?.replace(/^a=/, "").trim();
    if (!body) continue;
    const index = c.sdpMid != null && sections.some((s) => s.mid !== null)
      ? sections.findIndex((s) => s.mid === c.sdpMid)
      : c.sdpMLineIndex ?? -1;
    const section = sections[index];
    if (!section) continue;
    const ufrag = CANDIDATE_UFRAG.exec(body)?.[1];
    if (ufrag && section.ufrag && ufrag !== section.ufrag) continue;
    const line = `a=${body}`;
    const pending = additions.get(index) ?? [];
    if (pending.includes(line) || lines.slice(section.start, section.end).includes(line)) continue;
    additions.set(index, [...pending, line]);
  }
  if (additions.size === 0) return sdp;

  const out: string[] = lines.slice(0, sections[0].start);
  sections.forEach((section, index) => {
    out.push(...lines.slice(section.start, section.end), ...(additions.get(index) ?? []));
  });
  return out.join(eol) + (endsWithEol ? eol : "");
}
