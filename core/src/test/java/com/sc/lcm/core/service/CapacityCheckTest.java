package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.Node;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapacityCheckTest {

    private static Node node() {
        Node n = new Node("n1", 8, 32, 2, "A100");
        n.setGpuTopology("NVLink");
        n.setNvlinkBandwidthGbps(600);
        return n;
    }

    private static Job job(int cpu, long mem, int gpu, String model) {
        return new Job("j", cpu, mem, gpu, model);
    }

    @Test
    void fitsOnEmptyNodeAndRejectsSecondEightCoreJob() {
        Node n = node();
        Job first = job(8, 8, 0, null);
        assertNull(CapacityCheck.mismatch(n, CapacityCheck.Usage.NONE, first));
        String reason = CapacityCheck.mismatch(n, CapacityCheck.Usage.of(List.of(first)), job(8, 8, 0, null));
        assertTrue(reason.contains("insufficient CPU"), reason);
    }

    @Test
    void rejectsUnavailableGpuModelAndTopology() {
        Node n = node();
        assertTrue(CapacityCheck.mismatch(n, CapacityCheck.Usage.NONE, job(1, 1, 1, "H100")).contains("GPU model"));
        assertNull(CapacityCheck.mismatch(n, CapacityCheck.Usage.NONE, job(1, 1, 1, "a100")));
        Job needsBandwidth = job(1, 1, 0, null);
        needsBandwidth.setMinNvlinkBandwidthGbps(900);
        assertNotNull(CapacityCheck.mismatch(n, CapacityCheck.Usage.NONE, needsBandwidth));
        Job needsNvlink = job(1, 1, 0, null);
        needsNvlink.setRequiresNvlink(true);
        n.setGpuTopology("PCIe");
        assertTrue(CapacityCheck.mismatch(n, CapacityCheck.Usage.NONE, needsNvlink).contains("NVLink"));
    }

    @Test
    void rejectsMemoryAndGpuCountOverflow() {
        Node n = node();
        assertTrue(CapacityCheck.mismatch(n, CapacityCheck.Usage.NONE, job(1, 64, 0, null)).contains("memory"));
        assertTrue(CapacityCheck.mismatch(n, CapacityCheck.Usage.NONE, job(1, 1, 3, null)).contains("GPUs"));
    }

    @Test
    void rejectsNegativeResourceRequests() {
        assertNotNull(CapacityCheck.mismatch(node(), CapacityCheck.Usage.NONE, job(-1, 1, 0, null)));
        assertNotNull(CapacityCheck.mismatch(node(), CapacityCheck.Usage.NONE, job(1, -1, 0, null)));
        assertNotNull(CapacityCheck.mismatch(node(), CapacityCheck.Usage.NONE, job(1, 1, -1, null)));
    }

    @Test
    void overflowCannotTurnOccupiedMemoryIntoFreeCapacity() {
        CapacityCheck.Usage used = CapacityCheck.Usage.of(
                List.of(job(0, Long.MAX_VALUE, 0, null), job(0, 1, 0, null)));
        assertNotNull(CapacityCheck.mismatch(node(), used, job(1, 1, 0, null)));
    }

    @Test
    void legacyNegativeRequestsCannotCreditCapacity() {
        CapacityCheck.Usage used = CapacityCheck.Usage.of(List.of(job(-8, 0, 0, null), job(8, 0, 0, null)));
        assertNotNull(CapacityCheck.mismatch(node(), used, job(8, 1, 0, null)));
    }
}
