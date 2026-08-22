'use client';
import React, { useMemo, useState } from 'react';

interface LogoAnimateProps {
  size?: 'sm' | 'md' | 'lg' | 'xl' | 'xxl';
  className?: string;
  alwaysPulse?: boolean;
}

const markSources: Record<NonNullable<LogoAnimateProps['size']>, string> = {
  sm: '/branding/trinyx-mark-48.png',
  md: '/branding/trinyx-mark-96.png',
  lg: '/branding/trinyx-mark-96.png',
  xl: '/branding/trinyx-mark-96.png',
  xxl: '/branding/trinyx-mark-192.png',
};

const pulseAnimation = `
  @keyframes trinyxMarkPulse {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.72; }
  }
`;

const LogoAnimate = React.memo<LogoAnimateProps>(({ size = 'md', className = '', alwaysPulse = false }) => {
  const [isHovered, setIsHovered] = useState(false);

  const sizeClasses = useMemo(() => ({
    sm: 'w-8 h-8',
    md: 'w-12 h-12',
    lg: 'w-16 h-16',
    xl: 'w-20 h-20',
    xxl: 'w-35 h-35',
  }), []);

  const withShadow = size === 'lg' || size === 'xl' || size === 'xxl';
  const shouldPulse = isHovered || alwaysPulse;

  return (
    <>
      <style>{pulseAnimation}</style>
      <div
        className={`${sizeClasses[size]} ${className} cursor-pointer transition-all duration-300 ease-in-out`}
        onMouseEnter={() => setIsHovered(true)}
        onMouseLeave={() => setIsHovered(false)}
        style={{ imageRendering: 'auto' }}
      >
        <img
          src={markSources[size]}
          alt="Trinyx"
          width={160}
          height={160}
          draggable={false}
          className="h-full w-full object-contain transition-all duration-300 ease-in-out"
          style={{
            animation: shouldPulse ? 'trinyxMarkPulse 1.5s ease-in-out infinite' : 'none',
            filter: withShadow ? 'drop-shadow(0 0 0.35px currentColor)' : undefined,
          }}
        />
      </div>
    </>
  );
});

LogoAnimate.displayName = 'LogoAnimate';

export default LogoAnimate;
