#!/bin/bash

# Example: ./trace-generator.sh -d d02 -t result_d02.csv -b 701 -e 710
# For syntax help: ./trace-generator.sh -h

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"

cd "$DIR" || {
  echo "Redirection failed!"
  exit 1
}

JAR=$DIR/build/libs/azure-dataset-1.0-all.jar
MAIN=org.graalvm.argo.dataset.generator.InvocationTraceGenerator

$JAVA_HOME/bin/java -cp $JAR $MAIN $@
