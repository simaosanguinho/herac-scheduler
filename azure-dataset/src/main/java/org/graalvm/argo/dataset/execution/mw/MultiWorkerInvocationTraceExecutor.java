package org.graalvm.argo.dataset.execution.mw;

import org.graalvm.argo.dataset.generator.InvocationTraceGenerator;
import org.graalvm.argo.dataset.execution.Environment;
import org.graalvm.argo.dataset.execution.ExecutorConfiguration;
import org.graalvm.argo.dataset.execution.InvocationTraceExecutor;
import org.graalvm.argo.dataset.execution.mw.memory.MemoryManagerFactories.AbstractMemoryManagerFactory;
import org.graalvm.argo.dataset.execution.mw.memory.MemoryManagerFactories.SingleInvocationMemoryManagerFactory;
import org.graalvm.argo.dataset.execution.mw.memory.MemoryManagerFactories.OwnerCollocationMemoryManagerFactory;
import org.graalvm.argo.dataset.execution.mw.memory.MemoryManagerFactories.SingleFunctionMemoryManagerFactory;
import org.graalvm.argo.dataset.multilang.FunctionLanguage;
import org.graalvm.argo.dataset.utils.network.SocketNetworkUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MultiWorkerInvocationTraceExecutor extends InvocationTraceExecutor {

    private final AbstractWorker[] workers;
    private int overalloc = 0;
    private final List<String> statistics;
    final long beginningTimestamp;

    // To be removed (everything in ms):
    private static long timeInRead = 0;
    private static long timeInSchedule = 0;
    private static long timeInEnsureUploaded = 0;
    private static long timeInWait = 0;
    private static long timeInRequest = 0;
    private static long timeInEvict = 0;
    public final static Queue<Long> differences = new ConcurrentLinkedQueue<>();
    public final static Queue<Long> networkSend = new ConcurrentLinkedQueue<>();
    public final static Queue<Long> workerProcess = new ConcurrentLinkedQueue<>();
    public final static Queue<Long> traceDurations = new ConcurrentLinkedQueue<>();
    public final static Queue<Long> networkRecv = new ConcurrentLinkedQueue<>();
    public final static Queue<Double> ratios = new ConcurrentLinkedQueue<>();

    public MultiWorkerInvocationTraceExecutor(ExecutorConfiguration config) {
        super(config);
        AbstractMemoryManagerFactory factory = getMemoryManagerFactory(config);
        workers = new AbstractWorker[Environment.WORKER_COUNT];
        for (int i = 0; i < Environment.WORKER_COUNT; ++i) {
            if (i == Environment.REAL_WORKER_INDEX) {
                workers[i] = getRealWorker(factory);
            } else {
                workers[i] = new PhysicalFakeWorker(factory.createMemoryManager(), this, Environment.FAKE_WORKER_HOST + ":" + (Environment.FAKE_WORKER_FIRST_PORT + i));
            }
        }
        statistics = new LinkedList<>();
        beginningTimestamp = System.currentTimeMillis();
    }

    private AbstractMemoryManagerFactory getMemoryManagerFactory(ExecutorConfiguration config) {
        if ("true".equals(config.invocationCollocation)) {
            if ("true".equals(config.functionIsolation)) {
                System.out.println("Using SingleFunctionMemoryManager");
                return new SingleFunctionMemoryManagerFactory();
            } else {
                System.out.println("Using OwnerCollocationMemoryManager");
                return new OwnerCollocationMemoryManagerFactory();
            }
        } else {
            System.out.println("Using SingleInvocationMemoryManager");
            return new SingleInvocationMemoryManagerFactory();
        }
    }

    private RealWorker getRealWorker(AbstractMemoryManagerFactory factory) {
        try {
            return new RealWorker(factory.createMemoryManager(), this, config.getLambdaManagerAddress());
        } catch (IOException e) {
            System.err.println("Couldn't create a real worker: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // HashOwner,HashFunction,AverageAllocatedMb,AverageDuration,Timestamp,Language,BenchmarkName
    @Override
    public void execute(String invocationsFilePath) {
        try (BufferedReader br = new BufferedReader(new FileReader(invocationsFilePath))) {
            String line;
            String[] splitRow;
            br.readLine(); // To skip the header
            int lastStatisticsTimestamp = 0;
            long beforeTmp = 0;
            /* Timestamp used to understand whether the executor is too slow or too fast compared to the trace. */
            long beginningTimestamp = System.currentTimeMillis();
            /* Used to avoid waiting on the same period multiple times. */
            int lastCheckedTimestamp = 0;
            while ((line = br.readLine()) != null) {
                beforeTmp = System.nanoTime();
                splitRow = line.split(InvocationTraceGenerator.DELIMITER);
                String owner = getOwnerName(splitRow[0]);
                int timestamp = Integer.parseInt(splitRow[4]);
                String benchmarkName = splitRow[6];

                String function = getFunctionName(splitRow[1], benchmarkName);
                int functionMemory = config.getBenchmarkConfiguration(benchmarkName).memory;
                int duration = config.getBenchmarkConfiguration(benchmarkName).duration;
                timeInRead += (System.nanoTime() - beforeTmp);

                beforeTmp = System.nanoTime();
                AbstractWorker worker = schedule(owner, function, functionMemory);
                timeInSchedule += (System.nanoTime() - beforeTmp);

                if (worker != null) {
                    beforeTmp = System.nanoTime();
                    worker.ensureUploaded(owner, function, benchmarkName);
                    timeInEnsureUploaded += (System.nanoTime() - beforeTmp);
                }

                beforeTmp = System.nanoTime();
                /* Periodically check if we need to slow down the executor. */
                if ((timestamp - lastCheckedTimestamp) >= Environment.WAIT_PERIOD_MS) {
                    System.out.println(timestamp);
                    waitForInvocation(timestamp, System.currentTimeMillis() - beginningTimestamp);
                    lastCheckedTimestamp = timestamp;
                    SocketNetworkUtils.readAllAvailable();
                }
                timeInWait += (System.nanoTime() - beforeTmp);

                if (worker != null) {
                    beforeTmp = System.nanoTime();
                    worker.acceptFunctionInvocation(owner, function, functionMemory, duration, timestamp, benchmarkName);
                    timeInRequest += (System.nanoTime() - beforeTmp);
                }
//                beforeTmp = System.nanoTime();
//                evictTimedOutInvocations(timestamp);
//                timeInEvict += (System.nanoTime() - beforeTmp);
                if (Environment.COLLECT_STATISTICS && timestamp - lastStatisticsTimestamp >= Environment.STATISTICS_INTERVAL_MS) {
                    long before = System.nanoTime();
                    updateGlobalStatistics(timestamp);
                    lastStatisticsTimestamp = timestamp;
                    System.out.println("Time took to update statistics (ns): " + (System.nanoTime() - before));
                }
            }
            // Try to wait for remaining responses for 10 seconds more.
            SocketNetworkUtils.waitForResponses(10000);
        } catch (IOException e) {
            e.printStackTrace();
        }
        for (AbstractWorker w : workers) {
            w.printStatistics();
        }
        /*-------------------------------------*/
        System.out.println();
        System.out.println("Time In Read (ms): " + timeInRead / 1000000);
        System.out.println("Time In Schedule (ms): " + timeInSchedule / 1000000);
        System.out.println("Time In EnsureUploaded (ms): " + timeInEnsureUploaded / 1000000);
        System.out.println("Time In Request (ms): " + timeInRequest / 1000000);
        System.out.println("Time In Wait (ms): " + timeInWait / 1000000);
        System.out.println("Time In Evict (ms): " + timeInEvict / 1000000);
        System.out.println("Time total (ms): " + (timeInRead + timeInSchedule + timeInEnsureUploaded + timeInRequest + timeInWait + timeInEvict) / 1000000);
        System.out.println("Avg longer duration: " + differences.stream().mapToLong(x -> x).average().orElse(0));
        System.out.println("Avg increase: " + ratios.stream().mapToDouble(x -> x).average().orElse(0.0));
        System.out.println("Avg network send: " + networkSend.stream().mapToLong(x -> x).average().orElse(0));
        System.out.println("Avg worker time: " + workerProcess.stream().mapToLong(x -> x).average().orElse(0));
        System.out.println("Avg trace duration: " + traceDurations.stream().mapToLong(x -> x).average().orElse(0));
        System.out.println("Avg network recv: " + networkRecv.stream().mapToLong(x -> x).average().orElse(0));
        System.out.println("Total requests returned: " + differences.size());
        /*-------------------------------------*/
        System.out.println("Overallocated " + overalloc + " requests.");
        if (Environment.REAL_WORKER_INDEX >= 0 && Environment.REAL_WORKER_INDEX < workers.length) {
            // Only print stats and close if real worker is injected.
            System.out.println("Real node stats:");
            workers[Environment.REAL_WORKER_INDEX].printStatistics();
            if (workers[Environment.REAL_WORKER_INDEX] instanceof RealWorker) {
                ((RealWorker) workers[Environment.REAL_WORKER_INDEX]).close();
            }
        }
        if (Environment.COLLECT_STATISTICS) {
            printGlobalStatistics();
        }
        // To allow for all async requests to finish.
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) { }
    }

//    private void evictTimedOutInvocations(int timestamp) {
//        for (AbstractWorker w : workers) {
//            if (w instanceof FakeWorker) {
//                ((FakeWorker) w).evictInvocations(timestamp);
//            }
//        }
//    }

    private void updateGlobalStatistics(int currentTimestamp) {
        StringBuilder sb = new StringBuilder("{\"").append(currentTimestamp).append("\":[");
        for (AbstractWorker w : workers) {
            int memory = w.getCurrentMemoryUtilization();
            int vms = memory / Environment.VM_MEMORY;
            sb.append("{\"VMs\":").append(vms).append(",\"memory\":").append(memory).append("},");
        }
        statistics.add(sb.append("]},").toString());
    }

    private void printGlobalStatistics() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(Environment.GLOBAL_STATISTICS_OUTPUT, false))) {
            writer.write("[");
            writer.newLine();
            for (String record : statistics) {
                writer.write(record);
                writer.newLine();
            }
            writer.write("]");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private AbstractWorker schedule(String owner, String function, int invocationMemory) {
        AbstractWorker result = null;
        for (AbstractWorker w : workers) {
            if (w.canAccommodateRequest(owner, function, invocationMemory) && w.hasFunctionRegistered(owner, function)) {
                result = w;
                break;
            }
        }
        if (result == null) {
            for (AbstractWorker w : workers) {
                if (w.canAccommodateRequest(owner, function, invocationMemory) && w.hasOwnerRegistered(owner)) {
                    result = w;
                    break;
                }
            }
        }
        if (result == null) {
            result = findLeastUtilized();
            if (!result.canAccommodateRequest(owner, function, invocationMemory)) {
                overalloc++;
                result = null;
            }
        }
        return result;
    }

    private AbstractWorker findLeastUtilized() {
        AbstractWorker result = workers[0];
        for (int i = 1; i < workers.length; ++i) {
            if (workers[i].getCurrentMemoryUtilization() < result.getCurrentMemoryUtilization()) {
                result = workers[i];
            }
        }
        return result;
    }
}
