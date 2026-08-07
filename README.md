# Hydra Scheduler

This repository contains three folders:

1. `azure-dataset` - contains tools to generate, process, and execute traces;
2. `fake-worker` - contains code of a serverless worker that "accepts" function invocations and responses after the indicated duration;
3. `scripts` - contains convenience scripts to execute traces with the `azure-dataset` tools.

## Azure Dataset Tools

This directory contains the source code of the tools (written in Java) and convenience `.sh` scripts that invoke the tools.

Usually, the sequence of steps to start working with the dataset is as follows:

1. Build the tool with `build.sh`;
2. Download the dataset files with `download_dataset.sh`;
3. Generate the trace with the desired duration using `trace-generator.sh`. This script accepts parameters. You can learn more about using the parameters by looking at the source code in `InvocationTraceGenerator.java`. The resulting trace will be a CSV file containing a list of invocations;
4. Add a realistic language distribution of the functions in your trace by running `trace-languages.sh`. This script accepts two parameters: "i" (long version: "input") - the input trace and "t" (long version: "trace") - the output trace;

Now you have the tools built and the trace generated. If you want to ensure the correct language distribution, you can run the `check-multifunc.sh` script to see how many invocations per language there are.

In order to configure the multi-worker executor (i.e., the trace executor that runs the entire trace with multiple workers), you can change the global variables in `Environment.java`. Pay close attention to the `WORKER_COUNT` and `FAKE_WORKER_FIRST_PORT` fields - their values should match the parameters that you pass to the `deploy-swarm.sh` script in the `fake-worker` directory (described later in this README).

## Fake Worker

This directory contains the source code of the fake serverless worker that simulates invocation processing. Currently, the fake worker accepts only two types of messages:

1. "u" ("u" stands for "upload") - returns immediately, needed to simulate function registration;
2. "i \<duration\>" ("i" stands for "invocation") - returns after `<duration>` milliseconds; `<duration>` should be a valid integer.

Please note that this worker is not an HTTP server; it is a socket server written in Java (see `Server.java`).

The sequence of steps to launch fake workers:

1. Build the worker with `build.sh`;
2. Run `deploy.sh <port>` to run a single worker;
3. Alternatively, run `deploy-swarm.sh <count> <first-port>` to run `<count>` workers with port range starting from `<first-port>`;
4. You can check the logs of your workers in `/tmp/fake-workers`;
5. Run `cleanup-swarm.sh` to terminate all running workers.

If you want to test yor fake worker, you can run `run-client.sh` and start typing messages. This script expects the fake worker to run on port 5454.

## Scripts

This directory contains the scripts to run the large-scale experiment (abbreviated LSE). Currently, only the `benchmark-lse-simulation.sh` script works correctly as this repository is still under development.

The `benchmark-lse-simulation.sh` script expects fake workers to be already created. 

Usage of this script: `benchmark-lse-simulation.sh <hy|hy-sf|hy-si|ow> </path/to/trace.csv>`.
