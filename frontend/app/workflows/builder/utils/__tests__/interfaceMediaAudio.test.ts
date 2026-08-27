/**
 * Audio in an interface preview.
 *
 * An interface is live HTML, so it can carry an `<audio>`/`<video>` that starts
 * the moment it is embedded - and a card grid embeds a dozen of them at once.
 * The host cannot mute a sandboxed cross-origin frame, and cannot see whether the
 * document has any media at all, so both jobs are done by a script injected into
 * every interface: it reports presence upward, and it applies the mute state the
 * embedder asked for.
 *
 * The rule these pin down is that the injection is INERT unless an embedder opts
 * in. Every editing and running surface renders through this same funnel, so a
 * default that muted them all would silence the interfaces whose whole point is
 * to make a sound.
 */
import { describe, it, expect } from 'vitest';
import { ensureCompleteHtml, renderInterfaceTemplate } from '../interfaceHtmlUtils';

const FRAGMENT = '<div>Hello</div>';
const COMPLETE = '<!DOCTYPE html><html><head></head><body><audio src="a.mp3" autoplay></audio></body></html>';

describe('interface media-audio controller', () => {
  describe('is injected everywhere, so any embedder can ask about sound', () => {
    it('reaches a wrapped fragment', () => {
      expect(ensureCompleteHtml(FRAGMENT)).toContain('__iframe_audio');
    });

    it('reaches a complete document too', () => {
      // A complete document owns its own <head>/<body> and deliberately receives
      // no platform CSS, so the scripts are the one thing that must still land.
      expect(ensureCompleteHtml(COMPLETE)).toContain('__iframe_audio');
    });

    it('listens for the host flipping the mute at runtime', () => {
      // The alternative - rebuilding the srcDoc - would reload the interface and
      // throw away whatever state its JS had built, just to change the volume.
      expect(ensureCompleteHtml(FRAGMENT)).toContain('__iframe_set_muted');
    });
  });

  describe('changes nothing unless the embedder opts in', () => {
    it('emits no mute flag when muteMedia is left undefined', () => {
      const html = ensureCompleteHtml(FRAGMENT);

      // Without the flag the controller only reports; the page plays as authored.
      // Matched on the literal assignments only: the controller's own source
      // assigns the global too (when the host later flips it), so a looser
      // substring would pass on the script body and prove nothing.
      expect(html).not.toContain('window.__LC_MEDIA_MUTED__ = true;');
      expect(html).not.toContain('window.__LC_MEDIA_MUTED__ = false;');
    });

    it('emits the flag as true when the embedder asks for silence', () => {
      const html = ensureCompleteHtml(FRAGMENT, undefined, false, undefined, undefined, undefined, undefined, undefined, true);

      expect(html).toContain('window.__LC_MEDIA_MUTED__ = true;');
    });

    it('emits the flag as false when the embedder manages audio but wants it ON', () => {
      // Explicitly false is NOT the same as absent: it means "I am in charge and
      // the answer is unmuted", which the controller must apply to the elements.
      const html = ensureCompleteHtml(FRAGMENT, undefined, false, undefined, undefined, undefined, undefined, undefined, false);

      expect(html).toContain('window.__LC_MEDIA_MUTED__ = false;');
    });

    it('sets the flag BEFORE the controller runs, or the initial state is missed', () => {
      const html = ensureCompleteHtml(FRAGMENT, undefined, false, undefined, undefined, undefined, undefined, undefined, true);

      // The controller reads the global at startup: injected after it, the first
      // frames of a preview would play out loud before the host could correct it.
      expect(html.indexOf('__LC_MEDIA_MUTED__ = true')).toBeLessThan(html.indexOf('__iframe_audio'));
    });

    it('carries the flag into a complete document as well', () => {
      const html = ensureCompleteHtml(COMPLETE, undefined, false, undefined, undefined, undefined, undefined, undefined, true);

      expect(html).toContain('window.__LC_MEDIA_MUTED__ = true;');
    });
  });

  describe('reaches the controller through the render entry point', () => {
    it('renderInterfaceTemplate forwards muteMedia', () => {
      const html = renderInterfaceTemplate(FRAGMENT, { mode: 'edit', muteMedia: true });

      expect(html).toContain('window.__LC_MEDIA_MUTED__ = true;');
    });

    it('renderInterfaceTemplate without the option leaves the page alone', () => {
      const html = renderInterfaceTemplate(FRAGMENT, { mode: 'edit' });

      expect(html).not.toContain('window.__LC_MEDIA_MUTED__ = true;');
      expect(html).not.toContain('window.__LC_MEDIA_MUTED__ = false;');
      expect(html).toContain('__iframe_audio');
    });
  });

  describe('the controller only mutes what it is told to', () => {
    it('guards the element loop on the flag being defined', () => {
      // Reading the source is the point here: the guard is what keeps every
      // non-opted-in surface (builder, run panel, renderer) byte-identical in
      // behaviour to before this feature existed.
      expect(ensureCompleteHtml(FRAGMENT)).toContain('window.__LC_MEDIA_MUTED__ !== undefined');
    });

    it('watches for media added after load, not just what is in the initial HTML', () => {
      const html = ensureCompleteHtml(FRAGMENT);

      // An interface that builds its player in JS, or swaps a src on an existing
      // element, would otherwise never be muted and never be reported.
      expect(html).toContain('MutationObserver');
      expect(html).toContain("addEventListener('loadstart'");
    });
  });
});
