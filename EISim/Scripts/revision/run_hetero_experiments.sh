#!/usr/bin/env bash
set -e

YELLOW='\033[1;33m'
GREEN='\033[0;32m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Navigate to project root (assuming script is in EISim/Scripts/revision/)
cd "$SCRIPT_DIR/../../"

if [ ! -f "pom.xml" ]; then
    echo -e "${RED}Error: Could not find pom.xml in $(pwd)${NC}"
    echo -e "${RED}Expected script location: EISim/Scripts/revision/${NC}"
    exit 1
fi

echo -e "${GREEN}============================================================${NC}"
echo -e "${GREEN}       EISim Training and Evaluation Script${NC}"
echo -e "${GREEN}============================================================${NC}"
echo ""

echo -e "${CYAN}Select server configuration:${NC}"
echo "  1) homo   - Homogeneous servers"
echo "  2) hetero - Heterogeneous servers"
read -p "Enter choice (homo/hetero): " CONFIG_TYPE

if [[ "$CONFIG_TYPE" != "homo" && "$CONFIG_TYPE" != "hetero" ]]; then
    echo -e "${RED}Invalid choice. Use 'homo' or 'hetero'${NC}"
    exit 1
fi

echo ""
echo -e "${CYAN}Select topology:${NC}"
echo "  C - Centralized"
echo "  H - Hybrid"
echo "  D - Decentralized"
read -p "Enter choice (C/H/D): " TOPOLOGY

TOPOLOGY=$(echo "$TOPOLOGY" | tr '[:lower:]' '[:upper:]')
if [[ "$TOPOLOGY" != "C" && "$TOPOLOGY" != "H" && "$TOPOLOGY" != "D" ]]; then
    echo -e "${RED}Invalid choice. Use 'C', 'H', or 'D'${NC}"
    exit 1
fi

echo ""
echo -e "${CYAN}Select server count:${NC}"
echo "  20  - 20 edge servers"
echo "  100 - 100 edge servers"
read -p "Enter choice (20/100): " SERVERS

if [[ "$SERVERS" != "20" && "$SERVERS" != "100" ]]; then
    echo -e "${RED}Invalid choice. Use '20' or '100'${NC}"
    exit 1
fi

echo ""
read -p "Enter number of training rounds (default: 100): " TRAIN_ROUNDS
TRAIN_ROUNDS=${TRAIN_ROUNDS:-100}

read -p "Enter number of evaluation rounds (default: 5): " EVAL_ROUNDS
EVAL_ROUNDS=${EVAL_ROUNDS:-5}

if [[ "$CONFIG_TYPE" == "hetero" ]]; then
    SETTINGS_DIR="EISim_settings/revision/settings_${TOPOLOGY}_${SERVERS}servers_hetero"
    OUTPUT_TRAIN_DIR="EISim_output/revision/output_${TOPOLOGY}_${SERVERS}servers_hetero_train"
    OUTPUT_EVAL_DIR="EISim_output/revision/output_${TOPOLOGY}_${SERVERS}servers_hetero"
    MODEL_DIR="EISim_output/revision/models_${TOPOLOGY}_${SERVERS}servers_hetero"
else
    SETTINGS_DIR="EISim_settings/revision/settings_${TOPOLOGY}_${SERVERS}servers"
    OUTPUT_TRAIN_DIR="EISim_output/revision/output_${TOPOLOGY}_${SERVERS}servers_homo_train"
    OUTPUT_EVAL_DIR="EISim_output/revision/output_${TOPOLOGY}_${SERVERS}servers_homo"
    MODEL_DIR="EISim_output/revision/models_${TOPOLOGY}_${SERVERS}servers_homo"
fi

TOPO_NAME=""
case $TOPOLOGY in
    C) TOPO_NAME="CENTRALIZED" ;;
    H) TOPO_NAME="HYBRID" ;;
    D) TOPO_NAME="DECENTRALIZED" ;;
esac

echo ""
echo -e "${GREEN}============================================================${NC}"
echo -e "${GREEN}CONFIGURATION SUMMARY${NC}"
echo -e "${GREEN}============================================================${NC}"
echo -e "  Config Type:     ${YELLOW}${CONFIG_TYPE}${NC}"
echo -e "  Topology:        ${YELLOW}${TOPO_NAME}${NC}"
echo -e "  Servers:         ${YELLOW}${SERVERS}${NC}"
echo -e "  Training Rounds: ${YELLOW}${TRAIN_ROUNDS}${NC}"
echo -e "  Eval Rounds:     ${YELLOW}${EVAL_ROUNDS}${NC}"
echo -e "${GREEN}------------------------------------------------------------${NC}"
echo -e "  Settings:  ${SETTINGS_DIR}"
echo -e "  Models:    ${MODEL_DIR}"
echo -e "  Train Out: ${OUTPUT_TRAIN_DIR}"
echo -e "  Eval Out:  ${OUTPUT_EVAL_DIR}"
echo -e "${GREEN}============================================================${NC}"

if [ ! -d "$SETTINGS_DIR" ]; then
    echo -e "${RED}ERROR: Settings directory not found!${NC}"
    echo -e "${RED}Expected: ${SETTINGS_DIR}${NC}"
    echo -e "${RED}Please create the settings folder with edge_datacenters.xml first.${NC}"
    exit 1
fi

echo ""
echo -e "${CYAN}Select action:${NC}"
echo "  1) train - Train model only"
echo "  2) eval  - Evaluate model only"
echo "  3) both  - Train then evaluate"
read -p "Enter choice (train/eval/both): " ACTION

if [[ "$ACTION" != "train" && "$ACTION" != "eval" && "$ACTION" != "both" ]]; then
    echo -e "${RED}Invalid choice. Use 'train', 'eval', or 'both'${NC}"
    exit 1
fi

echo ""
echo -e "${GREEN}Starting in 3 seconds... (Ctrl+C to cancel)${NC}"
sleep 3

if [[ "$ACTION" == "train" || "$ACTION" == "both" ]]; then
    echo ""
    echo -e "${GREEN}============================================================${NC}"
    echo -e "${GREEN}TRAINING: ${TOPO_NAME} ${SERVERS}servers ${CONFIG_TYPE}${NC}"
    echo -e "${GREEN}============================================================${NC}"

    START_TIME=$(date +%s)

    for seed in $(seq 1 $TRAIN_ROUNDS); do
        if [ $seed -eq 1 ]; then
            R_STEPS=500
        else
            R_STEPS=1
        fi

        PROGRESS=$((seed * 100 / TRAIN_ROUNDS))
        ELAPSED=$(($(date +%s) - START_TIME))
        if [ $seed -gt 1 ]; then
            ETA=$(( (ELAPSED * TRAIN_ROUNDS / (seed - 1)) - ELAPSED ))
            ETA_MIN=$((ETA / 60))
            ETA_SEC=$((ETA % 60))
            ETA_STR="${ETA_MIN}m ${ETA_SEC}s"
        else
            ETA_STR="calculating..."
        fi

        echo -e "${YELLOW}[${PROGRESS}%] Training round ${seed}/${TRAIN_ROUNDS} (seed: ${seed}, R: ${R_STEPS}) | ETA: ${ETA_STR}${NC}"

        mvn -q exec:java -Dexec.mainClass="com.github.hennas.eisim.Main" \
            -Dexec.args="-i ${SETTINGS_DIR}/ -o ${OUTPUT_TRAIN_DIR}/ -m ${MODEL_DIR}/ -T -R ${R_STEPS} -s ${seed}"
    done

    TOTAL_TIME=$(($(date +%s) - START_TIME))
    echo -e "${GREEN}Training completed in $((TOTAL_TIME / 60))m $((TOTAL_TIME % 60))s${NC}"
fi

if [[ "$ACTION" == "eval" || "$ACTION" == "both" ]]; then
    echo ""
    echo -e "${GREEN}============================================================${NC}"
    echo -e "${GREEN}EVALUATING: ${TOPO_NAME} ${SERVERS}servers ${CONFIG_TYPE}${NC}"
    echo -e "${GREEN}============================================================${NC}"

    if [ ! -d "$MODEL_DIR" ]; then
        echo -e "${RED}ERROR: Model directory not found!${NC}"
        echo -e "${RED}Expected: ${MODEL_DIR}${NC}"
        echo -e "${RED}Please train the model first.${NC}"
        exit 1
    fi

    START_TIME=$(date +%s)
    EVAL_START_SEED=101

    for i in $(seq 1 $EVAL_ROUNDS); do
        seed=$((EVAL_START_SEED + i - 1))
        echo -e "${YELLOW}Evaluation round ${i}/${EVAL_ROUNDS} (seed: ${seed})${NC}"

        mvn -q exec:java -Dexec.mainClass="com.github.hennas.eisim.Main" \
            -Dexec.args="-i ${SETTINGS_DIR}/ -o ${OUTPUT_EVAL_DIR}/ -m ${MODEL_DIR}/ -s ${seed}"
    done

    TOTAL_TIME=$(($(date +%s) - START_TIME))
    echo -e "${GREEN}Evaluation completed in $((TOTAL_TIME / 60))m $((TOTAL_TIME % 60))s${NC}"
fi

echo ""
echo -e "${GREEN}============================================================${NC}"
echo -e "${GREEN}DONE!${NC}"
echo -e "${GREEN}============================================================${NC}"
echo -e "Results saved to:"
if [[ "$ACTION" == "train" || "$ACTION" == "both" ]]; then
    echo -e "  Training: ${OUTPUT_TRAIN_DIR}"
fi
if [[ "$ACTION" == "eval" || "$ACTION" == "both" ]]; then
    echo -e "  Evaluation: ${OUTPUT_EVAL_DIR}"
fi
echo -e "  Models: ${MODEL_DIR}"
