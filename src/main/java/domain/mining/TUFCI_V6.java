package domain.mining;

import domain.support.SupportCalculator;
import infrastructure.persistence.UncertainDatabase;
import domain.model.*;
import domain.support.DirectConvolutionSupportCalculator;
import infrastructure.topK.TopKHeap;

import java.util.*;

/**
 * TUFCI_V6: Depth-First Search + Computation Pruning Only (P4–P7)
 *
 * <p>DFS counterpart of {@link TUFCI_V5}.  Enables all computation-level
 * shortcuts (P4–P7) while disabling search-strategy pruning (P1, P3).  P2 uses
 * {@code continue} — the only form available in DFS — so that below-threshold
 * candidates are skipped without halting the search.</p>
 *
 * <p>The closure-check logic is identical to V5.  The only structural
 * differences are those required by DFS:</p>
 * <ul>
 *   <li>A {@link Deque} (stack, LIFO) replaces the {@link PriorityQueue}.</li>
 *   <li>Extensions are sorted ascending and pushed so that the highest-support
 *       child is explored first.</li>
 *   <li>{@link #maxStackSize} is tracked as a DFS-specific memory metric.</li>
 * </ul>
 *
 * <h2>Algorithm Characteristics</h2>
 * <ul>
 *   <li><b>Search strategy:</b>  Depth-First (Stack – LIFO order)</li>
 *   <li><b>Active pruning:</b>   P2 (continue/skip), P4–P7 (computation shortcuts)</li>
 *   <li><b>Closure order:</b>    Descending support</li>
 *   <li><b>Early termination:</b> No — DFS stack holds mixed support levels</li>
 * </ul>
 *
 * <h2>Pruning Status</h2>
 * <pre>
 * P1  Phase 2 Early Termination      DISABLED   all singletons are closure-checked
 * P2  Main Loop Threshold Pruning    CONTINUE   skips below-threshold candidates
 * P3  Item Support Threshold         DISABLED   item loop visits all frequent items
 * P4  Subset-Based Upper Bound       ENABLED    tightens bound via cached 2-itemset supports
 * P5  Upper Bound Filtering          ENABLED    filters extensions by P4-tightened bound
 * P6  Tidset Size Pruning            ENABLED    skips convolution when |tidset| &lt; threshold
 * P7  Tidset-Based Closure Skip      ENABLED    skips closure check when |tidset| &lt; supX
 * </pre>
 *
 * <h2>V5 vs V6 at a glance (BFS vs DFS with identical computation pruning)</h2>
 * <pre>
 * ┌────────────────────────────┬──────────────────────────┬──────────────────────────┐
 * │ Aspect                     │ V5  (BFS + P4–P7)        │ V6  (DFS + P4–P7)        │
 * ├────────────────────────────┼──────────────────────────┼──────────────────────────┤
 * │ Data Structure             │ PriorityQueue            │ Stack (Deque)            │
 * │ Candidate Selection        │ Highest support first    │ Most recent (LIFO)       │
 * │ P2 Action                  │ continue (skip)          │ continue (skip)          │
 * │ Closure-check method       │ identical (P4–P7)        │ identical (P4–P7)        │
 * └────────────────────────────┴──────────────────────────┴──────────────────────────┘
 * </pre>
 *
 * @author Dang Nguyen Le, Gia Huy Vo
 */
public class TUFCI_V6 extends AbstractMiner {

    // ==================== Instance Variables ====================

    /** Stack for depth-first traversal in Phase 3. */
    private Deque<FrequentItemset> stack;

    // ── experiment statistics ──
    private long candidatesExplored  = 0;
    private long candidatesPruned    = 0;
    private long supportCalculations = 0;
    private long closureChecks       = 0;
    private long maxStackSize        = 0;
    private experiment.ThresholdTracker   thresholdTracker;
    private experiment.ConvergenceTracker convergenceTracker;

    // ==================== Constructors ====================

    public TUFCI_V6(UncertainDatabase database, double tau, int k) {
        super(database, tau, k, new DirectConvolutionSupportCalculator(tau));
    }

    public TUFCI_V6(UncertainDatabase database, double tau, int k, SupportCalculator calculator) {
        super(database, tau, k, calculator);
    }

    // ==================== Tracker / Metrics Setters ====================

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

    /**
     * Initialises the Top-K heap, closure-checks ALL singletons (P1 disabled),
     * and seeds the stack with 2-itemsets.
     *
     * <p><b>P1 is DISABLED.</b>  Full singleton scan, identical to V5.</p>
     *
     * @param frequent1Itemsets all singletons from Phase 1, sorted support DESC
     */
    @Override
    protected void initializeTopKWithClosedSingletons(List<FrequentItemset> frequent1Itemsets) {
        this.topK  = new TopKHeap(getK());
        this.stack = new ArrayDeque<>();

        int minsup = 0;
        int processedItemCount = 0;
        long phase2StartTime = System.nanoTime();

        for (FrequentItemset fi : frequent1Itemsets) {
            int support = fi.getSupport();

            // P1 DISABLED: no early termination — all singletons closure-checked

            processedItemCount++;
            closureChecks++;

            boolean isClosed = checkClosure1Itemset(fi, support, frequent1Itemsets, minsup);

            if (isClosed) {
                boolean wasNotFull = !topK.isFull();
                boolean inserted   = topK.insert(fi);
                if (inserted && topK.isFull()) {
                    minsup = topK.getMinSupport();
                    if (convergenceTracker != null && wasNotFull) {
                        long elapsed = System.nanoTime() - phase2StartTime;
                        convergenceTracker.recordHeapFilled(processedItemCount, elapsed);
                    }
                }
            }
        }

        // Build frequent-items array (support >= final minsup; order = DESC)
        List<Integer> frequentItemIndices = new ArrayList<>();
        for (int i = 0; i < processedItemCount; i++) {
            FrequentItemset fi = frequent1Itemsets.get(i);
            if (fi.getSupport() >= minsup) {
                frequentItemIndices.add(fi.getItems().get(0));
            }
        }

        this.frequentItemCount = frequentItemIndices.size();
        this.frequentItems     = new int[frequentItemCount];
        for (int i = 0; i < frequentItemCount; i++) {
            frequentItems[i] = frequentItemIndices.get(i);
        }

        // Seed stack — sorted ascending so highest-support entry ends up on top
        List<FrequentItemset> twoItemsets = new ArrayList<>();
        for (Map.Entry<Itemset, CachedFrequentItemset> entry : cache.entrySet()) {
            Itemset itemset = entry.getKey();
            if (itemset.size() == 2) {
                CachedFrequentItemset cached = entry.getValue();
                if (cached.getSupport() >= minsup) {
                    twoItemsets.add(cached.toFrequentItemset());
                }
            }
        }

        twoItemsets.sort((a, b) -> Integer.compare(a.getSupport(), b.getSupport()));
        for (FrequentItemset fi : twoItemsets) {
            stack.push(fi);
        }

        maxStackSize = stack.size();
    }

    // ==================== Phase 3: DFS Mining ====================

    /**
     * Depth-first search loop with P2 as {@code continue}.
     *
     * <p>The stack does not guarantee support-descending order, so P2 can only
     * skip individual below-threshold candidates.  The closure-check method is
     * identical to {@link TUFCI_V5}, ensuring the computation-pruning behaviour
     * is the same across the BFS/DFS comparison.</p>
     *
     * @param frequent1itemsets unused; required by the template-method signature
     */
    @Override
    protected void executePhase3(List<FrequentItemset> frequent1itemsets) {
        candidatesExplored = 0;
        candidatesPruned   = 0;
        long phase3StartTime = System.nanoTime();

        while (!stack.isEmpty()) {
            if (stack.size() > maxStackSize) {
                maxStackSize = stack.size();
            }

            FrequentItemset candidate = stack.pop();
            candidatesExplored++;

            int threshold = getThreshold();

            // ★ KEY [Search]: DFS — P2 continue = skip one only
            if (candidate.getSupport() < threshold) {
                candidatesPruned++;
                continue;
            }

            closureChecks++;
            // ★ KEY [Pruning]: Computation — P4/P5/P6/P7 enabled, P3 disabled
            ClosureCheckResult result = checkClosureAndGenerateExtensionsComputationOnly(candidate, threshold);

            if (result.isClosed()) {
                topK.insert(candidate);
            }

            if (convergenceTracker != null && candidatesExplored % 100 == 0) {
                long elapsed = System.nanoTime() - phase3StartTime;
                convergenceTracker.recordThresholdSnapshot(getThreshold(), candidatesExplored, elapsed);
            }

            // Push extensions in ascending-support order so highest-support
            // child is explored first
            int newThreshold = getThreshold();
            List<FrequentItemset> validExtensions = new ArrayList<>();

            for (FrequentItemset ext : result.getExtensions()) {
                if (ext.getSupport() >= newThreshold) {
                    validExtensions.add(ext);
                } else {
                    candidatesPruned++;
                }
            }

            validExtensions.sort((a, b) -> Integer.compare(a.getSupport(), b.getSupport()));
            for (FrequentItemset ext : validExtensions) {
                stack.push(ext);
            }
        }

        if (convergenceTracker != null) {
            convergenceTracker.setFinalThreshold(getThreshold());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  COMPUTATION PRUNING CLOSURE CHECK & EXTENSION GENERATION  (P4–P7; no P3)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Checks closure and generates canonical extensions with P4–P7 enabled but
     * <em>without</em> P3.  Logic is identical to
     * {@link TUFCI_V5#checkClosureAndGenerateExtensionsComputationOnly}.
     *
     * @param candidate pattern {@code X} whose closure is being tested
     * @param threshold current Top-K minimum support
     * @return closure status and the list of canonical extensions
     */
    private ClosureCheckResult checkClosureAndGenerateExtensionsComputationOnly(
            FrequentItemset candidate, int threshold) {
        int supX         = candidate.getSupport();
        boolean isClosed = true;
        List<FrequentItemset> extensions = new ArrayList<>();
        int maxItemInX   = getMaxItemIndex(candidate);

        // ── metrics counters ──
        int totalExtensions    = 0;
        int extensionsExamined = 0;
        int violationPosition  = -1;

        if (closureMetrics != null) {
            for (int idx = 0; idx < frequentItemCount; idx++) {
                int item = frequentItems[idx];
                if (!candidate.contains(item) && item > maxItemInX) {
                    totalExtensions++;
                }
            }
        }

        /**
         * Anti-monotonicity flag.
         * frequentItems is sorted DESC.  Once support({i}) < supX, all later
         * items also satisfy this.  By anti-monotonicity:
         *   support(X ∪ {i}) ≤ support({i}) < supX
         * so no later item can produce a closure violation.
         */
        boolean closureCheckingDone = false;

        // Item loop — P3 DISABLED: no break on itemSupport < threshold
        for (int idx = 0; idx < frequentItemCount; idx++) {
            int item = frequentItems[idx];

            if (candidate.contains(item)) continue;

            int itemSupport = getItemSupport(item);

            // P3 DISABLED — P5 filters sub-threshold items via upper bound
            // (see TUFCI_V5 Javadoc for detailed explanation)

            // Update anti-monotonicity flag
            if (!closureCheckingDone && itemSupport < supX) {
                closureCheckingDone = true;
            }

            boolean needClosureCheck = !closureCheckingDone && isClosed;
            boolean needExtension    = (item > maxItemInX);

            // ── P4: Subset-Based Upper Bound Tightening ──────────────────
            int upperBound = Math.min(supX, itemSupport);
            if (topK.isFull() && needExtension) {
                for (int existingItem : candidate.getItemsArray()) {
                    Itemset twoItemset = Itemset.of(getVocabulary(),
                            Math.min(existingItem, item),
                            Math.max(existingItem, item));
                    CachedFrequentItemset cachedSubset = cache.get(twoItemset);
                    if (cachedSubset != null) {
                        upperBound = Math.min(upperBound, cachedSubset.getSupport());
                        if (upperBound < threshold) {
                            break;
                        }
                    }
                }
            }

            // ── P5: Upper Bound Filtering ─────────────────────────────────
            boolean canEnterTopK           = (upperBound >= threshold);
            boolean shouldGenerateExtension = needExtension && canEnterTopK;

            // Nothing to do for this item — skip cheaply
            if (!needClosureCheck && !shouldGenerateExtension) {
                continue;
            }

            if (needExtension) {
                extensionsExamined++;
            }

            // ── Compute support(X ∪ {item}) ───────────────────────────────
            Itemset itemItemset = singletonCache[item];
            Itemset Xe = candidate.union(itemItemset);
            int    supXe  = 0;
            double probXe = 0.0;

            CachedFrequentItemset cached = cache.get(Xe);
            if (cached != null) {
                // Cache hit – reuse
                supXe  = cached.getSupport();
                probXe = cached.getProbability();
            } else {
                // Cache miss – intersect tidsets
                CachedFrequentItemset xInfo    = cache.get(candidate);
                CachedFrequentItemset itemInfo = cache.get(itemItemset);

                Tidset tidsetXe;
                if (xInfo == null || itemInfo == null) {
                    tidsetXe = getDatabase().getTidset(candidate)
                                           .intersect(getDatabase().getTidset(itemItemset));
                } else {
                    tidsetXe = xInfo.getTidset().intersect(itemInfo.getTidset());
                }

                int tidsetSize = tidsetXe.size();

                // ── P6: Tidset Size Pruning ─────────────────────────────
                // Do NOT cache: support unknown; avoids cache poisoning.
                if (tidsetSize < threshold && !needClosureCheck) {
                    continue;
                }

                // ── P7: Tidset-Based Early Closure Detection ────────────
                if (needClosureCheck && tidsetSize < supX) {
                    if (!shouldGenerateExtension) {
                        continue;
                    }
                    needClosureCheck = false;
                }

                supportCalculations++;
                double[] result = getCalculator().computeProbabilisticSupportFromTidset(
                        tidsetXe, getDatabase().size());
                supXe  = (int) result[0];
                probXe = result[1];

                // Cache for reuse in deeper search levels
                cache.put(Xe, new CachedFrequentItemset(Xe, supXe, probXe, tidsetXe));
            }

            // ── Closure violation check ────────────────────────────────────
            if (needClosureCheck && supXe == supX) {
                isClosed = false;
                if (violationPosition < 0) {
                    violationPosition = extensionsExamined;
                }
            }

            // ── Canonical extension ────────────────────────────────────────
            if (shouldGenerateExtension) {
                extensions.add(new FrequentItemset(Xe, supXe, probXe));
            }
        }

        if (closureMetrics != null) {
            closureMetrics.recordClosureCheck(totalExtensions, extensionsExamined, isClosed, violationPosition);
        }

        return new ClosureCheckResult(isClosed, extensions);
    }

    // ==================== Results ====================

    @Override
    protected List<FrequentItemset> getTopKResults() {
        List<FrequentItemset> results = topK.getAll();
        results.sort(FrequentItemset::compareBySupport);
        return results;
    }

    // ==================== Statistics ====================

    public long getCandidatesExplored()   { return candidatesExplored; }
    public long getCandidatesPruned()     { return candidatesPruned; }
    public long getSupportCalculations()  { return supportCalculations; }
    public long getClosureChecks()        { return closureChecks; }
    public long getMaxStackSize()         { return maxStackSize; }

    public String getVariantName()        { return "V6 (DFS + P4-P7)"; }
}
