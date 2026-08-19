package org.graalvm.argo.dataset.execution.utils;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Benchmark {
    public String language;
    public String benchmarkName;
    public String code;
    public String entryPoint;
    public String payload;
    public int memory;
    public int duration;
    public String hydraSandbox;
    public String svmId;
    public Long heapSize;
    public Long inputBufferSize;
    public Long outputBufferSize;
    public Long scratchSize;

    @Override
    public String toString() {
        return "Benchmark{" +
                "language='" + language + '\'' +
                ", benchmarkName='" + benchmarkName + '\'' +
                ", code='" + code + '\'' +
                ", entryPoint='" + entryPoint + '\'' +
                ", payload='" + payload + '\'' +
                ", memory=" + memory +
                ", duration=" + duration +
                ", hydraSandbox='" + hydraSandbox + '\'' +
                ", svmId='" + svmId + '\'' +
                ", heapSize=" + heapSize +
                ", inputBufferSize=" + inputBufferSize +
                ", outputBufferSize=" + outputBufferSize +
                ", scratchSize=" + scratchSize +
                '}';
    }
}
