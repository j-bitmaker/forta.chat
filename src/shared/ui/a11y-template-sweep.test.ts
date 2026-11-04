import { describe, it, expect } from "vitest";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, resolve } from "node:path";

/**
 * Static accessibility sweep over every `.vue` template in `src/`.
 *
 * The manual web pass of 2026-11-03 found icon-only buttons without a name,
 * a dialog without a role and a button nested in a button; a follow-up sweep
 * found the same three classes across the whole app. This test keeps them
 * from coming back. It is a template parser, not a browser: it only looks at
 * markup, so a finding here is cheap to fix and never a false "pass".
 *
 * Rules:
 *  1. No interactive element (button, a, input, select, textarea, summary,
 *     RouterLink) inside a `<button>` or `<a>`.
 *  2. Every `<button>` has an accessible name: an `aria-label` /
 *     `aria-labelledby` / `title` attribute (static or bound), text content,
 *     a `.sr-only` child, an `<img>` with `alt`, a `<slot>`, a component
 *     child, or `v-text`. The shared `Toggle` (`role="switch"`) is named by
 *     its consumer and is allowed here.
 *  3. A component that renders a modal overlay (`fixed inset-0` with a
 *     `bg-black/…` or `bg-background-overlay` backdrop) declares
 *     `role="dialog"` or `role="alertdialog"` somewhere in its template.
 */
const SRC = resolve(__dirname, "../..");
const INTERACTIVE = /^(button|a|input|select|textarea|summary|RouterLink|router-link)$/;
const VOID = /^(img|input|br|hr|svg|path|line|circle|rect|polyline|polygon|use|source|track|ellipse|g)$/;
const NAMED_ATTR = /(^|\s):?(aria-label|aria-labelledby|title)=/;

function listVueFiles(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) {
      if (name !== "node_modules" && name !== "__tests__") listVueFiles(p, out);
    } else if (p.endsWith(".vue")) {
      out.push(p);
    }
  }
  return out;
}

interface Open { name: string; attrs: string; line: number; named: boolean; text: string; namedByChild: boolean }

interface Findings { unnamed: string[]; nested: string[]; overlays: string[] }

function sweep(file: string, out: Findings): void {
  const src = readFileSync(file, "utf-8");
  const templateStart = src.indexOf("<template>");
  if (templateStart < 0) return;
  const tpl = src.slice(templateStart);
  const rel = relative(SRC, file).replace(/\\/g, "/");
  const lineOf = (i: number) => src.slice(0, templateStart + i).split("\n").length;

  const tokens = /<!--[\s\S]*?-->|<\/([\w-]+)\s*>|<([\w-]+)((?:"[^"]*"|'[^']*'|[^'">])*)\/?>|[^<]+/g;
  const stack: Open[] = [];
  let m: RegExpExecArray | null;
  while ((m = tokens.exec(tpl))) {
    const tok = m[0];
    if (tok.startsWith("<!--")) continue;
    if (m[1]) {
      for (let i = stack.length - 1; i >= 0; i--) {
        if (stack[i].name !== m[1]) continue;
        const el = stack[i];
        const isSwitch = /role="switch"/.test(el.attrs) && rel === "shared/ui/toggle/Toggle.vue";
        if (el.name === "button" && !el.named && !el.text.trim() && !el.namedByChild && !isSwitch) {
          out.unnamed.push(`${rel}:${el.line}`);
        }
        stack.splice(i);
        break;
      }
      continue;
    }
    if (m[2]) {
      const name = m[2];
      const attrs = m[3] ?? "";
      const selfClosing = tok.endsWith("/>") || VOID.test(name);
      const named = NAMED_ATTR.test(attrs);
      const parent = stack.find((e) => INTERACTIVE.test(e.name));
      if (INTERACTIVE.test(name) && parent) {
        out.nested.push(`${rel}:${lineOf(m.index)} <${name}> inside <${parent.name}> opened at line ${parent.line}`);
      }
      const namesParent =
        /sr-only/.test(attrs) ||
        name === "slot" ||
        /^[A-Z]/.test(name) ||
        /v-text|v-html/.test(attrs) ||
        (name === "img" && /(^|\s):?alt=/.test(attrs));
      if (namesParent) for (const e of stack) e.namedByChild = true;
      if (name === "button" && named && selfClosing) {
        // `<button … :title="…" />` — named, nothing to check.
      } else if (name === "button" && selfClosing) {
        out.unnamed.push(`${rel}:${lineOf(m.index)}`);
      }
      if (!selfClosing) stack.push({ name, attrs, line: lineOf(m.index), named, text: "", namedByChild: false });
      continue;
    }
    if (tok.trim()) for (const e of stack) e.text += tok;
  }

  const hasModalBackdrop = /fixed inset-0[^"]*(bg-black\/|bg-background-overlay)/.test(tpl)
    || /class="[^"]*bg-black\/[^"]*fixed inset-0/.test(tpl);
  if (hasModalBackdrop && !/role="(alert)?dialog"/.test(tpl)) out.overlays.push(rel);
}

describe("a11y template sweep (src/**/*.vue)", () => {
  const findings: Findings = { unnamed: [], nested: [], overlays: [] };
  for (const file of listVueFiles(SRC)) sweep(file, findings);

  it("scans the whole app", () => {
    expect(listVueFiles(SRC).length).toBeGreaterThan(100);
  });

  it("no interactive element is nested inside a button or link", () => {
    expect(findings.nested).toEqual([]);
  });

  it("every <button> has an accessible name", () => {
    expect(findings.unnamed).toEqual([]);
  });

  it("every component with a modal backdrop declares a dialog role", () => {
    expect(findings.overlays).toEqual([]);
  });
});
