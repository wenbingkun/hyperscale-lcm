package com.sc.lcm.core.service;

import ai.timefold.solver.core.api.solver.SolverJob;
import ai.timefold.solver.core.api.solver.SolverManager;
import com.sc.lcm.core.domain.LcmSolution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.ExecutionException;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 对 Timefold SolverManager 的薄封装，便于在调度链路中复用并稳定测试替身。
 */
@ApplicationScoped
public class LcmSolverFacade {

    @Inject
    SolverManager<LcmSolution, UUID> solverManager;

    public void solveAsync(LcmSolution solution, Consumer<LcmSolution> bestSolutionConsumer) {
        solverManager.solve(
                UUID.randomUUID(),
                ignored -> solution,
                bestSolutionConsumer);
    }

    public LcmSolution solveBlocking(LcmSolution solution) {
        SolverJob<LcmSolution, UUID> solverJob = solverManager.solve(
                UUID.randomUUID(),
                ignored -> solution,
                ignored -> {
                });
        try {
            return solverJob.getFinalBestSolution();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for solver result", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to obtain solver result", e);
        }
    }
}
