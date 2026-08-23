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


function append_prereq_issue {
    local message="$1"
    if [[ -z "${PREREQ_ISSUES:-}" ]]; then
        PREREQ_ISSUES="$message"
    else
        PREREQ_ISSUES="$PREREQ_ISSUES
$message"
    fi
}


function check_sysctl_min {
    local name="$1"
    local expected="$2"
    local current
    current="$(sysctl -n "$name" 2>/dev/null || true)"
    if [[ -z "$current" ]]; then
        append_prereq_issue "- Missing sysctl '$name'."
        return
    fi
    if (( current < expected )); then
        append_prereq_issue "- sysctl $name=$current, recommended >= $expected for this large-scale replay."
    fi
}


function check_tmpfs_mount {
    local path="$1"
    local fs_type
    fs_type="$(stat -f -c %T "$path" 2>/dev/null || true)"
    if [[ -z "$fs_type" ]]; then
        append_prereq_issue "- Path '$path' is missing."
        return
    fi
    if [[ "$fs_type" != "tmpfs" ]]; then
        append_prereq_issue "- Path '$path' is on '$fs_type', recommended 'tmpfs' for this large-scale replay."
    fi
}


function check_pids_limit {
    local pids_path="/sys/fs/cgroup/user.slice/user-$(id -u).slice/pids.max"
    local expected="$1"
    local current
    current="$(cat "$pids_path" 2>/dev/null || true)"
    if [[ -z "$current" ]]; then
        append_prereq_issue "- cgroup pids.max is unavailable at '$pids_path'."
        return
    fi
    if [[ "$current" != "max" ]] && (( current < expected )); then
        append_prereq_issue "- cgroup pids.max=$current, recommended >= $expected for this large-scale replay."
    fi
}


function ensure_large_scale_prereqs {
    local recommended_limit="${LSE_RECOMMENDED_LIMIT:-4194304}"
    local allow_untuned="${ALLOW_UNTUNED_HOST:-0}"

    PREREQ_ISSUES=""
    check_sysctl_min kernel.threads-max "$recommended_limit"
    check_sysctl_min kernel.pid_max "$recommended_limit"
    check_sysctl_min vm.max_map_count "$recommended_limit"
    check_pids_limit "$recommended_limit"
    check_tmpfs_mount "$ARGO_HOME/lambda-manager/lambda_logs"
    check_tmpfs_mount "$ARGO_HOME/lambda-manager/codebase"

    if [[ -n "$PREREQ_ISSUES" ]]; then
        cat >&2 <<EOF
Large-scale replay prerequisites are not satisfied:
$PREREQ_ISSUES

These recommendations come from scheduler/azure-dataset/benchmark-results/README.md
and benchmarks/demos/*/update-limits.sh. They matter for the real-runtime replay path
used by benchmark-lse.sh, especially with Hydra process/snapshot-process workloads.

Set ALLOW_UNTUNED_HOST=1 to bypass this check if you want to continue anyway.
EOF
        if [[ "$allow_untuned" != "1" ]]; then
            exit 1
        fi
    fi
}


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

    if [[ -n "$RESULTS_DIR" ]]; then
        cp "$EXECUTOR_LOG_FILE" "$RESULTS_DIR/executor.log"
    fi

    sleep 10
    echo "Finished benchmark execution. Stopping the lambda manager..."
    stop_lambda_manager
}


MODE=$1
DATASET_FILE=$2
EXECUTOR_TYPE=$3
RESULTS_DIR=$4

#ensure_large_scale_prereqs

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
