package domain.mining;

import domain.support.SupportCalculator;
import infrastructure.persistence.UncertainDatabase;
import domain.model.*;
import domain.support.DirectConvolutionSupportCalculator;
import infrastructure.topK.TopKHeap;

import java.util.*;

/**
 * TUFCI_V5: Best-First Search + Computation Pruning Only (P4–P7)
 *
 * <p>Experimental variant that enables all computation-level shortcuts (P4–P7)
 * while disabling search-strategy pruning (P1, P3).  P2 is retained as a
 * {@code continue} skip — not the BFS-specific {@code break} — so the only
 * BFS-dependent termination rule (P2 {@code break}) is absent.</p>
 *
 * <p>Together with {@link TUFCI_V6} (the DFS mirror), V5 isolates the
 * contribution of P4–P7 computation shortcuts from any search-traversal
 * benefit.</p>
 *
 * <h2>Algorithm Characteristics</h2>
 * <ul>
 *   <li><b>Search strategy:</b>  Best-First (PriorityQueue ordered by support DESC)</li>
 *   <li><b>Active pruning:</b>   P2 (continue/skip), P4–P7 (computation shortcuts)</li>
 *   <li><b>Closure order:</b>    Descending support</li>
 *   <li><b>Early termination:</b> No — P2 is {@code continue} only.  After the
 *       dynamic threshold is reached the PQ is drained, skipping each
 *       candidate individually.</li>
 * </ul>
 *
 * <h2>Pruning Status</h2>
 * <pre>
 * P1  Phase 2 Early Termination      DISABLED   all singletons are closure-checked
 * P2  Main Loop Threshold Pruning    CONTINUE   skips below-threshold candidates (no global break)
 * P3  Item Support Threshold         DISABLED   item loop visits all frequent items
 * P4  Subset-Based Upper Bound       ENABLED    tightens bound via cached 2-itemset supports
 * P5  Upper Bound Filtering          ENABLED    filters extensions by P4-tightened bound
 * P6  Tidset Size Pruning            ENABLED    skips convolution when |tidset| &lt; threshold
 * P7  Tidset-Based Closure Skip      ENABLED    skips closure check when |tidset| &lt; supX
 * </pre>
 *
 * <h2>Why only P2 {@code break} is BFS-specific</h2>
 * <p>P1 relies on the singleton list sorted DESC (Phase 1 output — the same
 * in BFS and DFS); P3 relies on {@code frequentItems} sorted DESC (also
 * search-order-independent).  Both are search-strategy decisions but do
 * <em>not</em> require BFS.  P2 {@code break} is the only rule that requires
 * the PQ's global ordering guarantee: "if the top candidate is below threshold,
 * all remaining candidates are too."</p>
 * <p>V5 disables P1, P3 (search-strategy) and P2-break (BFS-specific),
 * keeping only P4–P7 (computation) and the anti-monotonicity flag
 * (mathematical property).</p>
 *
 * <h2>V1 vs V5 at a glance</h2>
 * <pre>
 * ┌────────────────────────────┬──────────────────────────┬──────────────────────────┐
 * │ Aspect                     │ V1  (Full P1–P7)         │ V5  (P4–P7 only)         │
 * ├────────────────────────────┼──────────────────────────┼──────────────────────────┤
 * │ Phase 2 singleton loop     │ P1 break                 │ no break (full scan)     │
 * │ Phase 3 main loop          │ P2 break                 │ P2 continue              │
 * │ Item-iteration break       │ P3 break at sup &lt; thr    │ no break                 │
 * │ Upper-bound tightening     │ P4 via cached 2-itemsets │ P4 via cached 2-itemsets │
 * │ Extension pre-filter       │ P5 by tightened bound    │ P5 by tightened bound    │
 * │ Tidset-size shortcuts      │ P6 + P7                  │ P6 + P7                  │
 * └────────────────────────────┴──────────────────────────┴──────────────────────────┘
 * </pre>
 *
 * @author Dang Nguyen Le, Gia Huy Vo
 */
public class TUFCI_V5 extends AbstractMiner {

    // ==================== Instance Variables ====================

    /** Priority queue for best-first traversal in Phase 3. */
    private PriorityQueue<FrequentItemset> pq;

    // ── experiment statistics ──
    private long candidatesExplored  = 0;
    private long candidatesPruned    = 0;
    private long supportCalculations = 0;
    private long closureChecks       = 0;
    private experiment.ThresholdTracker   thresholdTracker;
    private experiment.ConvergenceTracker convergenceTracker;

    // ==================== Constructors ====================

    public TUFCI_V5(UncertainDatabase database, double tau, int k) {
        super(database, tau, k, new DirectConvolutionSupportCalculator(tau));
    }

    public TUFCI_V5(UncertainDatabase database, double tau, int k, SupportCalculator calculator) {
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
     * and seeds the priority queue with 2-itemsets.
     *
     * <p><b>P1 is DISABLED.</b>  The entire singleton list is scanned regardless
     * of heap state.</p>
     *
     * @param frequent1Itemsets all singletons from Phase 1, sorted support DESC
     */
    @Override
    protected void initializeTopKWithClosedSingletons(List<FrequentItemset> frequent1Itemsets) {
        this.topK = new TopKHeap(getK());
        this.pq   = new PriorityQueue<>(FrequentItemset::compareForPriorityQueue);

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

        // Seed PQ with cached 2-itemsets that meet the final threshold
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

    /**
     * Best-first search loop with P2 as {@code continue} only.
     *
     * <p>The PQ still delivers candidates in support-descending order, so the
     * dynamic threshold rises quickly as high-support closed patterns are found.
     * However P2 does <em>not</em> terminate the search when a below-threshold
     * candidate is polled — it skips that candidate and continues.  Because the
     * PQ is sorted, every subsequent candidate is also below threshold and will
     * likewise be skipped, so the loop drains the PQ without further closure
     * checks after the threshold is reached.</p>
     *
     * <p>Inside each iteration
     * {@link #checkClosureAndGenerateExtensionsComputationOnly} applies P4–P7
     * but omits P3, isolating computation pruning from the item-loop search
     * strategy.</p>
     *
     * @param frequent1itemsets unused; required by the template-method signature
     */
    @Override
    protected void executePhase3(List<FrequentItemset> frequent1itemsets) {
        candidatesExplored = 0;
        candidatesPruned   = 0;
        long phase3StartTime = System.nanoTime();

        while (!pq.isEmpty()) {
            FrequentItemset candidate = pq.poll();
            candidatesExplored++;

            int threshold = getThreshold();

            // ★ KEY [Search]: BFS — P2 continue only (no global break)
            // P2: skip below-threshold candidate.  Do NOT terminate search.
            // (PQ is sorted, so all remaining are also below threshold and will
            //  be skipped one by one — but we do not break early.)
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

            int newThreshold = getThreshold();
            for (FrequentItemset ext : result.getExtensions()) {
                if (ext.getSupport() >= newThreshold) {
                    pq.add(ext);
                } else {
                    candidatesPruned++;
                }
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
     * <em>without</em> P3.
     *
     * <p>The item loop visits every item in {@code frequentItems} — no P3 break.
     * Items with {@code support(i) &lt; threshold} are still reached, but they are
     * filtered cheaply:</p>
     * <ol>
     *   <li>Anti-monotonicity flag stops closure checks once
     *       {@code support({i}) &lt; supX}.</li>
     *   <li>P4 computes {@code upperBound = min(supX, support(i))}.  When
     *       {@code support(i) &lt; threshold} this is already &lt; threshold.</li>
     *   <li>P5: {@code canEnterTopK = false}  →  {@code shouldGenerateExtension = false}.</li>
     *   <li>Both flags false  →  {@code continue}.  No tidset intersection, no convolution.</li>
     * </ol>
     * <p>For items that survive P5 (support ≥ threshold):</p>
     * <ol>
     *   <li>Tidset intersection is computed.</li>
     *   <li>P6 skips the convolution when {@code |tidset| &lt; threshold}.</li>
     *   <li>P7 skips the closure check when {@code |tidset| &lt; supX}.</li>
     * </ol>
     *
     * @param candidate pattern {@code X} whose closure is being tested
     * @param threshold current Top-K minimum support (cached from caller)
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

            // P3 DISABLED — items with support < threshold are still visited.
            // They are filtered by P5 (see Javadoc above): upperBound < threshold
            // ⟹ canEnterTopK = false, and anti-monotonicity ensures
            // needClosureCheck = false.  Net effect: cheap continue below.

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
                            break;   // early exit from 2-itemset loop
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
                // support(Xe) ≤ tidsetSize.  If tidset < threshold and no
                // closure check needed, skip the expensive convolution.
                // Do NOT cache: support unknown; avoids cache poisoning.
                if (tidsetSize < threshold && !needClosureCheck) {
                    continue;
                }

                // ── P7: Tidset-Based Early Closure Detection ────────────
                // If tidset < supX, closure violation is impossible.
                if (needClosureCheck && tidsetSize < supX) {
                    if (!shouldGenerateExtension) {
                        continue;   // nothing left to do
                    }
                    needClosureCheck = false;   // extension still needed
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

    public String getVariantName()        { return "V5 (Best-First + P4-P7)"; }
}
