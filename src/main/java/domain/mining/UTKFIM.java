package domain.mining;

import domain.support.DirectConvolutionSupportCalculator;
import domain.support.SupportCalculator;
import infrastructure.persistence.UncertainDatabase;
import infrastructure.persistence.Vocabulary;
import domain.model.*;
import infrastructure.topK.TopKHeap;

import java.util.*;

/**
 * UTKFIM – Uncertain TKFIM: Equivalence-class baseline for top-k CLOSED frequent
 * itemset mining from uncertain databases.
 *
 * <h2>Provenance (External Baseline)</h2>
 * Adapts the TKFIM algorithm published in:
 * <blockquote>
 *   Iqbal S, Shahid A, Roman M, Khan Z, Al-Otaibi S, Yu L. 2021.
 *   "TKFIM: Top-K frequent itemset mining technique based on equivalence classes."
 *   <i>PeerJ Comput. Sci.</i> 7:e385.
 *   DOI: <a href="https://doi.org/10.7717/peerj-cs.385">10.7717/peerj-cs.385</a>
 * </blockquote>
 *
 * <h2>Adaptations Made (Certain → Uncertain + Closed)</h2>
 * <ol>
 *   <li><b>Probabilistic support via GF/DP:</b> TKFIM's exact diffset formula
 *       {@code sup(X∪Y) = sup(X) − |tidset(X) − tidset(Y)|} only works on 0/1
 *       tidsets. It is replaced by:
 *       <pre>  tidset(Xi ∪ {extensionItem}) = tidset(Xi) ∩ singleton_tidset(extensionItem)</pre>
 *       followed by the same GF/DP probabilistic support calculation as TUFCI.
 *       <p><b>Critical correctness detail (shared-prefix double-counting):</b>
 *       At level ≥ 3 the two items joined (Xi, Xj) share a common prefix.
 *       Computing {@code tidset(Xi) ∩ tidset(Xj)} multiplies the shared-prefix
 *       probability twice — giving {@code P(prefix)²·P(A)·P(B)} instead of the
 *       correct {@code P(prefix)·P(A)·P(B)}.  The fix is always to intersect with
 *       the <em>singleton</em> tidset of the new extension item only.</p></li>
 *   <li><b>Closure checking at every level:</b> Applied to the 2-itemset seed level
 *       (which TKFIM's original loop never checks) and to every generated level
 *       thereafter.</li>
 *   <li><b>Equivalence-class search strategy preserved:</b> TKFIM's prefix-based
 *       grouping and within-group pairwise joining with support-ordered break
 *       pruning are kept intact.</li>
 * </ol>
 *
 * <h2>Algorithm</h2>
 * <pre>
 * Phase 1  Compute probabilistic support for all 1-itemsets.
 * Phase 2  Closure-check 1-itemsets → insert closed ones into Top-K heap.
 *          Cache all 2-itemset supports as a side effect.
 * Phase 3  TKFIM equivalence-class expansion (level-wise):
 *   currentLevel ← 2-itemsets meeting threshold
 *   closure-check currentLevel (the 2-itemset level, never checked in Ph. 2)
 *   while currentLevel ≠ ∅:
 *     Group currentLevel by prefix (= all items except the last)
 *     for each prefix group G (sorted by support DESC):
 *       for each pair (Xi, Xj) in G  [Xj's last item = extensionItem]:
 *         tidset(joined) = tidset(Xi) ∩ singleton_tidset(extensionItem)   // KEY
 *         support via GF/DP
 *         if support ≥ smallestK → add to nextLevel
 *         else → break (TKFIM pruning within group)
 *     closure-check nextLevel → insert closed patterns into Top-K heap
 *     currentLevel ← nextLevel pruned at updated smallestK
 * </pre>
 *
 * <h2>Key Differences vs. TUFCI (V1)</h2>
 * <ul>
 *   <li>Level-wise BFS (by itemset size) vs. best-first (PriorityQueue by support DESC)</li>
 *   <li>No P1–P7 pruning strategies</li>
 *   <li>No early global termination</li>
 *   <li>Deferred closure checking (after the full level is generated)</li>
 * </ul>
 *
 * <h2>Input / Output — identical contract to all TUFCI variants</h2>
 * <ul>
 *   <li>Input:  {@link UncertainDatabase}, tau (double), k (int)</li>
 *   <li>Output: {@code List<FrequentItemset>} sorted by support DESC</li>
 * </ul>
 */
public class UTKFIM {

    // ── Configuration ──────────────────────────────────────────────────────────
    private final UncertainDatabase database;
    private final double             tau;
    private final int                k;
    private final SupportCalculator  calculator;
    private final Vocabulary         vocab;

    // ── Statistics ─────────────────────────────────────────────────────────────
    private long candidatesGenerated = 0;
    private long candidatesPruned    = 0;
    private long supportCalculations = 0;
    private long closureChecks       = 0;
    private int  maxLevel            = 0;

    // ══════════════════════════════════════════════════════════════════════════
    // Construction
    // ══════════════════════════════════════════════════════════════════════════

    public UTKFIM(UncertainDatabase database, double tau, int k) {
        if (database == null || database.size() == 0)
            throw new IllegalArgumentException("Database must be non-null and non-empty");
        if (tau <= 0 || tau > 1)
            throw new IllegalArgumentException("tau must be in (0, 1]");
        if (k < 1)
            throw new IllegalArgumentException("k must be >= 1");

        this.database   = database;
        this.tau        = tau;
        this.k          = k;
        this.calculator = new DirectConvolutionSupportCalculator(tau);
        this.vocab      = database.getVocabulary();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════════

    public List<FrequentItemset> mine() {

        candidatesGenerated = candidatesPruned = supportCalculations = closureChecks = 0;
        maxLevel = 0;

        TopKHeap topK      = new TopKHeap(k);
        int      vocabSize = vocab.size();

        // Shared tidset / support cache: Itemset → CachedFrequentItemset
        Map<Itemset, CachedFrequentItemset> cache = new HashMap<>();

        // Pre-create all singleton Itemsets to avoid repeated allocation
        Itemset[] singletonCache = new Itemset[vocabSize];
        for (int i = 0; i < vocabSize; i++) {
            singletonCache[i] = new Itemset(vocab);
            singletonCache[i].add(i);
        }

        // ─────────────────────────────────────────────────────────────────────
        // PHASE 1 – Compute probabilistic support for all 1-itemsets
        // ─────────────────────────────────────────────────────────────────────
        List<CachedFrequentItemset> all1Itemsets = new ArrayList<>(vocabSize);

        for (int i = 0; i < vocabSize; i++) {
            Tidset tidset = database.getTidset(singletonCache[i]);
            if (tidset.isEmpty()) continue;

            double[] res = calculator.computeProbabilisticSupportFromTidset(
                    tidset, database.size());
            supportCalculations++;

            CachedFrequentItemset cfi = new CachedFrequentItemset(
                    singletonCache[i], (int) res[0], res[1], tidset);
            all1Itemsets.add(cfi);
            cache.put(singletonCache[i], cfi);
        }

        // TKFIM Step 2–3: sort by support DESC; ties broken by item ID ASC
        all1Itemsets.sort((a, b) -> {
            int c = Integer.compare(b.getSupport(), a.getSupport());
            return c != 0 ? c : Integer.compare(
                    a.getItems().get(0), b.getItems().get(0));
        });

        // ─────────────────────────────────────────────────────────────────────
        // PHASE 2 – Closure-check 1-itemsets; cache all 2-itemset supports
        //
        // {A} is closed iff ∀B with sup({B}) ≥ sup({A}): sup({A,B}) < sup({A}).
        // 2-itemset tidsets are tidset(A) ∩ tidset(B) — both singletons, no
        // shared-prefix issue at this stage.
        // ─────────────────────────────────────────────────────────────────────
        for (CachedFrequentItemset cfA : all1Itemsets) {
            int supA  = cfA.getSupport();
            int itemA = cfA.getItems().get(0);
            closureChecks++;

            boolean closed = true;

            for (CachedFrequentItemset cfB : all1Itemsets) {
                int itemB = cfB.getItems().get(0);
                if (itemA == itemB) continue;
                if (cfB.getSupport() < supA) break;   // anti-monotone + sorted DESC

                int lo = Math.min(itemA, itemB), hi = Math.max(itemA, itemB);
                Itemset pairKey = Itemset.of(vocab, lo, hi);

                CachedFrequentItemset pairCfi = cache.get(pairKey);
                if (pairCfi == null) {
                    Tidset tidPair = cfA.getTidset().intersect(cfB.getTidset());
                    if (tidPair.isEmpty()) {
                        cache.put(pairKey,
                            new CachedFrequentItemset(pairKey, 0, 0.0, tidPair));
                        continue;
                    }
                    double[] r2 = calculator.computeProbabilisticSupportFromTidset(
                            tidPair, database.size());
                    supportCalculations++;
                    pairCfi = new CachedFrequentItemset(
                            pairKey, (int) r2[0], r2[1], tidPair);
                    cache.put(pairKey, pairCfi);
                }

                if (pairCfi.getSupport() == supA) { closed = false; break; }
            }

            if (closed) topK.insert(cfA.toFrequentItemset());
        }

        // ─────────────────────────────────────────────────────────────────────
        // PHASE 3 – TKFIM equivalence-class expansion (level-wise)
        // ─────────────────────────────────────────────────────────────────────
        int smallestK = topK.isFull() ? topK.getMinSupport() : 0;

        // Seed: 2-itemsets computed in Phase 2 that meet the current threshold
        List<CachedFrequentItemset> currentLevel = new ArrayList<>();
        for (Map.Entry<Itemset, CachedFrequentItemset> e : cache.entrySet()) {
            if (e.getKey().size() == 2 && e.getValue().getSupport() >= smallestK) {
                currentLevel.add(e.getValue());
            }
        }
        currentLevel.sort((a, b) -> Integer.compare(b.getSupport(), a.getSupport()));

        // ── Closure-check 2-itemsets (Phase 2 only checked 1-itemsets) ────────
        // Without this step every closed 2-itemset would be missed entirely.
        smallestK = topK.isFull() ? topK.getMinSupport() : 0;
        for (CachedFrequentItemset candidate : currentLevel) {
            int supX = candidate.getSupport();
            if (supX < smallestK) continue;
            closureChecks++;
            if (checkClosure(candidate, supX, all1Itemsets, cache, singletonCache)) {
                boolean ins = topK.insert(candidate.toFrequentItemset());
                if (ins) smallestK = topK.isFull() ? topK.getMinSupport() : 0;
            }
        }

        int level = 2;

        while (!currentLevel.isEmpty()) {
            maxLevel  = level;
            smallestK = topK.isFull() ? topK.getMinSupport() : 0;

            // Prune candidates that fell below the updated threshold
            final int thr = smallestK;
            currentLevel.removeIf(c -> c.getSupport() < thr);
            if (currentLevel.isEmpty()) break;

            // ── Group by prefix; generate next level ──────────────────────────
            Map<List<Integer>, List<CachedFrequentItemset>> prefixGroups =
                    groupByPrefix(currentLevel);

            List<CachedFrequentItemset> nextLevel = new ArrayList<>();
            Set<Itemset> seenNext = new HashSet<>();

            for (List<CachedFrequentItemset> group : prefixGroups.values()) {
                // Sort by support DESC within group → enables TKFIM break pruning
                group.sort((a, b) -> Integer.compare(b.getSupport(), a.getSupport()));

                int gsize = group.size();

                for (int i = 0; i < gsize; i++) {
                    CachedFrequentItemset Xi = group.get(i);
                    if (Xi.getSupport() < smallestK) break;

                    for (int j = i + 1; j < gsize; j++) {
                        CachedFrequentItemset Xj = group.get(j);
                        if (Xj.getSupport() < smallestK) break;   // TKFIM pruning

                        // ── Identify the extension item ───────────────────────
                        // Xi = prefix + [itemA],  Xj = prefix + [itemB]  (B > A)
                        // Joined itemset = prefix + [itemA, itemB]
                        // The only new item added to Xi is itemB = last item of Xj.
                        List<Integer> xjItems = Xj.getItems();
                        int extensionItem = xjItems.get(xjItems.size() - 1);

                        // Key for the joined itemset = Xi ∪ {extensionItem}
                        // (equals Xi ∪ Xj because prefix ⊆ Xi already)
                        Itemset joinedKey = Xi.union(singletonCache[extensionItem]);
                        if (!seenNext.add(joinedKey)) continue;

                        candidatesGenerated++;

                        // ── Cache lookup ──────────────────────────────────────
                        CachedFrequentItemset joinedCfi = cache.get(joinedKey);
                        if (joinedCfi == null) {

                            // ── CORRECT tidset formula ────────────────────────
                            // tidset(Xi ∪ {ext}) = tidset(Xi) ∩ singleton_tidset(ext)
                            //
                            // WRONG alternative: tidset(Xi) ∩ tidset(Xj)
                            //   Xi = {prefix... A}: tidset has P(prefix)*P(A) per tid
                            //   Xj = {prefix... B}: tidset has P(prefix)*P(B) per tid
                            //   Direct intersection → P(prefix)²·P(A)·P(B)  ← WRONG
                            //
                            // Correct: intersect Xi's tidset with B's *singleton* tidset
                            //   tidset(Xi)           : P(prefix)·P(A) per tid
                            //   singleton_tidset(B)  : P(B) per tid
                            //   Result               : P(prefix)·P(A)·P(B)  ← CORRECT
                            CachedFrequentItemset extSingCfi =
                                    cache.get(singletonCache[extensionItem]);
                            if (extSingCfi == null) continue; // empty-tidset item

                            Tidset tidJoined = Xi.getTidset()
                                    .intersect(extSingCfi.getTidset());

                            // |tidset| is a cheap upper bound on probabilistic support
                            if (tidJoined.size() < smallestK) {
                                candidatesPruned++;
                                continue;
                            }

                            double[] rj = calculator
                                    .computeProbabilisticSupportFromTidset(
                                            tidJoined, database.size());
                            int supJ = (int) rj[0];
                            supportCalculations++;

                            joinedCfi = new CachedFrequentItemset(
                                    joinedKey, supJ, rj[1], tidJoined);
                            cache.put(joinedKey, joinedCfi);
                        }

                        if (joinedCfi.getSupport() >= smallestK) {
                            nextLevel.add(joinedCfi);
                        } else {
                            candidatesPruned++;
                            // TKFIM pruning: group is sorted support DESC;
                            // subsequent Xj will also produce support < threshold.
                            break;
                        }
                    }
                }
            }

            // ── Closure-check every candidate in nextLevel ───────────────────
            smallestK = topK.isFull() ? topK.getMinSupport() : 0;

            for (CachedFrequentItemset candidate : nextLevel) {
                int supX = candidate.getSupport();
                if (supX < smallestK) { candidatesPruned++; continue; }
                closureChecks++;

                if (checkClosure(candidate, supX, all1Itemsets, cache, singletonCache)) {
                    boolean ins = topK.insert(candidate.toFrequentItemset());
                    if (ins) smallestK = topK.isFull() ? topK.getMinSupport() : 0;
                }
            }

            // ── Advance ──────────────────────────────────────────────────────
            smallestK = topK.isFull() ? topK.getMinSupport() : 0;
            final int thr2 = smallestK;
            nextLevel.removeIf(c -> c.getSupport() < thr2);
            nextLevel.sort((a, b) -> Integer.compare(b.getSupport(), a.getSupport()));

            currentLevel = nextLevel;
            level++;

            if (level > vocabSize) break;   // safety guard
        }

        List<FrequentItemset> results = topK.getAll();
        results.sort(FrequentItemset::compareBySupport);
        return results;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Private helpers
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Groups candidates by their prefix (sorted item list minus the last element).
     * Two k-itemsets share a prefix iff they can be joined under TKFIM's
     * equivalence-class rule to form a valid (k+1)-itemset.
     */
    private Map<List<Integer>, List<CachedFrequentItemset>> groupByPrefix(
            List<CachedFrequentItemset> candidates) {
        Map<List<Integer>, List<CachedFrequentItemset>> groups = new LinkedHashMap<>();
        for (CachedFrequentItemset cfi : candidates) {
            List<Integer> items = cfi.getItems();   // sorted ascending (BitSet order)
            if (items.isEmpty()) continue;
            List<Integer> prefix =
                    new ArrayList<>(items.subList(0, items.size() - 1));
            groups.computeIfAbsent(prefix, ignored -> new ArrayList<>()).add(cfi);
        }
        return groups;
    }

    /**
     * Returns {@code true} iff {@code candidate} is a closed itemset.
     *
     * <p>X is closed iff ∀ item i ∉ X with {@code sup({i}) ≥ sup(X)}:
     * {@code sup(X ∪ {i}) < sup(X)}.
     *
     * <p>The extension tidset is computed as
     * {@code tidset(X) ∩ singleton_tidset(i)}, yielding probability
     * {@code P(X) · P(i)} per transaction — the correct joint probability.
     *
     * @param candidate     itemset X
     * @param supX          probabilistic support of X
     * @param all1Itemsets  1-itemsets sorted by support DESC
     * @param cache         shared cache
     * @param singletonCache pre-built singleton Itemset array (index = item ID)
     */
    private boolean checkClosure(
            CachedFrequentItemset candidate,
            int supX,
            List<CachedFrequentItemset> all1Itemsets,
            Map<Itemset, CachedFrequentItemset> cache,
            Itemset[] singletonCache) {

        for (CachedFrequentItemset singleCfi : all1Itemsets) {
            int item = singleCfi.getItems().get(0);

            if (candidate.contains(item)) continue;

            // Anti-monotone: sup(X∪{i}) ≤ sup({i}); if sup({i}) < supX → no violation
            if (singleCfi.getSupport() < supX) break;

            Itemset extKey = candidate.union(singletonCache[item]);
            CachedFrequentItemset extCfi = cache.get(extKey);

            if (extCfi == null) {
                // singleCfi.getTidset() is the singleton tidset of item i → P(i) per tid
                // candidate.getTidset()  has P(all items in X) per tid
                // Intersection → P(X) · P(i) = P(X ∪ {i})  per tid  ✓
                Tidset tidExt = candidate.getTidset()
                        .intersect(singleCfi.getTidset());

                if (tidExt.size() < supX) continue;   // cheap upper-bound guard

                double[] re = calculator.computeProbabilisticSupportFromTidset(
                        tidExt, database.size());
                supportCalculations++;
                extCfi = new CachedFrequentItemset(
                        extKey, (int) re[0], re[1], tidExt);
                cache.put(extKey, extCfi);
            }

            if (extCfi.getSupport() == supX) return false;   // closure violated
        }
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Statistics / metadata (mirrors TUFCI variant interface)
    // ══════════════════════════════════════════════════════════════════════════

    public long getCandidatesGenerated() { return candidatesGenerated; }
    public long getCandidatesPruned()    { return candidatesPruned; }
    public long getSupportCalculations() { return supportCalculations; }
    public long getClosureChecks()       { return closureChecks; }
    public int  getMaxLevel()            { return maxLevel; }

    public String getVariantName() {
        return "U-TKFIM (Uncertain TKFIM, DOI:10.7717/peerj-cs.385)";
    }
}