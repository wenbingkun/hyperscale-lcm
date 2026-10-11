import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { JobSubmissionForm } from './JobSubmissionForm';
import { fetchDiscoveredDevices, submitJob } from '../api/client';

vi.mock('../api/client', async () => {
    const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
    return {
        ...actual,
        submitJob: vi.fn(),
        fetchDiscoveredDevices: vi.fn(),
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

    it('submits an SSH task against an approved device only', async () => {
        const user = userEvent.setup();
        vi.mocked(fetchDiscoveredDevices).mockResolvedValue([
            { id: 'dev-1', ipAddress: '10.0.0.5', hostname: 'lab-1', discoveryMethod: 'SCAN', discoveredAt: '2026-01-01T00:00:00Z', status: 'APPROVED' },
            { id: 'dev-2', ipAddress: '10.0.0.6', discoveryMethod: 'SCAN', discoveredAt: '2026-01-01T00:00:00Z', status: 'PENDING' },
        ]);

        render(<JobSubmissionForm />);

        await user.selectOptions(screen.getByLabelText('Job Type'), 'ssh');
        await screen.findByRole('option', { name: /lab-1/ });
        expect(screen.queryByRole('option', { name: /10\.0\.0\.6/ })).not.toBeInTheDocument();
        await user.selectOptions(screen.getByLabelText(/Target Device/), 'dev-1');
        await user.click(screen.getByRole('button', { name: /launch job/i }));

        await waitFor(() => expect(mockedSubmitJob).toHaveBeenCalledWith(expect.objectContaining({
            executionType: 'SSH',
            targetDeviceId: 'dev-1',
            task: 'SYSTEM_INFO',
            clusterId: 'default',
        })));
    });
});
