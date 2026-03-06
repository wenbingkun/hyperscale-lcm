import React from 'react';
import { motion } from 'framer-motion';

interface GradientButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
    children: React.ReactNode;
    variant?: 'primary' | 'secondary';
    loading?: boolean;
}

export const GradientButton: React.FC<GradientButtonProps> = ({
    children,
    className = '',
    variant = 'primary',
    loading = false,
    disabled,
    ...props
}) => {
    // We remove hover:scale-105 and active:scale-95 from baseStyle because we use framer-motion now
    const baseStyle = "px-6 py-2 rounded-lg font-medium transition-colors duration-200 disabled:opacity-50 disabled:cursor-not-allowed shadow-lg";

    const variants = {
        primary: "bg-gradient-to-r from-cyan-500 to-blue-600 text-white hover:shadow-cyan-500/25",
        secondary: "bg-white/10 hover:bg-white/20 text-white border border-white/10 hover:border-white/20"
    };

    // Cast props to any because motion.button has slight type collision with standard button props
    return (
        <motion.button
            whileHover={{ scale: 1.05 }}
            whileTap={{ scale: 0.95 }}
            className={`${baseStyle} ${variants[variant]} ${className}`}
            disabled={disabled || loading}
            {...(props as any)}
        >
            {loading ? (
                <span className="flex items-center gap-2">
                    <svg className="animate-spin h-4 w-4" viewBox="0 0 24 24">
                        <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
                        <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                    </svg>
                    Processing...
                </span>
            ) : children}
        </motion.button>
    );
};
