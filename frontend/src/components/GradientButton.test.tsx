import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { GradientButton } from './GradientButton';

describe('GradientButton', () => {
    it('renders the primary variant and handles clicks', async () => {
        const user = userEvent.setup();
        const onClick = vi.fn();

        render(<GradientButton onClick={onClick}>Deploy</GradientButton>);

        const button = screen.getByRole('button', { name: 'Deploy' });
        expect(button).toHaveClass('from-cyan-500', 'to-blue-600');

        await user.click(button);

        expect(onClick).toHaveBeenCalledTimes(1);
    });

    it('renders the loading state as disabled', () => {
        render(
            <GradientButton variant="secondary" loading>
                Save
            </GradientButton>,
        );

        const button = screen.getByRole('button');
        expect(button).toBeDisabled();
        expect(button).toHaveTextContent('Processing...');
        expect(button).toHaveClass('bg-white/10');
    });
});
