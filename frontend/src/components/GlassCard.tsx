import React from 'react';
import { motion, type HTMLMotionProps } from 'framer-motion';
import { cn } from '../utils';



export interface GlassCardProps extends Omit<HTMLMotionProps<"div">, "children"> {
  children: React.ReactNode;
  className?: string;
  title?: string;
}

export const GlassCard: React.FC<GlassCardProps> = ({ children, className = '', title, ...motionProps }) => {
  return (
    <motion.div
      className={cn(
        "glass-panel p-6 relative overflow-hidden group transition-all duration-300",
        "hover:shadow-[0_0_30px_rgba(0,242,255,0.1)] hover:border-white/20",
        className
      )}
      {...motionProps}
    >
      {/* Glow Effect Gradient */}
      <div className="absolute top-0 right-0 w-[300px] h-[300px] bg-primary/5 blur-[100px] -translate-y-1/2 translate-x-1/2 rounded-full pointer-events-none" />

      {title && (
        <h3 className="text-lg font-semibold mb-4 text-gray-200 border-b border-white/5 pb-2 relative z-10 flex items-center justify-between">
          {title}
        </h3>
      )}
      <div className="relative z-10">
        {children}
      </div>
    </motion.div>
  );
};
