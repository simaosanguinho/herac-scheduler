package org.graalvm.argo.dataset.execution;

import org.graalvm.argo.dataset.execution.utils.Benchmark;
import org.graalvm.argo.dataset.execution.utils.FunctionRuntime;
import org.graalvm.argo.dataset.generator.InvocationTraceGenerator;
import org.graalvm.argo.dataset.multilang.FunctionLanguage;
import org.graalvm.argo.dataset.utils.network.SocketNetworkUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public class InvocationTraceExecutor {

    private static final int EXECUTOR_TRACE_COLUMN_COUNT = 7;

    public final ExecutorConfiguration config;


    public InvocationTraceExecutor(ExecutorConfiguration config) {
        this.config = config;
    }

    // HashOwner,HashFunction,AverageAllocatedMb,AverageDuration,Timestamp,Language,BenchmarkName
    public void execute(String invocationsFilePath) {
        uploadFunctions(invocationsFilePath);
        try (BufferedReader br = new BufferedReader(new FileReader(invocationsFilePath))) {
            String line;
            String[] splitRow;
            br.readLine(); // To skip the header
            /* Timestamp used to understand whether the executor is too slow or too fast compared to the trace. */
            long beginningTimestamp = System.currentTimeMillis();
            /* Used to avoid waiting on the same period multiple times. */
            int lastCheckedTimestamp = 0;
            while ((line = br.readLine()) != null) {
                splitRow = line.split(InvocationTraceGenerator.DELIMITER);
                validateTraceRow(splitRow, line);
                String owner = getOwnerName(splitRow[0]);
                int timestamp = Integer.parseInt(splitRow[4]);
                String benchmarkName = splitRow[6];
                int duration = config.getBenchmarkConfiguration(benchmarkName).duration;
                String function = getFunctionName(splitRow[1], benchmarkName);

                /* Periodically check if we need to slow down the executor. */
                if ((timestamp - lastCheckedTimestamp) >= Environment.WAIT_PERIOD_MS) {
                    System.out.println(timestamp);
                    waitForInvocation(timestamp, System.currentTimeMillis() - beginningTimestamp);
                    lastCheckedTimestamp = timestamp;
                    SocketNetworkUtils.readAllAvailable();
                }

                invokeFunction(config.getLambdaManagerAddress(), owner, function, timestamp, duration, benchmarkName, System.out::println);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        SocketNetworkUtils.waitForResponses(15000);
    }

    private void uploadFunctions(String invocationsFilePath) {
        Set<String> uploadedFunctions = new HashSet<>();
        try (BufferedReader br = new BufferedReader(new FileReader(invocationsFilePath))) {
            String line;
            String[] splitRow;
            br.readLine(); // To skip the header
            while ((line = br.readLine()) != null) {
                splitRow = line.split(InvocationTraceGenerator.DELIMITER);
                validateTraceRow(splitRow, line);
                String owner = getOwnerName(splitRow[0]);
                String benchmarkName = splitRow[6];
                String function = getFunctionName(splitRow[1], benchmarkName);
                ensureUploaded(uploadedFunctions, owner, function, benchmarkName);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected void ensureUploaded(Set<String> uploadedFunctions, String owner, String function, String benchmarkName) {
        if (!uploadedFunctions.contains(owner + "_" + function)) {
            uploadFunction(config.getLambdaManagerAddress(), owner, function, benchmarkName);
            uploadedFunctions.add(owner + "_" + function);
        }
    }

    protected void waitForInvocation(int traceTimestamp, long realTimestamp) {
        long timeDifference = traceTimestamp - realTimestamp;
        if (timeDifference > 0) {
            try {
                System.out.println("Sleeping (ms) " + timeDifference);
                Thread.sleep(timeDifference);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } else {
            System.out.println("Warning: executor lags behind the trace by (ms) " + timeDifference);
        }
    }

    protected String getOwnerName(String ownerFromTrace) {
        if ("hy-fc".equals(config.executionMode)) {
            return "user";
        }
        return ownerFromTrace;
    }

    protected String getFunctionName(String functionFromTrace, String benchmarkName) {
        if ("hy".equals(config.executionMode) || "hy-fc".equals(config.executionMode) || "he".equals(config.executionMode)) {
            // To avoid clashing SVM IDs when collocating different functions.
            return benchmarkName;
        }
        return functionFromTrace;
    }

    protected void validateTraceRow(String[] splitRow, String line) {
        if (splitRow.length < EXECUTOR_TRACE_COLUMN_COUNT) {
            throw new IllegalArgumentException(
                    "Invalid trace format: expected 7 columns "
                            + "(HashOwner,HashFunction,AverageAllocatedMb,AverageDuration,Timestamp,Language,Benchmark) "
                            + "but found " + splitRow.length + ". "
                            + "Run trace-languages.sh on the generated trace before executing it. Offending row: " + line);
        }
    }

    public void uploadFunction(String address, String owner, String function, String benchmarkName) {
        Benchmark benchmarkConfig = config.getBenchmarkConfiguration(benchmarkName);
        FunctionLanguage functionLanguage = FunctionLanguage.fromString(benchmarkConfig.language);

        String message = "u username=" + owner + " function_name=" + function +
                " function_language=" + functionLanguage + " function_entry_point=" + benchmarkConfig.entryPoint +
                " function_memory=" + benchmarkConfig.memory + " function_runtime=" + config.functionRuntime.toString() +
                " function_isolation=" + config.functionIsolation + " invocation_collocation=" + config.invocationCollocation;
        if (benchmarkConfig.heapSize != null) {
            message = message + " function_heap_size=" + benchmarkConfig.heapSize;
        }
        if (benchmarkConfig.inputBufferSize != null) {
            message = message + " function_input_buffer_size=" + benchmarkConfig.inputBufferSize;
        }
        if (benchmarkConfig.outputBufferSize != null) {
            message = message + " function_output_buffer_size=" + benchmarkConfig.outputBufferSize;
        }
        if (benchmarkConfig.scratchSize != null) {
            message = message + " function_scratch_size=" + benchmarkConfig.scratchSize;
        }
        if (benchmarkConfig.hydraSandbox != null) {
            message = message + " hydra_sandbox=" + benchmarkConfig.hydraSandbox;
            // For Python/JS functions that need sandbox snapshotting.
            if ("snapshot".equals(benchmarkConfig.hydraSandbox) && benchmarkConfig.svmId != null) {
                message = message + " svm_id=" + benchmarkConfig.svmId;
            }
        }
        if (FunctionRuntime.HERAC.equals(config.functionRuntime)) {
            if (benchmarkConfig.heapSize != null) {
                message = message + " function_heap_size=" + benchmarkConfig.heapSize;
            }
            if (benchmarkConfig.inputBufferSize != null) {
                message = message + " function_input_buffer_size=" + benchmarkConfig.inputBufferSize;
            }
            if (benchmarkConfig.scratchSize != null) {
                message = message + " function_scratch_size=" + benchmarkConfig.scratchSize;
            }
            if (benchmarkConfig.outputBufferSize != null) {
                message = message + " function_output_buffer_size=" + benchmarkConfig.outputBufferSize;
            }
        }
        // Append path to the function as payload in ''.
        message = message + " '" + benchmarkConfig.code + "'";
        if (!config.isDebugMode()) {
            SocketNetworkUtils.send(address, message, true, (s) -> {});
        }
    }

    public void invokeFunction(String address, String owner, String function, int timestamp, int duration, String benchmarkName, Consumer<String> asyncConsumer) {
        if (config.isDebugMode()) {
            System.out.println("Sending request with timestamp: " + timestamp);
        } else {
            Benchmark benchmarkConfig = config.getBenchmarkConfiguration(benchmarkName);
            String message = "i username=" + owner + " function_name=" + function +
                    " request_duration=" + duration +
                    " '" + benchmarkConfig.payload + "'"; // Append invocation parameters as payload in ''.
            SocketNetworkUtils.send(address, message, true, asyncConsumer);
        }
    }
}
