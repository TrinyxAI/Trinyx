/**
 * Trinyx brand mark rendered from the official raster asset.
 */
export function LogoMark({ className = '' }: { className?: string }) {
  return (
    <img
      src="/branding/trinyx-mark.png"
      alt=""
      width={160}
      height={160}
      draggable={false}
      className={`${className} object-contain`}
      aria-hidden="true"
    />
  );
}
