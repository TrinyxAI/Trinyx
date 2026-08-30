import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync } from 'fs';
import { join } from 'path';

/**
 * "Is the side panel SHOWING something" must be asked with `isForward`, never `isOpen`.
 *
 * <p><strong>Why this needs a test.</strong> The two used to be the same thing, so
 * every surface that wanted "is it showing" reached for `isOpen` and was right. The
 * detached window's collapse mode broke that: a panel shaded to a 36px strip is open
 * and shows nothing. Each stale call site then answers backwards, and the failure is
 * silent and specific to the collapsed state - a preview card paints its "click to
 * close" overlay over a panel nobody can see, and the click destroys the tab instead
 * of revealing it; the empty-canvas composer stays hidden with nothing explaining why.
 *
 * <p>A per-file test cannot hold this: the invariant spans eleven files today and
 * applies to every surface anyone adds next, and the only reason nine of them were
 * caught was a review that read all of them. This scans instead, so a new consumer
 * that asks the old question fails here rather than in the one UI state nobody
 * checks. It is the same shape as `auth-token-source-guard.test.ts`.
 *
 * <p><strong>Allow-list, not deny-list.</strong> Two earlier versions enumerated the
 * shapes they had SEEN, and both were defeated by ordinary spellings nobody had
 * written yet: `Boolean(panel?.isOpen)`, `panel?.isOpen === true`, a ternary, an
 * unparenthesised JSX prop, a plain call argument. A read of `isOpen` is now an
 * offender unless the shape ITSELF narrows it, so a spelling this file has never met
 * fails closed.
 *
 * <p><strong>What it still cannot see</strong>, stated so nobody reads a green run as
 * proof: the receiver has to be bound from `useSidePanel*()` with `const`/`let` in
 * the same file, so a `var`, an alias (`const p = sidePanel`), or a function that
 * takes the context as a PARAMETER is invisible to it. A guard whose dismissal is
 * indirect (`if (isOpen) dismiss()`) is invisible too, since only a `close(` written
 * on the panel is read, and a dismissal further than one `else` from the guard is
 * out of reach. This is a regex over source, not a type-aware pass; it is a net for
 * the common shapes, not a proof - so the list above is the honest boundary, and
 * every hole found since has been added to the corpus below rather than to it.
 */

const FRONTEND_ROOT = join(__dirname, '..');

/**
 * Blank out comments and string literals, keeping every byte position.
 *
 * Without this, a consumer file that EXPLAINS the rule ("`isOpen` stays true while
 * shaded, which is why we ask `isForward` here") is reported as breaking it, and the
 * only way out is an exemption. A guard that punishes the comment describing it
 * teaches people to delete the comment.
 */
function code(src: string): string {
  // Strings FIRST: a `//` inside one (`'https://example'`) is not a comment, and
  // blanking from it would swallow the rest of that line, closing quote included.
  return src
    .replace(/(['"`])(?:\\.|(?!\1)[^\\\n])*\1/g, (m) => m[0] + ' '.repeat(m.length - 2) + m[0])
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/\/\/[^\n]*/g, (m) => ' '.repeat(m.length));
}

/**
 * What `isOpen` is read THROUGH in this file, discovered rather than assumed.
 *
 * The first version hard-coded the name `sidePanel`, so the two most natural ways to
 * write a new consumer both walked straight past it: binding the context to any other
 * identifier, and destructuring.
 */
function panelBindings(src: string): { receivers: string[]; destructured: boolean } {
  const receivers: string[] = [];
  let destructured = false;
  const bind = /(?:const|let)\s+(\{[^}]*\}|[A-Za-z_$][\w$]*)\s*=\s*(?:await\s+)?useSidePanel(?:Safe)?\s*\(/g;
  for (let hit = bind.exec(src); hit; hit = bind.exec(src)) {
    const bound = hit[1];
    if (bound.startsWith('{')) {
      if (/\bisOpen\b/.test(bound)) destructured = true;
    } else {
      receivers.push(bound);
    }
  }
  // The inline form has no name to bind: `useSidePanelSafe()?.isOpen`.
  if (/useSidePanel(?:Safe)?\s*\(\s*\)\s*\??\.\s*isOpen/.test(src)) receivers.push('');
  return { receivers, destructured };
}

/** A receiver name reaches the pattern builder as text, so it has to be escaped. */
function esc(name: string): string {
  return name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** Every `isOpen` read in this file, wherever it is and however it is spelled. */
function readsOfIsOpen(src: string, receiver: string): RegExp {
  if (receiver === '__destructured__') return /\bisOpen\b/g;
  const r = receiver === '' ? 'useSidePanel(?:Safe)?\\s*\\(\\s*\\)' : esc(receiver);
  return new RegExp(`${r}\\s*\\??\\.\\s*isOpen\\b`, 'g');
}

/**
 * Is this bare `isOpen` a READ, rather than a binding site or somebody else's key?
 *
 * Destructured mode has no receiver to anchor on, so it scans the bare identifier and
 * has to rule out `const { isOpen } = useSidePanel()` itself, `{ isOpen: x }`, and a
 * prop NAME on an unrelated component.
 */
function isBareRead(src: string, start: number, end: number): boolean {
  const before = src.slice(Math.max(0, start - 200), start);
  const after = src.slice(end, end + 8);
  if (/[{,]\s*$/.test(before) && /^\s*[},:=]/.test(after)) return false;  // binding or key
  if (/^\s*=[^=]/.test(after)) return false;                              // a JSX prop NAME
  return true;
}

/**
 * The remainder of one boolean expression, ending where the expression does.
 *
 * Depth-aware, so a helper call or an index inside it does not truncate the scan,
 * and it stops at the separator that really ends the expression - a `;`, a `,` or a
 * closing bracket at depth zero, or a `?` that begins a ternary.
 */
function restOfExpression(text: string): string {
  let depth = 0;
  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i];
    if (ch === '(' || ch === '[' || ch === '{') depth += 1;
    else if (ch === ')' || ch === ']' || ch === '}') {
      if (depth === 0) return text.slice(0, i);
      depth -= 1;
    } else if (depth === 0) {
      if (ch === ';' || ch === ',') return text.slice(0, i);
      // `?.` is optional chaining, not a ternary.
      if (ch === '?' && text[i + 1] !== '.' && text[i + 1] !== '?') return text.slice(0, i);
    }
  }
  return text;
}

/**
 * The block that starts at the first `{` after `from`, brace-matched, with the index
 * just past it.
 *
 * The end index is returned rather than derived from the length: `)` and `{` are not
 * adjacent (`if (x) { ... }`), so adding the body length to the paren position lands
 * inside the block, and the `else` that follows is then never found.
 */
function blockRange(src: string, from: number): { body: string; end: number } {
  const open = src.indexOf('{', from);
  if (open < 0) return { body: '', end: from };
  let depth = 0;
  for (let i = open; i < src.length; i += 1) {
    if (src[i] === '{') depth += 1;
    else if (src[i] === '}') {
      depth -= 1;
      if (depth === 0) return { body: src.slice(open, i + 1), end: i + 1 };
    }
  }
  return { body: src.slice(open), end: src.length };
}

/**
 * Is this particular read legitimate?
 *
 * Only two shapes are, and both narrow the question the read is asking:
 *
 *  1. Combined with another condition that is NOT the active tab. "Open AND the dock
 *     takes layout space" is a different, correct question; "open AND showing this
 *     tab" is the exact shape the nine preview cards had.
 *  2. Guarding a branch that does not DISMISS the panel. `if (isOpen) setActiveTab()`
 *     is fine - a shaded panel takes that branch and `setActiveTab` un-shades at the
 *     source. `if (isOpen) close()` dismisses the panel the user pressed the button
 *     to bring up.
 *
 * Everything else - stored in a variable, passed as a prop or an argument, wrapped in
 * `Boolean()`, compared to `true`, used as a ternary test - keeps the raw value and
 * therefore answers backwards while the window is shaded.
 */
function readIsNarrowed(src: string, start: number, end: number, receiver: string): boolean {
  const before = src.slice(Math.max(0, start - 400), start);
  // Continuation: skip `?? false`, any closing parens, and whitespace or a newline.
  const after = src.slice(end).replace(/^\s*(?:\?\?\s*false\s*)?\)*\s*/, '');

  // A hook dependency array is not a question at all - it says "re-run when this
  // changes", which is true of `isOpen` on its own terms. The callback before it may
  // be an inline arrow (`}`) or a named reference, so do not require a brace.
  if (/,\s*\[[^\]]*$/.test(before)) return true;

  if (after.startsWith('&&')) {
    // The REST of the boolean expression, read to its end rather than to the first
    // bracket: `isOpen && Boolean(x) && panel?.activeTabId === tabId` is the
    // preview-card bug with one helper call in the middle, and truncating at that
    // call's `)` read it clean. A fixed character window had the same hole.
    if (/\bactiveTabId\b/.test(restOfExpression(after.slice(2)))) return false;
    // A second conjunct narrows the QUESTION, it does not make the ANSWER safe:
    // `if (isOpen && ready) close()` still dismisses a window the user only shaded.
    // Falling through here is what stops the extra condition acting as a bypass.
    return guardIsHarmless(src, end, receiver);
  }
  // One disjunct of a compound condition, on either side.
  if (after.startsWith('||') || /\|\|\s*!*\(?\s*$/.test(before)) return guardIsHarmless(src, end, receiver);

  // `if (` (possibly through `!`, `!!` or an opening paren) immediately before it.
  if (/\bif\s*\(\s*!*\(?\s*$/.test(before)) return guardIsHarmless(src, end, receiver);

  return false;
}

/**
 * A condition that merely GUARDS is fine; one that dismisses the panel is not.
 *
 * `if (isOpen) setActiveTab()` and `if (open) return` both behave correctly while
 * shaded - the first un-shades at the source, the second declines to pop a window
 * the user put away. `if (isOpen) close()` dismisses the panel the user pressed the
 * button to bring up, which is the whole bug.
 */
function guardIsHarmless(src: string, from: number, receiver: string): boolean {
  const close = src.indexOf(')', from);
  if (close < 0) return true;
  const tail = src.slice(close + 1, close + 60).trimStart();
  // An early return has no block to inspect.
  if (/^return\b/.test(tail)) return true;
  // Only THIS panel's dismissal counts. Any `.close(` at all reported
  // `if (sidePanel?.isOpen) { modal.close(); }`, which shuts something else entirely.
  // `close?.()` is the same dismissal with an optional call, so the `.` and the `?.`
  // between the receiver and the parenthesis are both optional here.
  const dismiss = receiver === '__destructured__'
    ? /(?:^|[^.\w])close\s*\??\.?\s*\(/
    : new RegExp(`${receiver === '' ? '[A-Za-z_$][\\w$]*' : esc(receiver)}\\s*\\??\\.\\s*close\\s*\\??\\.?\\s*\\(`);
  // A braceless branch is a statement, not a block. Reading it as "no block, so no
  // `.close()`, so harmless" declared `if (panel?.isOpen) panel.close();` - the
  // header-toggle bug verbatim - perfectly fine.
  if (!tail.startsWith('{')) return !dismiss.test(tail.split(';')[0]);
  const block = blockRange(src, close);
  if (dismiss.test(block.body)) return false;
  // ...and the ELSE, which is where the toggle usually puts the dismissal:
  // `if (!isOpen) { open(); } else { close(); }` reads clean if only the `if` body
  // is inspected, and that is the header bug written the other way round.
  const rest = src.slice(block.end).trimStart();
  if (!rest.startsWith('else')) return true;
  const tail2 = rest.slice(4).trimStart();
  return !dismiss.test(tail2.startsWith('{') ? blockRange(rest, 4).body : tail2.split(';')[0]);
}

/**
 * The per-LINE opt-out, and the pinned list of who may use it.
 *
 * A handful of call sites really do mean `isOpen` in a shape that reads like
 * "showing": asking whether a tab's BODY is mounted and listening is one, because a
 * shaded panel still renders its active tab's content (hidden, not unmounted).
 * Exempting the whole FILE would take its other call sites out of the guard with it,
 * so the marker is written on the line and has to carry a reason.
 *
 * The marker alone would be a self-service off switch, so the files that may use it
 * are pinned here too: adding one means editing this test, which is the point.
 */
const OPT_OUT = /isOpen-is-deliberate:/;
const MAY_OPT_OUT = new Set<string>([
  // The workflow panel's event handler asks whether the in-panel listener exists.
  'components/views/workflow/WorkflowDetailView.tsx',
]);

/**
 * Files where `isOpen` genuinely means open, shaded or not, throughout.
 *
 * Keep this list SHORT and justified. Prefer the per-line marker above.
 */
const ALLOWED = new Set<string>([
  // The panel decides its own open/closed box model; shading is a mode INSIDE it.
  'components/app/SidePanel.tsx',
  // Owns both values and derives one from the other.
  'contexts/SidePanelContext.tsx',
  // A developer demo page whose whole job is to print "Open" or "Closed".
  'app/[locale]/app/test/layout-demo/page.tsx',
]);

/**
 * Walked with `fs` rather than a glob library: `tinyglobby` is only present as a
 * transitive dependency of the vite tree, so importing it here would make this CI
 * job break on a lockfile refresh that hoists differently.
 */
function sourceFiles(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(join(FRONTEND_ROOT, dir), { withFileTypes: true })) {
    const rel = `${dir}/${entry.name}`;
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules' || entry.name === '__tests__' || entry.name.startsWith('.')) continue;
      sourceFiles(rel, out);
    } else if (/\.tsx?$/.test(entry.name) && !/\.(test|spec)\.tsx?$/.test(entry.name)) {
      out.push(rel);
    }
  }
  return out;
}

/**
 * Every tree that can hold a consumer. `contexts` and `hooks` are in the list because
 * leaving them out is invisible: a consumer written as a hook would have escaped the
 * guard silently, and the `contexts/SidePanelContext.tsx` exemption would have been
 * dead code that read like coverage.
 */
const SCANNED_ROOTS = ['components', 'app', 'lib', 'hooks', 'contexts'];

function scannedFiles(): string[] {
  return SCANNED_ROOTS.flatMap((root) => sourceFiles(root));
}

/** Every offending read in one file, so a file with three violations names three. */
function offendersIn(rel: string, rawSrc: string): string[] {
  // Prose is not code: a comment or a string that mentions `isOpen` is not a read.
  const src = code(rawSrc);
  const { receivers, destructured } = panelBindings(src);
  const sources = [...receivers, ...(destructured ? ['__destructured__'] : [])];
  // The opt-out marker is a COMMENT, so it has to be read in the raw source. Line
  // numbers still line up: `code` blanks characters, it does not remove them.
  const lines = rawSrc.split('\n');
  const mayOptOut = MAY_OPT_OUT.has(rel);
  const found = new Set<string>();

  for (const receiver of sources) {
    const re = readsOfIsOpen(src, receiver);
    for (let hit = re.exec(src); hit; hit = re.exec(src)) {
      // The destructuring site is not a read, and neither is `isOpen` used as a key
      // or a prop NAME on something else (`<Modal isOpen={dialogOpen} />`). Flagging
      // either sends the author to fix a line that is already correct, and the only
      // way out would be an exemption that is worse than the warning.
      if (receiver === '__destructured__' && !isBareRead(src, hit.index, hit.index + hit[0].length)) continue;
      if (readIsNarrowed(src, hit.index, hit.index + hit[0].length, receiver)) continue;
      const lineNo = src.slice(0, hit.index).split('\n').length;
      // The marker may sit on the offending line or in the comment block immediately
      // above it, because a reason worth writing rarely fits on one line. Bounded by
      // the contiguous comment block, so a marker written for one site cannot drift
      // down and silently cover an unrelated violation below it.
      let exempt = mayOptOut && OPT_OUT.test(lines[lineNo - 1] ?? '');
      for (let c = lineNo - 1; !exempt && mayOptOut && c > 0; c -= 1) {
        if (!/^\s*(\/\/|\*|\/\*)/.test(lines[c - 1] ?? '')) break;
        exempt = OPT_OUT.test(lines[c - 1]);
      }
      if (!exempt) found.add(`${rel}:${lineNo}`);
    }
  }
  return [...found];
}

describe('side panel: "is it showing" is asked with isForward', () => {
  it('has no consumer left asking isOpen when it means isForward', () => {
    const offenders = scannedFiles()
      .filter((rel) => !ALLOWED.has(rel))
      .flatMap((rel) => offendersIn(rel, readFileSync(join(FRONTEND_ROOT, rel), 'utf8')));

    expect(
      offenders,
      'These read `isOpen` where they mean "the panel is showing this". A detached '
        + 'window collapsed to a strip is open and shows nothing, so each of these '
        + 'answers backwards in that state - silently, and only there. Use '
        + '`isForward`. If the call site really does mean open-even-when-shaded, say '
        + 'why on the line with an `isOpen-is-deliberate:` comment AND add the file '
        + 'to MAY_OPT_OUT here, so the exemption is a decision and not a habit:\n  '
        + offenders.join('\n  '),
    ).toEqual([]);
  });

  it('flags every way of keeping the raw value, including ones never written yet', () => {
    // A scanning guard whose regexes have rotted reports zero offenders and reads
    // exactly like success. The first five here are shapes an earlier version of
    // this test MISSED - each was reproduced as a real file at the time, all green.
    const bind = 'const sidePanel = useSidePanelSafe();\n';
    const positives: [string, string][] = [
      ['unparenthesised JSX prop', `${bind}<X isSidePanelOpen={sidePanel?.isOpen ?? false} />`],
      ['Boolean()', `${bind}const showing = Boolean(sidePanel?.isOpen);`],
      ['compared to true', `${bind}const showing = sidePanel?.isOpen === true;`],
      ['ternary test', `${bind}const cls = sidePanel?.isOpen ? 'a' : 'b';`],
      ['plain call argument', `${bind}render(sidePanel?.isOpen);`],
      ['the nine preview cards', `${bind}const active = sidePanel?.isOpen && sidePanel?.activeTabId === tabId;`],
      // The same bug with the operands swapped, which is the likeliest way somebody
      // re-writes it. A head-anchored conjunct test read this clean.
      ['the same, operands swapped', `${bind}const active = sidePanel?.isOpen && tabId === sidePanel?.activeTabId;`],
      // A braceless branch is a statement, not a block. Looking for the next `{`
      // found none, concluded "no `.close()` here", and blessed the header bug.
      ['the toggle, braceless', `${bind}if (sidePanel?.isOpen) sidePanel.close();`],
      // A helper call in the middle used to truncate the conjunct scan at its `)`,
      // and a long one used to push `activeTabId` past a fixed character window.
      ['a helper call between the conjuncts',
        `${bind}const a = sidePanel?.isOpen && Boolean(x) && sidePanel?.activeTabId === tabId;`],
      ['the toggle written as if/else', `${bind}if (!sidePanel?.isOpen) { sidePanel.open(); } else { sidePanel.close(); }`],
      ['a dismissal behind a second conjunct', `${bind}if (sidePanel?.isOpen && ready) sidePanel.close();`],
      ['a dismissal through an optional call', `${bind}if (sidePanel?.isOpen) { sidePanel?.close?.(); }`],
      ['a long middle conjunct',
        `${bind}const a = sidePanel?.isOpen && label.padEnd(200, 'x').trim().toLowerCase().includes('zz')`
        + `\n  && sidePanel?.activeTabId === tabId;`],
      ['split across two lines', `${bind}const showing = !!sidePanel?.isOpen\n  && sidePanel.activeTabId === TAB;`],
      ['kept as a flag', 'const isSidePanelOpen = useSidePanelSafe()?.isOpen ?? false;'],
      ['the toggle that dismisses what the user asked for',
        `${bind}if (sidePanel.isOpen) {\n  sidePanel.close();\n}`],
      ['the toggle, with a nested block before the close',
        `${bind}if (sidePanel.isOpen) {\n  items.forEach((i) => { seen.add(i); });\n  sidePanel.close();\n}`],
      ['destructured', 'const { isOpen, activeTabId } = useSidePanel();\nconst a = isOpen && activeTabId === tabId;'],
      ['destructured and merely kept', 'const { isOpen } = useSidePanel();\nconst showing = isOpen;'],
      ['bound to an unusual name', 'const panel = useSidePanel();\nconst showing = panel.isOpen;'],
    ];
    for (const [why, sample] of positives) {
      expect(offendersIn('components/x.tsx', sample), why).not.toEqual([]);
    }

    const negatives: [string, string][] = [
      ['narrowed by the dock: does the panel take layout space?',
        "const sidePanel = useSidePanelSafe();\n"
        + "const shrinks = (useSidePanelSafe()?.isOpen ?? false) && dock !== 'floating';"],
      ['chooses between activating a tab and asking for the panel; both un-shade',
        `${bind}if (sidePanel?.isOpen) {\n  sidePanel.setActiveTab(id);\n}`],
      ['an auto-open guard that declines to pop a panel',
        `${bind}if (!sidePanel.isOpen) {\n  sidePanel.open();\n}`],
      ['a braceless branch that does not dismiss anything',
        `${bind}if (sidePanel?.isOpen) sidePanel.setActiveTab(id);`],
      ['the destructuring site itself, whose only read is narrowed',
        "const { isOpen } = useSidePanel();\nconst shrinks = isOpen && dock !== 'floating';"],
      ['a comment that explains the rule this guard enforces',
        `${bind}// sidePanel.isOpen stays true while shaded, which is why isForward.\nreturn sidePanel?.isForward;`],
      ['a guard that closes something else entirely',
        `${bind}if (sidePanel?.isOpen) { modal.close(); }`],
      ['a dep array whose callback is a named reference, not an inline arrow',
        `${bind}useEffect(fn, [sidePanel?.isOpen]);`],
      ['an unrelated component whose prop happens to be called isOpen',
        'const { isOpen, activeTabId } = useSidePanel();\n'
        + 'if (isOpen) log(activeTabId);\n'
        + 'return <Modal isOpen={dialogOpen} />;'],
      ['an unrelated object that happens to have isOpen',
        'const { isOpen } = useDialog();\nconst active = isOpen && activeTabId === tabId;'],
      ['a file that never touches the side panel at all',
        'const isOpen = useModal();\nconst x = isOpen && activeTabId === tabId;'],
    ];
    for (const [why, sample] of negatives) {
      expect(offendersIn('components/x.tsx', sample), why).toEqual([]);
    }
  });

  it('lets the pinned files opt out, and nobody else', () => {
    // The marker without a pinned list is an off switch anyone can reach for.
    const src = 'const sidePanel = useSidePanelSafe();\n'
      + '// isOpen-is-deliberate: the body is mounted and listening while shaded.\n'
      + 'const mounted = !!sidePanel?.isOpen\n      && sidePanel.activeTabId === TAB;';
    expect(offendersIn('components/views/workflow/WorkflowDetailView.tsx', src)).toEqual([]);
    expect(offendersIn('components/somewhere/Else.tsx', src), 'unpinned file silenced itself').not.toEqual([]);
  });

  it('does not let one marker cover an unrelated violation below it', () => {
    // Bounded by the contiguous comment block: a reason written for site A must not
    // drift down over site B.
    const src = 'const sidePanel = useSidePanelSafe();\n'
      + '// isOpen-is-deliberate: this one is about the mounted listener.\n'
      + 'const mounted = sidePanel?.isOpen && sidePanel?.activeTabId === TAB;\n'
      + 'const code = 1;\n'
      + 'const showing = sidePanel?.isOpen && sidePanel?.activeTabId === OTHER;';
    const offenders = offendersIn('components/views/workflow/WorkflowDetailView.tsx', src);
    expect(offenders.length, 'the second, uncommented site must still be reported').toBe(1);
    expect(offenders[0]).toContain(':5');
  });

  it('keeps the panel-toggle decision in one place', () => {
    // Four handlers in AppHeader, one legacy event listener, and the chat avatar all
    // make the same call: un-shade, else close if forward, else open. The chat avatar
    // HAD its own copy, and no test renders AppHeader or ChatPageV2, so a copy that
    // drifts drifts in silence.
    //
    // `bringForward()` is the tell, and deliberately so. Matching "reads `isForward`
    // and calls `close()`" would accuse all nine preview cards, whose `close()` shuts
    // THEIR OWN tab and which correctly do not un-shade at all (shaded, `isTabActive`
    // is already false, so the click opens their tab and lifts the shade in one go).
    // The un-shade step is what makes a fragment a copy of this decision.
    // Destructuring is covered too: `panelBindings` was taught to see it for the
    // isOpen scan, and a copy detector that only matched a member access was
    // defeated by `const { bringForward, isForward, close, open } = useSidePanel()`.
    const copies = scannedFiles()
      .filter((rel) => !ALLOWED.has(rel))
      .filter((rel) => {
        // Destructured too, not only as a member access: `panelBindings` was taught
        // to see destructuring for the isOpen scan, and a copy detector that was not
        // is defeated by the same spelling.
        const src = code(readFileSync(join(FRONTEND_ROOT, rel), 'utf8'));
        return /\.\s*bringForward\s*\(/.test(src)
          || (/\{[^}]*\bbringForward\b[^}]*\}\s*=\s*useSidePanel(?:Safe)?\s*\(/.test(src)
            && /(?:^|[^.\w])bringForward\s*\(/m.test(src));
      });

    expect(
      copies,
      'The panel toggle decision belongs in lib/sidePanel/togglePanelFromHeader.ts. '
        + 'Call that instead of re-writing the un-shade / close / open branch:\n  '
        + copies.join('\n  '),
    ).toEqual(['lib/sidePanel/togglePanelFromHeader.ts']);
  });

  it('routes every AppHeader panel toggle through that decision', () => {
    // The extraction is only worth anything if the header actually uses it. Nothing
    // in the repo renders AppHeader (it pulls in the router, the org store, streaming
    // and billing), so this is what stands between the refactor and a handler quietly
    // growing its own branch back.
    //
    // A floor rather than an exact count would not do it: a handler that regrows its
    // own `if (isForward) close(); else open();` LEAVES the other five calls in place,
    // so the floor still passes - and the branch reads `isForward`, so the `isOpen`
    // scan ignores it, and it needs no `bringForward`, so the copy check ignores it
    // too. Verified by reintroducing exactly that on `handleTogglePanelOnly`.
    const src = readFileSync(join(FRONTEND_ROOT, 'components/app/AppHeader.tsx'), 'utf8');
    const routed = src.match(/togglePanelFromHeader\(/g) ?? [];
    // Four button handlers plus TWO in the legacy event listener (its toggle branch
    // and its open-only branch). The import line carries no call and is not counted.
    expect(routed.length, 'four button handlers plus both legacy-event branches').toBe(6);
    // And the header must not dismiss the panel on its own account: the only close
    // belongs to the shared decision. Any receiver, since `panel.close()` is the
    // natural spelling inside the callbacks this file now passes.
    expect(/\w+\s*\??\.\s*close\s*\(\s*\)/.test(code(src)), 'a handler closes the panel itself').toBe(false);
  });

  it('walks every tree that can hold a consumer', () => {
    const walked = scannedFiles();
    expect(walked.length, 'the file scan itself must not come back empty').toBeGreaterThan(100);
    for (const root of SCANNED_ROOTS) {
      expect(walked.some((f) => f.startsWith(`${root}/`)), `${root} was not walked`).toBe(true);
    }
    for (const exempt of [...ALLOWED, ...MAY_OPT_OUT]) {
      expect(walked.includes(exempt), `${exempt} is exempted but never scanned`).toBe(true);
    }
  });
});
