
package com.pso;

import org.cloudbus.cloudsim.brokers.DatacenterBroker;
import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostSimple;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.schedulers.vm.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelFull;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class LoadBalancerTest {
    private static final long CLOUDLET_LENGTH_MI = 5_000;
    private static final double MAX_REASONABLE_TIME = 1_000_000.0;

    private double runTestSim(
            int numV,
            int numC,
            Algorithms.LoadBalancer algo) {

        assertNotNull(algo, "Algorithm cannot be null.");
        assertTrue(numV > 0, "VM count must be positive.");
        assertTrue(numC >= 0, "Cloudlet count must be non-negative.");

        System.out.printf(
                "[TEST START] algorithm=%s | vms=%d | cloudlets=%d%n",
                algo.getName(), numV, numC);

        CloudSim sim = new CloudSim();

        // 1. One processing element per VM.
        List<Pe> pes = new ArrayList<>(numV);
        for (int i = 0; i < numV; i++) {
            pes.add(new PeSimple(1000));
        }

        // 2. Create host and datacenter.
        Host host = new HostSimple(16_384, 1_000_000, 1_000_000, pes)
                .setVmScheduler(new VmSchedulerTimeShared());

        new DatacenterSimple(sim, List.of(host));

        // 3. Create VMs using the same resource settings as Main.java.
        List<Vm> vms = new ArrayList<>(numV);
        for (int i = 0; i < numV; i++) {
            Vm vm = new VmSimple(i, 1000, 1)
                    .setRam(512)
                    .setBw(1000)
                    .setSize(10_000)
                    .setCloudletScheduler(new CloudletSchedulerTimeShared());

            vms.add(vm);
        }

        // 4. Create cloudlets.
        List<Cloudlet> cloudlets = new ArrayList<>(numC);
        for (int i = 0; i < numC; i++) {
            Cloudlet cloudlet = new CloudletSimple(i, CLOUDLET_LENGTH_MI, 1)
                    .setFileSize(300)
                    .setOutputSize(300)
                    // FIX: Full CPU utilization only, not full RAM and bandwidth.
                    .setUtilizationModelCpu(new UtilizationModelFull());

            cloudlets.add(cloudlet);
        }

        // 5. Submit entities.
        DatacenterBroker broker = new DatacenterBrokerSimple(sim);
        broker.submitVmList(vms);
        broker.submitCloudletList(cloudlets);

        // 6. Generate and validate mapping.
        double[] lengths = cloudlets.stream()
                .mapToDouble(Cloudlet::getLength)
                .toArray();

        double[] mips = vms.stream()
                .mapToDouble(Vm::getMips)
                .toArray();

        int[] mapping = numC == 0
                ? new int[0]
                : algo.map(numC, numV, lengths, mips);

        assertNotNull(mapping, "Mapping must not be null.");
        assertEquals(numC, mapping.length, "Mapping length must equal cloudlet count.");

        int[] assignedPerVm = new int[numV];

        for (int i = 0; i < numC; i++) {
            assertTrue(
                    mapping[i] >= 0 && mapping[i] < numV,
                    "Invalid VM index " + mapping[i] + " for cloudlet " + i);

            assignedPerVm[mapping[i]]++;
            broker.bindCloudletToVm(cloudlets.get(i), vms.get(mapping[i]));
        }

        System.out.println("[TEST MAPPING] " + algo.getName()
                + " assignedPerVm=" + java.util.Arrays.toString(assignedPerVm));

        // 7. Run simulation.
        sim.start();

        // 8. Verify execution before accepting the test.
        assertEquals(numV, broker.getVmCreatedList().size(),
                "Not all requested VMs were created.");

        assertEquals(numC, broker.getCloudletFinishedList().size(),
                "Not all cloudlets were reported as finished.");

        for (Cloudlet cloudlet : cloudlets) {
            assertTrue(cloudlet.isFinished(),
                    "Cloudlet " + cloudlet.getId() + " did not finish.");

            double start = cloudlet.getExecStartTime();
            double finish = cloudlet.getFinishTime();

            assertTrue(Double.isFinite(start),
                    "Non-finite start time for cloudlet " + cloudlet.getId());

            assertTrue(Double.isFinite(finish),
                    "Non-finite finish time for cloudlet " + cloudlet.getId());

            assertTrue(finish >= start,
                    "Cloudlet " + cloudlet.getId() + " finishes before it starts.");

            assertTrue(finish < MAX_REASONABLE_TIME,
                    "Suspiciously huge finish time for cloudlet "
                            + cloudlet.getId() + ": " + finish);

            System.out.printf(
                    Locale.US,
                    "[TEST CLOUDLET] id=%d | vm=%d | start=%.4f | finish=%.4f%n",
                    cloudlet.getId(),
                    cloudlet.getVm().getId(),
                    start,
                    finish);
        }

        double makespan = cloudlets.stream()
                .mapToDouble(Cloudlet::getFinishTime)
                .max()
                .orElse(0.0);

        assertTrue(Double.isFinite(makespan), "Makespan must be finite.");
        assertTrue(makespan >= 0.0, "Makespan cannot be negative.");

        if (numC > 0) {
            assertTrue(makespan > 0.0,
                    "A non-empty workload should have a positive makespan.");
        }

        System.out.printf(
                Locale.US,
                "[TEST PASS] algorithm=%s | vmsCreated=%d | cloudletsFinished=%d | makespan=%.4f%n",
                algo.getName(),
                broker.getVmCreatedList().size(),
                broker.getCloudletFinishedList().size(),
                makespan);

        return makespan;
    }

    @Test
    void test1_Environment() {
        assertTrue(runTestSim(3, 10, new Algorithms.PSO(42)) < MAX_REASONABLE_TIME);
    }

    @Test
    void test2_Baselines() {
        assertTrue(runTestSim(4, 20, new Algorithms.RoundRobin()) < MAX_REASONABLE_TIME);
        assertTrue(runTestSim(4, 20, new Algorithms.RandomLB(42)) < MAX_REASONABLE_TIME);
        assertTrue(runTestSim(4, 20, new Algorithms.LeastLoaded()) < MAX_REASONABLE_TIME);
    }

    @Test
    void test3_EdgeCases() {
        assertTrue(runTestSim(1, 1, new Algorithms.PSO(42)) < MAX_REASONABLE_TIME);
        assertTrue(runTestSim(2, 10, new Algorithms.PSO(42)) < MAX_REASONABLE_TIME);

        // Empty cloudlet list should complete without attempting any mapping.
        assertEquals(0.0, runTestSim(2, 0, new Algorithms.PSO(42)), 1e-9);
    }

    @Test
    void test4_PSOProducesValidMapping() {
        int numC = 20;
        int numV = 5;

        double[] lengths = new double[numC];
        double[] mips = new double[numV];

        java.util.Arrays.fill(lengths, CLOUDLET_LENGTH_MI);
        java.util.Arrays.fill(mips, 1000);

        int[] mapping = new Algorithms.PSO(42).map(numC, numV, lengths, mips);

        assertNotNull(mapping, "PSO mapping must not be null.");
        assertEquals(numC, mapping.length,
                "PSO mapping length must match cloudlet count.");

        int[] assignedPerVm = new int[numV];

        for (int vmIndex : mapping) {
            assertTrue(vmIndex >= 0 && vmIndex < numV,
                    "PSO returned invalid VM index: " + vmIndex);
            assignedPerVm[vmIndex]++;
        }

        System.out.println("[PSO MAPPING TEST PASS] assignedPerVm="
                + java.util.Arrays.toString(assignedPerVm));
    }
}
