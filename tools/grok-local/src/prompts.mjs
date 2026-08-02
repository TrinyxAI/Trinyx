/**
 * Construction-ASMR prompt library.
 *
 * Two families, because they are not the same problem:
 *
 *   CYCLICAL  the gesture returns the scene to its starting state (a sander
 *             orbiting, a blade spinning, a paddle turning). These can loop, so
 *             they carry the loop constraint and the negative clauses below.
 *
 *   ADDITIVE  the gesture changes the scene permanently (a brick is laid,
 *             concrete is poured, grout is wiped away). Adding or consuming
 *             material is on every "never loops" list there is, and no amount
 *             of prompting fixes it: the last frame cannot match the first when
 *             the wall is one brick taller. These are written as ONE complete
 *             gesture instead, and closed in the edit rather than by the model.
 *
 * Construction is additive by nature, so mislabelling here is the main way to
 * waste generations on this format.
 *
 * Prompt order is subject -> motion -> framing -> look -> soundscape ->
 * constraints, which is the order the model follows most reliably. One subject,
 * one action: cramming three sub-actions into a prompt is the most common cause
 * of mush.
 *
 * The soundscape is not decoration. Grok Imagine generates audio natively from
 * the prompt text and on ASMR the sound IS the product, so "concrete pouring"
 * gives mush where "thick wet concrete slapping into a wooden form, low
 * splatter" gives ASMR.
 */

/**
 * Camera movement is the first cause of a broken loop, so framing is pinned
 * rather than described as a move. Every other clause here names a thing that
 * would differ between the first and last frame if left unsaid.
 */
const LOOP_CONSTRAINTS = [
  'seamless loop',
  'the motion returns exactly to its starting position',
  'camera holds the identical framing at the start and end',
  'no camera movement',
  'no new objects appearing or leaving the frame',
  'constant lighting, exposure and color throughout',
  'no cuts, no fade to black, no text',
].join(', ');

/** Additive gestures are not looped, so they only need a clean, complete take. */
const ONESHOT_CONSTRAINTS = [
  'one single continuous gesture from start to finish',
  'camera holds the identical framing throughout',
  'no camera movement',
  'constant lighting, exposure and color throughout',
  'no cuts, no fade to black, no text',
].join(', ');

const LOOK = 'macro detail, shallow depth of field, soft directional daylight, crisp material texture';

export const CYCLICAL = [
  {
    id: 'orbital_sander',
    label: 'Orbital sander',
    kind: 'cyclical',
    subject: 'an orbital sander running flat on a bare oak board',
    motion: 'the pad orbiting in place, a steady haze of fine dust lifting and settling',
    framing: 'locked macro top-down shot',
    sound: 'a steady abrasive whirr, fine grit hissing over wood, no music',
  },
  {
    id: 'mixer_paddle',
    label: 'Mixer paddle',
    kind: 'cyclical',
    subject: 'a mixing paddle turning slowly in a bucket of thick grey mortar',
    motion: 'the paddle rotating steadily, the surface folding over in the same repeating swirl',
    framing: 'locked macro top-down shot',
    sound: 'a thick wet churn, the low hum of the motor, mortar slapping the bucket wall, no music',
  },
  {
    id: 'wet_saw_idle',
    label: 'Wet saw running',
    kind: 'cyclical',
    subject: 'a wet saw blade spinning in place with a thin curtain of water running over it',
    motion: 'the blade turning at constant speed, water sheeting off in the same steady pattern',
    framing: 'locked macro side shot',
    sound: 'the even whine of the blade, water hissing on steel, no music',
  },
  {
    id: 'trowel_polish',
    label: 'Trowel polishing',
    kind: 'cyclical',
    subject: 'a steel trowel polishing the same patch of wet plaster back and forth',
    motion: 'the trowel sweeping left and right over the identical area, the surface staying glass-smooth',
    framing: 'locked close side shot',
    sound: 'a smooth wet hiss of steel on plaster, a faint rhythmic grit, no music',
  },
  {
    id: 'drip_trowel',
    label: 'Dripping trowel',
    kind: 'cyclical',
    subject: 'thick white plaster dripping off the edge of a held trowel into a tub below',
    motion: 'drops forming, stretching and falling at a steady rhythm',
    framing: 'locked macro side shot',
    sound: 'slow thick drips landing in a soft pool, no music',
  },
];

export const ADDITIVE = [
  {
    id: 'brick_laying',
    label: 'Brick laying',
    kind: 'additive',
    subject: 'a gloved hand pressing a red clay brick into a bed of wet grey mortar',
    motion: 'the brick settling and the mortar squeezing out along the seam',
    framing: 'locked macro side shot',
    sound: 'wet mortar squelching, a dull scrape of brick on trowel, soft grit, no music',
    step: 'the next brick placed alongside the previous one, the course extending, the wall one row further along',
  },
  {
    id: 'concrete_pour',
    label: 'Concrete pour',
    kind: 'additive',
    subject: 'thick wet concrete pouring into a wooden formwork',
    motion: 'the pour folding over itself in a slow spiral as the level rises',
    framing: 'locked top-down shot',
    sound: 'a heavy wet slap of concrete, a low gritty rumble, no music',
    step: 'the pour continuing, the level rising further up the formwork',
  },
  {
    id: 'tile_setting',
    label: 'Tile setting',
    kind: 'additive',
    subject: 'a white ceramic tile pressed into a bed of freshly combed grey adhesive',
    motion: 'the tile bedding in and flattening the ridges beneath it',
    framing: 'locked macro top-down shot',
    sound: 'a soft suction as the tile beds in, adhesive squelching, no music',
    step: 'the next tile set beside the previous one, the field of tiles growing',
  },
  {
    id: 'grout_wipe',
    label: 'Grout wiping',
    kind: 'additive',
    subject: 'a damp sponge wiping dark grout haze off pale mosaic tiles',
    motion: 'a single arc of the sponge revealing clean tile and crisp grout lines',
    framing: 'locked macro top-down shot',
    sound: 'a wet drag of sponge on ceramic, water squeezing out, no music',
    step: 'the sponge continuing across the next section, more clean tile revealed',
  },
  {
    id: 'wood_plane',
    label: 'Wood planing',
    kind: 'additive',
    subject: 'a hand plane gliding along an oak board',
    motion: 'one continuous ribbon of shaving curling up and falling away',
    framing: 'locked macro side shot',
    sound: 'a crisp slice of blade through grain, the papery rustle of the shaving, no music',
    step: 'the next pass along the board, another shaving curling away, the surface flattening further',
  },
];

export const CONCEPTS = [...CYCLICAL, ...ADDITIVE];

export const ASPECT_RATIOS = { vertical: '9:16', square: '1:1', wide: '16:9' };

export function buildPrompt(concept, { aspectRatio = '9:16', extra = '' } = {}) {
  const constraints = concept.kind === 'cyclical' ? LOOP_CONSTRAINTS : ONESHOT_CONSTRAINTS;
  return [
    concept.subject,
    concept.motion,
    concept.framing,
    ...(extra ? [extra] : []),
    LOOK,
    `audio: ${concept.sound}`,
    constraints,
    aspectRatio,
  ].join(', ');
}

export function findConcept(id) {
  return CONCEPTS.find((c) => c.id === id) ?? null;
}

/**
 * Rolls vary one axis each. Sending the same string N times returns N
 * near-identical near-misses, so a batch that does not vary does not explore.
 * Variants never introduce camera movement, which would defeat the constraints.
 */
const ROLL_VARIANTS = [
  '',
  'slightly wider framing',
  'slower pace, heavier weight to the motion',
  'tighter macro framing',
  'slightly faster pace, crisper contact sounds',
];

export function expandConcept(concept, { rolls = 3, aspectRatio = '9:16', durationSeconds = 6 } = {}) {
  const count = Math.max(1, Math.min(rolls, ROLL_VARIANTS.length));
  return Array.from({ length: count }, (_, i) => ({
    prompt: buildPrompt(concept, { aspectRatio, extra: ROLL_VARIANTS[i] }),
    duration_seconds: durationSeconds,
    concept_id: concept.id,
    kind: concept.kind,
    roll: i + 1,
  }));
}

export function listConcepts() {
  return CONCEPTS.map(({ id, label, kind, sound }) => ({
    id,
    label,
    kind,
    sound,
    loopable: kind === 'cyclical',
  }));
}

/**
 * Prompt for step N of a build sequence.
 *
 * Additive gestures cannot loop, so the way to get a long satisfying clip out of
 * them is progression: each step continues the previous one from its last frame.
 * The prompt therefore describes the INCREMENT, not the whole scene again -
 * restating the setup makes the model restart rather than continue.
 */
export function buildStepPrompt(concept, { extra = '' } = {}) {
  if (!concept.step) return null;
  return [
    concept.step,
    concept.framing,
    ...(extra ? [extra] : []),
    `audio: ${concept.sound}`,
    'continue the same shot, identical framing, no camera movement, constant lighting and color, no cuts',
  ].join(', ');
}

/** Concepts that can be chained into a build sequence. */
export function isSequenceable(concept) {
  return Boolean(concept && concept.kind === 'additive' && concept.step);
}
