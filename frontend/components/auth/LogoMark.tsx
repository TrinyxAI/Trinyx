/**
 * Trinyx brand mark rendered from the official raster asset.
 */
export function LogoMark({ className = '' }: { className?: string }) {
  return (
    <img
      src="/branding/trinyx-mark-96.png"
      alt=""
      width={96}
      height={96}
      draggable={false}
      className={`${className} object-contain`}
      aria-hidden="true"
    />
  );
}
