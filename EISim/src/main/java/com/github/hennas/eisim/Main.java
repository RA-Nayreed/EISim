package com.github.hennas.eisim;

import org.nd4j.linalg.factory.Nd4j;

import com.github.hennas.eisim.core.simulationmanager.Simulation;
import com.github.hennas.eisim.defaultclasses.EisimComputingNode;
import com.github.hennas.eisim.defaultclasses.StackelbergComputingNode;
import com.github.hennas.eisim.helpers.ArgumentParser;

/**
 * The main class for running EISim simulations.
 * <p>
 * First, command-line arguments are parsed. These arguments specify the path to
 * the simulation setting
 * folder and to the output folders. Further, these arguments can be used to
 * control whether the
 * simulation is seeded, whether the simulation is run in training mode, and
 * what are the
 * hyperparameters for the training.
 * <p>
 * After parsing, information about the simulation settings is printed to the
 * user. Then,
 * a {@code Simulation} instance is created. Custom implementation classes can
 * be set through
 * this instance. Finally, the simulation is launched.
 * 
 * @see ArgumentParser
 * @see Simulation
 * 
 * @author Henna Kokkonen
 *
 */
public class Main {

	public static void main(String[] args) {
		ArgumentParser parser = new ArgumentParser();
		boolean startExecution = parser.parseArguments(args);
		if (startExecution) {
			start();
		}
	}

	private static void start() {
		if (EisimSimulationParameters.useSeed) {
			System.out.println(
					"Simulation uses the provided seed, the value of which is " + EisimSimulationParameters.seed);
			System.out.println();
		}

		if (EisimSimulationParameters.useStackelberg) {
			System.out.println("Simulation uses the Myopic Stackelberg pricing agent");
			if (EisimSimulationParameters.heterogeneousConfig) {
				System.out.println("Server configuration: HETEROGENEOUS (capacity-differentiated costs)");
			} else {
				System.out.println("Server configuration: HOMOGENEOUS");
			}
		} else if (EisimSimulationParameters.train) {
			System.out.println("Simulation is run in the training mode with the following hyperparameter values:");
			System.out.println("randomDecisionSteps: " + EisimSimulationParameters.randomDecisionSteps);
			System.out.println("replayBufferSize: " + EisimSimulationParameters.replayBufferSize);
			System.out.println("batchSize: " + EisimSimulationParameters.batchSize);
			System.out.println("discountFactor: " + EisimSimulationParameters.discountFactor);
			System.out.println("learningRateActor: " + EisimSimulationParameters.learningRateActor);
			System.out.println("learningRateCritic: " + EisimSimulationParameters.learningRateCritic);
			System.out.println("tau: " + EisimSimulationParameters.tau);
			System.out.println("modelUpdates: " + EisimSimulationParameters.modelUpdates);
			System.out.println("noiseSD: " + EisimSimulationParameters.noiseSD);
			System.out.println("noiseDecay: " + EisimSimulationParameters.noiseDecay);
		} else {
			System.out.println("Simulation is run in the evaluation mode");
		}
		System.out.println();

		// Loading Nd4j class here avoids the NoAvailableBackendException when running
		// simulations in parallel
		String backend = Nd4j.getBackend().getEnvironment().isCPU() ? "CPU" : "GPU";
		System.out.println("Using " + backend + " backend for DL4J and ND4J");
		System.out.println("maxThreads on backend: " + Nd4j.getBackend().getEnvironment().maxThreads());
		System.out.println();

		System.out.println("Heap size: " + Runtime.getRuntime().maxMemory());
		System.out.println();

		// Create a simulation
		Simulation sim = new Simulation();

		sim.setCustomSettingsFolder(EisimSimulationParameters.settingFolder);
		sim.setCustomOutputFolder(EisimSimulationParameters.outputFolder);

		// If Stackelberg mode is enabled, use StackelbergComputingNode
		if (EisimSimulationParameters.useStackelberg) {
			StackelbergComputingNode.setHeterogeneousConfig(EisimSimulationParameters.heterogeneousConfig);
			sim.setCustomComputingNode(StackelbergComputingNode.class);
			System.out.println("Custom computing node set: StackelbergComputingNode");
		} else {
			// For DDPG path, wire heterogeneous config to EisimComputingNode
			EisimComputingNode.setHeterogeneousConfig(EisimSimulationParameters.heterogeneousConfig);
		}

		if (EisimSimulationParameters.heterogeneousConfig) {
			System.out.println("Server configuration: HETEROGENEOUS (state space dim = 3)");
		} else {
			System.out.println("Server configuration: HOMOGENEOUS (state space dim = 2)");
		}

		// Finally, launch the simulation
		sim.launchSimulation();
	}

}
