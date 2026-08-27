// Browser-free unit tests for page-scored audio: timeline validation, WAV synthesis and
// the mux argv. Run with `npm test` (node --test). Pure logic - no Chromium, no ffmpeg.

const { test } = require('node:test');
const assert = require('node:assert/strict');

const {
  validateAudioTimeline,
  synthesizeClipAudio,
  buildAudioMuxArgs,
  readPageAudioTimeline,
  validateVideoRequest,
  MAX_AUDIO_EVENTS,
  AUDIO_SAMPLE_RATE,
  DEFAULT_VIDEO_AUDIO_FLAG,
} = require('../lib');

/* --------------------------------------------------------- timeline input */

test('validateAudioTimeline rejects a non-array timeline', () => {
  assert.equal(validateAudioTimeline(null, {}).ok, false);
  assert.equal(validateAudioTimeline({ t: 1 }, {}).ok, false);
  assert.equal(validateAudioTimeline('nope', {}).ok, false);
});

test('validateAudioTimeline keeps good events and drops malformed ones instead of failing', () => {
  const r = validateAudioTimeline([
    { t: 0.5, kind: 'hit' },
    null,
    { t: 'abc' },            // unparseable time
    { t: -1 },               // negative time
    { nope: 1 },             // no time at all
    { t: 2, kind: 'ko' },
  ], {});
  assert.equal(r.ok, true);
  assert.equal(r.value.length, 2, 'only the two valid events survive');
  assert.equal(r.dropped, 4);
});

test('validateAudioTimeline drops events past the clip ceiling', () => {
  const r = validateAudioTimeline([{ t: 1 }, { t: 999 }], { maxSeconds: 10 });
  assert.equal(r.value.length, 1);
  assert.equal(r.value[0].t, 1);
});

test('validateAudioTimeline caps a runaway page at MAX_AUDIO_EVENTS', () => {
  const many = Array.from({ length: MAX_AUDIO_EVENTS + 500 }, (_, i) => ({ t: i * 0.01 }));
  const r = validateAudioTimeline(many, { maxSeconds: 180 });
  assert.equal(r.value.length, MAX_AUDIO_EVENTS);
  assert.ok(r.dropped >= 500);
});

test('validateAudioTimeline honours an explicitly declared voice', () => {
  const r = validateAudioTimeline([{ t: 0, freq: 880, dur: 0.5, gain: 0.25, type: 'square' }], {});
  const e = r.value[0];
  assert.equal(e.freq, 880);
  assert.equal(e.dur, 0.5);
  assert.equal(e.gain, 0.25);
  assert.equal(e.type, 'square');
});

test('validateAudioTimeline derives a deterministic pitch from kind when none is given', () => {
  const a = validateAudioTimeline([{ t: 0, kind: 'clash' }], {}).value[0];
  const b = validateAudioTimeline([{ t: 3, kind: 'clash' }], {}).value[0];
  const c = validateAudioTimeline([{ t: 0, kind: 'merge' }], {}).value[0];
  assert.equal(a.freq, b.freq, 'same kind always scores on the same note');
  assert.notEqual(a.freq, c.freq, 'different kinds get different notes');
  assert.ok(a.freq > 40 && a.freq < 12000);
});

test('validateAudioTimeline clamps out-of-range power and gain rather than trusting the page', () => {
  const r = validateAudioTimeline([{ t: 0, kind: 'x', power: 99, gain: 42 }], {}).value[0];
  assert.ok(r.gain > 0 && r.gain <= 1, `gain fell back into range, got ${r.gain}`);
  assert.ok(Number.isFinite(r.freq));
});

/* ------------------------------------------------------------- synthesis */

test('synthesizeClipAudio returns null when there is nothing to play', () => {
  assert.equal(synthesizeClipAudio([], {}), null);
  assert.equal(synthesizeClipAudio(null, {}), null);
});

test('synthesizeClipAudio emits a well-formed mono 16-bit PCM WAV', () => {
  const events = validateAudioTimeline([{ t: 0.1, kind: 'hit' }], {}).value;
  const wav = synthesizeClipAudio(events, { durationSeconds: 1 });
  assert.ok(Buffer.isBuffer(wav));
  assert.equal(wav.toString('ascii', 0, 4), 'RIFF');
  assert.equal(wav.toString('ascii', 8, 12), 'WAVE');
  assert.equal(wav.readUInt16LE(20), 1, 'PCM');
  assert.equal(wav.readUInt16LE(22), 1, 'mono');
  assert.equal(wav.readUInt32LE(24), AUDIO_SAMPLE_RATE);
  assert.equal(wav.readUInt16LE(34), 16, '16-bit');
  assert.equal(wav.readUInt32LE(4), 36 + wav.readUInt32LE(40), 'RIFF size matches the data chunk');
  assert.equal(wav.length, 44 + wav.readUInt32LE(40));
});

test('synthesizeClipAudio actually renders sound, not silence', () => {
  const events = validateAudioTimeline([{ t: 0.05, kind: 'hit', power: 1 }], {}).value;
  const wav = synthesizeClipAudio(events, { durationSeconds: 1 });
  let peak = 0;
  for (let i = 44; i < wav.length; i += 2) peak = Math.max(peak, Math.abs(wav.readInt16LE(i)));
  assert.ok(peak > 3000, `expected an audible peak, got ${peak}`);
});

test('synthesizeClipAudio stretches to cover the last event even past the requested duration', () => {
  const events = validateAudioTimeline([{ t: 8, kind: 'ko' }], {}).value;
  const wav = synthesizeClipAudio(events, { durationSeconds: 1 });
  const samples = wav.readUInt32LE(40) / 2;
  assert.ok(samples / AUDIO_SAMPLE_RATE > 8, 'the track reaches the last event');
});

test('synthesizeClipAudio soft-limits a burst instead of letting it clip', () => {
  const one = validateAudioTimeline([{ t: 0.1, kind: 'hit', gain: 0.9 }], {}).value;
  const many = validateAudioTimeline(
    Array.from({ length: 40 }, () => ({ t: 0.1, kind: 'hit', gain: 0.9 })), {},
  ).value;
  const peakOf = (wav) => {
    let p = 0;
    for (let i = 44; i < wav.length; i += 2) p = Math.max(p, Math.abs(wav.readInt16LE(i)));
    return p;
  };
  const p1 = peakOf(synthesizeClipAudio(one, { durationSeconds: 1 }));
  const p40 = peakOf(synthesizeClipAudio(many, { durationSeconds: 1 }));
  assert.ok(p40 <= 32767, 'never exceeds full scale');
  assert.ok(p40 < p1 * 3, `40 stacked events must compress, not sum linearly (1:${p1} 40:${p40})`);
});

test('synthesizeClipAudio renders the noise voice deterministically and without stalling', () => {
  // A timeline full of long noise events is the pathological case for the noise generator;
  // it must stay allocation-free integer maths, not per-sample object churn.
  const ev = validateAudioTimeline(
    Array.from({ length: 200 }, (_, i) => ({ t: i * 0.05, kind: 'n', type: 'noise', dur: 0.5 })), {},
  ).value;
  const t0 = Date.now();
  const x = synthesizeClipAudio(ev, { durationSeconds: 12 });
  const elapsed = Date.now() - t0;
  const y = synthesizeClipAudio(ev, { durationSeconds: 12 });
  assert.ok(x.equals(y), 'noise is seeded per event, so renders are reproducible');
  assert.ok(elapsed < 4000, `noise synthesis must stay fast, took ${elapsed}ms`);
});

test('synthesizeClipAudio is deterministic for the same timeline', () => {
  const ev = validateAudioTimeline([{ t: 0.2, kind: 'a' }, { t: 0.7, kind: 'b' }], {}).value;
  const x = synthesizeClipAudio(ev, { durationSeconds: 2 });
  const y = synthesizeClipAudio(ev, { durationSeconds: 2 });
  assert.ok(x.equals(y), 'same input renders byte-identical audio');
});

/* -------------------------------------------------------------- mux argv */

test('buildAudioMuxArgs stream-copies the video and pads the audio', () => {
  const args = buildAudioMuxArgs('/tmp/in.mp4', '/tmp/score.wav', '/tmp/out.mp4');
  const joined = args.join(' ');
  assert.ok(joined.includes('-i /tmp/in.mp4'));
  assert.ok(joined.includes('-i /tmp/score.wav'));
  assert.ok(joined.includes('-c:v copy'), 'video is copied, not re-encoded');
  assert.ok(joined.includes('-c:a aac'));
  assert.equal(args[args.length - 1], '/tmp/out.mp4');
});

test('buildAudioMuxArgs pairs apad with -shortest so the VIDEO is never truncated', () => {
  // Without apad, a soundtrack shorter than the clip makes -shortest cut the video down
  // to the audio length. This pairing is the whole point of the filter.
  const args = buildAudioMuxArgs('a.mp4', 'b.wav', 'c.mp4');
  const fc = args[args.indexOf('-filter_complex') + 1];
  assert.ok(/apad/.test(fc), 'audio is padded with silence');
  assert.ok(args.includes('-shortest'));
  assert.ok(args.includes('-map') && args.includes('0:v:0'), 'video stream is mapped explicitly');
});

/* ------------------------------------------------------ request plumbing */

test('validateVideoRequest turns page audio on for mp4 and exposes the default flag', () => {
  const v = validateVideoRequest({ html: '<p>x</p>' }, {});
  assert.equal(v.ok, true);
  assert.equal(v.value.audio, true);
  assert.equal(v.value.audioFlag, DEFAULT_VIDEO_AUDIO_FLAG);
});

test('validateVideoRequest lets a caller opt out of page audio', () => {
  const v = validateVideoRequest({ html: '<p>x</p>', audio: false }, {});
  assert.equal(v.value.audio, false);
});

test('validateVideoRequest never muxes aac into a webm container', () => {
  const v = validateVideoRequest({ html: '<p>x</p>', format: 'webm' }, {});
  assert.equal(v.value.audio, false, 'webm output stays silent rather than producing an invalid file');
});

test('validateVideoRequest rejects an audioFlag that is not an identifier', () => {
  const v = validateVideoRequest({ html: '<p>x</p>', audioFlag: 'not a flag!' }, {});
  assert.equal(v.ok, false);
  assert.match(v.error, /audioFlag/);
});

/* --------------------------------------------------------- page reading */

test('readPageAudioTimeline returns validated events from the page', async () => {
  const page = {
    evaluate: async (_fn, flag) => {
      assert.equal(flag, '__AUDIO__', 'the flag is passed in the ARG slot');
      return [{ t: 0.25, kind: 'hit', power: 0.5 }, { t: 'bad' }];
    },
  };
  const out = await readPageAudioTimeline(page, '__AUDIO__');
  assert.equal(out.length, 1);
  assert.equal(out[0].t, 0.25);
});

test('readPageAudioTimeline yields an empty timeline when the page has no score', async () => {
  const page = { evaluate: async () => null };
  assert.deepEqual(await readPageAudioTimeline(page, '__AUDIO__'), []);
});

test('readPageAudioTimeline swallows a page that throws so the clip still ships', async () => {
  const page = { evaluate: async () => { throw new Error('execution context destroyed'); } };
  assert.deepEqual(await readPageAudioTimeline(page, '__AUDIO__'), []);
});
