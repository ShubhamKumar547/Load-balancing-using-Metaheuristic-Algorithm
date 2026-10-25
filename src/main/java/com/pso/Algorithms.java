package com.pso;

import java.util.Random;

public class Algorithms {

    public interface LoadBalancer {
        int[] map(int numCloudlets, int numVMs, double[] cLengths, double[] vMips);
        String getName();
    }

    // 1. PSO Algorithm
    public static class PSO implements LoadBalancer {
        private long seed;
        public PSO(long seed) { this.seed = seed; }
        @Override public String getName() { return "PSO"; }

        @Override
        public int[] map(int numC, int numV, double[] cLen, double[] vMips) {
            if (numV == 0) return new int[numC];
            int swarm = 20, iters = 50;
            Random rand = new Random(seed);
            double[][] pos = new double[swarm][numC];
            double[][] vel = new double[swarm][numC];
            double[] gBest = new double[numC];
            double gFit = Double.MAX_VALUE;

            for (int i = 0; i < swarm; i++) {
                for (int j = 0; j < numC; j++) {
                    pos[i][j] = rand.nextDouble() * numV;
                    vel[i][j] = rand.nextDouble() * 2 - 1;
                }
                double fit = fitness(pos[i], numV, cLen, vMips);
                if (fit < gFit) { gFit = fit; System.arraycopy(pos[i], 0, gBest, 0, numC); }
            }

            for (int it = 0; it < iters; it++) {
                for (int i = 0; i < swarm; i++) {
                    for (int j = 0; j < numC; j++) {
                        vel[i][j] = 0.7 * vel[i][j] + 1.5 * rand.nextDouble() * (gBest[j] - pos[i][j]);
                        pos[i][j] += vel[i][j];
                        if (pos[i][j] < 0) pos[i][j] = 0;
                        if (pos[i][j] >= numV) pos[i][j] = numV - 0.01;
                    }
                    double fit = fitness(pos[i], numV, cLen, vMips);
                    if (fit < gFit) { gFit = fit; System.arraycopy(pos[i], 0, gBest, 0, numC); }
                }
            }
            return decode(gBest, numV);
        }

        private double fitness(double[] p, int numV, double[] cLen, double[] vMips) {
            double[] loads = new double[numV];
            int[] map = decode(p, numV);
            for (int i = 0; i < map.length; i++) loads[map[i]] += cLen[i] / vMips[map[i]];
            double max = 0, mean = 0;
            for (double l : loads) { max = Math.max(max, l); mean += l; }
            mean /= numV;
            double var = 0;
            for (double l : loads) var += Math.pow(l - mean, 2);
            return 0.7 * max + 0.3 * Math.sqrt(var / numV);
        }

        private int[] decode(double[] p, int numV) {
            int[] map = new int[p.length];
            for (int i = 0; i < p.length; i++) map[i] = (int) Math.floor(p[i]) % numV;
            return map;
        }
    }

    // 2. Round Robin
    public static class RoundRobin implements LoadBalancer {
        @Override public String getName() { return "RoundRobin"; }
        @Override public int[] map(int numC, int numV, double[] c, double[] v) {
            int[] m = new int[numC];
            for (int i = 0; i < numC; i++) m[i] = i % numV;
            return m;
        }
    }

    // 3. Random
    public static class RandomLB implements LoadBalancer {
        private long seed;
        public RandomLB(long seed) { this.seed = seed; }
        @Override public String getName() { return "Random"; }
        @Override public int[] map(int numC, int numV, double[] c, double[] v) {
            Random r = new Random(seed);
            int[] m = new int[numC];
            for (int i = 0; i < numC; i++) m[i] = r.nextInt(numV);
            return m;
        }
    }

    // 4. Least Loaded
    public static class LeastLoaded implements LoadBalancer {
        @Override public String getName() { return "LeastLoaded"; }
        @Override public int[] map(int numC, int numV, double[] cLen, double[] vMips) {
            int[] m = new int[numC];
            double[] loads = new double[numV];
            for (int i = 0; i < numC; i++) {
                int min = 0;
                for (int j = 1; j < numV; j++) if (loads[j] < loads[min]) min = j;
                m[i] = min;
                loads[min] += cLen[i] / vMips[min];
            }
            return m;
        }
    }
}
