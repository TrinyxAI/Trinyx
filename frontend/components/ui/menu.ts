/**
 * The app's action menu: a floating surface listing icon + label rows, opened
 * from a control (a `+`, a `...`, a chevron).
 *
 * <p><b>Why these strings live here.</b> The app already draws this menu in
 * several places - the side panel's tab menu, the chat header's run menu, the
 * skill tree's row menus - and every one of them wrote the surface and the row
 * out by hand. A new menu written from scratch is how the eighth one ends up
 * with a different radius, a different hover, or (the case that produced this
 * module) `PopoverContent`'s stock `bg-popover`, a token this theme does not
 * define, so the menu rendered with NO background at all and the page showed
 * through it. Reach for these before writing a menu.
 *
 * <p>Both follow the radius ladder in this folder's README: `rounded-2xl` for a
 * floating surface that holds controls, `rounded-xl` for the controls in it.
 */

/**
 * The floating surface, for a `PopoverContent`. Pair it with a width: the menu
 * is as wide as its labels need, and nothing here guesses that.
 *
 * <p>`bg-theme-primary` is not decoration - it is the whole reason to use this
 * rather than `PopoverContent`'s default (see above).
 */
export const menuSurfaceClass =
  'p-2 rounded-2xl bg-theme-primary border border-theme shadow-lg';

/**
 * One row: icon, then label. Give the icon `h-4 w-4 flex-shrink-0` and the label
 * nothing - the row already sizes and aligns it.
 *
 * <p>The label WRAPS rather than truncating. Menus elsewhere truncate because
 * their labels are one or two words; a row here can carry a whole sentence
 * ("Generate an image, video or sound", which overruns any sane menu width in
 * four of the six locales), and a row that has to be hovered to be read is no
 * better than the icon a menu was opened to explain.
 */
export const menuItemClass =
  'w-full flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors ' +
  'text-sm text-left leading-snug whitespace-normal text-theme-primary ' +
  'hover:bg-gray-100 dark:hover:bg-gray-800 ' +
  'disabled:opacity-60 disabled:pointer-events-none disabled:cursor-not-allowed';
