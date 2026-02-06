#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOGS_DIR="${SCRIPT_DIR}/Logs"
mkdir -p "${LOGS_DIR}"
TIMESTAMP="$(date +"%Y%m%d_%H%M%S")"
SCRIPT_NAME="$(basename "${BASH_SOURCE[0]}" .bash)"
LOG_FILE="${LOGS_DIR}/${SCRIPT_NAME}_${TIMESTAMP}.log"
exec > >(tee -a "${LOG_FILE}") 2>&1

YELLOW='\033[1;33m'
NC='\033[0m'

cd ..

actorRates=( 0.005 0.001 0.0005 )
criticRates=( 0.005 0.001 0.0005 )

alrCounter=0
for alr in "${actorRates[@]}"
do
    clrCounter=0
    for clr in "${criticRates[@]}"
    do
        echo -e "${YELLOW}TRAINING (actor lr: $alr critic lr: $clr)"
        rSteps=500
        for seed in {1..10}
        do
            echo -e "${NC}Training round with seed: $seed"
            mvn -q exec:java -Dexec.mainClass="com.github.hennas.eisim.Main" -Dexec.args="-i EISim_settings/settings_H_20servers/ -o EISim_output/H_20tuning/output_H_20servers_train_hparam_$alrCounter$clrCounter/ -m EISim_output/H_20tuning/models_H_20servers_hparam_$alrCounter$clrCounter/ -T -a $alr -c $clr -R $rSteps -s $seed"
            rSteps=1
        done
        echo -e "${YELLOW}EVALUATING (actor lr: $alr critic lr: $clr)"
        for seed in {11..15}
        do
            echo -e "${NC}Eval round with seed: $seed"
            mvn -q exec:java -Dexec.mainClass="com.github.hennas.eisim.Main" -Dexec.args="-i EISim_settings/settings_H_20servers/ -o EISim_output/H_20tuning/output_H_20servers_eval_hparam_$alrCounter$clrCounter/ -m EISim_output/H_20tuning/models_H_20servers_hparam_$alrCounter$clrCounter/ -s $seed"
        done
        ((clrCounter++))
    done
    ((alrCounter++))

done