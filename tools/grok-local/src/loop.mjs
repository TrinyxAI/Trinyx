import { pathToFileURL } from 'node:url';

/**
 * Scores how cleanly a clip loops.
 *
 * Getting a seamless loop is the hard part of this format and it is largely
 * luck, which is why the workflow is "generate several rolls, keep the best".
 * Judging that by eye across a batch is the tedious step, so it is measured
 * here instead: decode the first and last frame, compare them, and return a
 * number the caller can sort on.
 *
 * Decoding happens in the browser that is already running, so there is no
 * ffmpeg dependency to install on every machine this tool is copied to.
 */
export async function scoreLoop(context, videoPath, { sampleWidth = 64 } = {}) {
  const page = await context.newPage();
  try {
    const fileUrl = pathToFileURL(videoPath).href;
    await page.setContent('<body style="margin:0;background:#000"><video id="v" muted></video></body>');

    const result = await page.evaluate(async ({ url, w }) => {
      const video = document.getElementById('v');
      video.src = url;

      await new Promise((resolve, reject) => {
        video.onloadeddata = resolve;
        video.onerror = () => reject(new Error('video failed to decode'));
        setTimeout(() => reject(new Error('video load timed out')), 20000);
      });

      const duration = video.duration;
      if (!Number.isFinite(duration) || duration <= 0) throw new Error('unknown duration');

      const height = Math.max(1, Math.round(w * (video.videoHeight / video.videoWidth)));
      const canvas = document.createElement('canvas');
      canvas.width = w;
      canvas.height = height;
      const ctx = canvas.getContext('2d', { willReadFrequently: true });

      const grab = (time) => new Promise((resolve, reject) => {
        const onSeeked = () => {
          video.removeEventListener('seeked', onSeeked);
          ctx.drawImage(video, 0, 0, w, height);
          resolve(ctx.getImageData(0, 0, w, height).data);
        };
        video.addEventListener('seeked', onSeeked);
        setTimeout(() => reject(new Error(`seek to ${time}s timed out`)), 15000);
        video.currentTime = time;
      });

      // Not the very last frame: encoders routinely emit a duplicated or
      // half-rendered final frame, which would flatter the score.
      const first = await grab(0);
      const last = await grab(Math.max(0, duration - 0.08));

      // Mean absolute difference on luminance. Chroma noise moves a lot between
      // frames without being visible, so comparing RGB directly is noisier.
      let total = 0;
      const pixels = w * height;
      for (let i = 0; i < first.length; i += 4) {
        const lumaA = 0.299 * first[i] + 0.587 * first[i + 1] + 0.114 * first[i + 2];
        const lumaB = 0.299 * last[i] + 0.587 * last[i + 1] + 0.114 * last[i + 2];
        total += Math.abs(lumaA - lumaB);
      }
      const meanDiff = total / pixels;
      return { meanDiff, duration, width: video.videoWidth, height: video.videoHeight };
    }, { url: fileUrl, w: sampleWidth });

    // 0 diff is a perfect loop; ~40 luma levels apart is a hard cut. The
    // mapping is linear over that range and clamped, so the number is a
    // ranking aid within a batch, not a physical measurement.
    const score = Math.round(Math.max(0, Math.min(100, 100 - (result.meanDiff / 40) * 100)));
    return {
      loop_score: score,
      loop_mean_diff: Number(result.meanDiff.toFixed(2)),
      duration_seconds: Number(result.duration.toFixed(2)),
      resolution: `${result.width}x${result.height}`,
      verdict: score >= 85 ? 'clean' : score >= 65 ? 'usable' : 'visible seam',
    };
  } finally {
    await page.close().catch(() => {});
  }
}
