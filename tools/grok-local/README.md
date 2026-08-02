# grok-local

Drives Grok Imagine from a browser **you** are signed into, on **your** machine, and exposes it
over a small HTTP API. Jobs are queued and run one at a time, and finished clips are served back
as files.

Runs on Windows, macOS and Linux. Moving it to another computer is a `git clone` plus
`npm install`; nothing is written inside the checkout.

## Why it runs locally

grok.com serves a hard Cloudflare block to datacenter IPs, so the same setup on a server never
reaches the site. A normal residential connection does. There is no way around that from a
datacenter, and this tool does not try: it just runs where a browser is already welcome.

## Install

```bash
cd tools/grok-local
npm install          # also downloads the Chromium build Playwright uses
npm start
```

First run prints a bearer token once and stores it in `~/.grok-local/config.json`.
A browser window opens: **sign in to grok.com once**. The session lives in
`~/.grok-local/profile` and survives restarts and reboots.

Confirm it took:

```bash
curl http://127.0.0.1:8477/health
# {"browser_ok":true,"signed_in":true,...}
```

## Options

```bash
npm start                  # 127.0.0.1:8477, visible window
npm start -- --lan         # listen on every interface, so another machine can drive it
npm start -- --port 9000
npm start -- --headless    # hide the window (sign in first with it visible)
npm start -- --help
```

`--lan` is what you want when a second computer, or something else on your network, should
submit jobs. It is off by default because anyone who can reach the port and holds the token
controls a browser that is signed into your account.

## API

Everything except `/health` needs `Authorization: Bearer <token>`.

| Method | Path | What it does |
|---|---|---|
| GET | `/health` | browser reachable, session still signed in, queue depth |
| POST | `/jobs` | `{prompt, duration_seconds?}` → `202 {job_id, position}` |
| GET | `/jobs` | queue counters |
| GET | `/jobs/:id` | one job: status, timings, result or error |
| GET | `/jobs/:id/download` | the finished mp4 |
| GET | `/debug/snapshot` | screenshot + html of the current page |
| GET | `/debug/calibrate` | whether each selector still matches the live UI |

```bash
TOKEN=$(node -e "console.log(require(require('os').homedir()+'/.grok-local/config.json').token)")

curl -X POST http://127.0.0.1:8477/jobs \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"prompt":"a red fox in a snowy pine forest at dawn","duration_seconds":6}'

curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8477/jobs/<job_id>
curl -H "Authorization: Bearer $TOKEN" -o clip.mp4 http://127.0.0.1:8477/jobs/<job_id>/download
```

## Queue behaviour

One job at a time, on purpose: the site drives a single tab, so a second worker would interleave
clicks in the same session and corrupt both jobs. Waiting jobs are capped (`429` beyond that)
rather than piling up unbounded.

State is written to `~/.grok-local/state/jobs.json` after every transition. A job that was
running when the process died is re-queued once on restart, because its half-typed prompt and
its tab are gone and there is nothing to resume from.

## Construction ASMR

`src/prompts.mjs` holds ten concepts split into two families, because they are
not the same problem.

**Cyclical** gestures return the scene to its starting state, so they can loop:
orbital sander, mixer paddle, wet saw running, trowel polishing, dripping
trowel. These carry the loop constraints.

**Additive** gestures change the scene permanently: brick laying, concrete pour,
tile setting, grout wiping, wood planing. Adding or consuming material is on
every "never loops" list, and no prompting fixes it, because the last frame
cannot match the first when the wall is one brick taller. These are written as
one complete gesture and closed in the edit instead.

Construction is additive by nature, so getting this split wrong is the main way
to waste generations on this format.

```bash
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8477/concepts

curl -X POST http://127.0.0.1:8477/concepts/orbital_sander/batch   -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json'   -d '{"rolls":3,"aspect_ratio":"9:16","duration_seconds":6}'
```

### Why the prompts are shaped this way

**Forbid, do not just ask.** Asking for a loop is not enough; the clauses that
matter are the negative ones. Every prompt pins the framing and forbids camera
movement, new objects entering or leaving, lighting or exposure drift, and cuts
or fades. Camera movement alone is the single most common cause of a broken
loop, which is why framing is stated as locked rather than as a move.

**One subject, one action.** Three sub-actions in a prompt is the most common
cause of mush, so each concept describes a single gesture.

**Name the soundscape.** Grok Imagine generates audio natively from the prompt
text. On ASMR the sound is the product, so leaving it unspecified wastes the one
thing this model does better than the alternatives.

**The format is a short loop, not a long clip.** A 5-6s video that loops gets
rewatched several times per viewer, and that rewatch drives distribution.
Durations default to 6s for that reason, not because of a model limit.

**A batch is the unit of work.** Sending one string three times returns three
near-identical near-misses, so each roll nudges framing or pace.

### Loop scoring

Cyclical clips are measured: the first and last frames are decoded and compared
on luminance, and the result carries

```json
{ "loop_score": 91, "loop_mean_diff": 3.6, "verdict": "clean", "loop_applicable": true }
```

`clean` at 85+, `usable` at 65+, `visible seam` below. Sort a batch and keep the
top roll. Additive clips carry `loop_applicable: false` and no score: reporting a
seam on a gesture that legitimately ended somewhere else would be correct and
useless.

Decoding runs in the browser that is already open, so there is no ffmpeg to
install on each machine. Scoring is best-effort: a clip that generated fine is
never reported as failed because the measurement could not run.

### Closing a loop the model cannot close

There is no end-frame parameter: the API takes `image` (first frame only) or
`reference_images`, and the two are exclusive. The technique that works is to
generate the loop in two halves.

1. Generate clip 1 from image S. It ends on some frame E.
2. Extract E and S **from the rendered clip**, not from the source file - the
   model modifies its input image, so the original no longer matches.
   ```bash
   ffmpeg -sseof -3 -i clip1.mp4 -update 1 -q:v 1 end_frame.png
   ffmpeg -ss 0    -i clip1.mp4 -vframes 1 -q:v 1 start_frame.png
   ```
3. Generate clip 2 starting from E, with S passed as a reference image to pull
   it back toward the starting composition.
4. Concatenate, dropping the last frame of each clip. Without that, E and S each
   appear twice and the seam stutters.

For additive gestures, a short crossfade from the tail back to the head is the
practical close, at the cost of a fraction of a second of softness.

## What this does not change

Generation is metered by the site against your account, not by this tool. Daily caps, throttling
at peak hours and the occasional drop to a lower resolution all still apply. Running the same
prompts through an API key instead avoids the caps but is billed per second of output.

## When the site changes

`SELECTORS` at the top of `src/browser.mjs` is the only part coupled to the upstream UI. It is
role and text based rather than CSS paths, so small redesigns tend to survive. When one breaks:

```bash
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8477/debug/calibrate
```

Any entry reporting `matches: 0` is the one to fix. `/debug/snapshot` writes a screenshot and the
page HTML to `~/.grok-local/output/` so you can see what the page actually looks like.
