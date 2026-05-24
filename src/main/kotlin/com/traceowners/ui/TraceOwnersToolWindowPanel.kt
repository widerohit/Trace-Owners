package com.traceowners.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.traceowners.actions.FileOwnershipTargetFactory
import com.traceowners.model.AnalysisMode
import com.traceowners.model.Contributor
import com.traceowners.model.OwnershipAnalysis
import com.traceowners.model.RiskLevel
import com.traceowners.ownership.OwnershipListener
import com.traceowners.ownership.OwnershipService
import com.traceowners.ownership.OwnershipState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.Duration
import java.time.Instant
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class TraceOwnersToolWindowPanel(private val project: Project) : JBPanel<TraceOwnersToolWindowPanel>(BorderLayout()), OwnershipListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fileTargetFactory = FileOwnershipTargetFactory()
    private val search = SearchTextField()
    private val results = JPanel()
    private val modeSelector = JComboBox(AnalysisMode.entries.toTypedArray())
    private var currentAnalysis: OwnershipAnalysis? = null

    init {
        border = JBUI.Borders.empty(10)
        results.layout = BoxLayout(results, BoxLayout.Y_AXIS)
        results.background = background

        val header = JPanel()
        header.layout = BoxLayout(header, BoxLayout.Y_AXIS)
        header.isOpaque = false

        val titleRow = JPanel(BorderLayout())
        titleRow.isOpaque = false
        val title = JBLabel("TraceOwners")
        title.font = JBFont.label().asBold().deriveFont(16f)
        titleRow.add(title, BorderLayout.WEST)
        header.add(titleRow)

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))
        actions.isOpaque = false

        modeSelector.selectedItem = AnalysisMode.BALANCED
        modeSelector.toolTipText = "Choose how much Git history TraceOwners should read"
        modeSelector.maximumSize = Dimension(118, 30)
        modeSelector.preferredSize = Dimension(112, 30)
        modeSelector.renderer = javax.swing.DefaultListCellRenderer().also { renderer ->
            renderer.horizontalAlignment = javax.swing.SwingConstants.LEFT
        }
        actions.add(modeSelector)

        val currentFile = JButton("File")
        currentFile.toolTipText = "Analyze ownership for the file currently open in the editor"
        currentFile.addActionListener {
            analyzeCurrentFile()
        }
        actions.add(currentFile)

        val refresh = JButton("Refresh")
        refresh.toolTipText = "Refresh ownership analysis for the current TraceOwners target"
        refresh.addActionListener {
            currentAnalysis?.target?.let { target ->
                project.getService(OwnershipService::class.java).analyze(target, refresh = true, mode = selectedMode())
            }
        }
        actions.add(refresh)

        val copyReviewers = JButton("Reviewers")
        copyReviewers.toolTipText = "Copy suggested reviewers to the clipboard"
        copyReviewers.addActionListener {
            copyReviewers()
        }
        actions.add(copyReviewers)

        val copySummary = JButton("Summary")
        copySummary.toolTipText = "Copy the current ownership summary to the clipboard"
        copySummary.addActionListener {
            copySummary()
        }
        actions.add(copySummary)

        header.add(actions)
        header.add(search)
        add(header, BorderLayout.NORTH)

        add(JBScrollPane(results), BorderLayout.CENTER)
        renderIdle()

        search.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = renderAnalysis()
            override fun removeUpdate(e: DocumentEvent) = renderAnalysis()
            override fun changedUpdate(e: DocumentEvent) = renderAnalysis()
        })
    }

    private fun analyzeCurrentFile() {
        val selectedFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        if (selectedFile == null || selectedFile.isDirectory) {
            onOwnershipStateChanged(OwnershipState.Error(null, "No editor file is currently selected."))
            return
        }

        scope.launch {
            val target = fileTargetFactory.create(selectedFile)
            withContext(Dispatchers.Main) {
                if (target == null) {
                    onOwnershipStateChanged(OwnershipState.Error(null, "Current file is not inside a Git repository."))
                } else {
                    ToolWindowManager.getInstance(project).getToolWindow("TraceOwners")?.show(null)
                    project.getService(OwnershipService::class.java).analyze(target, mode = selectedMode())
                }
            }
        }
    }

    private fun selectedMode(): AnalysisMode = modeSelector.selectedItem as? AnalysisMode ?: AnalysisMode.BALANCED

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
            add(loadingPanel(state))
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
            add(helperText("${analysis.target.kind.name.lowercase().replaceFirstChar { it.uppercase() }} ownership analysis - ${analysis.analysisMode.displayName}${if (fromCache) " from cache" else ""}"))
            add(metadataPanel(analysis))
            add(Box.createVerticalStrut(10))

            contributors.forEachIndexed { index, contributor ->
                add(contributorCard(index + 1, contributor))
                add(Box.createVerticalStrut(8))
            }

            if (contributors.isEmpty()) {
                add(helperText("No contributors match the search."))
            }

            add(Box.createVerticalStrut(12))
            add(codeOwnersPanel(analysis))
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

    private fun metadataPanel(analysis: OwnershipAnalysis): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.add(helperText("Branch: ${analysis.branchName ?: "Unknown"}"))
        panel.add(helperText("History window: ${analysis.analysisMode.since}, file commits ${analysis.analysisMode.fileCommitLimit}, line commits ${analysis.analysisMode.lineCommitLimit}"))
        return panel
    }

    private fun loadingPanel(state: OwnershipState.Loading): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(12)
        )
        panel.maximumSize = Dimension(Int.MAX_VALUE, 190)

        val title = JBLabel("Finding ownership signals")
        title.font = JBFont.label().asBold()
        panel.add(title)
        panel.add(Box.createVerticalStrut(6))
        panel.add(helperText(state.target.displayName))
        panel.add(Box.createVerticalStrut(10))

        val progress = JProgressBar()
        progress.isIndeterminate = true
        progress.maximumSize = Dimension(Int.MAX_VALUE, 18)
        panel.add(progress)
        panel.add(Box.createVerticalStrut(10))

        panel.add(helperText("Mode: ${state.mode.displayName}"))
        panel.add(helperText("Reading local Git history, blame data, branch, and CODEOWNERS."))
        panel.add(helperText("Large repositories can take longer on the first uncached run."))
        return panel
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
        panel.maximumSize = Dimension(Int.MAX_VALUE, 178)

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

        constraints.gridy = 4
        panel.add(helperText("Score signals: ${contributor.scoreBreakdown.summary()}"), constraints)

        constraints.gridy = 5
        panel.add(helperText("First active: ${dateText(contributor.firstActiveDate)}"), constraints)

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

    private fun codeOwnersPanel(analysis: OwnershipAnalysis): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.add(sectionTitle("CODEOWNERS"))

        if (analysis.codeOwners.isEmpty()) {
            panel.add(helperText("No matching CODEOWNERS rule found."))
        } else {
            analysis.codeOwners.forEach { match ->
                panel.add(helperText("${match.pattern} -> ${match.owners.joinToString(", ")}"))
            }
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

    private fun dateText(date: Instant?): String {
        return date?.atZone(java.time.ZoneId.systemDefault())?.toLocalDate()?.toString() ?: "Unknown"
    }

    private fun copyReviewers() {
        val analysis = currentAnalysis ?: return
        val text = if (analysis.suggestedReviewers.isEmpty()) {
            "Suggested reviewers: none"
        } else {
            "Suggested reviewers: ${analysis.suggestedReviewers.joinToString(", ") { it.name }}"
        }
        copyToClipboard(text)
    }

    private fun copySummary() {
        val analysis = currentAnalysis ?: return
        val warnings = analysis.warnings.ifEmpty { emptyList() }
        val text = buildString {
            appendLine("TraceOwners summary for ${analysis.target.displayName}")
            appendLine("Branch: ${analysis.branchName ?: "Unknown"}")
            appendLine("Mode: ${analysis.analysisMode.displayName}")
            appendLine()
            appendLine("Top experts:")
            analysis.contributors.take(5).forEachIndexed { index, contributor ->
                appendLine("${index + 1}. ${contributor.name} - score ${contributor.expertiseScore}, ${activeText(contributor.lastActiveDate)}")
            }
            appendLine()
            appendLine(copyReviewersText(analysis))
            if (analysis.codeOwners.isNotEmpty()) {
                appendLine()
                appendLine("CODEOWNERS:")
                analysis.codeOwners.forEach { appendLine("- ${it.pattern}: ${it.owners.joinToString(", ")}") }
            }
            if (warnings.isNotEmpty()) {
                appendLine()
                appendLine("Warnings:")
                warnings.forEach { appendLine("- ${it.message}") }
            }
        }
        copyToClipboard(text)
    }

    private fun copyReviewersText(analysis: OwnershipAnalysis): String {
        return if (analysis.suggestedReviewers.isEmpty()) {
            "Suggested reviewers: none"
        } else {
            "Suggested reviewers: ${analysis.suggestedReviewers.joinToString(", ") { it.name }}"
        }
    }

    private fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    private fun scoreColor(score: Int): Color {
        return when {
            score >= 80 -> JBColor(0x0F7B3D, 0x78D99C)
            score >= 55 -> JBColor(0x9A6700, 0xD8B45C)
            else -> JBColor(0xA33A3A, 0xE08383)
        }
    }
}
