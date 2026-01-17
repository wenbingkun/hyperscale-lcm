import React from 'react';
import { GlassCard } from './GlassCard';
import { ArrowUpRight, ArrowDownRight, Activity } from 'lucide-react';

interface StatCardProps {
    title: string;
    value: string;
    unit?: string;
    subtext?: string;
    trend?: 'up' | 'down' | 'neutral';
    icon?: React.ReactNode;
    accentColor?: 'cyan' | 'green' | 'yellow' | 'purple';
}

export const StatCard: React.FC<StatCardProps> = ({
    title,
    value,
    unit,
    subtext,
    trend,
    icon,
    accentColor = 'cyan'
}) => {

    const getAccentClass = () => {
        switch (accentColor) {
            case 'cyan': return 'text-cyan-400 from-cyan-400 to-blue-500';
            case 'green': return 'text-green-400 from-green-400 to-emerald-500';
            case 'yellow': return 'text-yellow-400 from-yellow-400 to-orange-500';
            case 'purple': return 'text-purple-400 from-purple-400 to-pink-500';
            default: return 'text-white';
        }
    };

    return (
        <GlassCard className="glass-card-hover h-full">
            <div className="flex justify-between items-start mb-2">
                <span className="text-gray-400 text-sm font-medium uppercase tracking-wider">{title}</span>
                {icon && <div className={`text-${accentColor}-400 opacity-80`}>{icon}</div>}
            </div>

            <div className="flex items-baseline gap-2 mb-2">
                <span className={`text-4xl font-bold bg-clip-text text-transparent bg-gradient-to-r ${getAccentClass()}`}>
                    {value}
                </span>
                {unit && <span className="text-gray-500 font-medium">{unit}</span>}
            </div>

            {subtext && (
                <div className="flex items-center gap-2 text-sm text-gray-400">
                    {trend === 'up' && <ArrowUpRight size={14} className="text-green-400" />}
                    {trend === 'down' && <ArrowDownRight size={14} className="text-red-400" />}
                    {trend === 'neutral' && <Activity size={14} className="text-blue-400" />}
                    <span>{subtext}</span>
                </div>
            )}
        </GlassCard>
    );
};
