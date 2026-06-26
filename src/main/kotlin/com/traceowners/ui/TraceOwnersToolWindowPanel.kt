package com.traceowners.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
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
import com.traceowners.model.GitCommit
import com.traceowners.model.OwnershipAnalysis
import com.traceowners.model.RiskLevel
import com.traceowners.model.ScoreBreakdown
import com.traceowners.ownership.OwnershipListener
import com.traceowners.ownership.OwnershipService
import com.traceowners.ownership.OwnershipState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.swing.*
import javax.swing.border.AbstractBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class TraceOwnersToolWindowPanel(private val project: Project) :
    JBPanel<TraceOwnersToolWindowPanel>(BorderLayout()), OwnershipListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fileTargetFactory = FileOwnershipTargetFactory()
    private val search = SearchTextField()
    private val results = JPanel()
    private val modeSelector = JComboBox(AnalysisMode.entries.toTypedArray())
    private val fileSelector = JComboBox<FileOption>()
    private var currentAnalysis: OwnershipAnalysis? = null
    private val resultCountLabel = JBLabel("")
    private var allFileOptions: List<FileOption> = emptyList()
    private var suppressFileFilter = false
    private var suppressModeChange = false
    init {
        border = JBUI.Borders.empty(8)
        results.layout = BoxLayout(results, BoxLayout.Y_AXIS)
        results.isOpaque = false

        add(buildHeader(), BorderLayout.NORTH)
        add(JBScrollPane(results).also { it.border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        renderIdle()

        search.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = renderAnalysis()
            override fun removeUpdate(e: DocumentEvent) = renderAnalysis()
            override fun changedUpdate(e: DocumentEvent) = renderAnalysis()
        })
    }

    

    // ── Header ─────────────────────────────────────────────────────────────────

    private fun buildHeader(): JComponent {
        val header = JPanel()
        header.layout = BoxLayout(header, BoxLayout.Y_AXIS)
        header.isOpaque = false

        // ─ Toolbar: Single horizontal row with all controls ─
        val toolbar = JPanel()
        toolbar.layout = BoxLayout(toolbar, BoxLayout.X_AXIS)
        toolbar.isOpaque = false
        toolbar.maximumSize = Dimension(Int.MAX_VALUE, 32)

        // Depth mode: [Label] [Selector]
        val depthLabel = JBLabel("Depth:")
        depthLabel.font = JBFont.small()
        depthLabel.foreground = JBColor.GRAY
        depthLabel.preferredSize = Dimension(45, 26)
        toolbar.add(depthLabel)
        toolbar.add(Box.createHorizontalStrut(4))

        modeSelector.selectedItem = AnalysisMode.BALANCED
        modeSelector.toolTipText = "Choose how much Git history to read (Fast/Balanced/Deep)"
        modeSelector.preferredSize = Dimension(120, 26)
        modeSelector.maximumSize = Dimension(120, 26)
        modeSelector.addActionListener {
            if (!suppressModeChange) {
                currentAnalysis?.target?.let { target ->
                    project.getService(OwnershipService::class.java)
                        .analyze(target, refresh = true, mode = selectedMode())
                }
            }
        }
        toolbar.add(modeSelector)
        toolbar.add(Box.createHorizontalStrut(12))

        // File: [Label] [Selector]
        val fileLabel = JBLabel("File:")
        fileLabel.font = JBFont.small()
        fileLabel.foreground = JBColor.GRAY
        fileLabel.preferredSize = Dimension(30, 26)
        toolbar.add(fileLabel)
        toolbar.add(Box.createHorizontalStrut(4))

        fileSelector.isEditable = true
        fileSelector.toolTipText = "Select an open editor file to analyze"
        fileSelector.preferredSize = Dimension(220, 26)
        fileSelector.maximumSize = Dimension(420, 26)
        fileSelector.addActionListener {
            val option = fileSelector.selectedItem as? FileOption ?: return@addActionListener
            if (option.virtualFile == null) return@addActionListener
            analyzeFile(option.virtualFile)
        }
        toolbar.add(fileSelector)
        toolbar.add(Box.createHorizontalStrut(12))

        // Refresh button
        val refreshButton = JButton("\u21bb")
        refreshButton.toolTipText = "Refresh analysis (bypass cache)"
        refreshButton.preferredSize = Dimension(28, 26)
        refreshButton.minimumSize = Dimension(28, 26)
        refreshButton.maximumSize = Dimension(28, 26)
        refreshButton.isFocusPainted = false
        refreshButton.addActionListener {
            currentAnalysis?.target?.let { target ->
                project.getService(OwnershipService::class.java)
                    .analyze(target, refresh = true, mode = selectedMode())
            }
        }
        toolbar.add(refreshButton)
        toolbar.add(Box.createHorizontalGlue())

        header.add(toolbar)
        header.add(Box.createVerticalStrut(8))

        configureSearchableFileDropdown()
        refreshOpenFilesDropdown()
        setupFileEditorListener()

        search.textEditor.emptyText.text = "Search contributors by name or email\u2026"
        val searchRow = JPanel(BorderLayout())
        searchRow.isOpaque = false
        searchRow.border = JBUI.Borders.emptyTop(4)
        searchRow.add(search, BorderLayout.CENTER)
        header.add(searchRow)

        // Result count label row
        val countRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 2))
        countRow.isOpaque = false
        resultCountLabel.font = JBFont.small()
        resultCountLabel.foreground = JBColor.GRAY
        countRow.add(resultCountLabel)
        header.add(countRow)
        header.add(Box.createVerticalStrut(4))

        // Separator
        val sep = JSeparator()
        sep.maximumSize = Dimension(Int.MAX_VALUE, 1)
        header.add(sep)
        header.add(Box.createVerticalStrut(4))

        return header
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

    // ── State dispatch ──────────────────────────────────────────────────────────

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

    // ── Idle / Welcome ──────────────────────────────────────────────────────────


    private fun renderIdle() {
        resultCountLabel.text = ""
        replaceResults {
            add(Box.createVerticalStrut(12))
            add(welcomeCard())
        }
    }
    private fun welcomeCard(): JComponent {
        val card = roundedCard()

        val title = JBLabel("TraceOwners")
        title.font = JBFont.label().deriveFont(Font.BOLD, 14f)
        title.alignmentX = Component.LEFT_ALIGNMENT
        card.add(title)
        card.add(Box.createVerticalStrut(3))

        val subtitle = helperText("Ownership intelligence from local Git history.")
        subtitle.alignmentX = Component.LEFT_ALIGNMENT
        card.add(subtitle)
        card.add(Box.createVerticalStrut(16))

        listOf(
            "1" to "Open a file in the editor",
            "2" to "Select it from the File dropdown, or right-click \u2192 TraceOwners \u2192 Find Experts",
            "3" to "View experts, reviewers, and ownership risks here"
        ).forEach { (num, text) ->
            card.add(stepRow(num, text))
            card.add(Box.createVerticalStrut(8))
        }

        card.add(Box.createVerticalStrut(8))
        val hint = helperText("\uD83D\uDCA1  Use the File dropdown above to analyze any open editor tab.")
        hint.alignmentX = Component.LEFT_ALIGNMENT
        card.add(hint)

        return card
    }

    private fun stepRow(num: String, text: String): JComponent {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false
        row.alignmentX = Component.LEFT_ALIGNMENT
        row.add(circleBadge(num, JBColor(Color(0x4B9EFF), Color(0x4B9EFF)), Color.WHITE, 20))
        row.add(Box.createHorizontalStrut(8))
        val label = JBLabel(text)
        label.font = JBFont.label()
        row.add(label)
        row.add(Box.createHorizontalGlue())
        return row
    }

    // ── Loading ─────────────────────────────────────────────────────────────────

    private fun renderLoading(state: OwnershipState.Loading) {
        resultCountLabel.text = ""
        replaceResults {
            add(Box.createVerticalStrut(8))
            add(loadingCard(state))
        }
    }

    private fun loadingCard(state: OwnershipState.Loading): JComponent {
        val card = roundedCard()

        val title = JBLabel("Analyzing ownership\u2026")
        title.font = JBFont.label().asBold()
        title.alignmentX = Component.LEFT_ALIGNMENT
        card.add(title)
        card.add(Box.createVerticalStrut(4))

        val targetLabel = helperText(state.target.displayName)
        targetLabel.alignmentX = Component.LEFT_ALIGNMENT
        card.add(targetLabel)
        card.add(Box.createVerticalStrut(10))

        val progress = JProgressBar()
        progress.isIndeterminate = true
        progress.maximumSize = Dimension(Int.MAX_VALUE, 6)
        progress.alignmentX = Component.LEFT_ALIGNMENT
        card.add(progress)
        card.add(Box.createVerticalStrut(12))

        val modeDesc = when (state.mode) {
            AnalysisMode.FAST -> "Fast \u2014 last 1 year, up to 150 file commits"
            AnalysisMode.BALANCED -> "Balanced \u2014 last 2 years, up to 500 file commits"
            AnalysisMode.DEEP -> "Deep \u2014 last 5 years, up to 2,000 file commits"
        }
        val modeRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        modeRow.isOpaque = false
        modeRow.alignmentX = Component.LEFT_ALIGNMENT
        modeRow.add(tagLabel(state.mode.displayName, JBColor(Color(0x4B9EFF), Color(0x4B9EFF))))
        modeRow.add(helperText(modeDesc))
        card.add(modeRow)
        card.add(Box.createVerticalStrut(8))

        val hint1 = helperText("Reading git log \u00b7 git blame \u00b7 branch \u00b7 CODEOWNERS")
        hint1.alignmentX = Component.LEFT_ALIGNMENT
        card.add(hint1)
        card.add(Box.createVerticalStrut(2))
        val hint2 = helperText("Large repositories may take longer on the first uncached run.")
        hint2.alignmentX = Component.LEFT_ALIGNMENT
        card.add(hint2)

        return card
    }

    // ── Error ───────────────────────────────────────────────────────────────────

    private fun renderError(state: OwnershipState.Error) {
        resultCountLabel.text = ""
        replaceResults {
            add(Box.createVerticalStrut(8))
            val errColor = JBColor(Color(0xCC3333), Color(0xE06060))
            val card = object : JPanel() {
                override fun paintComponent(g: Graphics) {
                    g.color = JBColor(Color(0xFFF0F0), Color(0x3D1E1E))
                    g.fillRect(0, 0, width, height)
                    super.paintComponent(g)
                }
            }
            card.isOpaque = false
            card.layout = BoxLayout(card, BoxLayout.Y_AXIS)
            card.border = BorderFactory.createCompoundBorder(
                LeftBorderLine(errColor, 3),
                JBUI.Borders.empty(12, 14)
            )
            card.maximumSize = Dimension(Int.MAX_VALUE, 999)
            card.alignmentX = Component.LEFT_ALIGNMENT

            val titleLabel = JBLabel("\u26A0  Analysis failed")
            titleLabel.font = JBFont.label().asBold()
            titleLabel.foreground = errColor
            titleLabel.alignmentX = Component.LEFT_ALIGNMENT
            card.add(titleLabel)
            card.add(Box.createVerticalStrut(6))
            val msgLabel = helperText(state.message)
            msgLabel.alignmentX = Component.LEFT_ALIGNMENT
            card.add(msgLabel)
            add(card)
        }
    }

    // ── Analysis results ─────────────────────────────────────────────────────────

    private fun renderAnalysis(fromCache: Boolean = false) {
        val analysis = currentAnalysis ?: return
        val query = search.text.trim().lowercase()
        val contributors = analysis.contributors.filter {
            query.isBlank() || it.name.lowercase().contains(query) || it.email.lowercase().contains(query)
        }

        val total = analysis.contributors.size
        resultCountLabel.text = when {
            total == 0 -> ""
            query.isBlank() -> "$total contributor${if (total != 1) "s" else ""} found"
            else -> "Showing ${contributors.size} of $total"
        }

        val totalCommits = contributors.sumOf { it.commits }.coerceAtLeast(1)

        replaceResults {
            add(Box.createVerticalStrut(4))

            val targetTitle = copyableText(analysis.target.displayName, bold = true, size = 13f)
            targetTitle.alignmentX = Component.LEFT_ALIGNMENT
            add(targetTitle)
            add(Box.createVerticalStrut(4))

            add(metadataRow(analysis, fromCache))
            add(Box.createVerticalStrut(12))

            // Warnings (shown prominently at top when present)
            if (analysis.warnings.isNotEmpty()) {
                add(sectionHeader("Warnings"))
                analysis.warnings.forEach { w -> add(warningRow(w.message, w.level)) }
                add(Box.createVerticalStrut(4))
            }

            // Top Experts
            add(sectionHeader("Top Experts"))
            if (contributors.isEmpty()) {
                val msg = if (query.isBlank()) "No contributors found." else "No contributors match \u201c$query\u201d."
                add(helperText(msg).also { it.alignmentX = Component.LEFT_ALIGNMENT })
            } else {
                contributors.forEachIndexed { i, c ->
                    add(contributorCard(i + 1, c, totalCommits))
                    add(Box.createVerticalStrut(6))
                }
            }
            add(Box.createVerticalStrut(4))

            // CODEOWNERS
            add(sectionHeader("CODEOWNERS"))
            if (analysis.codeOwners.isEmpty()) {
                add(helperText("No matching CODEOWNERS rule found.").also { it.alignmentX = Component.LEFT_ALIGNMENT })
            } else {
                analysis.codeOwners.forEach { match ->
                    val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
                    row.isOpaque = false
                    row.alignmentX = Component.LEFT_ALIGNMENT
                    val patLabel = copyableText(match.pattern, italic = true)
                    row.add(patLabel)
                    row.add(JBLabel("\u2192").also { it.foreground = JBColor.GRAY })
                    match.owners.forEach { o -> row.add(tagLabel(o, JBColor(Color(0x4B9EFF), Color(0x4B9EFF)))) }
                    add(row)
                }
            }
            add(Box.createVerticalStrut(4))

            // Suggested Reviewers
            add(sectionHeader("Suggested Reviewers"))
            add(peopleList(analysis.suggestedReviewers))
            add(Box.createVerticalStrut(4))

            // Active Maintainers
            add(sectionHeader("Active Maintainers"))
            add(peopleList(analysis.activeMaintainers))
            add(Box.createVerticalStrut(4))

            // Recent Commits
            add(sectionHeader("Recent Commits"))
            if (analysis.recentCommits.isEmpty()) {
                add(helperText("No recent commits found.").also { it.alignmentX = Component.LEFT_ALIGNMENT })
            } else {
                analysis.recentCommits.forEach {
                    add(commitRow(it))
                    add(Box.createVerticalStrut(4))
                }
            }
            add(Box.createVerticalStrut(16))
        }
    }

    // ── Metadata row ─────────────────────────────────────────────────────────────

    private fun metadataRow(analysis: OwnershipAnalysis, fromCache: Boolean): JComponent {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        row.isOpaque = false
        row.alignmentX = Component.LEFT_ALIGNMENT

        fun dot() = JBLabel("\u00b7").also { it.foreground = JBColor.GRAY; it.font = JBFont.small() }

        row.add(helperText("Branch: ${analysis.branchName ?: "Unknown"}"))
        row.add(dot())
        row.add(helperText(analysis.analysisMode.displayName))
        row.add(dot())
        row.add(helperText(analysis.analysisMode.since))

        if (fromCache) {
            row.add(dot())
            row.add(tagLabel("cached", JBColor.GRAY))
        }

        val mins = Duration.between(analysis.analyzedAt, Instant.now()).toMinutes()
        val timeText = when {
            mins < 1L -> "just now"
            mins == 1L -> "1 min ago"
            else -> "$mins mins ago"
        }
        row.add(dot())
        row.add(helperText("Analyzed $timeText"))
        return row
    }

    // ── Section header ───────────────────────────────────────────────────────────

    private fun sectionHeader(title: String): JComponent {
        val wrapper = JPanel()
        wrapper.layout = BoxLayout(wrapper, BoxLayout.Y_AXIS)
        wrapper.isOpaque = false
        wrapper.alignmentX = Component.LEFT_ALIGNMENT

        val sep = JSeparator()
        sep.maximumSize = Dimension(Int.MAX_VALUE, 1)
        wrapper.add(sep)
        wrapper.add(Box.createVerticalStrut(6))

        val label = JBLabel(title.uppercase())
        label.font = JBFont.small().asBold()
        label.foreground = JBColor.GRAY
        label.alignmentX = Component.LEFT_ALIGNMENT
        wrapper.add(label)
        wrapper.add(Box.createVerticalStrut(6))
        return wrapper
    }

    // ── Contributor card ─────────────────────────────────────────────────────────

    private fun contributorCard(rank: Int, contributor: Contributor): JComponent {
        return contributorCard(rank, contributor, contributor.commits.coerceAtLeast(1))
    }

    private fun contributorCard(rank: Int, contributor: Contributor, totalCommits: Int): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.background = JBColor.namedColor("Panel.background", Color(0xF7F8FA))
        panel.border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(10, 12, 10, 12)
        )
        panel.maximumSize = Dimension(Int.MAX_VALUE, 999)
        panel.alignmentX = Component.LEFT_ALIGNMENT

        val c = GridBagConstraints()
        c.fill = GridBagConstraints.HORIZONTAL
        c.insets = Insets(2, 0, 2, 0)

        // Row 0: rank badge | contributor name | score badge
        c.gridx = 0; c.gridy = 0; c.weightx = 0.0; c.gridwidth = 1
        val rankBg = when (rank) {
            1 -> JBColor(Color(0xF5A623), Color(0xD4901A))
            2 -> JBColor(Color(0x9B9B9B), Color(0x888888))
            3 -> JBColor(Color(0xA0522D), Color(0xB06030))
            else -> JBColor(Color(0xCCCCCC), Color(0x555555))
        }
        val rankFg = if (rank <= 3) Color.WHITE else JBColor.GRAY
        panel.add(circleBadge(rank.toString(), rankBg, rankFg, 22), c)

        c.gridx = 1; c.weightx = 1.0; c.insets = Insets(2, 8, 2, 0)
        val nameLabel = copyableText(contributor.name, bold = true)
        panel.add(nameLabel, c)

        c.gridx = 2; c.weightx = 0.0; c.insets = Insets(2, 8, 2, 0)
        panel.add(scoreBadge(contributor.expertiseScore), c)

        // Row 1: email
        c.gridx = 1; c.gridy = 1; c.gridwidth = 2; c.weightx = 1.0; c.insets = Insets(1, 8, 1, 0)
        panel.add(helperText(contributor.email.ifBlank { "No email in Git history" }), c)

        // Row 2: commits · lines · activity dot + text
        c.gridy = 2
        val ownershipPct = (contributor.commits.toDouble() * 100.0 / totalCommits.toDouble()).coerceIn(0.0, 100.0)
        val statsRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        statsRow.isOpaque = false
        statsRow.add(helperText("${contributor.commits} commits"))
        statsRow.add(helperText("\u00b7"))
        statsRow.add(helperText(String.format("%.1f%% ownership", ownershipPct)))
        statsRow.add(helperText("\u00b7"))
        statsRow.add(helperText("${contributor.linesModified} lines"))
        statsRow.add(helperText("\u00b7"))
        val actDot = JBLabel("\u25CF")
        actDot.font = JBFont.small()
        actDot.foreground = activityColor(contributor.lastActiveDate)
        statsRow.add(actDot)
        statsRow.add(helperText(activeText(contributor.lastActiveDate)))
        panel.add(statsRow, c)

        // Row 3: active days · since date
        c.gridy = 3
        val datesRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        datesRow.isOpaque = false
        datesRow.add(helperText("${contributor.activeDays} active days"))
        datesRow.add(helperText("\u00b7"))
        datesRow.add(helperText("Since: ${dateText(contributor.firstActiveDate)}"))
        panel.add(datesRow, c)

        // Row 4: score breakdown dot bars
        c.gridy = 4
        panel.add(scoreBreakdownRow(contributor.scoreBreakdown), c)

        return panel
    }

    private fun scoreBadge(score: Int): JComponent {
        val color = scoreColor(score)
        val label = JBLabel(" $score ")
        label.font = JBFont.small().asBold()
        label.foreground = color
        label.border = BorderFactory.createLineBorder(color, 1)
        label.horizontalAlignment = SwingConstants.CENTER
        return label
    }

    private fun scoreBreakdownRow(breakdown: ScoreBreakdown): JComponent {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        row.isOpaque = false
        listOf(
            "Commits" to breakdown.commitFrequency,
            "Recency" to breakdown.recency,
            "Lines" to breakdown.linesModified,
            "Repeat" to breakdown.repeatedContributions,
            "Duration" to breakdown.longTermOwnership
        ).forEach { (name, value) ->
            val item = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
            item.isOpaque = false
            val lbl = JBLabel("$name ")
            lbl.font = JBFont.small()
            lbl.foreground = JBColor.GRAY
            item.add(lbl)
            item.add(dotBar(value))
            row.add(item)
        }
        return row
    }

    private fun dotBar(value: Int): JComponent {
        val filled = when {
            value >= 80 -> 5
            value >= 60 -> 4
            value >= 40 -> 3
            value >= 20 -> 2
            value > 0 -> 1
            else -> 0
        }
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 1, 0))
        panel.isOpaque = false
        val filledColor = scoreColor(value)
        val emptyColor = JBColor(Color(0xDDDDDD), Color(0x444444))
        for (i in 1..5) {
            val dot = JBLabel("\u25CF")
            dot.font = JBFont.small().deriveFont(7f)
            dot.foreground = if (i <= filled) filledColor else emptyColor
            panel.add(dot)
        }
        return panel
    }

    // ── Warning row ──────────────────────────────────────────────────────────────

    private fun warningRow(message: String, level: RiskLevel): JComponent {
        val (icon, fgColor, bgColor) = when (level) {
            RiskLevel.HIGH -> Triple(
                "\u26A0",
                JBColor(Color(0xCC3333), Color(0xE06060)),
                JBColor(Color(0xFFF0F0), Color(0x3D1E1E))
            )
            RiskLevel.MEDIUM -> Triple(
                "\u25B3",
                JBColor(Color(0xB87800), Color(0xD89030)),
                JBColor(Color(0xFFFAEC), Color(0x3A2E10))
            )
            RiskLevel.LOW -> Triple(
                "\u2139",
                JBColor.GRAY,
                JBColor(Color(0xF5F5F5), Color(0x2A2A2A))
            )
        }
        val panel = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                g.color = bgColor
                g.fillRect(0, 0, width, height)
                super.paintComponent(g)
            }
        }
        panel.isOpaque = false
        panel.border = BorderFactory.createCompoundBorder(
            LeftBorderLine(fgColor, 3),
            JBUI.Borders.empty(6, 10, 6, 10)
        )
        panel.maximumSize = Dimension(Int.MAX_VALUE, 40)
        panel.alignmentX = Component.LEFT_ALIGNMENT

        val label = JBLabel("$icon  $message")
        label.font = JBFont.label()
        label.foreground = fgColor
        panel.add(label, BorderLayout.CENTER)
        return panel
    }

    // ── People list ──────────────────────────────────────────────────────────────

    private fun peopleList(contributors: List<Contributor>): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.alignmentX = Component.LEFT_ALIGNMENT

        if (contributors.isEmpty()) {
            val lbl = helperText("None detected.")
            lbl.alignmentX = Component.LEFT_ALIGNMENT
            panel.add(lbl)
        } else {
            contributors.forEach { c ->
                panel.add(personChip(c))
                panel.add(Box.createVerticalStrut(5))
            }
        }
        return panel
    }

    private fun personChip(contributor: Contributor): JComponent {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false
        row.alignmentX = Component.LEFT_ALIGNMENT

        row.add(circleBadge(
            contributor.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            avatarColor(contributor.name),
            Color.WHITE,
            22
        ))
        row.add(Box.createHorizontalStrut(8))

        val nameLabel = copyableText(contributor.name)
        nameLabel.font = JBFont.label()
        row.add(nameLabel)
        row.add(Box.createHorizontalStrut(6))

        row.add(scoreBadge(contributor.expertiseScore))
        row.add(Box.createHorizontalStrut(6))

        val actDot = JBLabel("\u25CF")
        actDot.font = JBFont.small()
        actDot.foreground = activityColor(contributor.lastActiveDate)
        row.add(actDot)
        row.add(Box.createHorizontalStrut(4))
        row.add(helperText(activeText(contributor.lastActiveDate)))
        row.add(Box.createHorizontalGlue())
        return row
    }

    // ── Commit row ────────────────────────────────────────────────────────────────

    private fun commitRow(commit: GitCommit): JComponent {
        val panel = JPanel(BorderLayout(8, 0))
        panel.isOpaque = false
        panel.border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(8, 10)
        )
        panel.maximumSize = Dimension(Int.MAX_VALUE, 56)
        panel.alignmentX = Component.LEFT_ALIGNMENT

        val left = JPanel()
        left.layout = BoxLayout(left, BoxLayout.Y_AXIS)
        left.isOpaque = false

        val msg = if (commit.message.length > 72) commit.message.take(69) + "\u2026" else commit.message
        val msgLabel = copyableText(msg, bold = true)
        msgLabel.alignmentX = Component.LEFT_ALIGNMENT
        left.add(msgLabel)

        val meta = helperText("${commit.authorName}  \u00b7  ${activeText(commit.date)}")
        meta.alignmentX = Component.LEFT_ALIGNMENT
        left.add(meta)
        panel.add(left, BorderLayout.CENTER)

        val hashLabel = copyableText(commit.hash.take(8), monospace = true)
        hashLabel.foreground = JBColor.GRAY
        hashLabel.border = JBUI.Borders.empty(2, 6)
        panel.add(hashLabel, BorderLayout.EAST)

        return panel
    }

    // ── Shared utility components ────────────────────────────────────────────────

    /** Filled circle with centered text label — used for rank and avatar badges. */
    private fun circleBadge(text: String, bg: Color, fg: Color, size: Int): JComponent {
        return object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = bg
                g2.fillOval(0, 0, width, height)
                g2.color = fg
                g2.font = JBFont.small().asBold()
                val fm = g2.fontMetrics
                g2.drawString(text, (width - fm.stringWidth(text)) / 2, (height - fm.height) / 2 + fm.ascent)
            }
        }.also {
            it.isOpaque = false
            val dim = Dimension(size, size)
            it.preferredSize = dim; it.minimumSize = dim; it.maximumSize = dim
        }
    }

    /** Small bordered label used for mode tags, cache badge, CODEOWNERS owners. */
    private fun tagLabel(text: String, color: Color): JLabel {
        val lbl = JBLabel(" $text ")
        lbl.font = JBFont.small()
        lbl.foreground = color
        lbl.border = BorderFactory.createLineBorder(color, 1)
        return lbl
    }

    /** Standard card container with a themed background and border. */
    private fun roundedCard(): JPanel {
        val card = JPanel()
        card.layout = BoxLayout(card, BoxLayout.Y_AXIS)
        card.isOpaque = true
        card.background = JBColor.namedColor("Panel.background", Color(0xF4F4F4))
        card.border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(14, 14, 14, 14)
        )
        card.maximumSize = Dimension(Int.MAX_VALUE, 999)
        card.alignmentX = Component.LEFT_ALIGNMENT
        return card
    }

    // ── Color helpers ────────────────────────────────────────────────────────────

    private fun scoreColor(score: Int): Color = when {
        score >= 80 -> JBColor(Color(0x0F7B3D), Color(0x78D99C))
        score >= 55 -> JBColor(Color(0x9A6700), Color(0xD8B45C))
        else -> JBColor(Color(0xA33A3A), Color(0xE08383))
    }

    private fun activityColor(lastActiveDate: Instant?): Color {
        if (lastActiveDate == null) return JBColor.GRAY
        val days = Duration.between(lastActiveDate, Instant.now()).toDays()
        return when {
            days <= 30 -> JBColor(Color(0x2D9E5F), Color(0x5AC48A))
            days <= 90 -> JBColor(Color(0xB87800), Color(0xD8A040))
            else -> JBColor(Color(0xA33A3A), Color(0xE08383))
        }
    }

    private fun avatarColor(name: String): Color {
        val palette = listOf(
            JBColor(Color(0x4B9EFF), Color(0x3A8EEF)),
            JBColor(Color(0x2D9E5F), Color(0x2A9050)),
            JBColor(Color(0xB87800), Color(0xA06800)),
            JBColor(Color(0x9B59B6), Color(0x8549A6)),
            JBColor(Color(0xE67E22), Color(0xD06E12)),
            JBColor(Color(0x2980B9), Color(0x1970A9)),
            JBColor(Color(0x27AE60), Color(0x179E50)),
            JBColor(Color(0xC0392B), Color(0xB0291B))
        )
        return palette[name.hashCode().and(0x7FFFFFFF) % palette.size]
    }

    // ── String helpers ───────────────────────────────────────────────────────────

    private fun activeText(lastActiveDate: Instant?): String {
        if (lastActiveDate == null) return "No activity date"
        val days = Duration.between(lastActiveDate, Instant.now()).toDays().coerceAtLeast(0)
        return when (days) {
            0L -> "Active today"
            1L -> "Active 1 day ago"
            else -> "Active $days days ago"
        }
    }

    private fun dateText(date: Instant?): String =
        date?.atZone(ZoneId.systemDefault())?.toLocalDate()?.toString() ?: "Unknown"

    private fun helperText(text: String): JComponent =
        copyableText(text, small = true, gray = true)

    private fun copyableText(
        text: String,
        bold: Boolean = false,
        italic: Boolean = false,
        small: Boolean = false,
        gray: Boolean = false,
        monospace: Boolean = false,
        size: Float? = null
    ): JTextField {
        return JTextField(text).apply {
            isEditable = false
            isOpaque = false
            border = null
            background = null
            columns = text.length.coerceAtLeast(1)
            font = when {
                monospace -> Font(Font.MONOSPACED, Font.PLAIN, if (small) JBUI.scale(10) else JBUI.scale(12))
                bold -> JBFont.label().asBold()
                italic -> JBFont.label().deriveFont(Font.ITALIC)
                small -> JBFont.small()
                else -> JBFont.label()
            }.let { base ->
                size?.let { base.deriveFont(it) } ?: base
            }
            foreground = when {
                gray -> JBColor.GRAY
                else -> JBColor.foreground()
            }
        }
    }

    private fun replaceResults(block: JPanel.() -> Unit) {
        results.removeAll()
        results.block()
        results.revalidate()
        results.repaint()
    }

    private fun setupFileEditorListener() {
        FileEditorManager.getInstance(project).addFileEditorManagerListener(object : com.intellij.openapi.fileEditor.FileEditorManagerListener {
            override fun fileOpened(source: com.intellij.openapi.fileEditor.FileEditorManager, file: VirtualFile) {
                refreshOpenFilesDropdown()
            }

            override fun fileClosed(source: com.intellij.openapi.fileEditor.FileEditorManager, file: VirtualFile) {
                refreshOpenFilesDropdown()
            }

            override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) {
                refreshOpenFilesDropdown()
            }
        })
    }

    private fun refreshOpenFilesDropdown() {
        val openFiles = FileEditorManager.getInstance(project).openFiles
            .filter { !it.isDirectory }
        val options = openFiles.map { vf ->
            FileOption(
                displayName = vf.presentableName,
                path = vf.presentableUrl,
                virtualFile = vf
            )
        }
        allFileOptions = options
        suppressFileFilter = true
        fileSelector.model = DefaultComboBoxModel(options.toTypedArray())
        val selected = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        if (selected != null) {
            options.firstOrNull { it.virtualFile == selected }?.let { fileSelector.selectedItem = it }
        }
        suppressFileFilter = false
    }

    private fun configureSearchableFileDropdown() {
        val editor = fileSelector.editor.editorComponent as? JTextField ?: return
        editor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFileFilter(editor.text)
            override fun removeUpdate(e: DocumentEvent) = applyFileFilter(editor.text)
            override fun changedUpdate(e: DocumentEvent) = applyFileFilter(editor.text)
        })
    }

    private fun applyFileFilter(text: String) {
        if (suppressFileFilter) return
        val query = text.trim().lowercase()
        val filtered = if (query.isBlank()) {
            allFileOptions
        } else {
            allFileOptions.filter {
                it.displayName.lowercase().contains(query) || it.path.lowercase().contains(query)
            }
        }
        javax.swing.SwingUtilities.invokeLater {
            suppressFileFilter = true
            fileSelector.model = DefaultComboBoxModel(filtered.toTypedArray())
            if (filtered.isNotEmpty()) {
                fileSelector.selectedItem = filtered.first()
            }
            suppressFileFilter = false
        }
    }

    private fun analyzeFile(vFile: VirtualFile) {
        scope.launch {
            val target = fileTargetFactory.create(vFile)
            withContext(Dispatchers.Main) {
                if (target == null) {
                    onOwnershipStateChanged(OwnershipState.Error(null, "Selected file is not inside a Git repository."))
                } else {
                    ToolWindowManager.getInstance(project).getToolWindow("TraceOwners")?.show(null)
                    project.getService(OwnershipService::class.java).analyze(target, mode = selectedMode())
                }
            }
        }
    }

    // ── Custom border ────────────────────────────────────────────────────────────

    private class LeftBorderLine(private val color: Color, private val thickness: Int) : AbstractBorder() {
        override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
            (g as Graphics2D).color = color
            g.fillRect(x, y, thickness, height)
        }
        override fun getBorderInsets(c: Component): Insets = Insets(0, thickness + 8, 0, 0)
        override fun getBorderInsets(c: Component, insets: Insets): Insets {
            insets.set(0, thickness + 8, 0, 0)
            return insets
        }
    }

    private data class FileOption(
        val displayName: String,
        val path: String,
        val virtualFile: VirtualFile?
    ) {
        override fun toString(): String = displayName
    }
}
