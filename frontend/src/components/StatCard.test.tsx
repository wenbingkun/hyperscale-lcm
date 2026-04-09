import { render, screen } from '@testing-library/react';
import { Cpu } from 'lucide-react';
import { describe, expect, it } from 'vitest';
import { StatCard } from './StatCard';

describe('StatCard', () => {
    it('renders the metric value, unit, subtext, and icon', () => {
        const { container } = render(
            <StatCard
                title="GPU Nodes"
                value="128"
                unit="nodes"
                subtext="12 newly added"
                trend="up"
                accentColor="green"
                icon={<Cpu data-testid="cpu-icon" />}
            />,
        );

        expect(screen.getByText('GPU Nodes')).toBeInTheDocument();
        expect(screen.getByText('128')).toBeInTheDocument();
        expect(screen.getByText('nodes')).toBeInTheDocument();
        expect(screen.getByText('12 newly added')).toBeInTheDocument();
        expect(screen.getByTestId('cpu-icon')).toBeInTheDocument();
        expect(container.querySelector('svg')).toBeTruthy();
    });
});
