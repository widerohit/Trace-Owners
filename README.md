# TraceOwners

TraceOwners is an IntelliJ IDEA plugin for ownership intelligence in enterprise engineering teams.

It helps developers answer three practical questions directly inside the IDE:

- Who understands this code best?
- Who is actively maintaining it?
- Who should review this change?

TraceOwners is not a Git visualization tool. It is a focused, local-first ownership intelligence and reviewer recommendation plugin built on Git history.

---

# Core Workflow

1. Open any supported file in IntelliJ IDEA.
2. Select a file from the toolbar dropdown.
3. Optionally select a method/function for deeper ownership analysis.
4. Choose analysis depth mode:
   - Fast
   - Balanced
   - Deep
5. Review ownership insights in the TraceOwners tool window.

---

# Toolbar Workflow

The top toolbar contains all controls in a single horizontal row:

- Depth Mode Dropdown
- File Dropdown
- **Method Dropdown** – shows all methods of the selected class/file, allowing you to view ownership analysis per method.
- Clear Method Selection Icon
- Refresh Icon

When you select a class or file, the Method Dropdown is populated with its methods. Selecting a method updates the analysis view to show contributors specific to that method.

---
---

# Depth Mode Analysis

TraceOwners supports three ownership analysis modes.

## Fast

Optimized for speed.

Best for:
- quick ownership lookup
- recent contributors
- lightweight Git history scans

Uses smaller Git history windows for faster response times.

---

## Balanced

Default mode for everyday usage.

Balances:
- performance
- ownership accuracy
- reviewer quality

Recommended for normal development workflows.

---

## Deep

Performs larger Git history analysis.

Best for:
- legacy systems
- high-risk modules
- older codebases
- long-term ownership discovery

Uses deeper Git history traversal and broader contribution analysis.

---

# File Dropdown

The File Dropdown automatically shows all files currently opened in IntelliJ IDEA editor tabs.

Features:

- searchable dropdown
- instant filtering
- quick file switching
- automatic ownership refresh on selection

Selecting a file immediately triggers full file-level ownership analysis.

---

# File-Level Ownership Analysis

When only a file is selected, TraceOwners performs complete file ownership analysis.

The tool window displays:

- top contributors
- expertise scores
- active maintainers
- recent contributors
- reviewer suggestions
- ownership warnings
- recent commits
- Git branch analyzed
- CODEOWNERS matches
- contribution activity summary

Example ownership warnings:

- `Only one active maintainer detected`
- `Ownership concentration risk`
- `No recent contributors in the last 90 days`
- `No Git contributors found for this target`

---

# Method-Level Ownership Analysis

After selecting a file, the Method Dropdown automatically loads all methods/functions from the selected class or file.

Features:

- searchable dropdown
- instant filtering
- method-specific ownership analysis
- quick switching between methods
- clear/reset support

Selecting a method switches analysis from file-level ownership to method-level ownership.

---

# Method-Level Insights

Method-level analysis provides deeper contributor intelligence for a specific code region.

The tool window shows:

- original method author
- strongest current owner
- recent contributors
- recent commits affecting the method
- expertise score
- last modified date
- reviewer suggestions
- ownership risks
- active maintainers

If method selection is cleared, TraceOwners automatically returns to full file-level analysis.

---

# Clear Method Selection

A clear/reset icon is available beside the Method Dropdown.

Behavior:

- clears selected method
- switches analysis back to file-level ownership
- preserves selected file and depth mode

---

# Refresh Analysis

The refresh icon re-runs ownership analysis for the currently selected target.

Refresh behavior:

- invalidates current cache
- re-fetches Git history
- refreshes ownership calculations
- preserves current selections

All refresh operations run asynchronously without blocking the IntelliJ UI thread.

---

# How Analysis Works

TraceOwners uses the local Git CLI.

No code or Git history is sent to any cloud service.

The plugin also reads ownership rules from:

- `.github/CODEOWNERS`
- `CODEOWNERS`
- `docs/CODEOWNERS`

Matching CODEOWNERS rules are displayed beside Git-derived ownership insights.

---

# File-Level Git Analysis

For full file ownership analysis:

```bash
git log --max-count=500 --since="2 years ago" --follow --numstat
```

---

# Method-Level Git Analysis

For method/class/function ownership analysis:

```bash
git blame --line-porcelain
```

```bash
git log --max-count=150 --since="2 years ago" -L
```

Method-level analysis uses line-range ownership detection to identify:

- original authors
- strongest maintainers
- recent contributors
- reviewer candidates

If range-level analysis finds no contributors, TraceOwners falls back to file-level ownership history.

---

# Expertise Score

Expertise score is weighted and normalized from `0-100`.

The score is calculated using:

- commit frequency
- recent activity
- lines modified
- repeated contribution days
- long-term ownership duration

This produces stronger ownership ranking than simple commit counting.

---

# Reviewer Suggestions

Reviewer suggestions prioritize contributors with:

- strong ownership signals
- high expertise scores
- recent activity
- repeated contributions
- active maintenance history

Active maintainers are contributors with recent meaningful ownership activity.

---

# Performance

TraceOwners is designed to remain responsive even in large repositories.

Performance optimizations include:

- background coroutine execution
- non-blocking Git operations
- cache-based ownership reuse
- bounded Git history scans
- Git command timeout protection
- per-target caching
- refresh-only invalidation
- lightweight UI updates

Cache keys include:

- file
- method
- depth mode

---

# Architecture

The codebase is separated into ownership-focused modules:

- `actions`
  - IntelliJ actions and PSI target resolution

- `git`
  - Git repository resolution and CLI parsing

- `expertise`
  - ownership scoring logic

- `reviewer`
  - reviewer recommendation engine

- `risks`
  - ownership risk detection

- `cache`
  - local ownership analysis cache

- `ownership`
  - async orchestration and analysis services

- `ui`
  - IntelliJ tool window UI

- `model`
  - contributor and ownership models

---

# Install

Use the generated plugin zip:

```text
build/distributions/TraceOwners-0.1.0.zip
```

Install in IntelliJ IDEA:

1. Open `File -> Settings -> Plugins`
2. Click the gear icon
3. Select `Install Plugin from Disk...`
4. Choose the plugin zip
5. Restart IntelliJ IDEA

---

# Compatibility

Supported IntelliJ build range:

```text
241 - 261.*
```

Supports:

- IntelliJ IDEA
- Kotlin K2 mode
- Java projects
- Kotlin projects

---

# Build

Build using Gradle:

```powershell
gradle buildPlugin --no-daemon --stacktrace
```

Generated plugin artifacts are written to:

```text
build/distributions/
```

---

# MVP Non-Goals

TraceOwners intentionally does not include:

- AI summaries
- semantic expertise detection
- Slack integration
- Teams integration
- ownership heatmaps
- cloud sync
- ML models
- distributed indexing
- pull request integrations
- team dashboards

The MVP stays focused on fast, local Git-based ownership intelligence directly inside IntelliJ IDEA.