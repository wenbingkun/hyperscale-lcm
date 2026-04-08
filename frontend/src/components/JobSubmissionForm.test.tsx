import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { JobSubmissionForm } from './JobSubmissionForm';
import { submitJob } from '../api/client';

vi.mock('../api/client', async () => {
    const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
    return {
        ...actual,
        submitJob: vi.fn(),
    };
});

const mockedSubmitJob = vi.mocked(submitJob);

describe('JobSubmissionForm', () => {
    beforeEach(() => {
        mockedSubmitJob.mockResolvedValue(undefined);
    });

    it('submits the filled job request and resets the job name on success', async () => {
        const user = userEvent.setup();

        render(<JobSubmissionForm />);

        const nameInput = screen.getByPlaceholderText('e.g. LLM-Training-v1');
        const [cpuInput, memoryInput, gpuInput] = screen.getAllByRole('spinbutton');

        await user.type(nameInput, 'LLM-Training-v2');
        fireEvent.change(cpuInput, { target: { value: '8' } });
        fireEvent.change(memoryInput, { target: { value: '64' } });
        fireEvent.change(gpuInput, { target: { value: '4' } });
        await user.click(screen.getByRole('button', { name: /launch job/i }));

        await waitFor(() => expect(mockedSubmitJob).toHaveBeenCalledWith({
            name: 'LLM-Training-v2',
            cpuCores: 8,
            memoryGb: 64,
            gpuCount: 4,
        }));

        await waitFor(() => expect(nameInput).toHaveValue(''));
        expect(screen.getByRole('button', { name: /submitted/i })).toBeInTheDocument();
    });

    it('generates a fallback job name when the user leaves the name blank', async () => {
        const user = userEvent.setup();
        vi.spyOn(Date, 'now').mockReturnValue(1700000000000);

        render(<JobSubmissionForm />);

        await user.click(screen.getByRole('button', { name: /launch job/i }));

        await waitFor(() => expect(mockedSubmitJob).toHaveBeenCalledWith({
            name: 'Job-1700000000000',
            cpuCores: 4,
            memoryGb: 16,
            gpuCount: 1,
        }));
    });
});
