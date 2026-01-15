package com.sc.lcm.core.domain;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@PlanningSolution
@Getter @Setter @NoArgsConstructor
public class LcmSolution {

    @ProblemFactCollectionProperty
    @ValueRangeProvider(id = "nodeRange")
    private List<Node> nodeList;

    @PlanningEntityCollectionProperty
    private List<Job> jobList;

    @PlanningScore
    private HardSoftScore score;
}
