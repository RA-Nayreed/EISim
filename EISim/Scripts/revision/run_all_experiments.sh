#!/usr/bin/env bash
set -e

# Script to run all combinations of experiments simultaneously in the background.
# Output will be logged to separate log files.

LOG_DIR="EISim_output/revision/logs"
mkdir -p "$LOG_DIR"

SCRIPT="./EISim/Scripts/revision/run_hetero_experiments.sh"

echo "Spawning all experiments in the background..."

for config in homo hetero; do
    for topology in C H D; do
        for servers in 20 100; do
            echo "Starting ${config} servers, topology ${topology}, count ${servers}..."
            LOG_FILE="${LOG_DIR}/run_${config}_${topology}_${servers}.log"
            bash "$SCRIPT" -c "$config" -t "$topology" -s "$servers" -a both > "$LOG_FILE" 2>&1 &
        done
    done
done

echo "All 12 experiment combinations spawned. Run 'jobs' or check ${LOG_DIR} for progress."
wait
echo "All experiments finished."
