
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

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Main {
    private static final long CLOUDLET_LENGTH_MI = 10_000;
    private static final int RUNS = 30;
    private static final boolean DEBUG =
            Boolean.parseBoolean(System.getProperty("pso.debug", "false"));

    private static final int[][] LOADS = {
            {20, 4},
            {50, 8},
            {100, 12}
    };

    public static void main(String[] args) throws IOException {
        System.out.println("[START] PSO load-balancer experiments");

        int successfulRuns = 0;
        int expectedRuns = LOADS.length * RUNS * 4;

        try (FileWriter csv = new FileWriter("results.csv")) {
            csv.write("Scenario,Algorithm,Run,Makespan,AvgResponseTime,Imbalance,Throughput\n");

            for (int[] load : LOADS) {
                int numC = load[0];
                int numV = load[1];
                String scenario = numC + "C_" + numV + "V";

                for (int run = 1; run <= RUNS; run++) {
                    long seed = run * 100L;

                    Algorithms.LoadBalancer[] algorithms = {
                            new Algorithms.PSO(seed),
                            new Algorithms.RoundRobin(),
                            new Algorithms.RandomLB(seed),
                            new Algorithms.LeastLoaded()
                    };

                    for (Algorithms.LoadBalancer algo : algorithms) {
                        double[] metrics =
                                runSimulation(numC, numV, CLOUDLET_LENGTH_MI, algo);

                        csv.write(String.format(
                                Locale.US,
                                "%s,%s,%d,%.6f,%.6f,%.6f,%.6f%n",
                                scenario, algo.getName(), run,
                                metrics[0], metrics[1], metrics[2], metrics[3]));

                        csv.flush(); // FIX: Preserve completed rows if a later run fails.
                        successfulRuns++;

                        System.out.printf(
                                Locale.US,
                                "[SIM OK] %s | run=%d | algorithm=%s | makespan=%.4f | avgResponse=%.4f | imbalance=%.4f | throughput=%.6f%n",
                                scenario, run, algo.getName(),
                                metrics[0], metrics[1], metrics[2], metrics[3]);
                    }
                }
            }
        }

        if (successfulRuns != expectedRuns) {
            throw new IllegalStateException(
                    "[FATAL] Expected " + expectedRuns
                            + " successful simulations, got " + successfulRuns);
        }

        System.out.println("[DONE] " + successfulRuns
                + "/" + expectedRuns + " simulations completed.");
        System.out.println("[OUTPUT] results.csv");
    }

    static double[] runSimulation(
            int numC,
            int numV,
            long cloudletLength,
            Algorithms.LoadBalancer algo) {

        if (numC < 0 || numV <= 0 || cloudletLength <= 0) {
            throw new IllegalArgumentException(
                    "Cloudlets must be non-negative; VMs and cloudlet length must be positive.");
        }
        if (algo == null) {
            throw new IllegalArgumentException("Algorithm cannot be null.");
        }

        System.out.printf(
                "[SIM START] algorithm=%s | cloudlets=%d | VMs=%d | lengthMI=%d%n",
                algo.getName(), numC, numV, cloudletLength);

        CloudSim sim = new CloudSim();

        // 1. Create one 1000-MIPS PE per VM.
        List<Pe> pes = new ArrayList<>(numV);
        for (int i = 0; i < numV; i++) {
            pes.add(new PeSimple(1000));
        }

        // 2. Create the host and datacenter.
        Host host = new HostSimple(16_384, 1_000_000, 1_000_000, pes)
                .setVmScheduler(new VmSchedulerTimeShared());

        new DatacenterSimple(sim, List.of(host));

        // 3. Create VMs.
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
            Cloudlet cloudlet = new CloudletSimple(i, cloudletLength, 1)
                    .setFileSize(300)
                    .setOutputSize(300)
                    // FIX: Full CPU utilization only; avoid full RAM/BW utilization.
                    .setUtilizationModelCpu(new UtilizationModelFull());

            cloudlets.add(cloudlet);
        }

        // 5. Submit VMs and cloudlets.
        DatacenterBroker broker = new DatacenterBrokerSimple(sim);
        broker.submitVmList(vms);
        broker.submitCloudletList(cloudlets);

        // 6. Ask the selected algorithm to map cloudlets to VMs.
        double[] cloudletLengths = cloudlets.stream()
                .mapToDouble(Cloudlet::getLength)
                .toArray();

        double[] vmMips = vms.stream()
                .mapToDouble(Vm::getMips)
                .toArray();

        int[] mapping = numC == 0
                ? new int[0]
                : algo.map(numC, numV, cloudletLengths, vmMips);

        if (mapping == null || mapping.length != numC) {
            throw new IllegalStateException(
                    "[MAPPING ERROR] " + algo.getName()
                            + " returned an invalid mapping.");
        }

        int[] assignedPerVm = new int[numV];

        for (int i = 0; i < numC; i++) {
            if (mapping[i] < 0 || mapping[i] >= numV) {
                throw new IllegalStateException(
                        "[MAPPING ERROR] Cloudlet " + i
                                + " mapped to invalid VM index " + mapping[i]);
            }

            assignedPerVm[mapping[i]]++;
            broker.bindCloudletToVm(cloudlets.get(i), vms.get(mapping[i]));
        }

        if (DEBUG) {
            System.out.println("[MAPPING] " + algo.getName()
                    + " assignedPerVm=" + java.util.Arrays.toString(assignedPerVm));
        }

        // 7. Run simulation.
        sim.start();

        // 8. Verify VMs and cloudlets before calculating metrics.
        int createdVms = broker.getVmCreatedList().size();
        int finishedCloudlets = broker.getCloudletFinishedList().size();

        if (createdVms != numV) {
            throw new IllegalStateException(
                    "[VM CREATION ERROR] Expected " + numV
                            + " VMs, created " + createdVms);
        }

        if (finishedCloudlets != numC) {
            printDiagnostics(algo, numC, numV, broker, cloudlets, assignedPerVm);

            throw new IllegalStateException(
                    "[CLOUDLET COMPLETION ERROR] Expected " + numC
                            + " finished cloudlets, got " + finishedCloudlets);
        }

        for (Cloudlet cloudlet : cloudlets) {
            double start = cloudlet.getExecStartTime();
            double finish = cloudlet.getFinishTime();

            if (!cloudlet.isFinished()
                    || !Double.isFinite(start)
                    || !Double.isFinite(finish)
                    || finish < start
                    || finish > 1_000_000) {

                printDiagnostics(algo, numC, numV, broker, cloudlets, assignedPerVm);

                throw new IllegalStateException(
                        "[INVALID EXECUTION] Cloudlet " + cloudlet.getId()
                                + " | finished=" + cloudlet.isFinished()
                                + " | start=" + start
                                + " | finish=" + finish);
            }
        }

        // 9. Calculate metrics only after validating execution.
        double makespan = cloudlets.stream()
                .mapToDouble(Cloudlet::getFinishTime)
                .max()
                .orElse(0.0);

        // All cloudlets are submitted at simulation time zero.
        // Their finish time is therefore their response/turnaround time.
        double avgResponseTime = cloudlets.stream()
                .mapToDouble(Cloudlet::getFinishTime)
                .average()
                .orElse(0.0);

        double throughput = makespan > 0.0 ? numC / makespan : 0.0;

        // Workload imbalance: population standard deviation of estimated CPU service
        // time (cloudlet MI / assigned VM MIPS) across VMs.
        double[] vmLoads = new double[numV];

        for (int i = 0; i < numC; i++) {
            int vmIndex = mapping[i];
            vmLoads[vmIndex] +=
                    cloudlets.get(i).getLength() / vms.get(vmIndex).getMips();
        }

        double mean = 0.0;
        for (double load : vmLoads) {
            mean += load;
        }
        mean /= numV;

        double variance = 0.0;
        for (double load : vmLoads) {
            double delta = load - mean;
            variance += delta * delta;
        }

        double imbalance = Math.sqrt(variance / numV);

        if (!Double.isFinite(makespan)
                || !Double.isFinite(avgResponseTime)
                || !Double.isFinite(imbalance)
                || !Double.isFinite(throughput)) {
            throw new IllegalStateException(
                    "[METRIC ERROR] Non-finite metric for " + algo.getName());
        }

        if (DEBUG) {
            for (Cloudlet cloudlet : cloudlets) {
                System.out.printf(
                        Locale.US,
                        "[CLOUDLET] id=%d | vm=%d | start=%.4f | finish=%.4f | cpuTime=%.4f%n",
                        cloudlet.getId(),
                        cloudlet.getVm().getId(),
                        cloudlet.getExecStartTime(),
                        cloudlet.getFinishTime(),
                        cloudlet.getActualCpuTime());
            }
        }

        return new double[]{
                makespan, avgResponseTime, imbalance, throughput
        };
    }

    private static void printDiagnostics(
            Algorithms.LoadBalancer algo,
            int expectedCloudlets,
            int expectedVms,
            DatacenterBroker broker,
            List<Cloudlet> cloudlets,
            int[] assignedPerVm) {

        System.err.println("[DIAGNOSTIC FAILURE] algorithm=" + algo.getName()
                + " | expectedCloudlets=" + expectedCloudlets
                + " | finishedCloudlets=" + broker.getCloudletFinishedList().size()
                + " | expectedVMs=" + expectedVms
                + " | createdVMs=" + broker.getVmCreatedList().size());

        System.err.println("[DIAGNOSTIC] assignedPerVm="
                + java.util.Arrays.toString(assignedPerVm));

        for (Cloudlet cloudlet : cloudlets) {
            String vmId = cloudlet.getVm() == Vm.NULL
                    ? "NULL"
                    : Long.toString(cloudlet.getVm().getId());

            System.err.printf(
                    Locale.US,
                    "[CLOUDLET STATE] id=%d | status=%s | vm=%s | start=%.4f | finish=%.4f%n",
                    cloudlet.getId(),
                    cloudlet.getStatus(),
                    vmId,
                    cloudlet.getExecStartTime(),
                    cloudlet.getFinishTime());
        }
    }
}
