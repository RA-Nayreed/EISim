package com.github.hennas.eisim.defaultclasses;

import com.github.hennas.eisim.agents.stackelberg.StackelbergPricingAgent;
import com.github.hennas.eisim.core.simulationmanager.SimulationManager;

/**
 * Extends {@link EisimComputingNode} to integrate the Myopic Stackelberg
 * pricing agent.
 * <p>
 * This computing node:
 * <ul>
 * <li>Sets {@link StackelbergPricingAgent} as the pricing agent class</li>
 * <li>Passes server capacity and configuration to the agent before
 * initialization</li>
 * <li>Feeds demand observations back to the agent for online demand
 * estimation</li>
 * </ul>
 * <p>
 * To use this computing node instead of the default one, set it via
 * {@code sim.setCustomComputingNode(StackelbergComputingNode.class)} in
 * {@code Main.java}.
 *
 * @see EisimComputingNode
 * @see StackelbergPricingAgent
 */
public class StackelbergComputingNode extends EisimComputingNode {

    /** Whether the current simulation uses heterogeneous server configuration */
    private static boolean heterogeneousConfig = false;

    static {
        // Override the pricing agent class to use Stackelberg instead of DDPG
        setCustomPricingAgentClass(StackelbergPricingAgent.class);
    }

    /**
     * Sets whether the current simulation uses heterogeneous server configuration.
     * This must be called before the simulation starts.
     *
     * @param hetero True for heterogeneous, false for homogeneous
     */
    public static void setHeterogeneousConfig(boolean hetero) {
        heterogeneousConfig = hetero;
    }

    /**
     * Initialize an access point without any computational capabilities.
     */
    public StackelbergComputingNode(SimulationManager simulationManager) {
        super(simulationManager);
    }

    /**
     * Initialize an edge server with cluster information.
     */
    public StackelbergComputingNode(SimulationManager simulationManager, double mipsPerCore,
            int numberOfCPUCores, double storage, double ram, int cluster, boolean clusterHead) {
        super(simulationManager, mipsPerCore, numberOfCPUCores, storage, ram, cluster, clusterHead);
    }

    /**
     * Initialize other types of nodes (cloud data centers and edge devices,
     * edge servers with no cluster information).
     */
    public StackelbergComputingNode(SimulationManager simulationManager, double mipsPerCore,
            int numberOfCPUCores, double storage, double ram) {
        super(simulationManager, mipsPerCore, numberOfCPUCores, storage, ram);
    }

    /**
     * Initializes the Stackelberg pricing agent with server-specific capacity
     * information.
     * <p>
     * Before the agent is instantiated via reflection, this method configures the
     * static
     * parameters on {@link StackelbergPricingAgent} so the correct server capacity
     * and
     * configuration are available to the constructor.
     */
    @Override
    protected void initializeAgent() {
        // Pre-compute scale factors based on topology, since super.initializeAgent()
        // sets them AFTER constructing the agent, but the agent needs them at
        // construction.
        float preQueueScale;
        float preArrivalRateScale;
        if ("DECENTRALIZED".equals(this.algorithm)) {
            preQueueScale = 1e-3f;
            preArrivalRateScale = 1e-2f;
        } else if ("HYBRID".equals(this.algorithm)) {
            preQueueScale = 1e-2f;
            preArrivalRateScale = 1e-3f;
        } else { // CENTRALIZED
            preQueueScale = 1e-1f;
            preArrivalRateScale = 1e-3f;
        }

        // Configure the Stackelberg agent with this server's capacity
        // before it gets instantiated via reflection in super.initializeAgent()
        StackelbergPricingAgent.configure(
                this.getTotalMipsCapacity(), // Server capacity in MIPS
                heterogeneousConfig, // Heterogeneous flag
                preQueueScale, // Queue scale (topology-dependent)
                preArrivalRateScale, // Arrival rate scale (topology-dependent)
                this.clusterSize > 0 ? this.clusterSize : 1 // Cluster size
        );

        // Call super to perform the actual instantiation via reflection
        // (also sets this.queueScale, this.arrivalRateScale, this.rewardScale,
        // this.priceLog)
        super.initializeAgent();
    }

    /**
     * Updates the price at the beginning of a new slot.
     * <p>
     * Extends the parent behavior by feeding actual demand observations to the
     * Stackelberg agent for demand estimation updates.
     */
    @Override
    protected void updatePrice() {
        // Before calling super.updatePrice() which resets counters,
        // record the observation from the previous slot for the Stackelberg agent
        if (this.agent instanceof StackelbergPricingAgent && this.pricingSteps > 0) {
            StackelbergPricingAgent stackelbergAgent = (StackelbergPricingAgent) this.agent;

            // Compute average queue length across the cluster
            int totalQueueLength = 0;
            for (EisimComputingNode node : this.getClusterMembers()) {
                totalQueueLength += node.getTasksQueue().size();
            }
            double avgQueueLength = (double) (totalQueueLength + this.getTasksQueue().size()) /
                    (this.clusterSize > 0 ? this.clusterSize : 1);

            // Record (price, demand, queueLength) observation
            stackelbergAgent.recordObservation(
                    this.getPrice(), // Price used during the slot
                    this.totalTasksArrivedInSlot, // Demand (tasks arrived)
                    avgQueueLength // Average queue length
            );
        }

        // Call the parent updatePrice which handles:
        // - profit calculation
        // - logging
        // - agent.act() for new price
        // - counter resets
        super.updatePrice();
    }
}
