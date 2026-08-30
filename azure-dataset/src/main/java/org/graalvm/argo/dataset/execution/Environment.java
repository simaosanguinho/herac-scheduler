package org.graalvm.argo.dataset.execution;

public class Environment {

    // General configuration.
    public final static int WORKER_COUNT = 100;
    public final static int MAX_MEMORY_PER_WORKER_MB = 106496;
    // Negate if you don't want to insert real worker.
    public final static int REAL_WORKER_INDEX = 0;
    public final static String REAL_WORKER_TRACE_OUTPUT = "/tmp/lse_trace.csv";
    // Statistics about used memory and #VMs in each worker (theoretic, calculated by memory managers).
    public final static boolean COLLECT_STATISTICS = false;
    public final static String GLOBAL_STATISTICS_OUTPUT = "/tmp/lse_statistics.json";
    public final static int STATISTICS_INTERVAL_MS = 1000;
    // Period in which we check if the scheduler keeps up with the trace timestamps.
    public static final long WAIT_PERIOD_MS = 10;

    // Fake worker configuration.
    public static final String FAKE_WORKER_HOST = "localhost";
    public final static int FAKE_WORKER_FIRST_PORT = 50010;

    public static final int VM_MEMORY = 1024;
}
