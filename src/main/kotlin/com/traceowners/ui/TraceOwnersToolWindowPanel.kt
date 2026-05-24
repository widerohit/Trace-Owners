package com.traceowners.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.traceowners.model.Contributor
import com.traceowners.model.OwnershipAnalysis
import com.traceowners.model.RiskLevel
import com.traceowners.ownership.OwnershipListener
import com.traceowners.ownership.OwnershipService
import com.traceowners.ownership.OwnershipState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.time.Duration
import java.time.Instant
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class TraceOwnersToolWindowPanel(private val project: Project) : JBPanel<TraceOwnersToolWindowPanel>(BorderLayout()), OwnershipListener {
    private val search = SearchTextField()
    private val results = JPanel()
    private var currentAnalysis: OwnershipAnalysis? = null

    init {
        border = JBUI.Borders.empty(10)
        results.layout = BoxLayout(results, BoxLayout.Y_AXIS)
        results.background = background

        val header = JPanel(BorderLayout())
        header.isOpaque = false
        val title = JBLabel("TraceOwners")
        title.font = JBFont.label().asBold().deriveFont(16f)
        header.add(title, BorderLayout.WEST)
        val refresh = JButton("Refresh")
        refresh.addActionListener {
            currentAnalysis?.target?.let { target ->
                project.getService(OwnershipService::class.java).analyze(target, refresh = true)
            }
        }
        header.add(refresh, BorderLayout.EAST)
        header.add(search, BorderLayout.SOUTH)
        add(header, BorderLayout.NORTH)

        add(JBScrollPane(results), BorderLayout.CENTER)
        renderIdle()

        search.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = renderAnalysis()
            override fun removeUpdate(e: DocumentEvent) = renderAnalysis()
            override fun changedUpdate(e: DocumentEvent) = renderAnalysis()
        })
    }

    override fun onOwnershipStateChanged(state: OwnershipState) {
        when (state) {
            OwnershipState.Idle -> renderIdle()
            is OwnershipState.Loading -> renderLoading(state)
            is OwnershipState.Ready -> {
                currentAnalysis = state.analysis
                renderAnalysis(state.fromCache)
            }
            is OwnershipState.Error -> renderError(state)
        }
    }

    private fun renderIdle() {
        replaceResults {
            add(sectionTitle("Select code and run TraceOwners -> Find Experts."))
            add(helperText("Java methods/classes, Kotlin classes/functions, and files are supported."))
        }
    }

    private fun renderLoading(state: OwnershipState.Loading) {
        replaceResults {
            add(sectionTitle("Analyzing ${state.target.displayName}"))
            add(helperText("Reading Git history in the background."))
        }
    }

    private fun renderError(state: OwnershipState.Error) {
        replaceResults {
            add(sectionTitle("Analysis failed"))
            add(helperText(state.message))
        }
    }

    private fun renderAnalysis(fromCache: Boolean = false) {
        val analysis = currentAnalysis ?: return
        val query = search.text.trim().lowercase()
        val contributors = analysis.contributors.filter {
            query.isBlank() || it.name.lowercase().contains(query) || it.email.lowercase().contains(query)
        }

        replaceResults {
            add(sectionTitle("Top Experts for ${analysis.target.displayName}"))
            add(helperText("${analysis.target.kind.name.lowercase().replaceFirstChar { it.uppercase() }} ownership analysis${if (fromCache) " from cache" else ""}"))
            add(Box.createVerticalStrut(10))

            contributors.forEachIndexed { index, contributor ->
                add(contributorCard(index + 1, contributor))
                add(Box.createVerticalStrut(8))
            }

            if (contributors.isEmpty()) {
                add(helperText("No contributors match the search."))
            }

            add(Box.createVerticalStrut(12))
            add(warningsPanel(analysis))
            add(Box.createVerticalStrut(12))
            add(peoplePanel("Suggested Reviewers", analysis.suggestedReviewers))
            add(Box.createVerticalStrut(8))
            add(peoplePanel("Active Maintainers", analysis.activeMaintainers))
        }
    }

    private fun replaceResults(block: JPanel.() -> Unit) {
        results.removeAll()
        results.block()
        results.revalidate()
        results.repaint()
    }

    private fun sectionTitle(text: String): JComponent {
        val label = JBLabel(text)
        label.font = JBFont.label().asBold()
        label.border = JBUI.Borders.emptyBottom(6)
        return label
    }

    private fun helperText(text: String): JComponent {
        val label = JBLabel(text)
        label.foreground = JBColor.GRAY
        return label
    }

    private fun contributorCard(rank: Int, contributor: Contributor): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.background = JBColor.namedColor("Panel.background", Color(0xF7F8FA))
        panel.border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(10)
        )
        panel.maximumSize = Dimension(Int.MAX_VALUE, 132)

        val constraints = GridBagConstraints()
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.weightx = 1.0
        constraints.fill = GridBagConstraints.HORIZONTAL

        val name = JBLabel("$rank. ${contributor.name}")
        name.font = JBFont.label().asBold()
        panel.add(name, constraints)

        constraints.gridx = 1
        constraints.weightx = 0.0
        val score = scoreBadge(contributor.expertiseScore)
        panel.add(score, constraints)

        constraints.gridx = 0
        constraints.gridy = 1
        constraints.gridwidth = 2
        constraints.weightx = 1.0
        panel.add(helperText(contributor.email.ifBlank { "No email in Git history" }), constraints)

        constraints.gridy = 2
        panel.add(helperText("${contributor.commits} commits - ${contributor.linesModified} lines modified - ${activeText(contributor.lastActiveDate)}"), constraints)

        constraints.gridy = 3
        panel.add(helperText("${contributor.activeDays} active contribution days"), constraints)

        return panel
    }

    private fun scoreBadge(score: Int): JComponent {
        val label = JBLabel("Score $score")
        label.font = JBFont.small().asBold()
        label.foreground = scoreColor(score)
        label.border = JBUI.Borders.empty(2, 8)
        return label
    }

    private fun warningsPanel(analysis: OwnershipAnalysis): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.add(sectionTitle("Warnings"))

        if (analysis.warnings.isEmpty()) {
            panel.add(helperText("No ownership warnings detected."))
            return panel
        }

        analysis.warnings.forEach {
            val label = JBLabel("${it.level.name}: ${it.message}")
            label.foreground = when (it.level) {
                RiskLevel.HIGH -> JBColor.RED
                RiskLevel.MEDIUM -> JBColor.ORANGE
                RiskLevel.LOW -> JBColor.GRAY
            }
            panel.add(label)
        }
        return panel
    }

    private fun peoplePanel(title: String, contributors: List<Contributor>): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.add(sectionTitle(title))
        if (contributors.isEmpty()) {
            panel.add(helperText("None detected."))
        } else {
            contributors.forEach { contributor ->
                panel.add(helperText("${contributor.name} - score ${contributor.expertiseScore}, ${activeText(contributor.lastActiveDate)}"))
            }
        }
        return panel
    }

    private fun activeText(lastActiveDate: Instant?): String {
        if (lastActiveDate == null) return "No activity date"
        val days = Duration.between(lastActiveDate, Instant.now()).toDays().coerceAtLeast(0)
        return when (days) {
            0L -> "Active today"
            1L -> "Active 1 day ago"
            else -> "Active $days days ago"
        }
    }

    private fun scoreColor(score: Int): Color {
        return when {
            score >= 80 -> JBColor(0x0F7B3D, 0x78D99C)
            score >= 55 -> JBColor(0x9A6700, 0xD8B45C)
            else -> JBColor(0xA33A3A, 0xE08383)
        }
    }
}
