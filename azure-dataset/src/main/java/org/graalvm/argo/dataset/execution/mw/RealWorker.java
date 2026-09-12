package org.graalvm.argo.dataset.execution.mw;

import org.graalvm.argo.dataset.execution.Environment;
import org.graalvm.argo.dataset.execution.mw.memory.AbstractMemoryManager;
import org.graalvm.argo.dataset.multilang.FunctionLanguage;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.function.Consumer;

public class RealWorker extends AbstractWorker {

    private static final String TRACE_INVOCATION_RECORD = "%s,%s,%d,%d,%d,%s,%s";

    private final MultiWorkerInvocationTraceExecutor executor;
    private final BufferedWriter bw;
    private int conc;
    private final String address;

    protected RealWorker(AbstractMemoryManager memoryManager, MultiWorkerInvocationTraceExecutor executor, String address) throws IOException {
        super(memoryManager);
        this.executor = executor;
        // Initialize file writer for a downscaled trace.
        File outputTraceFile = new File(Environment.REAL_WORKER_TRACE_OUTPUT);
        outputTraceFile.createNewFile();
        this.bw = new BufferedWriter(new FileWriter(outputTraceFile));
        bw.write("HashOwner,HashFunction,AverageAllocatedMb,AverageDuration,Timestamp,Language,Function");
        bw.newLine();
        this.address = address;
        conc = 0;
    }

    @Override
    public void ensureUploaded(String owner, String function, String benchmarkName) {
        if (!functions.contains(owner + "_" + function)) {
            executor.uploadFunction(address, owner, function, benchmarkName);
            owners.add(owner);
            functions.add(owner + "_" + function);
        }
    }

    @Override
    public void acceptFunctionInvocation(String owner, String function, int functionMemory, int duration, int timestamp, String benchmarkName) throws IOException {
        String language = executor.config.getBenchmarkConfiguration(benchmarkName).language;

        bw.write(String.format(TRACE_INVOCATION_RECORD, owner, function, functionMemory, duration, (System.currentTimeMillis() - executor.beginningTimestamp), language, benchmarkName));
        bw.newLine();
        ++conc;
        System.out.println("conc: " + conc);
        memoryManager.startRequest(owner, function, functionMemory);
        executor.invokeFunction(address, owner, function, timestamp, duration, benchmarkName, new InvocationCallback(this, owner, function));

        ++totalRequests;
    }

    private static class InvocationCallback implements Consumer<String> {

        private final AbstractWorker worker;
        private final String owner;
        private final String function;

        private InvocationCallback(AbstractWorker worker, String owner, String function) {
            this.worker = worker;
            this.owner = owner;
            this.function = function;
        }

        @Override
        public void accept(String s) {
            //System.out.println("Invocation completed: " + s);
            --((RealWorker)worker).conc;
            worker.memoryManager.finishRequest(owner, function);
        }
    }

    public void close() {
        try {
            bw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
