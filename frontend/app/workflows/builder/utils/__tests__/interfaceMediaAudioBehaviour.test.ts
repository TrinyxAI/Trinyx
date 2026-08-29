// @vitest-environment jsdom
/**
 * The media controller, RUN rather than read.
 *
 * The sibling suite pins what gets INJECTED into the page; this one boots the injected script in
 * a document and pins what it DOES. None of this is new behaviour: it is the behaviour the new
 * interface viewer leans on, written down because nothing exercised it before.
 *
 * The rule it protects is severe on purpose. An interface is untrusted HTML, rendered a dozen at
 * a time in a card grid, so the mute is ENFORCED on every pass rather than merely set once: a
 * page that unmutes itself, at load or later, is put back. The reader gets the sound through the
 * HOST's control, which is the one thing that overrules it, and which is the whole reason the
 * viewer grew a control of its own rather than just silencing pages.
 *
 * Each test gets its own frame, and its observers are disconnected afterwards. The controller
 * binds listeners and a MutationObserver to the document it runs in and has no teardown of its
 * own, so sharing one document would leave every earlier controller still muting.
 */
import { afterEach, describe, expect, it } from 'vitest';
import { ensureCompleteHtml } from '../interfaceHtmlUtils';

/** The controller's own source, lifted out of the page it is injected into. */
function controllerSource(): string {
  const html = ensureCompleteHtml('<div></div>');
  const block = html.split('<script>').find((part) => part.includes('__iframe_audio'));
  if (!block) throw new Error('media controller not found in the injected page');
  return block.split('</script>')[0];
}

const SOURCE = controllerSource();

interface Frame {
  doc: Document;
  addAudio: (id: string) => HTMLAudioElement;
  mutate: () => void;
  setMuted: (muted: boolean) => void;
  /** Fire the page's load pass, which is where a page that unmutes itself gets overruled. */
  finishLoading: () => void;
  /** The page's own script changing the volume. */
  pageUnmutes: (el: HTMLAudioElement) => void;
}

let frames: HTMLIFrameElement[] = [];
let observers: MutationObserver[] = [];

/**
 * A document of its own, seeded and then handed to the controller - the order an injected
 * script actually runs in: the page exists, then the script runs over it.
 */
function boot(muted: boolean | undefined, seed: (doc: Document) => void): Frame {
  const iframe = document.createElement('iframe');
  document.body.appendChild(iframe);
  frames.push(iframe);

  const win = iframe.contentWindow as unknown as Window & Record<string, unknown>;
  const doc = iframe.contentDocument as Document;
  seed(doc);
  if (muted !== undefined) win.__LC_MEDIA_MUTED__ = muted;

  // Keep hold of the observer the controller makes: it has no teardown of its own, so without
  // this it goes on firing against a document being torn down.
  const RealObserver = (win as unknown as { MutationObserver: typeof MutationObserver }).MutationObserver;
  (win as unknown as { MutationObserver: unknown }).MutationObserver = class extends RealObserver {
    constructor(callback: MutationCallback) {
      super(callback);
      observers.push(this);
    }
  };

  (win as unknown as { eval: (src: string) => void }).eval(SOURCE);

  const WinEvent = (win as unknown as { Event: typeof Event }).Event;

  return {
    doc,
    addAudio: (id: string) => {
      const el = doc.createElement('audio');
      el.id = id;
      doc.body.appendChild(el);
      return el as HTMLAudioElement;
    },
    mutate: () => doc.body.appendChild(doc.createElement('div')),
    setMuted: (next: boolean) =>
      win.postMessage({ type: '__iframe_set_muted', muted: next }, '*'),
    finishLoading: () => win.dispatchEvent(new WinEvent('load')),
    pageUnmutes: (el: HTMLAudioElement) => {
      el.muted = false;
      el.dispatchEvent(new WinEvent('volumechange', { bubbles: false }));
    },
  };
}

const audio = (doc: Document, id: string) => doc.getElementById(id) as HTMLAudioElement;

const seedAudio = (id: string) => (doc: Document) => {
  const el = doc.createElement('audio');
  el.id = id;
  doc.body.appendChild(el);
};

/** Let the MutationObserver and the message queue run. */
const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

afterEach(() => {
  // Disconnect what the controller left behind before dropping its frame. It has no teardown of
  // its own, so an observer outliving its document is a stream of errors at the end of the run,
  // the kind of noise that hides a real one later.
  observers.forEach((o) => o.disconnect());
  observers = [];
  frames.forEach((f) => f.remove());
  frames = [];
});

describe('the page arrives silent', () => {
  it('silences media that is already there', () => {
    const frame = boot(true, seedAudio('a'));

    expect(audio(frame.doc, 'a').muted).toBe(true);
  });

  it('silences media that appears later, so a page cannot slip sound in after load', async () => {
    const frame = boot(true, () => {});

    frame.addAudio('late');
    await settle();

    expect(audio(frame.doc, 'late').muted).toBe(true);
  });
});

/**
 * The half with the wide blast radius: a card grid renders untrusted pages by the dozen, and a
 * page must not be able to talk its own way out of the silence the host asked for. The reader
 * gets the sound from the host's control instead, which is what the viewer's new button is.
 */
describe('a page that unmutes ITSELF is overruled', () => {
  it('is put back on the load pass, after the page has had its say', async () => {
    const frame = boot(true, seedAudio('a'));

    frame.pageUnmutes(audio(frame.doc, 'a'));
    frame.finishLoading();
    await settle();

    expect(audio(frame.doc, 'a').muted).toBe(true);
  });

  it('is put back the next time the page changes anything', async () => {
    const frame = boot(true, seedAudio('a'));

    frame.pageUnmutes(audio(frame.doc, 'a'));
    frame.mutate();
    await settle();

    expect(audio(frame.doc, 'a').muted).toBe(true);
  });

  it('is put back when the element loads new media', async () => {
    const frame = boot(true, seedAudio('a'));

    frame.pageUnmutes(audio(frame.doc, 'a'));
    audio(frame.doc, 'a').dispatchEvent(new Event('loadstart', { bubbles: false }));
    await settle();

    expect(audio(frame.doc, 'a').muted).toBe(true);
  });

  it('is put back however many times it tries', async () => {
    const frame = boot(true, seedAudio('a'));

    for (let i = 0; i < 3; i += 1) {
      frame.pageUnmutes(audio(frame.doc, 'a'));
      frame.mutate();
      await settle();
      expect(audio(frame.doc, 'a').muted).toBe(true);
    }
  });
});

describe("the host's control is how the sound is actually obtained", () => {
  it('gives the sound back when the host asks', async () => {
    const frame = boot(true, seedAudio('a'));
    expect(audio(frame.doc, 'a').muted).toBe(true);

    frame.setMuted(false);
    await settle();

    expect(audio(frame.doc, 'a').muted).toBe(false);
  });

  it('keeps it playing while the page goes on changing around it', async () => {
    const frame = boot(true, seedAudio('a'));
    frame.setMuted(false);
    await settle();

    frame.mutate();
    frame.finishLoading();
    await settle();

    expect(audio(frame.doc, 'a').muted).toBe(false);
  });

  it('takes it away again when the host asks', async () => {
    const frame = boot(false, seedAudio('a'));
    frame.setMuted(false);
    await settle();

    frame.setMuted(true);
    await settle();

    expect(audio(frame.doc, 'a').muted).toBe(true);
  });
});

describe('a page nobody claimed plays exactly as authored', () => {
  it('touches nothing when no embedder is in charge', async () => {
    const frame = boot(undefined, seedAudio('a'));
    audio(frame.doc, 'a').muted = false;

    frame.mutate();
    frame.finishLoading();
    await settle();

    expect(audio(frame.doc, 'a').muted).toBe(false);
  });

  it('still reports whether the page has any audio, which is how a control is offered at all', async () => {
    const heard: boolean[] = [];
    const listener = (event: MessageEvent) => {
      if ((event.data as { type?: string })?.type === '__iframe_audio') {
        heard.push((event.data as { hasAudio: boolean }).hasAudio);
      }
    };
    window.addEventListener('message', listener);

    boot(undefined, seedAudio('a'));
    await settle();
    window.removeEventListener('message', listener);

    expect(heard).toContain(true);
  });

  it('reports a page with no media, which is what hides the control', async () => {
    const heard: boolean[] = [];
    const listener = (event: MessageEvent) => {
      if ((event.data as { type?: string })?.type === '__iframe_audio') {
        heard.push((event.data as { hasAudio: boolean }).hasAudio);
      }
    };
    window.addEventListener('message', listener);

    boot(true, () => {});
    await settle();
    window.removeEventListener('message', listener);

    expect(heard).toContain(false);
  });
});
