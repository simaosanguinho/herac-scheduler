package org.graalvm.argo.dataset.aot;

import java.util.List;
import java.util.TreeSet;
import org.graalvm.argo.dataset.Invocation;
import org.graalvm.argo.dataset.InvocationTraceSimulator;
import org.graalvm.argo.dataset.OutputEntry;
import org.graalvm.argo.dataset.SimulationState;
import org.graalvm.argo.dataset.utils.ColdStartSlidingWindow;

/**
 * This class is an extension of InvocationTraceSimulator that also
 * allows for simulating AOT optimizations.
 */
public class AOTInvocationTraceSimulator extends InvocationTraceSimulator {

    /**
     * Minimum number of cold starts within a period.
     */
    private static final int AOT_OPTIMIZATION_THRESHOLD = 10;

    /**
     * Period during which we count number of cold starts (in ms).
     */
    private static final int SLIDING_WINDOW_PERIOD = 60000;

    @Override
    protected Invocation createInvocation(String owner, String function, int memory, int duration, int timestamp) {
        return new AOTInvocation(owner, function, memory, duration, timestamp);
    }

    class AOTSimulationState extends SimulationState {
        int optimizedColdStarts;
        ColdStartSlidingWindow window = new ColdStartSlidingWindow(AOT_OPTIMIZATION_THRESHOLD, SLIDING_WINDOW_PERIOD);
    }


    @Override
    protected OutputEntry updateStatistics(TreeSet<Invocation> activeInvocations, List<Invocation> runningInvocations, SimulationState ss) {
        @SuppressWarnings("unchecked")
        List<AOTInvocation> runningAOTInvocations = (List<AOTInvocation>)(List<?>) runningInvocations;
        AOTOutputEntry aotOutputEntry = new AOTOutputEntry();
        aotOutputEntry.optimizedColdStarts = ((AOTSimulationState)ss).optimizedColdStarts;
        aotOutputEntry.runningOptimizedFunctions  = (int) runningAOTInvocations.parallelStream().filter(AOTInvocation::isOptimized).map(AOTInvocation::getFunction).distinct().count();
        return super.updateStatistics(activeInvocations, runningInvocations, aotOutputEntry, ss);
    }

    @Override
    protected void resetSimulationStateAfterUpdateStatistics(SimulationState ss) {
        ((AOTSimulationState)ss).optimizedColdStarts = 0;
        super.resetSimulationStateAfterUpdateStatistics(ss);
    }

    @Override
    protected void updateAfterWarmCheck(SimulationState ss, Invocation currentInvocation, Invocation warm) {
        AOTInvocation currentAOTInvocation = (AOTInvocation) currentInvocation;
        AOTSimulationState aotss = ((AOTSimulationState)ss);
        if (warm == null) {
            String currentFunction = currentAOTInvocation.getFunction();
            if (aotss.window.optimized(currentFunction) || aotss.window.worthOptimizing(currentFunction, ss.currentTimestamp)) {
                aotss.optimizedColdStarts++;
                // Cold start happened, but the function is optimized.
                currentAOTInvocation.optimize();
            } else {
                aotss.window.add(currentFunction, ss.currentTimestamp);
            }
        } else {
            // Reuse memory and optimization status of the warm invocation instead of always using unoptimized.
            currentAOTInvocation.setOptimizedMemory((AOTInvocation) warm);
        }
        super.updateAfterWarmCheck(ss, currentInvocation, warm);
    }

    protected List<OutputEntry> simulateInvocations(String inputFile, int keepalive, int interval) {
        return simulateInvocations(inputFile, new AOTSimulationState(), keepalive, interval);
    }
}
