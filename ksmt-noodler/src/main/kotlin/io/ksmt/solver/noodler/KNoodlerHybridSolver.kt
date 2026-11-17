@file:Suppress("UNCHECKED_CAST")

package io.ksmt.solver.noodler

import io.ksmt.KContext
import io.ksmt.decl.KDecl
import io.ksmt.expr.KExpr
import io.ksmt.expr.KIntNumExpr
import io.ksmt.expr.KTrue
import io.ksmt.solver.KModel
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.KSolverException
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.model.KFuncInterp
import io.ksmt.solver.model.KFuncInterpVarsFree
import io.ksmt.solver.noodler.hybrid.AutomataLimitException
import io.ksmt.solver.noodler.hybrid.AutomataLimits
import io.ksmt.solver.noodler.hybrid.AutomatonLengthProjection
import io.ksmt.solver.noodler.hybrid.AutomataWitnessSearch
import io.ksmt.solver.noodler.hybrid.CandidateRealization
import io.ksmt.solver.noodler.hybrid.HybridCheckControl
import io.ksmt.solver.noodler.hybrid.HybridIncomplete
import io.ksmt.solver.noodler.hybrid.NoodlerRefiner
import io.ksmt.solver.noodler.hybrid.NoodlerYicesModel
import io.ksmt.solver.noodler.hybrid.PeriodicWordEquality
import io.ksmt.solver.noodler.hybrid.PreprocessingBudget
import io.ksmt.solver.noodler.hybrid.PreprocessingLimitException
import io.ksmt.solver.noodler.hybrid.RegexAutomata
import io.ksmt.solver.noodler.hybrid.StringAbstraction
import io.ksmt.solver.noodler.hybrid.StringAtom
import io.ksmt.solver.noodler.hybrid.StringEquation
import io.ksmt.solver.noodler.hybrid.StringTermPart
import io.ksmt.solver.noodler.hybrid.SymbolicDfa
import io.ksmt.solver.noodler.hybrid.SolverFreePreflight
import io.ksmt.sort.KBoolSort
import io.ksmt.sort.KIntSort
import io.ksmt.sort.KSort
import io.ksmt.sort.KStringSort
import io.ksmt.utils.ArithUtils.bigIntegerValue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * Unbounded, sound (but incomplete) hybrid string solver over ordinary KSMT assertions.
 * A backend searches Boolean/length candidates; automata propagate necessary conditions and an
 * independent backend instance realizes exact characters. Only translated arithmetic/BV constraints
 * reach either backend. This is distinct from [KNoodlerSolver]'s native-string preprocessing.
 *
 * Each check owns and closes two fresh native solver contexts created by [backendFactory]. They
 * share this expression [KContext], as required by KSMT, but never share mutable native solver state.
 * The caller owns the KContext. Assertions/scopes are buffered; no native resource survives a check.
 * Configure before the first check. The detached model contains only original declarations.
 */
open class KNoodlerHybridSolver(
    private val ctx: KContext,
    private val backendFactory: KNoodlerBackendFactory = KNoodlerBackend.YICES,
) : KSolver<KNoodlerHybridSolverConfiguration> {
    private data class Assertion(val expression: KExpr<KBoolSort>, val tracked: Boolean)

    private val assertions = arrayListOf<Assertion>()
    private val scopes = ArrayDeque<Int>()
    private var configuration = NoodlerYicesConfiguration()
    private var configuredContextUsed = false
    private var closed = false
    private val checking = AtomicBoolean(false)
    private val interrupted = AtomicBoolean(false)
    private val nativeLock = Any()
    private var activeNative: KSolver<*>? = null
    private var lastStatus: KSolverStatus? = null
    private var lastModel: KModel? = null
    private var lastCore: List<KExpr<KBoolSort>> = emptyList()
    private var lastReason: String = "no check has been performed"

    var lastCheckStatistics: CheckStatistics? = null
        private set

    override fun configure(configurator: KNoodlerHybridSolverConfiguration.() -> Unit) {
        ensureMutable()
        check(!configuredContextUsed) { "Configure the hybrid solver before the first check" }
        configuration = configuration.copy().apply(configurator)
    }

    override fun assert(expr: KExpr<KBoolSort>) = addAssertion(expr, tracked = false)

    override fun assertAndTrack(expr: KExpr<KBoolSort>) = addAssertion(expr, tracked = true)

    private fun addAssertion(expr: KExpr<KBoolSort>, tracked: Boolean) {
        ensureMutable()
        ctx.ensureContextMatch(expr)
        assertions += Assertion(expr, tracked)
        invalidate()
    }

    override fun push() {
        ensureMutable()
        scopes.addLast(assertions.size)
        invalidate()
    }

    override fun pop(n: UInt) {
        ensureMutable()
        require(n <= scopes.size.toUInt()) { "Cannot pop $n levels from ${scopes.size}" }
        repeat(n.toInt()) {
            val size = scopes.removeLast()
            assertions.subList(size, assertions.size).clear()
        }
        if (n != 0u) invalidate()
    }

    override fun check(timeout: Duration): KSolverStatus = checkWithAssumptions(emptyList(), timeout)

    override fun checkWithAssumptions(assumptions: List<KExpr<KBoolSort>>, timeout: Duration): KSolverStatus {
        check(!closed) { "Solver is closed" }
        ctx.ensureContextMatch(assumptions)
        synchronized(nativeLock) {
            check(checking.compareAndSet(false, true)) { "A check is already running" }
            interrupted.set(false)
        }
        invalidate()
        configuredContextUsed = true
        val statistics = MutableStatistics()
        val input = assertions.map { it.expression } + assumptions
        val control = HybridCheckControl(timeout, interrupted)
        try {
            lastStatus = solve(input, control, statistics)
            if (lastStatus == KSolverStatus.UNSAT) {
                // Conservative but valid relative to the untracked assertions; auxiliaries never escape.
                lastCore = (assertions.filter { it.tracked }.map { it.expression } + assumptions).distinct()
            }
            return requireNotNull(lastStatus)
        } catch (ex: HybridIncomplete) {
            return unknown(ex.reason)
        } catch (ex: PreprocessingLimitException) {
            return unknown(ex.message ?: "preprocessing resource limit")
        } catch (ex: AutomataLimitException) {
            return unknown("automata resource limit: ${ex.message}")
        } catch (ex: KSolverException) {
            return unknown(ex.message ?: "Backend could not complete the check")
        } finally {
            if (lastStatus == KSolverStatus.UNKNOWN) statistics.failurePhase = statistics.phase
            statistics.terminationReason = when (lastStatus) {
                KSolverStatus.SAT -> "validated model"
                KSolverStatus.UNSAT -> statistics.preflightReason ?: "inconsistent unbounded abstraction"
                else -> lastReason
            }
            lastCheckStatistics = statistics.snapshot()
            checking.set(false)
        }
    }

    private fun solve(
        input: List<KExpr<KBoolSort>>,
        control: HybridCheckControl,
        statistics: MutableStatistics,
    ): KSolverStatus = CheckSession(input, control, statistics).solve()

    /** All learned information and both native contexts belong to this one check. */
    private inner class CheckSession(
        private val input: List<KExpr<KBoolSort>>,
        private val control: HybridCheckControl,
        private val statistics: MutableStatistics,
    ) {
        private val limits = AutomataLimits(configuration.maxAutomataStates, configuration.maxAutomataStorage)
        private val preprocessing = PreprocessingBudget(configuration.maxPreprocessingUnits, control::checkpoint)
        private val optionalBudget by lazy { preprocessing.limited(preprocessing.remaining / OPTIONAL_BUDGET_SHARE) }
        private val optionalCheckpoint: () -> Unit = { optionalBudget.reserve() }
        private val regex = RegexAutomata(control::checkpoint, preprocessing, limits)
        private val abstraction = StringAbstraction(
            ctx, regex, control::checkpoint, preprocessing,
        )
        private val branches = hashMapOf<List<Boolean>, BranchState>()
        private val complements = hashMapOf<SymbolicDfa, SymbolicDfa>()
        private val lengthProjection = AutomatonLengthProjection(ctx, configuration.adaptiveLengthProjection)
        private val skippedBranches = hashSetOf<List<Boolean>>()
        private val learned = hashSetOf<KExpr<KBoolSort>>()
        private val progress = IdleRoundTracker(configuration.maxIdleRounds)
        private val boundGuards = hashMapOf<Int, KExpr<KBoolSort>>()
        private var lengthBound = 0
        private var seededLengths = false
        private var optionalWorkExhausted = false
        private lateinit var search: KSolver<*>
        private lateinit var realization: KSolver<*>

        fun solve(): KSolverStatus {
            control.checkpoint()
            val skeleton = statistics.measure("compilation") {
                try {
                    input.map(abstraction::translate)
                } finally {
                    statistics.regexDeterminizations = regex.determinizationCount
                    statistics.regexPeakIntermediateAutomata = regex.peakIntermediateAutomata
                    statistics.regexCachedRoots = regex.cachedRootCount
                }
            }
            // This optional proof never submits constraints to either backend. Resource failure
            // abandons only the proof; cancellation/deadline exceptions still terminate the check.
            val proof = statistics.measure("preflight") {
                try {
                    SolverFreePreflight(ctx, abstraction, control::checkpoint,
                        preprocessing.limited(minOf(MAX_PREFLIGHT_UNITS, preprocessing.remaining / 8)),
                        limits, configuration.periodicLengthReasoning).prove(input)
                } catch (ex: AutomataLimitException) {
                    control.checkpoint()
                    statistics.preflightSkippedReason = ex.message
                    null
                }
            }
            if (proof != null) {
                statistics.preflightReason = proof.reason
                statistics.periodicLengthLemmas += proof.periodicCertificates
                return KSolverStatus.UNSAT
            }
            statistics.backendConstructions++
            return backendFactory.create(ctx).use { searchContext ->
                search = CountingBackend(searchContext, statistics)
                configuration.configure(search)
                search.assert(skeleton)
                search.assert(abstraction.definitions)
                if (!configuration.optionalPreprocessing) seedRegularLengths()
                statistics.backendConstructions++
                backendFactory.create(ctx).use { realizationContext ->
                    require(realizationContext !== searchContext) {
                        "Backend factory must create independent solver instances for search and realization"
                    }
                    realization = CountingBackend(realizationContext, statistics)
                    configuration.configure(realization)
                    searchCandidates()
                }
            }
        }

        private fun learn(constraint: KExpr<KBoolSort>): Boolean {
            control.checkpoint()
            if (constraint is KTrue || !learned.add(constraint)) return false
            search.assert(constraint)
            statistics.learnedConstraints++
            return true
        }

        private fun seedRegularLengths() {
            if (seededLengths) return
            seededLengths = true
            for (atom in abstraction.atoms.values.filterIsInstance<StringAtom.Membership>()) {
                if (configuration.optionalPreprocessing) {
                    optionalPreparation { seedLength(atom, optionalCheckpoint) }
                } else {
                    seedLength(atom, control::checkpoint)
                }
            }
        }

        private fun seedLength(atom: StringAtom.Membership, checkpoint: () -> Unit) {
            statistics.measure("seed-lengths") {
                checkpoint()
                val length = if (configuration.optionalPreprocessing) {
                    optionalBudget.reserve(atom.term.size.toLong())
                    val parts = atom.term.map {
                        when (it) {
                            is StringTermPart.Variable -> abstraction.lengths.getValue(it.declaration)
                            is StringTermPart.Literal -> ctx.mkIntNum(it.codePoints.size)
                        }
                    }
                    when (parts.size) {
                        0 -> ctx.mkIntNum(0)
                        1 -> parts.single()
                        else -> ctx.mkArithAddNoSimplify(parts)
                    }
                } else {
                    abstraction.length(atom.term)
                }
                val positive = projectedLength(length, atom.language, checkpoint)
                learn(ctx.mkImplies(atom.proxy, positive))
                val negative = projectedLength(length, complement(atom.language), checkpoint)
                learn(ctx.mkImplies(ctx.mkNot(atom.proxy), negative))
            }
        }

        private fun searchCandidates(): KSolverStatus {
            while (true) {
                control.checkpoint()
                val candidate = findCandidate() ?: return KSolverStatus.UNSAT
                val completed = candidate.use(::checkCandidate)
                if (completed != null) return completed
            }
        }

        private fun searchCheck(assumptions: List<KExpr<KBoolSort>> = emptyList()): KSolverStatus {
            statistics.rounds++
            val status = statistics.measure("search") { nativeCheck(search, control, assumptions) }
            if (status == KSolverStatus.UNKNOWN) throw HybridIncomplete(search.reasonOfUnknown())
            return status
        }

        /** Only the assumption-free check can establish unbounded UNSAT. */
        private fun findCandidate(): KModel? {
            if (!configuration.smallFirstSearch) {
                return if (searchCheck() == KSolverStatus.SAT) search.model().detach() else null
            }
            while (true) {
                val guard = boundGuards.getOrPut(lengthBound) {
                    val lengths = abstraction.lengths.values.toList()
                    val total = if (lengths.isEmpty()) ctx.mkIntNum(0) else ctx.mkArithAdd(lengths)
                    val bound = ctx.mkArithLe(total, ctx.mkIntNum(lengthBound))
                    ctx.mkFreshConst("noodler.length.bound", ctx.boolSort).also {
                        search.assert(ctx.mkImplies(it, bound))
                        statistics.boundedSearchBounds += lengthBound
                    }
                }
                if (searchCheck(listOf(guard)) == KSolverStatus.SAT) return search.model().detach()
                if (searchCheck() == KSolverStatus.UNSAT) return null
                if (lengthBound == configuration.maxCharacterPositions) characterResourceLimit()
                lengthBound = minOf(
                    configuration.maxCharacterPositions.toLong(), maxOf(1L, lengthBound.toLong() * 2),
                ).toInt()
            }
        }

        private fun checkCandidate(candidate: KModel): KSolverStatus? {
            val atoms = abstraction.atoms.values.toList()
            val polarities = atoms.map { candidate.eval(it.proxy, isComplete = true) is KTrue }
            val cube = ctx.mkAnd(atoms.zip(polarities) { atom, positive ->
                if (positive) atom.proxy else ctx.mkNot(atom.proxy)
            })
            val before = statistics.learnedConstraints
            if (configuration.optionalPreprocessing) {
                // Exact SAT requires no necessary-condition preparation. Failed exact candidates
                // still contribute their sound blocker before optional generalization is attempted.
                val result = realizeCandidate(candidate, atoms, polarities, cube)
                if (result != null) return result
                seedRegularLengths()
                if (polarities !in skippedBranches) {
                    val prepared = optionalPreparation {
                        prepareCandidate(candidate, atoms, polarities, cube)
                    }
                    if (prepared == null) skippedBranches += polarities
                }
                recordProgress(statistics.learnedConstraints != before)
                return null
            }
            val prepared = prepareCandidate(candidate, atoms, polarities, cube)
            if (prepared.excluded) {
                recordProgress(prepared.changed || statistics.learnedConstraints != before)
                return null
            }
            val result = realizeCandidate(candidate, atoms, polarities, cube)
            if (result == null) recordProgress(prepared.changed || statistics.learnedConstraints != before)
            return result
        }

        private fun prepareCandidate(
            candidate: KModel, atoms: List<StringAtom>, polarities: List<Boolean>, cube: KExpr<KBoolSort>,
        ): BranchProgress {
            val checkpoint = if (configuration.optionalPreprocessing) optionalCheckpoint else control::checkpoint
            val workBudget = if (configuration.optionalPreprocessing) optionalBudget else preprocessing
            val branch = statistics.measure("branch") {
                branches.getOrPut(polarities) {
                    makeBranch(abstraction, atoms, polarities, checkpoint, limits, ::complement)
                }
            }
            // Candidate lengths never restrict these languages. Once a complete sweep makes no
            // change and its length consequences are published, the same atom cube needs no rerun.
            if (branch.refinementStable) {
                statistics.refinementSkips++
                return BranchProgress(excluded = false, changed = false)
            }
            val refinement = statistics.measure("refinement") {
                statistics.refinementSweeps++
                NoodlerRefiner(checkpoint, workBudget, limits).refine(branch.languages, branch.equations)
            }
            branch.languages = refinement.languages
            if (refinement.contradiction) {
                learn(ctx.mkNot(cube))
                return BranchProgress(excluded = true, changed = refinement.changed)
            }
            val periodicExcluded = propagatePeriodicLengths(branch, atoms, polarities, cube, candidate)
            val excluded = propagateLengths(branch, cube, candidate, checkpoint) || periodicExcluded
            branch.refinementStable = !refinement.changed
            return BranchProgress(excluded, refinement.changed)
        }

        /** A common word-power domain makes equality equivalent to equality of unbounded lengths. */
        @Suppress("ComplexMethod", "LoopWithTooManyJumpStatements") // Cheap guards precede optional DFA proofs.
        private fun propagatePeriodicLengths(
            branch: BranchState, atoms: List<StringAtom>, polarities: List<Boolean>,
            cube: KExpr<KBoolSort>, candidate: KModel,
        ): Boolean {
            if (!configuration.periodicLengthReasoning) return false
            val parent = if (configuration.optionalPreprocessing) optionalBudget else preprocessing
            val allowance = minOf(MAX_PERIODIC_UNITS, parent.remaining / PERIODIC_BUDGET_SHARE)
            if (allowance == 0) return false
            val budget = parent.limited(allowance)
            var excluded = false
            statistics.measure("periodic-lengths") {
                try {
                    for ((index, atom) in atoms.withIndex()) {
                        budget.reserve()
                        if (atom !is StringAtom.Equality || polarities[index]) continue
                        if (atom.equation in branch.periodicEquations) continue
                        if (!PeriodicWordEquality.provesLengthEquivalence(
                            atom.equation, branch.languages, control::checkpoint, budget,
                        )) continue
                        val unequalLengths = ctx.mkNot(ctx.mkEq(
                            abstraction.length(atom.equation.left, budget),
                            abstraction.length(atom.equation.right, budget),
                        ))
                        val lemma = ctx.mkImplies(cube, unequalLengths)
                        branch.periodicEquations += atom.equation
                        if (learn(lemma)) {
                            statistics.periodicLengthLemmas++
                            if (candidate.eval(lemma, isComplete = true) !is KTrue) excluded = true
                        }
                    }
                } catch (_: AutomataLimitException) {
                    // Optional proof failure adds no condition. Keep previously certified lemmas
                    // and continue with ordinary length propagation and exact realization.
                    control.checkpoint()
                }
            }
            return excluded
        }

        private fun <T> optionalPreparation(action: () -> T): T? {
            control.checkpoint()
            if (optionalWorkExhausted) return null
            return try {
                action()
            } catch (resource: AutomataLimitException) {
                control.checkpoint()
                statistics.optionalPreprocessingSkips++
                statistics.lastOptionalSkipReason = resource.message
                if (resource is PreprocessingLimitException) optionalWorkExhausted = true
                null
            }
        }

        private fun propagateLengths(
            branch: BranchState, cube: KExpr<KBoolSort>, candidate: KModel, checkpoint: () -> Unit,
        ): Boolean {
            var candidateExcluded = false
            for ((declaration, language) in branch.languages) {
                val length = abstraction.lengths[declaration] ?: continue
                val lemma = statistics.measure("propagate-lengths") {
                    val consequence = projectedLength(length, language, checkpoint)
                    ctx.mkImplies(cube, consequence)
                }
                if (learn(lemma) && candidate.eval(lemma, isComplete = true) !is KTrue) candidateExcluded = true
            }
            return candidateExcluded
        }

        private fun complement(language: SymbolicDfa): SymbolicDfa =
            complements.getOrPut(language) { language.complement() }

        private fun projectedLength(
            length: KExpr<KIntSort>, language: SymbolicDfa, checkpoint: () -> Unit,
        ): KExpr<KBoolSort> = try {
            lengthProjection.constraint(length, language, checkpoint)
        } finally {
            statistics.lengthProjectionFallbacks = lengthProjection.fallbacks
            statistics.lengthProjectionCacheHits = lengthProjection.cacheHits
        }

        private fun realizeCandidate(
            candidate: KModel, atoms: List<StringAtom>, polarities: List<Boolean>, cube: KExpr<KBoolSort>,
        ): KSolverStatus? {
            statistics.lastCandidateEncodingEstimate = null
            statistics.lastCandidateEncodingConstraints = null
            val lengths = statistics.measure("candidate-lengths") {
                candidateLengths(abstraction, candidate, control, statistics)
            }
            statistics.realizationChecks++
            if (tryAutomataWitness(candidate, polarities, lengths)) return KSolverStatus.SAT
            realization.push()
            try {
                var witness: Map<KDecl<KStringSort>, IntArray>? = null
                val realizer = statistics.measure("encoding") {
                    statistics.lastCandidateEncodingEstimate = estimateEncoding(atoms, lengths)
                    CandidateRealization(
                        ctx, realization, lengths, control::checkpoint,
                        configuration.maxCharacterPositions, configuration.maxEncodingConstraints,
                        preprocessing, configuration.maxMembershipPruningUnits,
                        encodingTarget = configuration.encodingTarget,
                    ).also { encoder ->
                        try {
                            atoms.zip(polarities).forEach { (atom, positive) -> encoder.assertAtom(atom, positive) }
                        } finally {
                            statistics.lastCandidateEncodingConstraints = encoder.encodedConstraints
                            statistics.membershipTransitionsConsidered += encoder.membershipTransitionsConsidered
                            statistics.membershipTransitionsEncoded += encoder.membershipTransitionsEncoded
                            statistics.membershipPruningFallbacks += encoder.membershipPruningFallbacks
                        }
                    }
                }
                val status = statistics.measure("realization") {
                    val result = nativeCheck(realization, control, onStart = { statistics.nativeRealizationChecks++ })
                    if (result == KSolverStatus.SAT) realization.model().use { witness = realizer.strings(it) }
                    result
                }
                when (status) {
                    KSolverStatus.SAT -> {
                        statistics.measure("validation") {
                            preprocessing.reserve(requireNotNull(witness).values.sumOf { it.size.toLong() })
                            val model = makeModel(abstraction, candidate, requireNotNull(witness), limits)
                            if (!model.validates(input, control::checkpoint, preprocessing)) {
                                throw HybridIncomplete("joint witness validation failed")
                            }
                            lastModel = model
                        }
                        return KSolverStatus.SAT
                    }
                    KSolverStatus.UNSAT -> {
                        val lengthCube = lengths.map { (declaration, value) ->
                            ctx.mkEq(abstraction.lengths.getValue(declaration), ctx.mkIntNum(value))
                        }
                        // Exact infeasibility rejects this complete candidate, never all lengths.
                        learn(ctx.mkNot(ctx.mkAnd(lengthCube + cube)))
                    }
                    KSolverStatus.UNKNOWN -> throw HybridIncomplete(realization.reasonOfUnknown())
                }
                return null
            } finally {
                realization.pop()
            }
        }

        /** A guessed path assignment proves only SAT after joint validation; failure learns nothing. */
        private fun tryAutomataWitness(
            candidate: KModel, polarities: List<Boolean>, lengths: Map<KDecl<KStringSort>, Int>,
        ): Boolean {
            if (!configuration.automataWitnessSearch) return false
            val branch = branches[polarities] ?: return false
            val allowance = minOf(MAX_WITNESS_UNITS, preprocessing.remaining / WITNESS_BUDGET_SHARE)
            if (allowance == 0) return false
            val budget = preprocessing.limited(allowance)
            statistics.automataWitnessAttempts++
            val accepted = statistics.measure("automata-witness") {
                try {
                    val witness = AutomataWitnessSearch.chooseWitness(
                        branch.languages, lengths, control::checkpoint, budget,
                    ) ?: return@measure false
                    budget.reserve(witness.values.sumOf { it.size.toLong() })
                    val model = makeModel(abstraction, candidate, witness, limits)
                    var validated = false
                    try {
                        validated = model.validates(input, control::checkpoint, budget)
                        if (validated) lastModel = model
                        validated
                    } finally {
                        if (!validated) model.close()
                    }
                } catch (_: AutomataLimitException) {
                    // Optional path search/validation exhausted its allowance. Exact realization
                    // still has the parent budget; cancellation and deadlines are not swallowed.
                    control.checkpoint()
                    false
                }
            }
            if (accepted) statistics.automataWitnessSuccesses++ else statistics.automataWitnessFallbacks++
            return accepted
        }

        private fun recordProgress(changed: Boolean) {
            if (progress.record(changed)) {
                throw HybridIncomplete("stalled after ${configuration.maxIdleRounds} idle rounds")
            }
        }

        private fun estimateEncoding(atoms: List<StringAtom>, lengths: Map<KDecl<KStringSort>, Int>): Long {
            fun size(term: List<StringTermPart>): Long = term.fold(0L) { total, part ->
                control.checkpoint()
                saturatedAdd(total, when (part) {
                    is StringTermPart.Variable -> lengths.getValue(part.declaration).toLong()
                    is StringTermPart.Literal -> part.codePoints.size.toLong()
                })
            }
            var estimate = saturatedMultiply(lengths.values.sumOf { it.toLong() }, CHARACTER_BOUND_CONSTRAINTS)
            for (atom in atoms) {
                val cost = when (atom) {
                    is StringAtom.Equality -> {
                        val left = size(atom.equation.left)
                        if (left == size(atom.equation.right)) left else 0L
                    }
                    is StringAtom.Membership -> {
                        val edges = atom.language.transitions.sumOf { it.size.toLong() }
                        val stepCost = saturatedAdd(saturatedMultiply(edges, DFA_EDGE_CONSTRAINTS), 1)
                        val steps = saturatedMultiply(size(atom.term), stepCost)
                        saturatedAdd(steps, atom.language.acceptingStates.size.toLong())
                    }
                }
                estimate = saturatedAdd(estimate, cost)
            }
            return estimate
        }
    }

    @Suppress("LongParameterList", "NestedBlockDepth") // Branch construction shares check-local caches and limits.
    private fun makeBranch(
        abstraction: StringAbstraction,
        atoms: List<StringAtom>,
        polarities: List<Boolean>,
        checkpoint: () -> Unit,
        limits: AutomataLimits,
        complement: (SymbolicDfa) -> SymbolicDfa,
    ): BranchState {
        val languages = abstraction.lengths.keys.associateWithTo(linkedMapOf()) {
            checkpoint()
            SymbolicDfa.allWords(limits)
        }
        val equations = arrayListOf<StringEquation>()
        val restricted = hashSetOf<KDecl<KStringSort>>()
        atoms.zip(polarities).forEach { (atom, positive) ->
            checkpoint()
            when (atom) {
                is StringAtom.Equality -> if (positive) equations += atom.equation
                is StringAtom.Membership -> {
                    val language = if (positive) atom.language else complement(atom.language)
                    val variable = atom.term.singleOrNull() as? StringTermPart.Variable
                    if (variable != null) {
                        languages[variable.declaration] = if (restricted.add(variable.declaration)) {
                            language
                        } else {
                            languages.getValue(variable.declaration).intersection(language, checkpoint)
                        }
                    } else {
                        val auxiliary = ctx.mkFreshConstDecl("noodler.regular.term", ctx.stringSort)
                        languages[auxiliary] = language
                        equations += StringEquation(atom.term, listOf(StringTermPart.Variable(auxiliary)))
                    }
                }
            }
        }
        return BranchState(languages, equations)
    }

    private fun candidateLengths(
        abstraction: StringAbstraction, candidate: KModel, control: HybridCheckControl,
        statistics: MutableStatistics,
    ): Map<KDecl<KStringSort>, Int> {
        val values = abstraction.lengths.mapValues { (_, length) ->
            control.checkpoint()
            (candidate.eval(length, isComplete = true) as? KIntNumExpr)?.bigIntegerValue
                ?: throw HybridIncomplete("Backend did not assign a length")
        }
        statistics.lastCandidateTotalLength = values.values.fold(java.math.BigInteger.ZERO) { sum, value ->
            sum + value
        }.toString()
        var positions = 0L
        return values.mapValues { (_, value) ->
            control.checkpoint()
            if (value.signum() < 0 || value > configuration.maxCharacterPositions.toBigInteger()) {
                characterResourceLimit()
            }
            val size = value.intValueExact()
            positions += size
            if (positions > configuration.maxCharacterPositions) {
                characterResourceLimit()
            }
            size
        }
    }

    private fun characterResourceLimit(): Nothing =
        throw HybridIncomplete("character realization resource limit (${configuration.maxCharacterPositions})")

    private fun makeModel(
        abstraction: StringAbstraction,
        candidate: KModel,
        strings: Map<KDecl<KStringSort>, IntArray>,
        limits: AutomataLimits,
    ): NoodlerYicesModel {
        val interpretations = linkedMapOf<KDecl<*>, KFuncInterp<*>>()
        for (declaration in abstraction.originalDeclarations) {
            val value = if (declaration.sort is KStringSort) {
                ctx.mkStringLiteral(strings.getValue(declaration as KDecl<KStringSort>))
            } else {
                candidate.eval(ctx.mkConstApp(declaration), isComplete = true)
            }
            interpretations[declaration] = KFuncInterpVarsFree(
                declaration as KDecl<KSort>, emptyList(), value as KExpr<KSort>
            )
        }
        return NoodlerYicesModel(ctx, interpretations, limits)
    }

    private fun nativeCheck(
        solver: KSolver<*>, control: HybridCheckControl,
        assumptions: List<KExpr<KBoolSort>> = emptyList(), onStart: () -> Unit = {},
    ): KSolverStatus {
        synchronized(nativeLock) { activeNative = solver }
        try {
            val remaining = control.remaining()
            onStart()
            val result = if (assumptions.isEmpty()) solver.check(remaining) else {
                solver.checkWithAssumptions(assumptions, remaining)
            }
            control.checkpoint()
            return result
        } finally {
            synchronized(nativeLock) { activeNative = null }
        }
    }

    override fun model(): KModel {
        check(!closed && lastStatus == KSolverStatus.SAT) { "Model is only available after SAT" }
        return requireNotNull(lastModel)
    }

    override fun unsatCore(): List<KExpr<KBoolSort>> {
        check(!closed && lastStatus == KSolverStatus.UNSAT) { "Unsat core is only available after UNSAT" }
        return lastCore.toList()
    }

    override fun reasonOfUnknown(): String {
        check(!closed && lastStatus == KSolverStatus.UNKNOWN) { "Unknown reason is only available after UNKNOWN" }
        return lastReason
    }

    override fun interrupt() {
        synchronized(nativeLock) {
            interrupted.set(true)
            activeNative?.interrupt()
        }
    }

    override fun close() {
        if (closed) return
        ensureMutable()
        closed = true
        assertions.clear()
        scopes.clear()
        invalidate()
    }

    private fun ensureMutable() {
        check(!closed) { "Solver is closed" }
        check(!checking.get()) { "Cannot change a solver while check is running" }
    }

    private fun invalidate() {
        lastStatus = null
        lastModel = null
        lastCore = emptyList()
        lastCheckStatistics = null
    }

    private fun unknown(reason: String): KSolverStatus {
        lastReason = reason
        lastStatus = KSolverStatus.UNKNOWN
        return KSolverStatus.UNKNOWN
    }

    private data class BranchState(
        var languages: Map<KDecl<KStringSort>, SymbolicDfa>, val equations: List<StringEquation>,
        var refinementStable: Boolean = false,
        val periodicEquations: MutableSet<StringEquation> = hashSetOf(),
    )

    private data class BranchProgress(val excluded: Boolean, val changed: Boolean)

    data class CheckStatistics(
        val rounds: Int,
        val learnedConstraints: Int,
        val realizationChecks: Int,
        val noodlerTimeMs: Long,
        val searchTimeMs: Long,
        val realizationTimeMs: Long,
        val terminationReason: String,
        val failurePhase: String? = null,
        val optionalPreprocessingSkips: Int = 0,
        val lastOptionalSkipReason: String? = null,
        val boundedSearchBounds: List<Int> = emptyList(),
        val nativeRealizationChecks: Int = 0,
        val lastCandidateTotalLength: String? = null,
        val lastCandidateEncodingEstimate: Long? = null,
        val phaseTimesMs: Map<String, Long> = emptyMap(),
        val lengthProjectionFallbacks: Int = 0,
        val lengthProjectionCacheHits: Int = 0,
        val lastCandidateEncodingConstraints: Long? = null,
        val membershipTransitionsConsidered: Long = 0,
        val membershipTransitionsEncoded: Long = 0,
        val membershipPruningFallbacks: Int = 0,
        val regexDeterminizations: Int = 0,
        val regexPeakIntermediateAutomata: Int = 0,
        val regexCachedRoots: Int = 0,
        val refinementSweeps: Int = 0,
        val refinementSkips: Int = 0,
        val automataWitnessAttempts: Int = 0,
        val automataWitnessSuccesses: Int = 0,
        val automataWitnessFallbacks: Int = 0,
        val periodicLengthLemmas: Int = 0,
        /** Factory invocations, submitted assertion expressions, and check calls, respectively. */
        val backendConstructions: Int = 0,
        val backendAssertions: Long = 0,
        val backendChecks: Int = 0,
        val preflightReason: String? = null,
        val preflightSkippedReason: String? = null,
    )

    private class MutableStatistics {
        var rounds = 0
        var learnedConstraints = 0
        var realizationChecks = 0
        var noodlerNanos = 0L
        var searchNanos = 0L
        var realizationNanos = 0L
        var terminationReason = "unknown"
        var phase = "initialization"
        var failurePhase: String? = null
        var optionalPreprocessingSkips = 0
        var lastOptionalSkipReason: String? = null
        val boundedSearchBounds = arrayListOf<Int>()
        var nativeRealizationChecks = 0
        var lastCandidateTotalLength: String? = null
        var lastCandidateEncodingEstimate: Long? = null
        var lengthProjectionFallbacks = 0
        var lengthProjectionCacheHits = 0
        var lastCandidateEncodingConstraints: Long? = null
        var membershipTransitionsConsidered = 0L
        var membershipTransitionsEncoded = 0L
        var membershipPruningFallbacks = 0
        var regexDeterminizations = 0
        var regexPeakIntermediateAutomata = 0
        var regexCachedRoots = 0
        var refinementSweeps = 0
        var refinementSkips = 0
        var automataWitnessAttempts = 0
        var automataWitnessSuccesses = 0
        var automataWitnessFallbacks = 0
        var periodicLengthLemmas = 0
        var backendConstructions = 0
        var backendAssertions = 0L
        var backendChecks = 0
        var preflightReason: String? = null
        var preflightSkippedReason: String? = null
        private val phaseNanos = linkedMapOf<String, Long>()

        fun <T> measure(name: String, action: () -> T): T {
            phase = name
            val started = System.nanoTime()
            try {
                return action()
            } finally {
                val elapsed = System.nanoTime() - started
                phaseNanos[name] = (phaseNanos[name] ?: 0L) + elapsed
                when (name) {
                    "preflight", "seed-lengths", "refinement", "propagate-lengths", "periodic-lengths" ->
                        noodlerNanos += elapsed
                    "search" -> searchNanos += elapsed
                    "encoding", "realization", "automata-witness" -> realizationNanos += elapsed
                }
            }
        }

        fun snapshot() = CheckStatistics(
            rounds, learnedConstraints, realizationChecks,
            noodlerNanos / NANOS_PER_MILLISECOND, searchNanos / NANOS_PER_MILLISECOND,
            realizationNanos / NANOS_PER_MILLISECOND, terminationReason,
            failurePhase, optionalPreprocessingSkips, lastOptionalSkipReason, boundedSearchBounds.toList(),
            nativeRealizationChecks, lastCandidateTotalLength, lastCandidateEncodingEstimate,
            phaseNanos.mapValues { (_, value) -> value / NANOS_PER_MILLISECOND },
            lengthProjectionFallbacks, lengthProjectionCacheHits,
            lastCandidateEncodingConstraints, membershipTransitionsConsidered, membershipTransitionsEncoded,
            membershipPruningFallbacks,
            regexDeterminizations, regexPeakIntermediateAutomata, regexCachedRoots,
            refinementSweeps, refinementSkips,
            automataWitnessAttempts, automataWitnessSuccesses, automataWitnessFallbacks,
            periodicLengthLemmas,
            backendConstructions, backendAssertions, backendChecks, preflightReason, preflightSkippedReason,
        )

        companion object {
            private const val NANOS_PER_MILLISECOND = 1_000_000
        }
    }

    /** Counts actual generated expressions submitted, including character encoding assertions. */
    private class CountingBackend(
        private val delegate: KSolver<*>, private val statistics: MutableStatistics,
    ) : KSolver<KSolverConfiguration> by (delegate as KSolver<KSolverConfiguration>) {
        override fun assert(expr: KExpr<KBoolSort>) {
            statistics.backendAssertions++
            delegate.assert(expr)
        }

        override fun assert(exprs: List<KExpr<KBoolSort>>) {
            statistics.backendAssertions += exprs.size
            delegate.assert(exprs)
        }

        override fun check(timeout: Duration): KSolverStatus {
            statistics.backendChecks++
            return delegate.check(timeout)
        }

        override fun checkWithAssumptions(assumptions: List<KExpr<KBoolSort>>, timeout: Duration): KSolverStatus {
            statistics.backendChecks++
            return delegate.checkWithAssumptions(assumptions, timeout)
        }
    }

    companion object {
        private const val OPTIONAL_BUDGET_SHARE = 4
        private const val MAX_PREFLIGHT_UNITS = 25_000
        private const val WITNESS_BUDGET_SHARE = 8
        private const val MAX_WITNESS_UNITS = 50_000
        private const val MAX_PERIODIC_UNITS = 100_000
        private const val PERIODIC_BUDGET_SHARE = 8
        private const val CHARACTER_BOUND_CONSTRAINTS = 2L
        private const val DFA_EDGE_CONSTRAINTS = 4L

        private fun saturatedAdd(left: Long, right: Long): Long =
            if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

        private fun saturatedMultiply(left: Long, right: Long): Long =
            if (right != 0L && left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right
    }
}

/** Progress counts learned information, not another solver call or a differently printed formula. */
internal class IdleRoundTracker(private val limit: Int) {
    private var idle = 0
    fun record(progress: Boolean): Boolean {
        idle = if (progress) 0 else idle + 1
        return idle >= limit
    }
}
