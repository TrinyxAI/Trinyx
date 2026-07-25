// Unit tests for the /internal/media helpers: pure spec validation + ffmpeg argv builders
// (lib.js) and the impure pipeline glue (media.js, exercised with an injected exec).
// Run with `npm test` (node --test). One REAL-ffmpeg integration test at the bottom runs
// the full mux path end to end when ffmpeg/ffprobe are on PATH (they are in the sidecar
// image and on the dev hosts); it self-skips otherwise.

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { Readable } = require('node:stream');
const { spawnSync } = require('node:child_process');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');

const execFileAsync = promisify(execFile);

const {
  validateMediaSpec,
  computeMediaTimeoutMs,
  buildProbeArgs,
  transformProbeJson,
  buildMuxAudioArgs,
  buildMixArgs,
  buildExtractAudioArgs,
  mediaOutputExtension,
  MEDIA_HEADER_DURATION,
  MEDIA_HEADER_OPERATION,
  MEDIA_TIMEOUT_MIN_MS,
  MEDIA_TIMEOUT_MAX_MS,
  MEDIA_STDERR_TAIL_BYTES,
} = require('../lib');

const { parseMediaMultipart, runMediaOperation } = require('../media');

// ---- fixtures ---------------------------------------------------------------
function muxSpec(options = {}) {
  return {
    operation: 'mux_audio',
    options,
    inputs: [{ name: 'input0', role: 'video' }, { name: 'input1', role: 'audio' }],
  };
}

const MUX_PATHS = {
  parts: {
    input0: { path: '/in/v.mp4', durationSeconds: 10 },
    input1: { path: '/in/a.mp3', durationSeconds: 8 },
  },
  outputPath: '/out/out.mp4',
};

// validate-then-build, exactly like the route does - builders consume NORMALISED specs.
function validated(spec) {
  const v = validateMediaSpec(spec);
  assert.equal(v.ok, true, v.error);
  return v.value;
}

function rejected(spec) {
  const v = validateMediaSpec(spec);
  assert.equal(v.ok, false, 'spec should have been rejected');
  return v;
}

// ---- validateMediaSpec: structure -------------------------------------------
test('media spec: unknown operation rejected with UNKNOWN_OPERATION', () => {
  const r = rejected({ operation: 'transcode', options: {}, inputs: [{ name: 'input0' }] });
  assert.equal(r.code, 'UNKNOWN_OPERATION');
  assert.match(r.error, /probe, mux_audio, mix, extract_audio/);
});

test('media spec: non-object spec and missing/empty inputs rejected', () => {
  assert.equal(rejected(null).code, 'INVALID_SPEC');
  assert.equal(rejected([]).code, 'INVALID_SPEC');
  assert.equal(rejected({ operation: 'probe', options: {} }).code, 'INVALID_SPEC');
  assert.equal(rejected({ operation: 'probe', options: {}, inputs: [] }).code, 'INVALID_SPEC');
});

test('media spec: input names must match input<N>; duplicates and bad roles rejected', () => {
  assert.equal(rejected({ operation: 'probe', options: {}, inputs: [{ name: 'file1' }] }).code, 'INVALID_SPEC');
  assert.equal(rejected({
    operation: 'probe',
    options: {},
    inputs: [{ name: 'input0', role: 'subtitle' }],
  }).code, 'INVALID_SPEC');
  assert.equal(rejected({
    operation: 'mix',
    options: { tracks: [{ source_part: 'input0' }] },
    inputs: [{ name: 'input0' }, { name: 'input0' }],
  }).code, 'INVALID_SPEC');
});

test('media spec: probe takes exactly one input part', () => {
  const r = rejected({ operation: 'probe', options: {}, inputs: [{ name: 'input0' }, { name: 'input1' }] });
  assert.equal(r.code, 'INVALID_SPEC');
  const ok = validated({ operation: 'probe', options: {}, inputs: [{ name: 'input0', role: 'input' }] });
  assert.equal(ok.operation, 'probe');
});

test('media spec: mux_audio requires one video-role and one audio-role input', () => {
  const r = rejected({
    operation: 'mux_audio',
    options: {},
    inputs: [{ name: 'input0', role: 'video' }, { name: 'input1', role: 'input' }],
  });
  assert.equal(r.code, 'INVALID_SPEC');
  assert.match(r.error, /video.*audio/);
});

// ---- validateMediaSpec: bounds -----------------------------------------------
test('media spec: volume out of 0-400 rejected (mux and mix track)', () => {
  assert.equal(rejected(muxSpec({ volume: 500 })).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(rejected(muxSpec({ volume: -1 })).code, 'VALUE_OUT_OF_RANGE');
  const r = rejected({
    operation: 'mix',
    options: { tracks: [{ source_part: 'input0', volume: 401 }] },
    inputs: [{ name: 'input0' }],
  });
  assert.equal(r.code, 'VALUE_OUT_OF_RANGE');
});

test('media spec: speed outside 0.5-2.0 rejected', () => {
  const spec = (speed) => ({
    operation: 'mix',
    options: { tracks: [{ source_part: 'input0', speed }] },
    inputs: [{ name: 'input0' }],
  });
  assert.equal(rejected(spec(3)).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(rejected(spec(0.25)).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(validated(spec(0.5)).options.tracks[0].speed, 0.5);
});

test('media spec: normalize true/-16 default, false -> off, number bounded to [-70,-5]', () => {
  assert.equal(validated(muxSpec({})).options.normalizeLufs, -16);
  assert.equal(validated(muxSpec({ normalize: true })).options.normalizeLufs, -16);
  assert.equal(validated(muxSpec({ normalize: false })).options.normalizeLufs, null);
  assert.equal(validated(muxSpec({ normalize: -20 })).options.normalizeLufs, -20);
  assert.equal(rejected(muxSpec({ normalize: -80 })).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(rejected(muxSpec({ normalize: -3 })).code, 'VALUE_OUT_OF_RANGE');
});

test('media spec: trim_end must exceed trim_start; negatives rejected', () => {
  assert.equal(rejected(muxSpec({ trim_start_seconds: 5, trim_end_seconds: 5 })).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(rejected(muxSpec({ trim_start_seconds: -1 })).code, 'VALUE_OUT_OF_RANGE');
  const ok = validated(muxSpec({ trim_start_seconds: 1, trim_end_seconds: 4 }));
  assert.equal(ok.options.trimStart, 1);
  assert.equal(ok.options.trimEnd, 4);
});

test('media spec: loop (or audio_fit=loop) combined with trims rejected', () => {
  assert.equal(rejected(muxSpec({ loop: true, trim_start_seconds: 1 })).code, 'INVALID_SPEC');
  assert.equal(rejected(muxSpec({ audio_fit: 'loop', trim_end_seconds: 4 })).code, 'INVALID_SPEC');
});

test('media spec: audio_fit and audio_bitrate whitelisted', () => {
  assert.equal(rejected(muxSpec({ audio_fit: 'stretch' })).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(rejected(muxSpec({ audio_bitrate: 'fast' })).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(validated(muxSpec({ audio_bitrate: '128K' })).options.audioBitrate, '128k');
});

// ---- validateMediaSpec: mix tracks + ducking ---------------------------------
test('media spec: tracks must be 1-8 (TRACKS_LIMIT on both sides)', () => {
  assert.equal(rejected({
    operation: 'mix', options: { tracks: [] }, inputs: [{ name: 'input0' }],
  }).code, 'TRACKS_LIMIT');
  const nine = Array.from({ length: 9 }, () => ({ source_part: 'input0' }));
  assert.equal(rejected({
    operation: 'mix', options: { tracks: nine }, inputs: [{ name: 'input0' }],
  }).code, 'TRACKS_LIMIT');
});

test('media spec: duck_under must reference ANOTHER existing track id', () => {
  const spec = (tracks, inputs) => ({ operation: 'mix', options: { tracks }, inputs });
  const selfRef = rejected(spec(
    [{ id: 'a', source_part: 'input0', duck_under: 'a' }],
    [{ name: 'input0' }],
  ));
  assert.equal(selfRef.code, 'DUCK_REF_INVALID');
  assert.match(selfRef.error, /itself/);
  const ghost = rejected(spec(
    [{ id: 'a', source_part: 'input0', duck_under: 'ghost' }, { id: 'b', source_part: 'input1' }],
    [{ name: 'input0' }, { name: 'input1' }],
  ));
  assert.equal(ghost.code, 'DUCK_REF_INVALID');
});

test('media spec: one input part per track; ids default track_N and must be unique', () => {
  const dup = rejected({
    operation: 'mix',
    options: { tracks: [{ source_part: 'input0' }, { source_part: 'input0' }] },
    inputs: [{ name: 'input0' }],
  });
  assert.equal(dup.code, 'INVALID_SPEC');
  const ok = validated({
    operation: 'mix',
    options: { tracks: [{ source_part: 'input0' }, { source_part: 'input1' }] },
    inputs: [{ name: 'input0' }, { name: 'input1' }],
  });
  assert.deepEqual(ok.options.tracks.map((t) => t.id), ['track_1', 'track_2']);
  const dupIds = rejected({
    operation: 'mix',
    options: { tracks: [{ id: 'x', source_part: 'input0' }, { id: 'x', source_part: 'input1' }] },
    inputs: [{ name: 'input0' }, { name: 'input1' }],
  });
  assert.equal(dupIds.code, 'INVALID_SPEC');
});

test('media spec: audio-only mix where EVERY track loops has no length anchor - rejected', () => {
  const r = rejected({
    operation: 'mix',
    options: { tracks: [{ source_part: 'input0', loop: true }] },
    inputs: [{ name: 'input0' }],
  });
  assert.equal(r.code, 'INVALID_SPEC');
  assert.match(r.error, /non-looping/);
});

test('media spec: mix with a video input forces mp4 output; audio-only validates its format', () => {
  const withVideo = validated({
    operation: 'mix',
    options: { tracks: [{ source_part: 'input1' }], output_format: 'mp3' },
    inputs: [{ name: 'input0', role: 'video' }, { name: 'input1' }],
  });
  assert.equal(withVideo.options.outputFormat, 'mp4');
  assert.equal(mediaOutputExtension(withVideo), 'mp4');
  const bad = rejected({
    operation: 'mix',
    options: { tracks: [{ source_part: 'input0' }], output_format: 'ogg' },
    inputs: [{ name: 'input0' }],
  });
  assert.equal(bad.code, 'VALUE_OUT_OF_RANGE');
});

test('media spec: extract_audio output_format whitelisted, defaults mp3', () => {
  const def = validated({ operation: 'extract_audio', options: {}, inputs: [{ name: 'input0' }] });
  assert.equal(def.options.outputFormat, 'mp3');
  assert.equal(mediaOutputExtension(def), 'mp3');
  assert.equal(rejected({
    operation: 'extract_audio', options: { output_format: 'ogg' }, inputs: [{ name: 'input0' }],
  }).code, 'VALUE_OUT_OF_RANGE');
});

// ---- timeout formula ----------------------------------------------------------
test('media timeout: max(60s, 3s per input second, cap 300s)', () => {
  assert.equal(computeMediaTimeoutMs(0), MEDIA_TIMEOUT_MIN_MS);
  assert.equal(computeMediaTimeoutMs(null), MEDIA_TIMEOUT_MIN_MS);
  assert.equal(computeMediaTimeoutMs(10), 60000, '10s of input stays on the 60s floor');
  assert.equal(computeMediaTimeoutMs(40), 120000, '40s of input buys 120s');
  assert.equal(computeMediaTimeoutMs(100), MEDIA_TIMEOUT_MAX_MS, '100s of input hits the cap exactly');
  assert.equal(computeMediaTimeoutMs(5000), MEDIA_TIMEOUT_MAX_MS, 'the cap bounds hostile durations');
});

// ---- probe --------------------------------------------------------------------
test('probe args: ffprobe json with format + streams, input last', () => {
  assert.deepEqual(
    buildProbeArgs('/in/x.mp4'),
    ['-v', 'error', '-show_format', '-show_streams', '-of', 'json', '/in/x.mp4'],
  );
});

test('probe transform: mp4 with h264+aac maps to the contract shape (fps from the r_frame_rate fraction)', () => {
  const raw = {
    format: { duration: '12.480000', size: '3140000', format_name: 'mov,mp4,m4a,3gp,3g2,mj2', bit_rate: '2012345' },
    streams: [
      { codec_type: 'video', codec_name: 'h264', width: 1920, height: 1080, r_frame_rate: '30000/1001' },
      { codec_type: 'audio', codec_name: 'aac', sample_rate: '44100', channels: 2 },
    ],
  };
  assert.deepEqual(transformProbeJson(raw), {
    duration_seconds: 12.48,
    size_bytes: 3140000,
    format_name: 'mov,mp4,m4a,3gp,3g2,mj2',
    bit_rate: 2012345,
    has_video: true,
    has_audio: true,
    video: { codec: 'h264', width: 1920, height: 1080, fps: 29.97 },
    audio: { codec: 'aac', sample_rate: 44100, channels: 2 },
  });
});

test('probe transform: mp3 cover art (attached_pic) is NOT a video stream; absent bit_rate is null', () => {
  const raw = {
    format: { duration: '180.5', size: '2880000', format_name: 'mp3' },
    streams: [
      {
        codec_type: 'video', codec_name: 'mjpeg', width: 600, height: 600,
        r_frame_rate: '90000/1', disposition: { attached_pic: 1 },
      },
      { codec_type: 'audio', codec_name: 'mp3', sample_rate: '44100', channels: 2 },
    ],
  };
  const out = transformProbeJson(raw);
  assert.equal(out.has_video, false, 'embedded artwork must not report as video');
  assert.equal(out.video, null);
  assert.equal(out.bit_rate, null);
  assert.equal(out.has_audio, true);
});

// ---- buildMuxAudioArgs ----------------------------------------------------------
test('mux args: all defaults - fade-out anchored at audio end, loudnorm+aresample, apad, -c:v copy, faststart', () => {
  const args = buildMuxAudioArgs(validated(muxSpec({})), MUX_PATHS);
  assert.deepEqual(args, [
    '-nostdin', '-y', '-loglevel', 'error',
    '-i', '/in/v.mp4',
    '-i', '/in/a.mp3',
    '-filter_complex', '[1:a]afade=t=out:st=7:d=1,loudnorm=I=-16:TP=-1.5,aresample=48000,aformat=channel_layouts=stereo,apad[aout]',
    '-map', '0:v:0', '-map', '[aout]',
    '-c:v', 'copy', '-c:a', 'aac', '-b:a', '192k',
    '-shortest', '-movflags', '+faststart',
    '/out/out.mp4',
  ]);
});

test('mux args: volume + fade-out + offset compose in chain order (volume, fade, delay)', () => {
  const args = buildMuxAudioArgs(
    validated(muxSpec({ volume: 80, fade_out_seconds: 2, offset_seconds: 3 })),
    MUX_PATHS,
  );
  // audible span = min(8s audio, 10s video - 3s offset) = 7s -> fade-out starts at 5s
  assert.equal(
    args[args.indexOf('-filter_complex') + 1],
    '[1:a]volume=0.8,afade=t=out:st=5:d=2,adelay=3000:all=1,loudnorm=I=-16:TP=-1.5,aresample=48000,aformat=channel_layouts=stereo,apad[aout]',
  );
});

test('mux args: keep_original_audio mixes [0:a] under the new track at original_volume', () => {
  const args = buildMuxAudioArgs(
    validated(muxSpec({
      keep_original_audio: true, original_volume: 50,
      normalize: false, fade_out_seconds: 0, audio_fit: 'shortest',
    })),
    MUX_PATHS,
  );
  assert.equal(
    args[args.indexOf('-filter_complex') + 1],
    '[1:a]anull[anew];[0:a]volume=0.5[aorig];[anew][aorig]amix=inputs=2:duration=longest:normalize=0[aout]',
  );
});

test('mux args: audio_fit=shortest drops apad but keeps -shortest', () => {
  const args = buildMuxAudioArgs(validated(muxSpec({ audio_fit: 'shortest' })), MUX_PATHS);
  assert.ok(!args[args.indexOf('-filter_complex') + 1].includes('apad'));
  assert.ok(args.includes('-shortest'));
});

test('mux args: audio_fit=loop stream-loops the audio input and cuts it to the video length', () => {
  const args = buildMuxAudioArgs(
    validated(muxSpec({ audio_fit: 'loop', fade_out_seconds: 0, normalize: false })),
    MUX_PATHS,
  );
  // -stream_loop must precede the AUDIO -i (input 1), never the video's
  assert.equal(args[4], '-i');
  assert.equal(args[5], '/in/v.mp4');
  assert.equal(args[6], '-stream_loop');
  assert.equal(args[7], '-1');
  assert.equal(args[8], '-i');
  assert.equal(args[9], '/in/a.mp3');
  assert.equal(
    args[args.indexOf('-filter_complex') + 1],
    '[1:a]atrim=0:10,asetpts=PTS-STARTPTS[aout]',
    'looped audio is cut to the 10s video target; no apad needed',
  );
});

test('mux args: looped audio with an UNPROBEABLE video duration throws a 422 FFMPEG_FAILED mediaError, not a plain Error', () => {
  // Pre-fix this was `throw new Error(...)` and surfaced as a 500; the anchor input is
  // caller data, so it must map to the structured 422 the route serialises.
  let thrown = null;
  try {
    buildMuxAudioArgs(validated(muxSpec({ loop: true })), {
      parts: {
        input0: { path: '/in/v.mp4', durationSeconds: null },
        input1: { path: '/in/a.mp3', durationSeconds: 8 },
      },
      outputPath: '/out/out.mp4',
    });
  } catch (err) {
    thrown = err;
  }
  assert.ok(thrown, 'a looped track without a target duration must throw');
  assert.equal(thrown.status, 422);
  assert.equal(thrown.code, 'FFMPEG_FAILED');
  assert.match(thrown.message, /a looped track needs a known target duration/);
});

// ---- buildMixArgs ----------------------------------------------------------------
test('mix args: two tracks with ducking + loudnorm - asplit keys the sidechain, ratio from duck_amount_db', () => {
  const spec = validated({
    operation: 'mix',
    options: {
      tracks: [
        { id: 'music', source_part: 'input0', volume: 60, duck_under: 'voice' },
        { id: 'voice', source_part: 'input1' },
      ],
    },
    inputs: [{ name: 'input0', role: 'audio' }, { name: 'input1', role: 'audio' }],
  });
  const args = buildMixArgs(spec, {
    parts: {
      input0: { path: '/in/music.mp3', durationSeconds: 60 },
      input1: { path: '/in/voice.wav', durationSeconds: 30 },
    },
    outputPath: '/out/mix.mp3',
  });
  assert.deepEqual(args, [
    '-nostdin', '-y', '-loglevel', 'error',
    '-i', '/in/music.mp3',
    '-i', '/in/voice.wav',
    '-filter_complex', [
      '[0:a]volume=0.6[t0]',
      '[1:a]anull[t1]',
      '[t1]asplit=2[t1m][t1sc0]',
      // The sidechain key is apad-ed: sidechaincompress ends at its SHORTER input, so an
      // unpadded key that stops early would truncate the ducked track with it.
      '[t1sc0]apad[t1sc0p]',
      '[t0][t1sc0p]sidechaincompress=threshold=0.05:ratio=6:attack=20:release=300[t0d]',
      '[t0d][t1m]amix=inputs=2:duration=longest:normalize=0,loudnorm=I=-16:TP=-1.5,aresample=48000,aformat=channel_layouts=stereo[aout]',
    ].join(';'),
    '-map', '[aout]',
    '-c:a', 'libmp3lame', '-b:a', '192k',
    '/out/mix.mp3',
  ]);
});

test('mix args: with video - keep_original_audio joins the amix, -c:v copy, apad, faststart', () => {
  const spec = validated({
    operation: 'mix',
    options: {
      tracks: [{ id: 'bed', source_part: 'input1' }],
      keep_original_audio: true,
      original_volume: 40,
    },
    inputs: [{ name: 'input0', role: 'video' }, { name: 'input1', role: 'audio' }],
  });
  const args = buildMixArgs(spec, {
    parts: {
      input0: { path: '/in/v.mp4', durationSeconds: 12 },
      input1: { path: '/in/bed.mp3', durationSeconds: 8 },
    },
    outputPath: '/out/mix.mp4',
  });
  assert.equal(
    args[args.indexOf('-filter_complex') + 1],
    '[1:a]anull[t0];[0:a]volume=0.4[aorig];[t0][aorig]amix=inputs=2:duration=longest:normalize=0,loudnorm=I=-16:TP=-1.5,aresample=48000,aformat=channel_layouts=stereo,apad[aout]',
  );
  assert.equal(args[args.indexOf('-map') + 1], '0:v:0');
  assert.ok(args.includes('copy'));
  assert.ok(args.includes('-shortest'));
  assert.ok(args.includes('+faststart'));
  assert.equal(args[args.length - 1], '/out/mix.mp4');
});

test('mix args: audio-only wav output uses pcm_s16le with NO bitrate', () => {
  const spec = validated({
    operation: 'mix',
    options: { tracks: [{ source_part: 'input0' }], output_format: 'wav', normalize: false },
    inputs: [{ name: 'input0' }],
  });
  const args = buildMixArgs(spec, {
    parts: { input0: { path: '/in/a.mp3', durationSeconds: 5 } },
    outputPath: '/out/out.wav',
  });
  assert.equal(args[args.indexOf('-c:a') + 1], 'pcm_s16le');
  assert.ok(!args.includes('-b:a'), 'wav is PCM - a bitrate would be ignored noise');
  assert.equal(
    args[args.indexOf('-filter_complex') + 1],
    '[0:a]anull[t0];[t0]anull[aout]',
    'a single track skips amix',
  );
});

test('mix args: audio-only looped bed is stream-looped, cut to the longest non-loop track, and -t bounds the run', () => {
  const spec = validated({
    operation: 'mix',
    options: {
      tracks: [
        { id: 'bed', source_part: 'input0', loop: true },
        { id: 'voice', source_part: 'input1' },
      ],
    },
    inputs: [{ name: 'input0' }, { name: 'input1' }],
  });
  const args = buildMixArgs(spec, {
    parts: {
      input0: { path: '/in/bed.mp3', durationSeconds: 5 },
      input1: { path: '/in/voice.wav', durationSeconds: 30 },
    },
    outputPath: '/out/mix.mp3',
  });
  assert.equal(args[4], '-stream_loop');
  assert.equal(args[5], '-1');
  assert.equal(args[6], '-i');
  assert.equal(args[7], '/in/bed.mp3');
  assert.ok(
    args[args.indexOf('-filter_complex') + 1].startsWith('[0:a]atrim=0:30,asetpts=PTS-STARTPTS[t0]'),
    'looped bed cut to the 30s target',
  );
  // Without -t an audio-only graph with a -stream_loop input never signals EOF and
  // runs until the timeout kill - the output cap is the termination.
  assert.equal(args[args.indexOf('-t') + 1], '30');
});

test('mix args: looped track whose ANCHOR track duration is unprobeable throws the same 422 FFMPEG_FAILED', () => {
  const spec = validated({
    operation: 'mix',
    options: {
      tracks: [
        { id: 'bed', source_part: 'input0', loop: true },
        { id: 'voice', source_part: 'input1' },
      ],
    },
    inputs: [{ name: 'input0' }, { name: 'input1' }],
  });
  let thrown = null;
  try {
    buildMixArgs(spec, {
      parts: {
        input0: { path: '/in/bed.mp3', durationSeconds: 5 },
        input1: { path: '/in/voice.wav', durationSeconds: null },
      },
      outputPath: '/out/mix.mp3',
    });
  } catch (err) {
    thrown = err;
  }
  assert.ok(thrown, 'no anchor duration means the looped bed has no cut target - must throw');
  assert.equal(thrown.status, 422);
  assert.equal(thrown.code, 'FFMPEG_FAILED');
  assert.match(thrown.message, /a looped track needs a known target duration/);
});

// ---- buildExtractAudioArgs ---------------------------------------------------------
test('extract args: mp3 with trims - accurate output-side -ss/-to, -vn, first audio stream', () => {
  const spec = validated({
    operation: 'extract_audio',
    options: { trim_start_seconds: 2, trim_end_seconds: 5 },
    inputs: [{ name: 'input0', role: 'input' }],
  });
  const args = buildExtractAudioArgs(spec, {
    parts: { input0: { path: '/in/v.mp4', durationSeconds: 60 } },
    outputPath: '/out/out.mp3',
  });
  assert.deepEqual(args, [
    '-nostdin', '-y', '-loglevel', 'error',
    '-i', '/in/v.mp4',
    '-ss', '2', '-to', '5',
    '-vn', '-map', '0:a:0',
    '-c:a', 'libmp3lame', '-b:a', '192k',
    '/out/out.mp3',
  ]);
});

test('extract args: wav is pcm_s16le without bitrate; aac keeps its bitrate', () => {
  const base = (output_format) => validated({
    operation: 'extract_audio', options: { output_format }, inputs: [{ name: 'input0' }],
  });
  const paths = { parts: { input0: { path: '/in/v.mp4', durationSeconds: 60 } }, outputPath: '/out/o' };
  const wav = buildExtractAudioArgs(base('wav'), paths);
  assert.equal(wav[wav.indexOf('-c:a') + 1], 'pcm_s16le');
  assert.ok(!wav.includes('-b:a'));
  const aac = buildExtractAudioArgs(base('aac'), paths);
  assert.equal(aac[aac.indexOf('-c:a') + 1], 'aac');
  assert.equal(aac[aac.indexOf('-b:a') + 1], '192k');
});

// ---- response header literals -------------------------------------------------------
// Cross-layer contract with the orchestrator's MediaRenderService, same discipline as
// the X-Render-* pair: renaming here alone silently breaks the duration passthrough.
test('media response header names are the literals the orchestrator matches', () => {
  assert.equal(MEDIA_HEADER_DURATION, 'X-Media-Duration-Seconds');
  assert.equal(MEDIA_HEADER_OPERATION, 'X-Media-Operation');
});

// ---- runMediaOperation (injected exec - no real children) ---------------------------
function fakeExec({ ffmpeg, probeDurations = {}, fullProbe } = {}) {
  const calls = [];
  return {
    calls,
    async exec(bin, args, opts) {
      calls.push([bin, args, opts]);
      if (bin === 'ffprobe') {
        if (args.includes('-show_format')) {
          if (fullProbe) return { stdout: JSON.stringify(fullProbe) };
          return { stdout: '{"format":{},"streams":[]}' };
        }
        const file = args[args.length - 1];
        const d = probeDurations[file] !== undefined ? probeDurations[file] : 3;
        if (d instanceof Error) throw d;
        return { stdout: JSON.stringify({ format: { duration: String(d) } }) };
      }
      if (ffmpeg) return ffmpeg(args, opts);
      return { stdout: '' };
    },
  };
}

test('runMediaOperation: ffmpeg failure maps to 422 FFMPEG_FAILED with a <=2KB stderr tail', async () => {
  const fake = fakeExec({
    ffmpeg: () => {
      const err = new Error('ffmpeg exited 1');
      err.stderr = 'x'.repeat(5000);
      err.code = 1;
      throw err;
    },
  });
  await assert.rejects(
    () => runMediaOperation(validated(muxSpec({})), { input0: '/v', input1: '/a' }, '/w', { execFileAsync: fake.exec }),
    (err) => err.status === 422
      && err.code === 'FFMPEG_FAILED'
      && err.stderrTail.length === MEDIA_STDERR_TAIL_BYTES,
  );
});

test('runMediaOperation: a timeout kill maps to 504 MEDIA_TIMEOUT, never 422', async () => {
  const fake = fakeExec({
    ffmpeg: () => {
      const err = new Error('spawn killed');
      err.killed = true;
      err.signal = 'SIGTERM';
      throw err;
    },
  });
  await assert.rejects(
    () => runMediaOperation(validated(muxSpec({})), { input0: '/v', input1: '/a' }, '/w', { execFileAsync: fake.exec }),
    (err) => err.status === 504 && err.code === 'MEDIA_TIMEOUT',
  );
});

test('runMediaOperation: an unreadable input part fails the quick probe as 422 naming the part', async () => {
  const bad = new Error('moov atom not found');
  bad.stderr = 'moov atom not found';
  const fake = fakeExec({ probeDurations: { '/a': bad } });
  await assert.rejects(
    () => runMediaOperation(validated(muxSpec({})), { input0: '/v', input1: '/a' }, '/w', { execFileAsync: fake.exec }),
    (err) => err.status === 422 && err.code === 'FFMPEG_FAILED' && /input1/.test(err.message),
  );
});

test('runMediaOperation: the ffmpeg budget comes from the summed input durations (capped)', async () => {
  // path.join because runMediaOperation builds the output path with the OS separator
  const fake = fakeExec({ probeDurations: { '/v': 100, '/a': 100, [path.join('/w', 'out.mp4')]: 100 } });
  const out = await runMediaOperation(
    validated(muxSpec({})), { input0: '/v', input1: '/a' }, '/w', { execFileAsync: fake.exec },
  );
  const ffmpegCall = fake.calls.find(([bin]) => bin === 'ffmpeg');
  assert.equal(ffmpegCall[2].timeout, MEDIA_TIMEOUT_MAX_MS, '200s of input caps at 300s');
  assert.equal(out.kind, 'file');
  assert.equal(out.mime, 'video/mp4');
  assert.equal(out.durationSeconds, 100, 'output duration re-probed for the response header');
  assert.ok(out.path.endsWith('out.mp4'));
});

test('runMediaOperation: probe answers transformed JSON, no ffmpeg run', async () => {
  const fake = fakeExec({
    probeDurations: { '/x': 7 },
    fullProbe: {
      format: { duration: '7.0', size: '1000', format_name: 'mp3' },
      streams: [{ codec_type: 'audio', codec_name: 'mp3', sample_rate: '48000', channels: 1 }],
    },
  });
  const spec = validated({ operation: 'probe', options: {}, inputs: [{ name: 'input0' }] });
  const out = await runMediaOperation(spec, { input0: '/x' }, '/w', { execFileAsync: fake.exec });
  assert.equal(out.kind, 'json');
  assert.equal(out.durationSeconds, 7);
  assert.deepEqual(out.body.audio, { codec: 'mp3', sample_rate: 48000, channels: 1 });
  assert.equal(fake.calls.some(([bin]) => bin === 'ffmpeg'), false);
});

// ---- parseMediaMultipart --------------------------------------------------------------
function fakeMultipartReq(boundary, bodyBuffer) {
  const req = Readable.from([bodyBuffer]);
  req.headers = { 'content-type': `multipart/form-data; boundary=${boundary}` };
  return req;
}

function multipartBody(boundary, specJson, fileBytes) {
  const CRLF = '\r\n';
  return Buffer.from(
    `--${boundary}${CRLF}Content-Disposition: form-data; name="spec"${CRLF}Content-Type: application/json${CRLF}${CRLF}`
    + specJson + CRLF
    + `--${boundary}${CRLF}Content-Disposition: form-data; name="input0"; filename="clip.mp3"${CRLF}Content-Type: audio/mpeg${CRLF}${CRLF}`
    + fileBytes + CRLF
    + `--${boundary}--${CRLF}`,
  );
}

test('multipart: spec part buffered, input part streamed to workDir with its extension hint', async () => {
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-ut-'));
  try {
    const specJson = JSON.stringify({ operation: 'probe' });
    const req = fakeMultipartReq('bnd1', multipartBody('bnd1', specJson, 'AUDIOBYTES'));
    const out = await parseMediaMultipart(req, workDir, { maxTotalBytes: 1024 });
    assert.equal(out.specText, specJson);
    assert.ok(out.parts.input0.endsWith('input0.mp3'), 'filename extension kept as a demuxer hint');
    assert.equal(await fs.readFile(out.parts.input0, 'utf8'), 'AUDIOBYTES');
    assert.equal(out.totalBytes, 'AUDIOBYTES'.length);
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

test('multipart: total byte cap rejects 413 INPUT_TOO_LARGE mid-stream', async () => {
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-ut-'));
  try {
    const req = fakeMultipartReq('bnd2', multipartBody('bnd2', '{}', 'AUDIOBYTES'));
    await assert.rejects(
      () => parseMediaMultipart(req, workDir, { maxTotalBytes: 4 }),
      (err) => err.status === 413 && err.code === 'INPUT_TOO_LARGE',
    );
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

test('multipart: an OVERSIZED spec sent as a FILE part rejects 400 INVALID_SPEC (chunked bodies bypass Content-Length)', async () => {
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-ut-'));
  try {
    const CRLF = '\r\n';
    // 256KB is media.js's MAX_SPEC_BYTES; one byte over must trip the manual cap on
    // the spec-as-file path (busboy's fieldSize limit only covers the field variant).
    const hugeSpec = 'x'.repeat(256 * 1024 + 1);
    const body = Buffer.from(
      `--bnd3${CRLF}Content-Disposition: form-data; name="spec"; filename="spec.json"${CRLF}Content-Type: application/json${CRLF}${CRLF}`
      + hugeSpec + CRLF
      + `--bnd3--${CRLF}`,
    );
    const req = fakeMultipartReq('bnd3', body);
    await assert.rejects(
      () => parseMediaMultipart(req, workDir, { maxTotalBytes: 1024 }),
      (err) => err.status === 400 && err.code === 'INVALID_SPEC' && /spec part exceeds/.test(err.message),
    );
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

// ---- integration: real ffmpeg mux end to end --------------------------------------------
const ffmpegOnPath = (() => {
  try {
    return spawnSync('ffmpeg', ['-version'], { timeout: 5000 }).status === 0
      && spawnSync('ffprobe', ['-version'], { timeout: 5000 }).status === 0;
  } catch (_) {
    return false;
  }
})();

test('integration: real mux_audio produces an mp4 with BOTH streams (lavfi test video + sine)', async (t) => {
  if (!ffmpegOnPath) {
    t.skip('ffmpeg/ffprobe not on PATH (present in the sidecar image and on dev hosts)');
    return;
  }
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-it-'));
  try {
    const videoPath = path.join(workDir, 'video.mp4');
    const audioPath = path.join(workDir, 'audio.wav');
    await execFileAsync('ffmpeg', [
      '-nostdin', '-y', '-loglevel', 'error',
      '-f', 'lavfi', '-i', 'testsrc=duration=2:size=320x240:rate=10',
      '-pix_fmt', 'yuv420p', videoPath,
    ], { timeout: 120000 });
    await execFileAsync('ffmpeg', [
      '-nostdin', '-y', '-loglevel', 'error',
      '-f', 'lavfi', '-i', 'sine=frequency=440:duration=2', audioPath,
    ], { timeout: 120000 });

    const spec = validated(muxSpec({})); // defaults: pad + loudnorm + 1s fade-out
    const out = await runMediaOperation(spec, { input0: videoPath, input1: audioPath }, workDir, {});
    assert.equal(out.kind, 'file');
    assert.equal(out.mime, 'video/mp4');
    assert.ok(out.durationSeconds > 1.5 && out.durationSeconds < 3.5,
      `output should stay ~2s, got ${out.durationSeconds}`);

    const { stdout } = await execFileAsync('ffprobe', buildProbeArgs(out.path),
      { timeout: 15000, maxBuffer: 16 * 1024 * 1024 });
    const probe = transformProbeJson(JSON.parse(stdout));
    assert.equal(probe.has_video, true, 'video stream must survive -c:v copy');
    assert.equal(probe.has_audio, true, 'the muxed audio must be present');
    assert.equal(probe.video.codec, 'h264');
    assert.equal(probe.audio.codec, 'aac');
    assert.equal(probe.video.width, 320);
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

test('integration: real mux of a MONO MP3 with normalize+pad must not fail on channel-layout negotiation (live-found bug)', async (t) => {
  // Pre-fix, the loudnorm->aresample chain left the channel layout undetermined and a
  // downstream apad could not negotiate it: "Cannot select channel layout for the link
  // between filters Parsed_aresample and Parsed_apad". Surfaced live with an ElevenLabs
  // mono mp3; the aformat=channel_layouts=stereo pin is the fix.
  if (!ffmpegOnPath) {
    t.skip('ffmpeg/ffprobe not on PATH (present in the sidecar image and on dev hosts)');
    return;
  }
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-it-'));
  try {
    const videoPath = path.join(workDir, 'video.mp4');
    const audioPath = path.join(workDir, 'audio.mp3');
    await execFileAsync('ffmpeg', [
      '-nostdin', '-y', '-loglevel', 'error',
      '-f', 'lavfi', '-i', 'testsrc=duration=3:size=320x240:rate=10',
      '-pix_fmt', 'yuv420p', videoPath,
    ], { timeout: 120000 });
    await execFileAsync('ffmpeg', [
      '-nostdin', '-y', '-loglevel', 'error',
      '-f', 'lavfi', '-i', 'sine=frequency=440:duration=2',
      '-ac', '1', '-c:a', 'libmp3lame', audioPath,
    ], { timeout: 120000 });

    // Defaults exercise the failing chain: normalize (loudnorm+aresample) + audio_fit pad.
    const spec = validated(muxSpec({}));
    const out = await runMediaOperation(spec, { input0: videoPath, input1: audioPath }, workDir, {});
    assert.equal(out.kind, 'file');

    const { stdout } = await execFileAsync('ffprobe', buildProbeArgs(out.path),
      { timeout: 15000, maxBuffer: 16 * 1024 * 1024 });
    const probe = transformProbeJson(JSON.parse(stdout));
    assert.equal(probe.has_audio, true, 'the mono mp3 must survive normalize+pad as an aac track');
    assert.equal(probe.audio.channels, 2, 'mono sources are upmixed to the pinned stereo layout');
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

test('integration: ducking under a SHORTER key track must not truncate the mix (live-found bug)', async (t) => {
  // Pre-fix, sidechaincompress ended at its shorter input: a 6s bed ducked under a 3s
  // voice came out ~4s. The apad on the sidechain key is the fix; this pins it against
  // real ffmpeg, not just the argv string.
  if (!ffmpegOnPath) {
    t.skip('ffmpeg/ffprobe not on PATH (present in the sidecar image and on dev hosts)');
    return;
  }
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-it-'));
  try {
    const musicPath = path.join(workDir, 'music.wav');
    const voicePath = path.join(workDir, 'voice.wav');
    await execFileAsync('ffmpeg', [
      '-nostdin', '-y', '-loglevel', 'error',
      '-f', 'lavfi', '-i', 'sine=frequency=220:duration=6', musicPath,
    ], { timeout: 120000 });
    await execFileAsync('ffmpeg', [
      '-nostdin', '-y', '-loglevel', 'error',
      '-f', 'lavfi', '-i', 'sine=frequency=880:duration=3', voicePath,
    ], { timeout: 120000 });

    const spec = validated({
      operation: 'mix',
      options: {
        tracks: [
          { id: 'music', source_part: 'input0', volume: 70, duck_under: 'voice' },
          { id: 'voice', source_part: 'input1', offset_seconds: 1 },
        ],
      },
      inputs: [{ name: 'input0', role: 'audio' }, { name: 'input1', role: 'audio' }],
    });
    const out = await runMediaOperation(spec, { input0: musicPath, input1: voicePath }, workDir, {});
    assert.equal(out.kind, 'file');
    assert.equal(out.mime, 'audio/mpeg');
    assert.ok(out.durationSeconds > 5.5 && out.durationSeconds < 6.6,
      `the 6s bed must survive the 4s duck key, got ${out.durationSeconds}s`);
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});
