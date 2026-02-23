package com.github.hennas.eisim.agents.stackelberg;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.nd4j.linalg.api.ndarray.INDArray;

import com.github.hennas.eisim.agents.PricingAgent;
import com.github.hennas.eisim.core.simulationmanager.SimulationManager;

/**
 * Implements a Myopic Stackelberg game-theoretic pricing agent for edge
 * computing orchestration.
 * <p>
 * At each pricing slot, the agent solves a single-period profit maximization
 * problem:
 * 
 * <pre>
 *   p* = argmax_{p >= 0} p * D(p, q) - K(D(p, q), s, q)
 * </pre>
 * 
 * where demand follows {@code D(p, q) = max{0, a(q) - b*p}} and the cost
 * function is
 * {@code K(D, s, q) = k*D + (gamma / (2*s)) * (D + q)^2}.
 * <p>
 * The closed-form optimal price is:
 * 
 * <pre>
 *   p* = a(q) / (2*b) * (1 + gamma*b/s) + k/2 + gamma*q / (2*s)
 * </pre>
 * <p>
 * Demand parameters {@code a(q)} and {@code b} are estimated online using
 * sliding-window
 * OLS regression over recent observations. For initial timesteps with
 * insufficient data,
 * conservative default parameters are used.
 * <p>
 * This agent is stateless (no neural networks) and requires no training phase,
 * making it directly deployable for evaluation-only simulation runs.
 *
 * @see PricingAgent
 */
public class StackelbergPricingAgent extends PricingAgent {

    // ===================== Stackelberg model parameters =====================

    /** Server capacity in MIPS (set per-agent at initialization) */
    private double serverCapacity;

    /** Marginal processing cost per task unit */
    private double marginalCost;

    /** Congestion penalty coefficient */
    private double gamma;

    /** Whether this is a heterogeneous server configuration */
    private boolean heterogeneous;

    // ===================== Demand estimation parameters =====================

    /** Sliding window size for OLS regression */
    private static final int WINDOW_SIZE = 50;

    /** Minimum observations before using OLS (use defaults otherwise) */
    private static final int MIN_OBSERVATIONS = 10;

    /** Default price sensitivity (moderate) */
    private static final double DEFAULT_ELASTICITY = 0.01;

    /** Minimum demand intercept to prevent numerical instability */
    private static final double MIN_INTERCEPT = 0.1;

    /** Minimum price sensitivity to prevent division by near-zero */
    private static final double MIN_ELASTICITY = 0.001;

    /** Regularization coefficient for OLS regression */
    private static final double REGULARIZATION_LAMBDA = 0.01;

    /** Minimum price (avoid zero pricing) */
    private static final float MIN_PRICE_FLOOR = 0.0f;

    /** Base marginal cost k_0 */
    private static final double BASE_MARGINAL_COST = 0.5;

    /** Default congestion penalty coefficient */
    private static final double DEFAULT_GAMMA = 1.0;

    // ===================== Observation history =====================

    /** History of price observations for demand estimation */
    private final List<Double> priceHistory;

    /** History of demand observations for demand estimation */
    private final List<Double> demandHistory;

    /** History of queue length observations for demand estimation */
    private final List<Double> queueHistory;

    /** Current estimated demand intercept coefficient (β₀) */
    private double estBeta0;

    /** Current estimated queue coefficient (β₁) */
    private double estBeta1;

    /** Current estimated price coefficient (β₂, negative = price sensitivity) */
    private double estBeta2;

    /** Default demand intercept based on server capacity */
    private double defaultIntercept;

    // ===================== Scaling factors (from EisimComputingNode)
    // =====================

    /** Scale factor applied to queue length in state representation */
    private float queueScale;

    /** Scale factor applied to arrival rate in state representation */
    private float arrivalRateScale;

    // ===================== Static configuration =====================
    // These static fields allow EisimComputingNode to pass configuration
    // before the agent is instantiated via reflection.

    /** Server capacity to be used by next instantiation */
    private static double pendingServerCapacity = 1000.0;

    /** Whether next instantiation uses heterogeneous config */
    private static boolean pendingHeterogeneous = false;

    /** Queue scale for next instantiation */
    private static float pendingQueueScale = 1e-3f;

    /** Arrival rate scale for next instantiation */
    private static float pendingArrivalRateScale = 1e-2f;

    /** Cluster size for next instantiation */
    private static int pendingClusterSize = 1;

    /** Gamma override (for sensitivity analysis), 0 means use default */
    private static double pendingGamma = 0.0;

    /** Cluster size */
    private int clusterSize;

    /**
     * Set the server configuration before instantiation.
     * Must be called before the constructor is invoked via reflection.
     *
     * @param capacity Total MIPS capacity of the server
     * @param hetero   Whether this is a heterogeneous server configuration
     * @param qScale   Queue scale factor used in state representation
     * @param arScale  Arrival rate scale factor used in state representation
     * @param clSize   Size of the cluster (including the cluster head)
     */
    public static void configure(double capacity, boolean hetero,
            float qScale, float arScale, int clSize) {
        pendingServerCapacity = capacity;
        pendingHeterogeneous = hetero;
        pendingQueueScale = qScale;
        pendingArrivalRateScale = arScale;
        pendingClusterSize = clSize;
    }

    /**
     * Set the gamma parameter for sensitivity analysis.
     * 
     * @param g The gamma value to use. Set to 0 for default (1.0).
     */
    public static void setGamma(double g) {
        pendingGamma = g;
    }

    /**
     * Initializes a Myopic Stackelberg pricing agent.
     * <p>
     * The agent's cost function parameters are determined by the server capacity
     * and
     * whether the server configuration is heterogeneous. For heterogeneous servers,
     * marginal cost scales inversely with capacity: {@code k_j = k_0 / s_j}.
     *
     * @param serverName        The unique name of the server node
     * @param stateSpaceDim     Dimension of the state space
     * @param minPrice          Minimum price
     * @param maxPrice          Maximum price
     * @param simulationManager The simulation manager
     */
    public StackelbergPricingAgent(String serverName, int stateSpaceDim, float minPrice, float maxPrice,
            SimulationManager simulationManager) {
        super(serverName, stateSpaceDim, minPrice, maxPrice, simulationManager);

        // Consume the pending static configuration
        this.serverCapacity = pendingServerCapacity;
        this.heterogeneous = pendingHeterogeneous;
        this.queueScale = pendingQueueScale;
        this.arrivalRateScale = pendingArrivalRateScale;
        this.clusterSize = pendingClusterSize;

        // Set gamma
        this.gamma = (pendingGamma > 0) ? pendingGamma : DEFAULT_GAMMA;

        // Set marginal cost based on server configuration
        if (this.heterogeneous && this.serverCapacity > 0) {
            // For heterogeneous: economies of scale
            this.marginalCost = BASE_MARGINAL_COST / this.serverCapacity;
        } else {
            this.marginalCost = BASE_MARGINAL_COST;
        }

        // Default demand intercept: 70% of total cluster capacity as base demand
        this.defaultIntercept = 0.7 * this.serverCapacity * this.clusterSize;

        // Initialize observation histories
        this.priceHistory = new ArrayList<>();
        this.demandHistory = new ArrayList<>();
        this.queueHistory = new ArrayList<>();

        // Initialize OLS coefficients to defaults
        this.estBeta0 = this.defaultIntercept;
        this.estBeta1 = 0.0;
        this.estBeta2 = -DEFAULT_ELASTICITY;

        System.out.println("StackelbergPricingAgent initialized for " + serverName
                + " | capacity=" + this.serverCapacity
                + " | clusterSize=" + this.clusterSize
                + " | hetero=" + this.heterogeneous
                + " | gamma=" + this.gamma
                + " | marginalCost=" + String.format("%.6f", this.marginalCost));
    }

    /**
     * Computes the optimal Stackelberg price based on the current state.
     * <p>
     * Extracts the (scaled) queue length and arrival rate from the state,
     * updates demand model parameters, and computes the closed-form optimal price.
     *
     * @param state Current state observation [scaledAvgQueueLength,
     *              scaledAvgArrivalRate]
     * @return The optimal Stackelberg price clipped to [minPrice, maxPrice]
     */
    @Override
    public float act(INDArray state) {
        // Extract state variables (reverse the scaling applied in EisimComputingNode)
        float scaledQueue = state.getFloat(0, 0);

        double avgQueueLength = (queueScale > 0) ? scaledQueue / queueScale : scaledQueue;

        // Compute total queue across cluster
        double totalQueue = avgQueueLength * this.clusterSize;

        // Get demand model parameters
        double a_q = getQueueDependentIntercept(totalQueue);
        double b = getPriceElasticity();

        // Total cluster capacity
        double totalCapacity = this.serverCapacity * this.clusterSize;

        // Compute optimal price using closed-form solution:
        // p* = a(q) / (2*b) * (1 + gamma*b/s) + k/2 + gamma*q / (2*s)
        double price;

        if (b < MIN_ELASTICITY) {
            b = MIN_ELASTICITY;
        }

        if (totalCapacity > 0) {
            double term1 = (a_q / (2.0 * b)) * (1.0 + (gamma * b) / totalCapacity);
            double term2 = marginalCost / 2.0;
            double term3 = (gamma * totalQueue) / (2.0 * totalCapacity);
            price = term1 + term2 + term3;
        } else {
            // Fallback: simple markup over marginal cost
            price = marginalCost + 0.1;
        }

        // Clip to [minPrice, maxPrice]
        float finalPrice = (float) Math.max(MIN_PRICE_FLOOR, Math.min(maxPrice, price));
        finalPrice = Math.max(minPrice, finalPrice);

        return finalPrice;
    }

    /**
     * Records the experience and updates demand estimation.
     * <p>
     * Unlike DDPG, no neural network training is performed. Instead, the
     * observation
     * is recorded for the sliding-window demand estimation.
     *
     * @param state     Initial state
     * @param action    The price action taken
     * @param reward    The reward received
     * @param nextState The next state
     */
    @Override
    public void learn(INDArray state, float action, float reward, INDArray nextState) {
        // No neural network training needed for Stackelberg agent.
        // Demand estimation updates happen via recordObservation() called from
        // StackelbergComputingNode.
    }

    /**
     * Records a demand observation for the sliding-window OLS estimator.
     * <p>
     * Called by the computing node at the end of each pricing slot with the actual
     * observed demand (tasks arrived), the price that was set, and the queue
     * length.
     *
     * @param price       The price set during the slot
     * @param demand      The actual demand observed (tasks arrived in slot)
     * @param queueLength The average queue length during the slot
     */
    public void recordObservation(double price, double demand, double queueLength) {
        priceHistory.add(price);
        demandHistory.add(demand);
        queueHistory.add(queueLength);

        // Trim to window size
        while (priceHistory.size() > WINDOW_SIZE) {
            priceHistory.remove(0);
            demandHistory.remove(0);
            queueHistory.remove(0);
        }

        // Update OLS estimates if we have enough data
        if (priceHistory.size() >= MIN_OBSERVATIONS) {
            updateDemandEstimates();
        }
    }

    /**
     * No persistent state to save for the Stackelberg agent.
     */
    @Override
    public void saveAgentState() throws IOException {
        // Stateless policy — nothing to save
    }

    // ===================== Demand Estimation (OLS) =====================

    /**
     * Updates demand model parameters using ridge regression on the sliding window.
     * <p>
     * Fits the model: D = β₀ + β₁*q + β₂*p + ε
     * using ordinary least squares with L2 regularization (ridge).
     * <p>
     * Extracts: a(q) = β₀ + β₁*q (queue-dependent intercept)
     * and b = -β₂ (price elasticity, ensuring b > 0).
     */
    private void updateDemandEstimates() {
        int n = priceHistory.size();
        if (n < MIN_OBSERVATIONS)
            return;

        // Build matrices for: D = β₀ + β₁*q + β₂*p
        // X is [n x 3]: columns are [1, q, p]
        // y is [n x 1]: demand values

        double[] y = new double[n];
        double[][] X = new double[n][3];

        for (int i = 0; i < n; i++) {
            X[i][0] = 1.0; // intercept
            X[i][1] = queueHistory.get(i); // queue length
            X[i][2] = priceHistory.get(i); // price
            y[i] = demandHistory.get(i); // demand
        }

        // Compute X^T * X + lambda * I (3x3 matrix)
        double[][] XtX = new double[3][3];
        double[] Xty = new double[3];

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double sum = 0;
                for (int k = 0; k < n; k++) {
                    sum += X[k][i] * X[k][j];
                }
                XtX[i][j] = sum;
                // Add ridge regularization (don't regularize intercept)
                if (i == j && i > 0) {
                    XtX[i][j] += REGULARIZATION_LAMBDA;
                }
            }
            // Compute X^T * y
            double sum = 0;
            for (int k = 0; k < n; k++) {
                sum += X[k][i] * y[k];
            }
            Xty[i] = sum;
        }

        // Solve 3x3 system using Cramer's rule (simple and stable enough for 3x3)
        double[] beta = solve3x3(XtX, Xty);

        if (beta != null) {
            this.estBeta0 = beta[0];
            this.estBeta1 = beta[1];
            this.estBeta2 = beta[2];
        }
    }

    /**
     * Returns the queue-dependent demand intercept: a(q) = β₀ + β₁ * q
     * with a floor of MIN_INTERCEPT.
     */
    private double getQueueDependentIntercept(double queueLength) {
        if (priceHistory.size() < MIN_OBSERVATIONS) {
            // Use default: higher queue reduces willingness to pay
            double defaultA = this.defaultIntercept * Math.max(0.3, 1.0 - 0.01 * queueLength);
            return Math.max(MIN_INTERCEPT, defaultA);
        }
        double a_q = estBeta0 + estBeta1 * queueLength;
        return Math.max(MIN_INTERCEPT, a_q);
    }

    /**
     * Returns the price elasticity: b = -β₂ (ensuring b > MIN_ELASTICITY).
     */
    private double getPriceElasticity() {
        if (priceHistory.size() < MIN_OBSERVATIONS) {
            return DEFAULT_ELASTICITY;
        }
        double b = -estBeta2;
        return Math.max(MIN_ELASTICITY, b);
    }

    // ===================== Linear Algebra Utilities =====================

    /**
     * Solves a 3x3 linear system Ax = b using Cramer's rule.
     *
     * @param A 3x3 coefficient matrix
     * @param b 3x1 right-hand side
     * @return Solution vector x, or null if the system is singular
     */
    private static double[] solve3x3(double[][] A, double[] b) {
        double det = determinant3x3(A);

        if (Math.abs(det) < 1e-12) {
            return null; // Singular matrix
        }

        double[] result = new double[3];

        for (int col = 0; col < 3; col++) {
            // Create modified matrix with column 'col' replaced by b
            double[][] modified = new double[3][3];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    modified[i][j] = (j == col) ? b[i] : A[i][j];
                }
            }
            result[col] = determinant3x3(modified) / det;
        }

        return result;
    }

    /**
     * Computes the determinant of a 3x3 matrix.
     */
    private static double determinant3x3(double[][] M) {
        return M[0][0] * (M[1][1] * M[2][2] - M[1][2] * M[2][1])
                - M[0][1] * (M[1][0] * M[2][2] - M[1][2] * M[2][0])
                + M[0][2] * (M[1][0] * M[2][1] - M[1][1] * M[2][0]);
    }
}
