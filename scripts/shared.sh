#!/bin/bash

if [[ -z "${ARGO_HOME}" ]]; then
    echo "ARGO_HOME is not defined. Exiting..."
    exit 1
fi


LOCAL_LAMBDA_MANAGER_HOST=localhost
LAMBDA_MANAGER_PORT=30009
LAMBDA_MANAGER_HOME=$ARGO_HOME/lambda-manager


function wait_port {
    host=$1
    port=$2
    if [[ -z "$host" || -z "$port" ]]; then
        echo "wait_port requires both host and port. Got host='$host' port='$port'"
        return 1
    fi
    while ! nc -z "$host" "$port"; do sleep 0.01; done
}

# Assumes that $LOCAL_EXECUTION, $SSH_KEY, $REMOTE_USER, and $REMOTE_HOST are set in the caller.
function start_lambda_manager {
    config_path=$1
    variables_path=$2

    if [[ -n "${LOCAL_EXECUTION}" ]]; then
        bash $LAMBDA_MANAGER_HOME/deploy.sh --config $config_path --variables $variables_path --socket &
        wait_port $LOCAL_LAMBDA_MANAGER_HOST $LAMBDA_MANAGER_PORT
    else
        ssh -i $SSH_KEY $REMOTE_USER@$REMOTE_HOST 'bash $ARGO_HOME/lambda-manager/deploy.sh --config '$config_path' --variables '$variables_path' --socket &> /tmp/lm.log' &
        wait_port $REMOTE_HOST $LAMBDA_MANAGER_PORT
    fi
}

# Assumes that $LOCAL_EXECUTION, $SSH_KEY, $REMOTE_USER, and $REMOTE_HOST are set in the caller.
function stop_lambda_manager {
    if [[ -n "${LOCAL_EXECUTION}" ]]; then
        kill $(lsof -i -P -n | grep LISTEN | grep $LAMBDA_MANAGER_PORT | awk '{print $2}')
    else
        ssh -i $SSH_KEY $REMOTE_USER@$REMOTE_HOST 'kill $(lsof -i -P -n | grep LISTEN | grep '$LAMBDA_MANAGER_PORT' | awk '"'"'{print $2}'"'"')'
    fi
}

# Assumes that $LOCAL_EXECUTION, $SSH_KEY, $REMOTE_USER, and $REMOTE_HOST are set in the caller.
function save_experiment_results {
    mode=$1
    results_dir=$2
    if [[ -n "${LOCAL_EXECUTION}" ]]; then
        cp $LAMBDA_MANAGER_HOME/manager_metrics/metrics.log $results_dir/"$mode"_metrics.log
        cp $LAMBDA_MANAGER_HOME/manager_logs/lambda_manager.log $results_dir/"$mode"_manager.log
        tar vzcf $results_dir/"$mode"_lambda_logs.tar.gz $LAMBDA_MANAGER_HOME/lambda_logs &> /dev/null
    else
        ssh -i $SSH_KEY $REMOTE_USER@$REMOTE_HOST 'cp $ARGO_HOME/lambda-manager/manager_metrics/metrics.log '$results_dir'/'$mode'_metrics.log'
        ssh -i $SSH_KEY $REMOTE_USER@$REMOTE_HOST 'cp $ARGO_HOME/lambda-manager/manager_logs/lambda_manager.log '$results_dir'/'$mode'_manager.log'
        ssh -i $SSH_KEY $REMOTE_USER@$REMOTE_HOST 'tar vzcf '$results_dir'/'$mode'_lambda_logs.tar.gz $ARGO_HOME/lambda-manager/lambda_logs' &> /dev/null
    fi
}
