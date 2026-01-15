import React from 'react';

interface GlassCardProps {
  children: React.ReactNode;
  className?: string;
  title?: string;
}

export const GlassCard: React.FC<GlassCardProps> = ({ children, className = '', title }) => {
  return (
    <div className={`glass-panel p-6 ${className}`}>
        {title && (
            <h3 className="text-lg font-semibold mb-4 text-gray-200 border-b border-gray-700 pb-2">
                {title}
            </h3>
        )}
      {children}
    </div>
  );
};
