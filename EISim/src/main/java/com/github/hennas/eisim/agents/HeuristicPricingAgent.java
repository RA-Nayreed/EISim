package com.github.hennas.eisim.agents;

import java.io.IOException;

import org.nd4j.linalg.api.ndarray.INDArray;

import com.github.hennas.eisim.core.simulationmanager.SimulationManager;

/**
 * A simple non-learning pricing agent based on a fixed heuristic.
 */
public class HeuristicPricingAgent extends PricingAgent {

	public HeuristicPricingAgent(String serverName, int stateSpaceDim, float minPrice, float maxPrice,
			SimulationManager simulationManager) {
		super(serverName, stateSpaceDim, minPrice, maxPrice, simulationManager);
	}

	@Override
	public float act(INDArray state) {
		if (state == null) {
			return minPrice;
		}
		float queueLength = state.getFloat(0);
		return queueLength > 0.5f ? maxPrice : minPrice;
	}

	@Override
	public float act() {
		return minPrice;
	}

	@Override
	public void learn(INDArray state, float action, float reward, INDArray nextState) {
		// No learning for heuristic agent.
	}

	@Override
	public void saveAgentState() throws IOException {
		// No agent state to save for heuristic agent.
	}
}
