package domain.mining;

import domain.support.SupportCalculator;
import infrastructure.persistence.UncertainDatabase;
import infrastructure.persistence.Vocabulary;
import domain.model.*;
import domain.support.DirectConvolutionSupportCalculator;
import infrastructure.topK.TopKHeap;

import java.util.*;
import java.util.stream.Collectors;

/**
 * TUFCI_V1: Best-First Search + Full Pruning (P1-P7) + Descending Closure Order
 *
 * This is the PRIMARY algorithm variant for experiments.
 *
 * <h2>Algorithm Characteristics:</h2>
 * <ul>
 *   <li><b>Search Strategy:</b> Best-First (PriorityQueue ordered by support DESC)</li>
 *   <li><b>Pruning:</b> Full (P1-P7 all enabled)</li>
 *   <li><b>Closure Order:</b> Descending (check items by support high→low)</li>
 *   <li><b>Early Termination:</b> Yes (when best candidate < threshold)</li>
 * </ul>
 *
 * <h2>Pruning Strategies Enabled:</h2>
 * <pre>
 * P1: Early Termination (Phase 2)
 * P2: Main Loop Threshold Pruning
 * P3: Item Support Threshold
 * P4: Subset-Based Upper Bound
 * P5: Upper Bound Filtering
 * P6: Tidset Size Pruning
 * P7: Tidset-Based Closure Skip
 * </pre>
 *
 * @author Dang Nguyen Le, Gia Huy Vo
 */
public class TUFCI_V1 extends AbstractMiner {

    // ==================== Instance Variables ====================

    private PriorityQueue<FrequentItemset> pq;

    // Statistics for experiments
    private long candidatesExplored = 0;
    private long candidatesPruned = 0;
    private long supportCalculations = 0;
    private long closureChecks = 0;
    private experiment.ThresholdTracker thresholdTracker;
    private experiment.ConvergenceTracker convergenceTracker;

    // ==================== Constructors ====================

    public TUFCI_V1(UncertainDatabase database, double tau, int k) {
        super(database, tau, k, new DirectConvolutionSupportCalculator(tau));
    }

    public TUFCI_V1(UncertainDatabase database, double tau, int k, SupportCalculator calculator) {
        super(database, tau, k, calculator);
    }

    public void setThresholdTracker(experiment.ThresholdTracker tracker) {
        this.thresholdTracker = tracker;
    }

    public void setClosureMetrics(experiment.ClosureMetrics metrics) {
        this.closureMetrics = metrics;
    }

    public void setConvergenceTracker(experiment.ConvergenceTracker tracker) {
        this.convergenceTracker = tracker;
    }

    // ==================== Phase 2: Initialize ====================

    @Override
    protected void initializeTopKWithClosedSingletons(List<FrequentItemset> frequent1Itemsets) {
        this.topK = new TopKHeap(getK());
        this.pq = new PriorityQueue<>(FrequentItemset::compareForPriorityQueue);

        int minsup = 0;
        int processedItemCount = 0;
        long phase2StartTime = System.nanoTime();

        // Process 1-itemsets with P1: Early Termination
        for (FrequentItemset fi : frequent1Itemsets) {
            int support = fi.getSupport();

            // P1a: Phase 2 Early Termination
            if (topK.isFull() && support < minsup) {
                break;
            }

            processedItemCount++;
            closureChecks++;

            boolean isClosed = checkClosure1Itemset(fi, support, frequent1Itemsets, minsup);

            if (isClosed) {
                boolean wasNotFull = !topK.isFull();
                boolean inserted = topK.insert(fi);
                if (inserted && topK.isFull()) {
                    minsup = topK.getMinSupport();
                    // Record when heap becomes full
                    if (convergenceTracker != null && wasNotFull) {
                        long elapsed = System.nanoTime() - phase2StartTime;
                        convergenceTracker.recordHeapFilled(processedItemCount, elapsed);
                    }
                }
            }
        }

        // Build frequent items array (descending order by support)
        List<Integer> frequentItemIndices = new ArrayList<>();
        for (int i = 0; i < processedItemCount; i++) {
            FrequentItemset fi = frequent1Itemsets.get(i);
            if (fi.getSupport() >= minsup) {
                frequentItemIndices.add(fi.getItems().get(0));
            }
        }

        this.frequentItemCount = frequentItemIndices.size();
        this.frequentItems = new int[frequentItemCount];
        for (int i = 0; i < frequentItemCount; i++) {
            frequentItems[i] = frequentItemIndices.get(i);
        }

        // Seed PQ with 2-itemsets
        for (Map.Entry<Itemset, CachedFrequentItemset> entry : cache.entrySet()) {
            Itemset itemset = entry.getKey();
            if (itemset.size() == 2) {
                CachedFrequentItemset cached = entry.getValue();
                if (cached.getSupport() >= minsup) {
                    pq.add(cached.toFrequentItemset());
                }
            }
        }
    }

    // ==================== Phase 3: Best-First Mining ====================

    @Override
    protected void executePhase3(List<FrequentItemset> frequent1itemsets) {
        candidatesExplored = 0;
        candidatesPruned = 0;
        long phase3StartTime = System.nanoTime();

        while (!pq.isEmpty()) {
            FrequentItemset candidate = pq.poll();
            candidatesExplored++;

            int threshold = getThreshold();

            // ★ KEY [Search]: BFS — P2 break = global termination
            // P2b: Early Termination - Best-First allows this!
            if (candidate.getSupport() < threshold) {
                candidatesPruned += pq.size() + 1;
                break;
            }

            closureChecks++;
            // ★ KEY [Pruning]: Full — P4/P5/P6/P7 shortcuts active
            ClosureCheckResult result = checkClosureAndGenerateExtensions(candidate, threshold);

            if (result.isClosed()) {
                topK.insert(candidate);
            }

            // Record convergence snapshot every 100 candidates
            if (convergenceTracker != null && candidatesExplored % 100 == 0) {
                long elapsed = System.nanoTime() - phase3StartTime;
                convergenceTracker.recordThresholdSnapshot(getThreshold(), candidatesExplored, elapsed);
            }

            int newThreshold = getThreshold();
            for (FrequentItemset ext : result.getExtensions()) {
                if (ext.getSupport() >= newThreshold) {
                    pq.add(ext);
                } else {
                    candidatesPruned++;
                }
            }
        }

        // Set final threshold for convergence calculations
        if (convergenceTracker != null) {
            convergenceTracker.setFinalThreshold(getThreshold());
        }
    }

    // ==================== Results ====================

    @Override
    protected List<FrequentItemset> getTopKResults() {
        List<FrequentItemset> results = topK.getAll();
        results.sort(FrequentItemset::compareBySupport);
        return results;
    }

    // ==================== Statistics ====================

    public long getCandidatesExplored() { return candidatesExplored; }
    public long getCandidatesPruned() { return candidatesPruned; }
    public long getSupportCalculations() { return supportCalculations; }
    public long getClosureChecks() { return closureChecks; }

    public String getVariantName() { return "V1 (Best-First + Full Pruning)"; }
}