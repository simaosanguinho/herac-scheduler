#!/bin/bash

# Example usage of this script:
# bash benchmark-lse.sh hy|hy-sf|hy-si|ow|he /path/to/dataset/file --single|--multi </path/to/results/folder>
# The structure of the .csv file should be as follows:
# HashOwner HashFunction AverageAllocatedMb AverageDuration Timestamp
#
# Note: by default, the script assumes a remote real worker. If you want to run the
# entire experiment locally, set LOCAL_EXECUTION environment variable to any value.

function DIR {
    echo "$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
}

export LOCAL_EXECUTION=1

# Defines some variables and functions
source $(DIR)/shared.sh

if [[ -z "${ARGO_HOME}" ]]; then
    echo "ARGO_HOME is not defined. Exiting..."
    exit 1
fi

if [[ -z "${JAVA_HOME}" ]]; then
    echo "JAVA_HOME is not defined. Exiting..."
    exit 1
fi

echo "Running the large scale experiment locally."
LAMBDA_MANAGER_ADDRESS="$LOCAL_LAMBDA_MANAGER_HOST:$LAMBDA_MANAGER_PORT"

WORKER_COUNT=100
FIRST_PORT=50010


function process_dataset {
    csv_file=$1
    execution_mode=$2

    azure_executor_jar=$(DIR)/../azure-dataset/build/libs/azure-dataset-1.0-all.jar
    azure_executor_entrypoint=org.graalvm.argo.dataset.execution.ExecutorEntryPoint

    multi_worker_option=
    if [[ "$EXECUTOR_TYPE" = "--multi" ]]; then
        multi_worker_option="--multiWorker"
    fi

    time $JAVA_HOME/bin/java -cp $azure_executor_jar $azure_executor_entrypoint \
        --input $csv_file \
        --lambdaManagerAddress $LAMBDA_MANAGER_ADDRESS \
        --executionMode $execution_mode $multi_worker_option &> $EXECUTOR_LOG_FILE

    sleep 10
    echo "Finished benchmark execution. Stopping the lambda manager..."
    stop_lambda_manager
}


MODE=$1
DATASET_FILE=$2
EXECUTOR_TYPE=$3
RESULTS_DIR=$4

# Default paths
LAMBDA_MANAGER_CONFIGURATION="$ARGO_HOME/run/configs/manager/default-lambda-manager.json"
LAMBDA_MANAGER_VARIABLES="$ARGO_HOME/run/configs/manager/default-variables.json"


if [[ "$MODE" = "hy" ]]; then
    LAMBDA_MANAGER_CONFIGURATION="$ARGO_HOME/run/configs/manager/hy-lm.json"
elif [[ "$MODE" = "hy-fc" ]]; then
    LAMBDA_MANAGER_CONFIGURATION="$ARGO_HOME/run/configs/manager/hy-lm.json"
elif [[ "$MODE" = "hy-sf" ]]; then
    LAMBDA_MANAGER_CONFIGURATION="$ARGO_HOME/run/configs/manager/hy-lm.json"
elif [[ "$MODE" = "hy-si" ]]; then
    LAMBDA_MANAGER_CONFIGURATION="$ARGO_HOME/run/configs/manager/hy-lm.json"
elif [[ "$MODE" = "he" ]]; then
    LAMBDA_MANAGER_CONFIGURATION="$ARGO_HOME/run/configs/manager/he-lm.json"
elif [[ "$MODE" = "ow" ]]; then
    LAMBDA_MANAGER_CONFIGURATION="$ARGO_HOME/run/configs/manager/ow-lm.json"
elif [[ "$MODE" = "kn" ]]; then
    LAMBDA_MANAGER_CONFIGURATION="$ARGO_HOME/run/configs/manager/kn-lm.json"
elif [[ "$MODE" = "gos" ]]; then
    LAMBDA_MANAGER_CONFIGURATION="$ARGO_HOME/run/configs/manager/gos-lm.json"
elif [[ "$MODE" = "gos-native" ]]; then
    LAMBDA_MANAGER_CONFIGURATION="$ARGO_HOME/run/configs/manager/gos-native-lm.json"
else
    echo "Syntax: <mode> </path/to/dataset/directory> <executor-type>"
	exit 1
fi

if [[ "$EXECUTOR_TYPE" = "--single" ]]; then
    echo "Using the single-worker deterministic executor."
    EXECUTOR_LOG_FILE="/tmp/lse_executor-determ.log"
elif [[ "$EXECUTOR_TYPE" = "--multi" ]]; then
    echo "Using the multi-worker executor. Launching $WORKER_COUNT fake workers."
    EXECUTOR_LOG_FILE="/tmp/lse_executor.log"
else
    echo "Syntax: <mode> </path/to/dataset/directory> <executor-type>"
	exit 1
fi


# Deploy lambda manager and wait for it to launch
start_lambda_manager $LAMBDA_MANAGER_CONFIGURATION $LAMBDA_MANAGER_VARIABLES

# Spawn fake workers
if [[ "$EXECUTOR_TYPE" = "--multi" ]]; then
    bash $(DIR)/../fake-worker/deploy-swarm.sh $WORKER_COUNT $FIRST_PORT
fi

# To ensure that the LM process and fake workers are started up properly
sleep 10

# Run the trace
process_dataset $DATASET_FILE $MODE

# Terminate fake workers
if [[ "$EXECUTOR_TYPE" = "--multi" ]]; then
    bash $(DIR)/../fake-worker/cleanup-swarm.sh
fi

# Wait for the lambda manager to finish execution
wait

# Save results (always overwriting previous files)
if [ -n "$RESULTS_DIR" ]
then
    save_experiment_results $MODE $RESULTS_DIR
fi
