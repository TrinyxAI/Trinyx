// Unit + integration tests for the /internal/media v2 operations (concat / frame /
// overlay). Same style as media.test.js: pinned-argv builder tests against hand-built
// probes, every contract 422 preflight case, and real-ffmpeg integration tests at the
// bottom (self-skipping when ffmpeg/ffprobe are not on PATH). The v1 suite is untouched;
// this file only exercises the NEW operations plus the constants they added.

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');

const execFileAsync = promisify(execFile);

const {
  validateMediaSpec,
  computeMediaTimeoutMs,
  buildProbeArgs,
  transformProbeJson,
  extractVideoSar,
  validateConcatStatic,
  concatCopyEligible,
  planConcat,
  buildConcatListText,
  buildConcatCopyArgs,
  buildConcatFilterArgs,
  resolveFrameTimestamp,
  buildFrameArgs,
  isImageProbeFormat,
  buildOverlayArgs,
  mediaOutputExtension,
  MEDIA_MIME_TYPES,
  MEDIA_HEADER_TIMESTAMP,
  MEDIA_TIMEOUT_MIN_MS,
  CONCAT_MAX_INPUTS,
} = require('../lib');

const { runMediaOperation, probeFullInfo } = require('../media');

// ---- fixtures ---------------------------------------------------------------

function concatSpec(options = {}, partCount = 2) {
  return {
    operation: 'concat',
    options,
    inputs: Array.from({ length: partCount }, (_, i) => ({ name: `input${i}`, role: 'video' })),
  };
}

function frameSpec(options = {}) {
  return { operation: 'frame', options, inputs: [{ name: 'input0', role: 'input' }] };
}

function overlaySpec(options = {}) {
  return {
    operation: 'overlay',
    options,
    inputs: [{ name: 'input0', role: 'video' }, { name: 'input1', role: 'image' }],
  };
}

// Raw ffprobe JSON for a video file; probeOf() turns it into the {info, sar} shape the
// concat planner consumes (exactly what media.js's probeFullInfo produces).
function rawVideoProbe({
  duration = 10, codec = 'h264', width = 1280, height = 720, fps = '30/1', sar = '1:1',
  audio = { codec: 'aac', sample_rate: '48000', channels: 2 },
} = {}) {
  const streams = [{
    codec_type: 'video', codec_name: codec, width, height, r_frame_rate: fps, sample_aspect_ratio: sar,
  }];
  if (audio) {
    streams.push({ codec_type: 'audio', codec_name: audio.codec, sample_rate: audio.sample_rate, channels: audio.channels });
  }
  return {
    format: { duration: String(duration), size: '1000', format_name: 'mov,mp4,m4a,3gp,3g2,mj2' },
    streams,
  };
}

function probeOf(raw) {
  return { info: transformProbeJson(raw), sar: extractVideoSar(raw) };
}

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

// Asserts the thrown error is the contract's 422 FFMPEG_FAILED with a message fragment.
function assert422(fn, messagePattern) {
  let thrown = null;
  try {
    fn();
  } catch (err) {
    thrown = err;
  }
  assert.ok(thrown, 'expected a 422 FFMPEG_FAILED throw');
  assert.equal(thrown.status, 422);
  assert.equal(thrown.code, 'FFMPEG_FAILED');
  assert.match(thrown.message, messagePattern);
  return thrown;
}

// Two identical homogeneous parts - the copy-path baseline every ineligibility test mutates.
function homogeneousProbes() {
  return {
    input0: probeOf(rawVideoProbe({ duration: 4 })),
    input1: probeOf(rawVideoProbe({ duration: 6 })),
  };
}

// ---- validateMediaSpec: concat (400 structural layer) -------------------------
test('concat spec: unknown-operation list now includes concat, frame, overlay', () => {
  const r = rejected({ operation: 'transcode', options: {}, inputs: [{ name: 'input0' }] });
  assert.equal(r.code, 'UNKNOWN_OPERATION');
  assert.match(r.error, /probe, mux_audio, mix, extract_audio, concat, frame, overlay/);
});

test('concat spec: options.inputs must be an array of objects with a valid, unique source_part', () => {
  assert.equal(rejected(concatSpec({ inputs: 'nope' })).code, 'INVALID_SPEC');
  assert.equal(rejected(concatSpec({ inputs: ['nope'] })).code, 'INVALID_SPEC');
  assert.equal(rejected(concatSpec({ inputs: [{ source_part: 'input9' }] })).code, 'INVALID_SPEC');
  const dup = rejected(concatSpec({ inputs: [{ source_part: 'input0' }, { source_part: 'input0' }] }));
  assert.equal(dup.code, 'INVALID_SPEC');
  assert.match(dup.error, /already used/);
});

test('concat spec: absent options.inputs derives one default item per part in input<N> numeric order', () => {
  const spec = {
    operation: 'concat',
    options: {},
    inputs: [{ name: 'input2' }, { name: 'input10' }, { name: 'input0' }],
  };
  const v = validated(spec);
  assert.deepEqual(v.options.items.map((it) => it.sourcePart), ['input0', 'input2', 'input10']);
  assert.deepEqual(v.options.items[0], { sourcePart: 'input0', trimStart: null, trimEnd: null, speed: 1 });
});

test('concat spec: option defaults - cut, 0.5s transition, no fades, normalize FALSE (unlike mux/mix), 192k', () => {
  const v = validated(concatSpec({}));
  assert.equal(v.options.transition, 'cut');
  assert.equal(v.options.transitionSeconds, 0.5);
  assert.equal(v.options.fadeIn, 0);
  assert.equal(v.options.fadeOut, 0, 'concat fade_out defaults to 0, NOT mux_audio\'s 1');
  assert.equal(v.options.normalizeLufs, null, 'concat normalize defaults FALSE (forces re-encode otherwise)');
  assert.equal(v.options.audioBitrate, '192k');
  assert.equal(v.options.targetWidth, null);
  assert.equal(v.options.targetFps, null);
});

test('concat spec: normalize true -> -16 LUFS; explicit target dims are floored to EVEN values', () => {
  const v = validated(concatSpec({ normalize: true, target_width: 641, target_height: 361 }));
  assert.equal(v.options.normalizeLufs, -16);
  assert.equal(v.options.targetWidth, 640);
  assert.equal(v.options.targetHeight, 360);
});

test('concat spec: 400 range checks - transition enum, transition_seconds 0.1-5, dims 16-4096, fps 1-60, negative trims/fades', () => {
  assert.equal(rejected(concatSpec({ transition: 'wipe' })).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(rejected(concatSpec({ transition_seconds: 0.05 })).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(rejected(concatSpec({ transition_seconds: 6 })).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(rejected(concatSpec({ target_width: 8, target_height: 8 })).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(rejected(concatSpec({ target_width: 640, target_height: 5000 })).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(rejected(concatSpec({ target_fps: 0 })).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(rejected(concatSpec({ target_fps: 61 })).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(rejected(concatSpec({ fade_in_seconds: -1 })).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(rejected(concatSpec({ inputs: [{ source_part: 'input0', trim_start_seconds: -1 }] })).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(rejected(concatSpec({ inputs: [{ source_part: 'input0', speed: 'fast' }] })).code, 'VALUE_OUT_OF_RANGE');
});

// ---- validateConcatStatic: the contract's static 422 FFMPEG_FAILED preflight ---
test('concat 422: more than 8 inputs rejected before any child process', () => {
  const parts = 9;
  const spec = validated(concatSpec({}, parts));
  assert.equal(CONCAT_MAX_INPUTS, 8);
  assert422(() => validateConcatStatic(spec), /1 to 8 inputs \(got 9\)/);
});

test('concat 422: an explicitly EMPTY clip list rejected', () => {
  const spec = validated(concatSpec({}, 2));
  spec.options.items = [];
  assert422(() => validateConcatStatic(spec), /1 to 8 inputs \(got 0\)/);
});

test('concat 422: speed outside 0.5-2.0 (both sides)', () => {
  const fast = validated(concatSpec({ inputs: [{ source_part: 'input0', speed: 3 }, { source_part: 'input1' }] }));
  assert422(() => validateConcatStatic(fast), /inputs\[0\]\.speed must be between 0\.5 and 2\.0/);
  const slow = validated(concatSpec({ inputs: [{ source_part: 'input0' }, { source_part: 'input1', speed: 0.25 }] }));
  assert422(() => validateConcatStatic(slow), /inputs\[1\]\.speed/);
});

test('concat 422: trim_end_seconds <= trim_start_seconds', () => {
  const spec = validated(concatSpec({
    inputs: [{ source_part: 'input0', trim_start_seconds: 5, trim_end_seconds: 5 }, { source_part: 'input1' }],
  }));
  assert422(() => validateConcatStatic(spec), /inputs\[0\]\.trim_end_seconds must be greater than trim_start_seconds/);
});

test('concat 422: crossfade with fewer than 2 inputs', () => {
  const spec = validated(concatSpec({ transition: 'crossfade' }, 1));
  assert422(() => validateConcatStatic(spec), /crossfade needs at least 2 inputs/);
});

test('concat 422: target_width XOR target_height (both or neither)', () => {
  const spec = validated(concatSpec({ target_width: 640 }));
  assert422(() => validateConcatStatic(spec), /target_width and target_height must be provided together/);
  const other = validated(concatSpec({ target_height: 360 }));
  assert422(() => validateConcatStatic(other), /together/);
});

// ---- planConcat: duration-dependent 422s + the numbers -------------------------
test('planConcat 422: clip without a readable duration, clip without a video stream', () => {
  const spec = validated(concatSpec({}));
  const noDuration = {
    input0: probeOf(rawVideoProbe({ duration: 4 })),
    input1: probeOf({ format: {}, streams: rawVideoProbe({}).streams }),
  };
  assert422(() => planConcat(spec, noDuration), /inputs\[1\] \(part 'input1'\) has no readable duration/);
  const noVideo = {
    input0: probeOf({
      format: { duration: '4' },
      streams: [{ codec_type: 'audio', codec_name: 'aac', sample_rate: '48000', channels: 2 }],
    }),
    input1: probeOf(rawVideoProbe({})),
  };
  assert422(() => planConcat(spec, noVideo), /inputs\[0\] \(part 'input0'\) has no video stream/);
});

test('planConcat 422: nothing left after trimming (trim_start at or past the end)', () => {
  const spec = validated(concatSpec({
    inputs: [{ source_part: 'input0', trim_start_seconds: 10 }, { source_part: 'input1' }],
  }));
  const probes = { input0: probeOf(rawVideoProbe({ duration: 10 })), input1: probeOf(rawVideoProbe({ duration: 5 })) };
  assert422(() => planConcat(spec, probes), /inputs\[0\] has no duration left after trim\/speed/);
});

test('planConcat 422: transition_seconds >= an effective clip duration names the OFFENDING clip index', () => {
  const spec = validated(concatSpec({
    transition: 'crossfade',
    transition_seconds: 2,
    inputs: [{ source_part: 'input0' }, { source_part: 'input1', trim_end_seconds: 4, speed: 2 }],
  }));
  // clip 1 effective = 4 / 2 = 2s, equal to the transition -> rejected, naming inputs[1]
  const probes = { input0: probeOf(rawVideoProbe({ duration: 10 })), input1: probeOf(rawVideoProbe({ duration: 10 })) };
  const err = assert422(() => planConcat(spec, probes), /transition_seconds \(2\) must be smaller than the effective duration of inputs\[1\] \(2s\)/);
  assert.ok(err.message.includes('inputs[1]'), 'the message must name the clip index');
});

test('planConcat: effective-duration math (trim + speed), output duration, defaults from the FIRST input', () => {
  const spec = validated(concatSpec({
    inputs: [
      { source_part: 'input0', trim_start_seconds: 2, trim_end_seconds: 8, speed: 2 },
      { source_part: 'input1', speed: 0.5 },
    ],
  }));
  const probes = {
    input0: probeOf(rawVideoProbe({ duration: 10, width: 1279, height: 721, fps: '25/1' })),
    input1: probeOf(rawVideoProbe({ duration: 4 })),
  };
  const plan = planConcat(spec, probes);
  assert.deepEqual(plan.effectiveSeconds, [3, 8], '(8-2)/2=3 and 4/0.5=8');
  assert.equal(plan.outputDurationSeconds, 11, 'cut output = sum of effectives');
  assert.equal(plan.width, 1278, 'default canvas = first input, floored to even');
  assert.equal(plan.height, 720);
  assert.equal(plan.fps, 25, 'default fps = first input');
  assert.equal(plan.copy, false, 'trims/speed force the re-encode path');
});

test('planConcat: crossfade output duration = sum(effective) - (N-1)*transition', () => {
  const spec = validated(concatSpec({ transition: 'crossfade', transition_seconds: 1 }, 3));
  const probes = {
    input0: probeOf(rawVideoProbe({ duration: 10, audio: null })),
    input1: probeOf(rawVideoProbe({ duration: 10, audio: null })),
    input2: probeOf(rawVideoProbe({ duration: 10, audio: null })),
  };
  const plan = planConcat(spec, probes);
  assert.equal(plan.outputDurationSeconds, 28, '30 - 2*1');
});

// ---- concatCopyEligible ----------------------------------------------------------
test('concat copy path: eligible for homogeneous clips with pure defaults', () => {
  const spec = validated(concatSpec({}));
  assert.equal(concatCopyEligible(spec, homogeneousProbes()), true);
  assert.equal(planConcat(spec, homogeneousProbes()).copy, true);
});

test('concat copy path: any edit option forces the re-encode (trim, speed, crossfade, fades, normalize)', () => {
  const probes = homogeneousProbes();
  const cases = [
    concatSpec({ inputs: [{ source_part: 'input0', trim_start_seconds: 1 }, { source_part: 'input1' }] }),
    concatSpec({ inputs: [{ source_part: 'input0' }, { source_part: 'input1', speed: 1.5 }] }),
    concatSpec({ transition: 'crossfade' }),
    concatSpec({ fade_in_seconds: 1 }),
    concatSpec({ fade_out_seconds: 1 }),
    concatSpec({ normalize: true }),
  ];
  for (const spec of cases) {
    assert.equal(concatCopyEligible(validated(spec), probes), false, JSON.stringify(spec.options));
  }
});

test('concat copy path: heterogeneous inputs force the re-encode (codec, dims, fps > 0.01, SAR, audio situation)', () => {
  const spec = validated(concatSpec({}));
  const base = () => homogeneousProbes();
  const withSecond = (raw) => ({ input0: base().input0, input1: probeOf(raw) });
  assert.equal(concatCopyEligible(spec, withSecond(rawVideoProbe({ duration: 6, codec: 'hevc' }))), false, 'codec');
  assert.equal(concatCopyEligible(spec, withSecond(rawVideoProbe({ duration: 6, width: 1920, height: 1080 }))), false, 'dims');
  assert.equal(concatCopyEligible(spec, withSecond(rawVideoProbe({ duration: 6, fps: '30000/1001' }))), false, 'fps 29.97 vs 30');
  assert.equal(concatCopyEligible(spec, withSecond(rawVideoProbe({ duration: 6, sar: '4:3' }))), false, 'SAR');
  assert.equal(concatCopyEligible(spec, withSecond(rawVideoProbe({ duration: 6, audio: null }))), false, 'audio vs none');
  assert.equal(concatCopyEligible(spec, withSecond(rawVideoProbe({ duration: 6, audio: { codec: 'mp3', sample_rate: '48000', channels: 2 } }))), false, 'non-aac audio');
  assert.equal(concatCopyEligible(spec, withSecond(rawVideoProbe({ duration: 6, audio: { codec: 'aac', sample_rate: '44100', channels: 2 } }))), false, 'sample_rate');
  assert.equal(concatCopyEligible(spec, withSecond(rawVideoProbe({ duration: 6, audio: { codec: 'aac', sample_rate: '48000', channels: 1 } }))), false, 'channels');
  // ALL-no-audio clips are homogeneous too
  const silent = {
    input0: probeOf(rawVideoProbe({ duration: 4, audio: null })),
    input1: probeOf(rawVideoProbe({ duration: 6, audio: null })),
  };
  assert.equal(concatCopyEligible(spec, silent), true, 'none-has-audio is a valid copy situation');
});

test('concat copy path: explicit target dims/fps must MATCH the source; a matching explicit value stays lossless', () => {
  const probes = homogeneousProbes(); // 1280x720 @30
  assert.equal(concatCopyEligible(validated(concatSpec({ target_width: 640, target_height: 360 })), probes), false);
  assert.equal(concatCopyEligible(validated(concatSpec({ target_fps: 25 })), probes), false);
  assert.equal(concatCopyEligible(validated(concatSpec({ target_width: 1280, target_height: 720, target_fps: 30 })), probes), true);
});

// ---- concat builders ---------------------------------------------------------------
test('concat list file: one file line per clip in concat order, single quotes escaped', () => {
  const text = buildConcatListText(['/in/a.mp4', "/in/it's.mp4"]);
  assert.equal(text, "file '/in/a.mp4'\nfile '/in/it'\\''s.mp4'\n");
});

test('concat copy argv: concat demuxer + -c copy + faststart, pinned', () => {
  assert.deepEqual(buildConcatCopyArgs('/w/concat.txt', '/w/out.mp4'), [
    '-nostdin', '-y', '-loglevel', 'error',
    '-f', 'concat', '-safe', '0', '-i', '/w/concat.txt',
    '-c', 'copy', '-movflags', '+faststart',
    '/w/out.mp4',
  ]);
});

test('concat re-encode argv: trims/speed/scale+pad/fps/yuv420p, atempo audio, SILENT BED for the audio-less clip, global fades + loudnorm - pinned', () => {
  const spec = validated(concatSpec({
    inputs: [
      { source_part: 'input0', trim_start_seconds: 2, trim_end_seconds: 8, speed: 2 },
      { source_part: 'input1' },
    ],
    target_width: 640,
    target_height: 360,
    target_fps: 30,
    fade_in_seconds: 1,
    fade_out_seconds: 2,
    normalize: true,
  }));
  const probes = {
    input0: probeOf(rawVideoProbe({ duration: 10 })),
    input1: probeOf(rawVideoProbe({ duration: 4, width: 640, height: 360, audio: null })),
  };
  const plan = planConcat(spec, probes);
  assert.deepEqual(plan.effectiveSeconds, [3, 4]);
  const args = buildConcatFilterArgs(spec, probes, plan, {
    parts: { input0: { path: '/in/a.mp4' }, input1: { path: '/in/b.mp4' } },
    outputPath: '/out/out.mp4',
  });
  assert.deepEqual(args, [
    '-nostdin', '-y', '-loglevel', 'error',
    '-i', '/in/a.mp4',
    '-i', '/in/b.mp4',
    '-filter_complex', [
      '[0:v]trim=start=2:end=8,setpts=PTS-STARTPTS,setpts=PTS/2,scale=640:360:force_original_aspect_ratio=decrease,pad=640:360:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30,format=yuv420p[v0]',
      // real audio is padded AND clamped to the clip's effective duration (3s) so the
      // audio segment is exactly as long as its video segment (A/V splice alignment)
      '[0:a]atrim=start=2:end=8,asetpts=PTS-STARTPTS,atempo=2,aresample=48000,aformat=channel_layouts=stereo,apad=whole_dur=3,atrim=0:3,asetpts=PTS-STARTPTS[a0]',
      '[1:v]scale=640:360:force_original_aspect_ratio=decrease,pad=640:360:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30,format=yuv420p[v1]',
      // the audio-less clip gets a silent bed cut to ITS effective duration (4s)
      'anullsrc=r=48000:cl=stereo,atrim=0:4,asetpts=PTS-STARTPTS[a1]',
      '[v0][a0][v1][a1]concat=n=2:v=1:a=1[vcat][acat]',
      // output duration 3+4=7 -> fade-out starts at 7-2=5
      '[vcat]fade=t=in:st=0:d=1,fade=t=out:st=5:d=2[vout]',
      '[acat]afade=t=in:st=0:d=1,afade=t=out:st=5:d=2,loudnorm=I=-16:TP=-1.5,aresample=48000,aformat=channel_layouts=stereo[aout]',
    ].join(';'),
    '-map', '[vout]', '-map', '[aout]',
    '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '20',
    '-c:a', 'aac', '-b:a', '192k',
    '-movflags', '+faststart',
    '/out/out.mp4',
  ]);
});

test('concat crossfade argv: pairwise xfade offsets = sum(effective so far) - k*T; acrossfade chain - pinned graph', () => {
  const spec = validated(concatSpec({ transition: 'crossfade', transition_seconds: 1 }, 3));
  const probes = {
    input0: probeOf(rawVideoProbe({ duration: 10, width: 640, height: 360, audio: null })),
    input1: probeOf(rawVideoProbe({ duration: 10, width: 640, height: 360, audio: null })),
    input2: probeOf(rawVideoProbe({ duration: 10, width: 640, height: 360, audio: null })),
  };
  const plan = planConcat(spec, probes);
  const args = buildConcatFilterArgs(spec, probes, plan, {
    parts: { input0: { path: '/a' }, input1: { path: '/b' }, input2: { path: '/c' } },
    outputPath: '/out/out.mp4',
  });
  const graph = args[args.indexOf('-filter_complex') + 1];
  const clip = 'scale=640:360:force_original_aspect_ratio=decrease,pad=640:360:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30,format=yuv420p';
  assert.equal(graph, [
    `[0:v]${clip}[v0]`,
    'anullsrc=r=48000:cl=stereo,atrim=0:10,asetpts=PTS-STARTPTS[a0]',
    `[1:v]${clip}[v1]`,
    'anullsrc=r=48000:cl=stereo,atrim=0:10,asetpts=PTS-STARTPTS[a1]',
    `[2:v]${clip}[v2]`,
    'anullsrc=r=48000:cl=stereo,atrim=0:10,asetpts=PTS-STARTPTS[a2]',
    '[v0][v1]xfade=transition=fade:duration=1:offset=9[vx1]',
    '[a0][a1]acrossfade=d=1[ax1]',
    '[vx1][v2]xfade=transition=fade:duration=1:offset=18[vcat]',
    '[ax1][a2]acrossfade=d=1[acat]',
  ].join(';'));
  // no fades/normalize -> the concat labels are mapped directly
  assert.equal(args[args.indexOf('-map') + 1], '[vcat]');
  assert.equal(args[args.indexOf('-map', args.indexOf('-map') + 1) + 1], '[acat]');
});

test('concat re-encode: REAL-audio chains are padded and clamped to the effective duration even WITHOUT trims (short-audio desync regression)', () => {
  // Regression for the audit finding: a clip whose embedded audio is shorter than its
  // video was neither padded nor clamped, so concat spliced the next clip's audio early.
  // The pad/clamp pair must be present on every real-audio chain, not only trimmed ones.
  const spec = validated(concatSpec({}));
  const probes = {
    input0: probeOf(rawVideoProbe({ duration: 4 })),
    input1: probeOf(rawVideoProbe({ duration: 6, width: 640, height: 360 })), // dims differ -> re-encode
  };
  const plan = planConcat(spec, probes);
  const args = buildConcatFilterArgs(spec, probes, plan, {
    parts: { input0: { path: '/a' }, input1: { path: '/b' } },
    outputPath: '/o.mp4',
  });
  const graph = args[args.indexOf('-filter_complex') + 1];
  assert.ok(graph.includes('[0:a]aresample=48000,aformat=channel_layouts=stereo,apad=whole_dur=4,atrim=0:4,asetpts=PTS-STARTPTS[a0]'), graph);
  assert.ok(graph.includes('[1:a]aresample=48000,aformat=channel_layouts=stereo,apad=whole_dur=6,atrim=0:6,asetpts=PTS-STARTPTS[a1]'), graph);
});

test('concat accepts EXACTLY 8 clips (the documented maximum); 9 is rejected 422', () => {
  const spec = validated(concatSpec({ normalize: true }, 8)); // normalize forces the re-encode path
  validateConcatStatic(spec); // must NOT throw at the maximum
  const probes = {};
  const parts = {};
  for (let i = 0; i < 8; i++) {
    probes[`input${i}`] = probeOf(rawVideoProbe({ duration: 2, width: 640, height: 360, audio: null }));
    parts[`input${i}`] = { path: `/in/${i}.mp4` };
  }
  const plan = planConcat(spec, probes);
  assert.equal(plan.outputDurationSeconds, 16);
  const args = buildConcatFilterArgs(spec, probes, plan, { parts, outputPath: '/o.mp4' });
  assert.equal(args.filter((a) => a === '-i').length, 8, 'all 8 clips become ffmpeg inputs');
  assert.ok(args[args.indexOf('-filter_complex') + 1].includes('concat=n=8:v=1:a=1'));
  // one more clip is over the contract limit
  assert422(() => validateConcatStatic(validated(concatSpec({}, 9))), /1 to 8 inputs \(got 9\)/);
});

test('concat single input (trim/speed edit use case): concat filter with n=1 keeps the recipe uniform', () => {
  const spec = validated(concatSpec({ inputs: [{ source_part: 'input0', trim_start_seconds: 1, trim_end_seconds: 3 }] }, 1));
  const probes = { input0: probeOf(rawVideoProbe({ duration: 10, width: 640, height: 360 })) };
  const plan = planConcat(spec, probes);
  assert.equal(plan.copy, false);
  const args = buildConcatFilterArgs(spec, probes, plan, {
    parts: { input0: { path: '/a' } },
    outputPath: '/o.mp4',
  });
  const graph = args[args.indexOf('-filter_complex') + 1];
  assert.ok(graph.includes('concat=n=1:v=1:a=1[vcat][acat]'), graph);
  assert.ok(graph.startsWith('[0:v]trim=start=1:end=3,setpts=PTS-STARTPTS,'), graph);
});

// ---- frame ---------------------------------------------------------------------------
test('frame spec: exactly one input; format/width/at_seconds validated 400-style', () => {
  assert.equal(rejected({ operation: 'frame', options: {}, inputs: [{ name: 'input0' }, { name: 'input1' }] }).code, 'INVALID_SPEC');
  assert.equal(rejected(frameSpec({ at_seconds: -1 })).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(rejected(frameSpec({ image_format: 'webp' })).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(rejected(frameSpec({ width: 8 })).code, 'VALUE_OUT_OF_RANGE');
  const v = validated(frameSpec({}));
  assert.equal(v.options.imageFormat, 'jpeg', 'default jpeg');
  assert.equal(v.options.atSeconds, null);
  assert.equal(v.options.width, null);
});

test('frame timestamp: default = the MIDDLE of the video; beyond-end (and exactly-at-end) clamps to duration-0.1; short clips floor at 0', () => {
  assert.equal(resolveFrameTimestamp(null, 8), 4, 'default middle');
  assert.equal(resolveFrameTimestamp(undefined, 5), 2.5);
  assert.equal(resolveFrameTimestamp(3, 8), 3, 'explicit within range is untouched');
  assert.equal(resolveFrameTimestamp(99, 8), 7.9, 'beyond the end clamps to duration-0.1');
  assert.equal(resolveFrameTimestamp(8, 8), 7.9, 'exactly at the end has no frame either - clamped');
  assert.equal(resolveFrameTimestamp(1, 0.05), 0, 'clamp never goes negative');
  assert.equal(resolveFrameTimestamp(null, null), 0, 'unknown duration defaults to the first frame');
  assert.equal(resolveFrameTimestamp(3, null), 3, 'unknown duration cannot clamp an explicit timestamp');
});

test('frame argv: jpeg default gets -q:v 2, png does not; width adds scale=<w>:-2 - pinned', () => {
  const jpeg = buildFrameArgs(validated(frameSpec({})), '/in/v.mp4', 4, '/out/out.jpg');
  assert.deepEqual(jpeg, [
    '-nostdin', '-y', '-loglevel', 'error',
    '-ss', '4', '-i', '/in/v.mp4',
    '-frames:v', '1',
    '-q:v', '2',
    '/out/out.jpg',
  ]);
  const png = buildFrameArgs(validated(frameSpec({ image_format: 'png', width: 480 })), '/in/v.mp4', 1.25, '/out/out.png');
  assert.deepEqual(png, [
    '-nostdin', '-y', '-loglevel', 'error',
    '-ss', '1.25', '-i', '/in/v.mp4',
    '-frames:v', '1',
    '-vf', 'scale=480:-2',
    '/out/out.png',
  ]);
});

test('frame output naming/mime: jpg or png extension; concat/overlay are always mp4', () => {
  assert.equal(mediaOutputExtension(validated(frameSpec({}))), 'jpg');
  assert.equal(mediaOutputExtension(validated(frameSpec({ image_format: 'png' }))), 'png');
  assert.equal(mediaOutputExtension(validated(concatSpec({}))), 'mp4');
  assert.equal(mediaOutputExtension(validated(overlaySpec({}))), 'mp4');
  assert.equal(MEDIA_MIME_TYPES.jpg, 'image/jpeg');
  assert.equal(MEDIA_MIME_TYPES.png, 'image/png');
});

test('frame timestamp response header literal (cross-layer contract with MediaRenderService)', () => {
  assert.equal(MEDIA_HEADER_TIMESTAMP, 'X-Media-Timestamp-Seconds');
});

// ---- overlay ---------------------------------------------------------------------------
test('overlay spec: needs a video-role and an image-role input; bounds validated 400-style', () => {
  const missing = rejected({ operation: 'overlay', options: {}, inputs: [{ name: 'input0', role: 'video' }, { name: 'input1' }] });
  assert.equal(missing.code, 'INVALID_SPEC');
  assert.match(missing.error, /video.*image/);
  assert.equal(rejected(overlaySpec({ position: 'middle' })).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(rejected(overlaySpec({ margin_px: -1 })).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(rejected(overlaySpec({ width_percent: 0 })).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(rejected(overlaySpec({ width_percent: 101 })).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(rejected(overlaySpec({ opacity: 1.5 })).code, 'VALUE_OUT_OF_RANGE');
  assert.equal(rejected(overlaySpec({ start_seconds: 5, end_seconds: 5 })).code, 'VALUE_OUT_OF_RANGE');
  const v = validated(overlaySpec({}));
  assert.deepEqual(v.options, {
    position: 'bottom_right', marginPx: 24, widthPercent: 15, opacity: 1, startSeconds: null, endSeconds: null,
  });
});

const OVERLAY_PATHS = {
  parts: { input0: { path: '/in/v.mp4' }, input1: { path: '/in/logo.png' } },
  outputPath: '/out/out.mp4',
};

function overlayFilterOf(spec, videoInfo) {
  const args = buildOverlayArgs(spec, videoInfo, OVERLAY_PATHS);
  return { args, filter: args[args.indexOf('-filter_complex') + 1] };
}

test('overlay argv: defaults (bottom_right, 15% of the VIDEO width, opaque, whole video, audio copied) - pinned', () => {
  const videoInfo = transformProbeJson(rawVideoProbe({ duration: 10, width: 1280, height: 720 }));
  const args = buildOverlayArgs(validated(overlaySpec({})), videoInfo, OVERLAY_PATHS);
  assert.deepEqual(args, [
    '-nostdin', '-y', '-loglevel', 'error',
    '-i', '/in/v.mp4',
    '-i', '/in/logo.png',
    // 15% of 1280 = 192; opaque -> no rgba/colorchannelmixer; no window -> no enable
    '-filter_complex', '[1:v]scale=192:-1[ovl];[0:v][ovl]overlay=x=W-w-24:y=H-h-24,format=yuv420p[vout]',
    '-map', '[vout]',
    '-map', '0:a:0', '-c:a', 'copy',
    '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '20',
    '-movflags', '+faststart',
    '/out/out.mp4',
  ]);
});

test('overlay anchors: each of the five positions maps to its x/y expressions (margin from the two nearest edges)', () => {
  const videoInfo = transformProbeJson(rawVideoProbe({}));
  const at = (position) => overlayFilterOf(validated(overlaySpec({ position, margin_px: 10 })), videoInfo).filter;
  assert.ok(at('top_left').includes('overlay=x=10:y=10,'));
  assert.ok(at('top_right').includes('overlay=x=W-w-10:y=10,'));
  assert.ok(at('bottom_left').includes('overlay=x=10:y=H-h-10,'));
  assert.ok(at('bottom_right').includes('overlay=x=W-w-10:y=H-h-10,'));
  assert.ok(at('center').includes('overlay=x=(W-w)/2:y=(H-h)/2,'), 'center ignores the margin');
});

test('overlay opacity < 1 inserts format=rgba + colorchannelmixer on the IMAGE chain', () => {
  const videoInfo = transformProbeJson(rawVideoProbe({ width: 1000 }));
  const { filter } = overlayFilterOf(validated(overlaySpec({ opacity: 0.6, width_percent: 20 })), videoInfo);
  assert.ok(filter.startsWith('[1:v]scale=200:-1,format=rgba,colorchannelmixer=aa=0.6[ovl];'), filter);
});

test('overlay timing window: between(t,S,E); start-only ends at the probed duration; end-only starts at 0; absent = no enable', () => {
  const videoInfo = transformProbeJson(rawVideoProbe({ duration: 10 }));
  const filterFor = (opts) => overlayFilterOf(validated(overlaySpec(opts)), videoInfo).filter;
  assert.ok(filterFor({ start_seconds: 2, end_seconds: 5 }).includes(":enable='between(t,2,5)'"));
  assert.ok(filterFor({ start_seconds: 2 }).includes(":enable='between(t,2,10)'"));
  assert.ok(filterFor({ end_seconds: 5 }).includes(":enable='between(t,0,5)'"));
  assert.ok(!filterFor({}).includes('enable='));
});

test('overlay on a SILENT video: no audio map, no -c:a copy', () => {
  const videoInfo = transformProbeJson(rawVideoProbe({ audio: null }));
  const { args } = overlayFilterOf(validated(overlaySpec({})), videoInfo);
  assert.ok(!args.includes('0:a:0'));
  assert.ok(!args.includes('-c:a'));
});

test('overlay image gate: ffprobe format_name tokens classify stills vs real media', () => {
  assert.equal(isImageProbeFormat('png_pipe'), true);
  assert.equal(isImageProbeFormat('image2'), true, 'jpeg files probe as image2');
  assert.equal(isImageProbeFormat('jpeg_pipe'), true);
  assert.equal(isImageProbeFormat('webp_pipe'), true);
  assert.equal(isImageProbeFormat('mov,mp4,m4a,3gp,3g2,mj2'), false, 'a video container is NOT an image');
  assert.equal(isImageProbeFormat('mp3'), false);
  assert.equal(isImageProbeFormat(''), false);
  assert.equal(isImageProbeFormat(undefined), false);
});

// ---- runMediaOperation glue (injected exec - no real children) --------------------------
function fakeMediaExec({ fullProbeByFile = {}, durationsByFile = {}, onFfmpeg } = {}) {
  const calls = [];
  return {
    calls,
    ffmpegCalls: () => calls.filter(([bin]) => bin === 'ffmpeg'),
    async exec(bin, args, opts) {
      calls.push([bin, args, opts]);
      if (bin === 'ffprobe') {
        const file = args[args.length - 1];
        if (args.includes('-show_streams')) {
          const raw = fullProbeByFile[file];
          if (raw instanceof Error) throw raw;
          return { stdout: JSON.stringify(raw || { format: {}, streams: [] }) };
        }
        const d = durationsByFile[file] !== undefined ? durationsByFile[file] : 3;
        if (d instanceof Error) throw d;
        return { stdout: JSON.stringify({ format: { duration: String(d) } }) };
      }
      if (onFfmpeg) return onFfmpeg(args, opts);
      return { stdout: '' };
    },
  };
}

test('runMediaOperation concat: the STATIC 422 preflight fires before ANY ffprobe/ffmpeg spawn', async () => {
  const fake = fakeMediaExec({});
  const spec = validated(concatSpec({ inputs: [{ source_part: 'input0', speed: 3 }, { source_part: 'input1' }] }));
  await assert.rejects(
    () => runMediaOperation(spec, { input0: '/a', input1: '/b' }, '/w', { execFileAsync: fake.exec }),
    (err) => err.status === 422 && err.code === 'FFMPEG_FAILED' && /speed/.test(err.message),
  );
  assert.equal(fake.calls.length, 0, 'no child process for a spec doomed by the static preflight');
});

test('runMediaOperation concat COPY path: writes the demuxer list file and runs -c copy under the small fixed budget', async () => {
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-v2-ut-'));
  try {
    const fake = fakeMediaExec({
      fullProbeByFile: { '/in/a.mp4': rawVideoProbe({ duration: 4 }), '/in/b.mp4': rawVideoProbe({ duration: 6 }) },
      durationsByFile: { [path.join(workDir, 'out.mp4')]: 10 },
    });
    const spec = validated(concatSpec({}));
    const out = await runMediaOperation(spec, { input0: '/in/a.mp4', input1: '/in/b.mp4' }, workDir, { execFileAsync: fake.exec });
    const [, args, opts] = fake.ffmpegCalls()[0];
    assert.equal(args[args.indexOf('-f') + 1], 'concat', 'concat demuxer, not filter_complex');
    assert.ok(args.includes('copy'));
    assert.equal(opts.timeout, MEDIA_TIMEOUT_MIN_MS, 'copy path uses the small fixed budget');
    const listText = await fs.readFile(path.join(workDir, 'concat.txt'), 'utf8');
    assert.equal(listText, "file '/in/a.mp4'\nfile '/in/b.mp4'\n");
    assert.equal(out.mime, 'video/mp4');
    assert.equal(out.durationSeconds, 10, 'output duration re-probed for the response header');
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

test('runMediaOperation concat RE-ENCODE path: filter_complex + budget scaled with the effective OUTPUT duration', async () => {
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-v2-ut-'));
  try {
    const fake = fakeMediaExec({
      fullProbeByFile: {
        '/in/a.mp4': rawVideoProbe({ duration: 30 }),
        '/in/b.mp4': rawVideoProbe({ duration: 30, width: 640, height: 360 }), // dims differ -> re-encode
      },
    });
    const spec = validated(concatSpec({}));
    await runMediaOperation(spec, { input0: '/in/a.mp4', input1: '/in/b.mp4' }, workDir, { execFileAsync: fake.exec });
    const [, args, opts] = fake.ffmpegCalls()[0];
    assert.ok(args.includes('-filter_complex'));
    assert.equal(opts.timeout, computeMediaTimeoutMs(60), '60s of output buys the scaled budget');
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

test('runMediaOperation frame: default-middle -ss, null duration, timestampSeconds echoed, image/jpeg mime', async () => {
  const fake = fakeMediaExec({ durationsByFile: { '/in/v.mp4': 8 } });
  const out = await runMediaOperation(validated(frameSpec({})), { input0: '/in/v.mp4' }, '/w', { execFileAsync: fake.exec });
  const [, args] = fake.ffmpegCalls()[0];
  assert.equal(args[args.indexOf('-ss') + 1], '4', 'default = probed duration / 2');
  assert.equal(out.kind, 'file');
  assert.equal(out.mime, 'image/jpeg');
  assert.ok(out.path.endsWith('out.jpg'));
  assert.equal(out.timestampSeconds, 4);
  assert.equal(out.durationSeconds, null, 'a still has no duration - the header must be omitted');
});

test('runMediaOperation overlay: a NON-image part is rejected 422 naming the part, before ffmpeg', async () => {
  const fake = fakeMediaExec({
    fullProbeByFile: {
      '/in/v.mp4': rawVideoProbe({ duration: 10 }),
      '/in/not-an-image.mp4': rawVideoProbe({ duration: 5 }),
    },
  });
  const spec = validated(overlaySpec({}));
  await assert.rejects(
    () => runMediaOperation(spec, { input0: '/in/v.mp4', input1: '/in/not-an-image.mp4' }, '/w', { execFileAsync: fake.exec }),
    (err) => err.status === 422 && err.code === 'FFMPEG_FAILED' && /input1.*is not an image/.test(err.message),
  );
  assert.equal(fake.ffmpegCalls().length, 0);
});

test('runMediaOperation overlay: scales from the PROBED video width, copies audio, budget from the video duration', async () => {
  const fake = fakeMediaExec({
    fullProbeByFile: {
      '/in/v.mp4': rawVideoProbe({ duration: 40, width: 1280 }),
      '/in/logo.png': {
        format: { format_name: 'png_pipe' },
        streams: [{ codec_type: 'video', codec_name: 'png', width: 64, height: 64, r_frame_rate: '25/1' }],
      },
    },
  });
  const out = await runMediaOperation(validated(overlaySpec({})), { input0: '/in/v.mp4', input1: '/in/logo.png' }, '/w', { execFileAsync: fake.exec });
  const [, args, opts] = fake.ffmpegCalls()[0];
  const filter = args[args.indexOf('-filter_complex') + 1];
  assert.ok(filter.includes('scale=192:-1'), '15% of the probed 1280px video width');
  assert.ok(args.includes('0:a:0') && args.includes('copy'), 'audio stream-copied');
  assert.equal(opts.timeout, computeMediaTimeoutMs(40), 'budget follows the v1 per-input-second pattern');
  assert.equal(out.mime, 'video/mp4');
});

// ---- integration: real ffmpeg -----------------------------------------------------------
const ffmpegOnPath = (() => {
  try {
    return spawnSync('ffmpeg', ['-version'], { timeout: 5000 }).status === 0
      && spawnSync('ffprobe', ['-version'], { timeout: 5000 }).status === 0;
  } catch (_) {
    return false;
  }
})();

const FIXTURE_TIMEOUT = 120000;

async function makeClipNoAudio(outPath, { duration = 2, size = '320x240', rate = 10, src = 'testsrc' } = {}) {
  await execFileAsync('ffmpeg', [
    '-nostdin', '-y', '-loglevel', 'error',
    '-f', 'lavfi', '-i', `${src}=duration=${duration}:size=${size}:rate=${rate}`,
    '-pix_fmt', 'yuv420p', outPath,
  ], { timeout: FIXTURE_TIMEOUT });
}

async function makeClipWithAudio(outPath, { duration = 2, size = '320x240', rate = 10 } = {}) {
  await execFileAsync('ffmpeg', [
    '-nostdin', '-y', '-loglevel', 'error',
    '-f', 'lavfi', '-i', `testsrc=duration=${duration}:size=${size}:rate=${rate}`,
    '-f', 'lavfi', '-i', `sine=frequency=440:duration=${duration}`,
    '-c:a', 'aac', '-pix_fmt', 'yuv420p', '-shortest', outPath,
  ], { timeout: FIXTURE_TIMEOUT });
}

async function probeOut(filePath) {
  const { stdout } = await execFileAsync('ffprobe', buildProbeArgs(filePath),
    { timeout: 15000, maxBuffer: 16 * 1024 * 1024 });
  return transformProbeJson(JSON.parse(stdout));
}

test('integration: concat FAST COPY of two identical-spec clips - duration is the sum and the stream is copied (same codec/fps)', async (t) => {
  if (!ffmpegOnPath) {
    t.skip('ffmpeg/ffprobe not on PATH (present in the sidecar image and on dev hosts)');
    return;
  }
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-v2-it-'));
  try {
    const a = path.join(workDir, 'a.mp4');
    const b = path.join(workDir, 'b.mp4');
    await makeClipNoAudio(a, { duration: 2 });
    await fs.copyFile(a, b);

    const spec = validated(concatSpec({}));
    // prove against REAL probes that the fast copy path is what this input selects
    const probes = {
      input0: await probeFullInfo(execFileAsync, 'ffprobe', a, 'input0'),
      input1: await probeFullInfo(execFileAsync, 'ffprobe', b, 'input1'),
    };
    assert.equal(concatCopyEligible(spec, probes), true, 'identical clips + pure defaults must take the copy path');

    const out = await runMediaOperation(spec, { input0: a, input1: b }, workDir, {});
    assert.equal(out.mime, 'video/mp4');
    assert.ok(out.durationSeconds > 3.6 && out.durationSeconds < 4.4,
      `copy concat of 2s+2s should be ~4s, got ${out.durationSeconds}`);
    const probe = await probeOut(out.path);
    assert.equal(probe.has_video, true);
    assert.equal(probe.video.codec, 'h264', 'same codec as the (copied) source');
    assert.equal(probe.video.width, 320);
    assert.equal(probe.video.fps, 10, 'copy keeps the source fps, no re-encode to a different rate');
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

test('integration: concat RE-ENCODE of different-resolution clips (one WITHOUT audio) - plays, duration = sum, aac stereo audio present', async (t) => {
  if (!ffmpegOnPath) {
    t.skip('ffmpeg/ffprobe not on PATH (present in the sidecar image and on dev hosts)');
    return;
  }
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-v2-it-'));
  try {
    const a = path.join(workDir, 'a.mp4'); // 320x240 with aac audio
    const b = path.join(workDir, 'b.mp4'); // 640x360, NO audio -> silent bed
    await makeClipWithAudio(a, { duration: 2 });
    await makeClipNoAudio(b, { duration: 2, size: '640x360', src: 'testsrc2' });

    const out = await runMediaOperation(validated(concatSpec({})), { input0: a, input1: b }, workDir, {});
    assert.equal(out.mime, 'video/mp4');
    assert.ok(out.durationSeconds > 3.5 && out.durationSeconds < 4.6,
      `2s+2s effectives should be ~4s, got ${out.durationSeconds}`);
    const probe = await probeOut(out.path);
    assert.equal(probe.has_video, true);
    assert.equal(probe.video.codec, 'h264');
    assert.equal(probe.video.width, 320, 'canvas defaults to the FIRST input\'s dimensions');
    assert.equal(probe.video.height, 240);
    assert.equal(probe.has_audio, true, 'the audio-less clip got a silent bed, so the output has one continuous track');
    assert.equal(probe.audio.codec, 'aac');
    assert.equal(probe.audio.channels, 2, 'the stereo aformat pin holds on the concat path too');
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

test('integration: concat CROSSFADE of two clips - duration = sum - transition', async (t) => {
  if (!ffmpegOnPath) {
    t.skip('ffmpeg/ffprobe not on PATH (present in the sidecar image and on dev hosts)');
    return;
  }
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-v2-it-'));
  try {
    const a = path.join(workDir, 'a.mp4');
    const b = path.join(workDir, 'b.mp4');
    await makeClipNoAudio(a, { duration: 3 });
    await makeClipNoAudio(b, { duration: 3, src: 'testsrc2' });

    const spec = validated(concatSpec({ transition: 'crossfade', transition_seconds: 1 }));
    const out = await runMediaOperation(spec, { input0: a, input1: b }, workDir, {});
    assert.ok(out.durationSeconds > 4.6 && out.durationSeconds < 5.4,
      `3s+3s with a 1s crossfade should be ~5s, got ${out.durationSeconds}`);
    const probe = await probeOut(out.path);
    assert.equal(probe.has_video, true);
    assert.equal(probe.has_audio, true, 'silent beds crossfade into one audio track');
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

test('integration: frame default-middle - valid jpeg bytes, timestamp = duration/2 (+-0.1), no duration', async (t) => {
  if (!ffmpegOnPath) {
    t.skip('ffmpeg/ffprobe not on PATH (present in the sidecar image and on dev hosts)');
    return;
  }
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-v2-it-'));
  try {
    const v = path.join(workDir, 'v.mp4');
    await makeClipWithAudio(v, { duration: 2 });

    const out = await runMediaOperation(validated(frameSpec({})), { input0: v }, workDir, {});
    assert.equal(out.kind, 'file');
    assert.equal(out.mime, 'image/jpeg');
    assert.equal(out.durationSeconds, null);
    assert.ok(Math.abs(out.timestampSeconds - 1) <= 0.1,
      `middle of a 2s clip should be ~1s, got ${out.timestampSeconds}`);
    const bytes = await fs.readFile(out.path);
    assert.equal(bytes[0], 0xff);
    assert.equal(bytes[1], 0xd8, 'JPEG SOI magic');
    assert.ok(bytes.length > 500, 'a real frame, not an empty file');
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

test('integration REGRESSION (short-audio desync): a clip whose audio ends ~1s before its video stays A/V-aligned through re-encode concat', async (t) => {
  // Named for the audit finding: pre-fix, an audio track shorter than its video was
  // neither padded nor clamped, so concat spliced the next clip's audio EARLY - the
  // output's audio stream ended ~1s short (and drifted). Post-fix the audio stream
  // must last the full output.
  if (!ffmpegOnPath) {
    t.skip('ffmpeg/ffprobe not on PATH (present in the sidecar image and on dev hosts)');
    return;
  }
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-v2-it-'));
  try {
    const a = path.join(workDir, 'short-audio.mp4'); // 3s video, ~2s audio (NO -shortest)
    const b = path.join(workDir, 'b.mp4');           // different resolution -> re-encode path
    await execFileAsync('ffmpeg', [
      '-nostdin', '-y', '-loglevel', 'error',
      '-f', 'lavfi', '-i', 'testsrc=duration=3:size=320x240:rate=10',
      '-f', 'lavfi', '-i', 'sine=frequency=440:duration=2',
      '-c:a', 'aac', '-pix_fmt', 'yuv420p', a,
    ], { timeout: FIXTURE_TIMEOUT });
    await makeClipNoAudio(b, { duration: 2, size: '640x360', src: 'testsrc2' });

    const out = await runMediaOperation(validated(concatSpec({})), { input0: a, input1: b }, workDir, {});
    assert.ok(Math.abs(out.durationSeconds - 5) <= 0.15,
      `3s + 2s effectives must give ~5s, got ${out.durationSeconds}`);
    // the AUDIO STREAM itself must last the whole output, not stop where the short
    // source audio (2s) + the second clip's bed (2s) would have (=4s pre-fix)
    const { stdout } = await execFileAsync('ffprobe', buildProbeArgs(out.path),
      { timeout: 15000, maxBuffer: 16 * 1024 * 1024 });
    const raw = JSON.parse(stdout);
    const audioStream = raw.streams.find((s) => s.codec_type === 'audio');
    assert.ok(audioStream, 'output must have an audio stream');
    const audioDur = Number(audioStream.duration);
    assert.ok(audioDur >= 4.8,
      `the padded audio must last the full ~5s output, got ${audioDur}s (4s = the pre-fix desync)`);
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

test('integration (route): the frame response OMITS X-Media-Duration-Seconds and carries X-Media-Timestamp-Seconds', async (t) => {
  // Exercises the server.js header branch itself (spawned on a private port), not just
  // runFrame's return shape.
  if (!ffmpegOnPath) {
    t.skip('ffmpeg/ffprobe not on PATH (present in the sidecar image and on dev hosts)');
    return;
  }
  const { spawn } = require('node:child_process');
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-v2-it-'));
  const port = 18100 + Math.floor(Math.random() * 800);
  const child = spawn(process.execPath, ['server.js'], {
    cwd: path.join(__dirname, '..'),
    env: { ...process.env, PORT: String(port) },
    stdio: ['ignore', 'ignore', 'pipe'],
  });
  let childStderr = '';
  child.stderr.on('data', (d) => { childStderr = (childStderr + d).slice(-2000); });
  try {
    const v = path.join(workDir, 'v.mp4');
    await makeClipWithAudio(v, { duration: 2 });

    let up = false;
    for (let i = 0; i < 100 && !up; i++) {
      try {
        const r = await fetch(`http://127.0.0.1:${port}/internal/health`);
        up = r.ok;
      } catch (_) { /* not listening yet */ }
      if (!up) await new Promise((resolve) => { setTimeout(resolve, 100); });
    }
    assert.ok(up, `renderer must come up on :${port} (stderr: ${childStderr})`);

    const form = new FormData();
    form.append('spec', JSON.stringify({
      operation: 'frame',
      options: {},
      inputs: [{ name: 'input0', role: 'input' }],
    }));
    form.append('input0', new Blob([await fs.readFile(v)], { type: 'video/mp4' }), 'v.mp4');
    const res = await fetch(`http://127.0.0.1:${port}/internal/media`, { method: 'POST', body: form });
    assert.equal(res.status, 200, await res.clone().text().catch(() => ''));
    assert.equal(res.headers.get('x-media-operation'), 'frame');
    assert.equal(res.headers.get('x-media-duration-seconds'), null,
      'a still has no duration - the header must be omitted at the route level');
    const ts = Number(res.headers.get('x-media-timestamp-seconds'));
    assert.ok(Math.abs(ts - 1) <= 0.1, `middle of a 2s clip should be ~1s, got ${ts}`);
    assert.equal(res.headers.get('content-type'), 'image/jpeg');
    const bytes = Buffer.from(await res.arrayBuffer());
    assert.equal(bytes[0], 0xff);
    assert.equal(bytes[1], 0xd8, 'JPEG SOI magic');
  } finally {
    child.kill();
    await fs.rm(workDir, { recursive: true, force: true });
  }
});

test('integration: overlay of an alpha png bottom_right - output valid, duration unchanged, audio preserved', async (t) => {
  if (!ffmpegOnPath) {
    t.skip('ffmpeg/ffprobe not on PATH (present in the sidecar image and on dev hosts)');
    return;
  }
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lc-media-v2-it-'));
  try {
    const v = path.join(workDir, 'v.mp4');
    const logo = path.join(workDir, 'logo.png');
    await makeClipWithAudio(v, { duration: 2 });
    await execFileAsync('ffmpeg', [
      '-nostdin', '-y', '-loglevel', 'error',
      '-f', 'lavfi', '-i', 'color=c=red@0.5:s=64x64,format=rgba',
      '-frames:v', '1', logo,
    ], { timeout: FIXTURE_TIMEOUT });

    const out = await runMediaOperation(validated(overlaySpec({})), { input0: v, input1: logo }, workDir, {});
    assert.equal(out.mime, 'video/mp4');
    assert.ok(out.durationSeconds > 1.7 && out.durationSeconds < 2.4,
      `overlay must not change the 2s duration, got ${out.durationSeconds}`);
    const probe = await probeOut(out.path);
    assert.equal(probe.has_video, true);
    assert.equal(probe.video.codec, 'h264');
    assert.equal(probe.video.width, 320, 'video dimensions untouched');
    assert.equal(probe.has_audio, true, 'audio stream-copied through the overlay');
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
});
