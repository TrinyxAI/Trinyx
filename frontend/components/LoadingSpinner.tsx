'use client';
import React from 'react';

interface LoadingSpinnerProps {
  size?: 'xs' | 'sm' | 'md' | 'lg' | 'xl';
  className?: string;
  text?: string;
}

const LoadingSpinner = React.memo<LoadingSpinnerProps>(({
  size = 'md',
  className = '',
  text,
}) => {
  const sizeClasses = {
    xs: 'w-4 h-4',
    sm: 'w-6 h-6',
    md: 'w-8 h-8',
    lg: 'w-12 h-12',
    xl: 'w-16 h-16',
  };

  return (
    <div className={`flex items-center ${className}`}>
      <div className={`${sizeClasses[size]} animate-spin`}>
        <img
          src="/branding/trinyx-mark.png"
          alt="Loading"
          width={160}
          height={160}
          draggable={false}
          className="h-full w-full object-contain"
        />
      </div>
      {text && <span className="ml-3 text-sm text-gray-600 dark:text-gray-300">{text}</span>}
    </div>
  );
});

LoadingSpinner.displayName = 'LoadingSpinner';

export default LoadingSpinner;
