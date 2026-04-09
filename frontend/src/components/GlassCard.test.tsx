import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { GlassCard } from './GlassCard';

describe('GlassCard', () => {
    it('renders the optional title, content, and custom classes', () => {
        const { container } = render(
            <GlassCard title="Cluster Overview" className="custom-shell">
                <div>Card body</div>
            </GlassCard>,
        );

        expect(screen.getByText('Cluster Overview')).toBeInTheDocument();
        expect(screen.getByText('Card body')).toBeInTheDocument();
        expect(container.firstChild).toHaveClass('glass-panel', 'custom-shell');
    });
});
