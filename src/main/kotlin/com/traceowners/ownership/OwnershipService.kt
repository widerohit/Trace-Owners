package com.traceowners.ownership

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.traceowners.cache.AnalysisCache
import com.traceowners.expertise.ExpertiseScoreEngine
import com.traceowners.git.GitHistoryAnalyzer
import com.traceowners.model.OwnershipAnalysis
import com.traceowners.model.OwnershipTarget
import com.traceowners.reviewer.ReviewerEngine
import com.traceowners.risks.RiskDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class OwnershipService(private val project: Project) : Disposable {
    private val log = Logger.getInstance(OwnershipService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val listeners = CopyOnWriteArrayList<OwnershipListener>()
    private val analyzer = GitHistoryAnalyzer()
    private val scorer = ExpertiseScoreEngine()
    private val reviewerEngine = ReviewerEngine()
    private val riskDetector = RiskDetector()

    @Volatile
    private var lastState: OwnershipState = OwnershipState.Idle

    fun addListener(listener: OwnershipListener) {
        listeners += listener
        listener.onOwnershipStateChanged(lastState)
    }

    fun removeListener(listener: OwnershipListener) {
        listeners -= listener
    }

    fun analyze(target: OwnershipTarget, refresh: Boolean = false) {
        val cache = project.getService(AnalysisCache::class.java)
        if (!refresh) {
            val cached = cache.get(target)
            if (cached != null) {
                publish(OwnershipState.Ready(cached, fromCache = true))
                return
            }
        } else {
            cache.invalidate(target)
        }

        publish(OwnershipState.Loading(target))
        scope.launch {
            try {
                val analysis = withContext(Dispatchers.Default) {
                    val now = Instant.now()
                    val raw = analyzer.analyze(target)
                    val contributors = scorer.score(raw, now)
                    val reviewers = reviewerEngine.suggest(contributors, now)
                    val activeMaintainers = reviewerEngine.activeMaintainers(contributors, now)
                    val risks = riskDetector.detect(contributors, activeMaintainers, now)

                    OwnershipAnalysis(
                        target = target,
                        contributors = contributors,
                        riskLevel = risks.level,
                        suggestedReviewers = reviewers,
                        activeMaintainers = activeMaintainers,
                        warnings = risks.warnings,
                        analyzedAt = now
                    )
                }
                cache.put(analysis)
                publish(OwnershipState.Ready(analysis, fromCache = false))
            } catch (error: Throwable) {
                log.warn("TraceOwners analysis failed", error)
                publish(OwnershipState.Error(target, error.message ?: "Analysis failed"))
            }
        }
    }

    private fun publish(state: OwnershipState) {
        lastState = state
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it.onOwnershipStateChanged(state) }
        }
    }

    override fun dispose() {
        scope.cancel()
    }
}

interface OwnershipListener {
    fun onOwnershipStateChanged(state: OwnershipState)
}

sealed class OwnershipState {
    data object Idle : OwnershipState()
    data class Loading(val target: OwnershipTarget) : OwnershipState()
    data class Ready(val analysis: OwnershipAnalysis, val fromCache: Boolean) : OwnershipState()
    data class Error(val target: OwnershipTarget?, val message: String) : OwnershipState()
}
