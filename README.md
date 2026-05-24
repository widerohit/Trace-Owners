# TraceOwners

TraceOwners is an IntelliJ IDEA plugin for ownership intelligence in enterprise engineering teams.

It helps developers answer three practical questions from inside the IDE:

- Who understands this code best?
- Who is actively maintaining it?
- Who should review a change?

TraceOwners is not a Git visualization tool. It is a focused, local-first expert discovery and reviewer recommendation plugin built on Git history.

## Core Workflow

1. Select or place the caret inside a supported target:
   - Java method
   - Java class
   - Kotlin function
   - Kotlin class/object
   - File
2. Right-click in the editor or project view.
3. Choose `TraceOwners -> Find Experts`.
4. Review the ownership analysis in the `TraceOwners` tool window.

When the tool window is already open, switch to another editor tab and click `Current File` to analyze ownership for that active file without using the context menu again.

Use the analysis mode selector to control Git history depth:

- `Fast` - prioritizes current ownership and recent file history.
- `Balanced` - default mode for everyday ownership analysis.
- `Deep` - reads a larger history window for older or high-risk code.

The result includes top contributors, expertise scores, recent activity, reviewer suggestions, active maintainers, and ownership warnings.

## Current Output

The tool window shows:

- Top experts for the selected method, class, function, or file.
- Contributor ranking cards with:
  - expertise score
  - score signal breakdown
  - commit count
  - lines modified
  - first active date
  - last active date
  - active contribution days
- Searchable contributor list.
- Suggested reviewers.
- Active maintainers.
- Git branch analyzed.
- Matching CODEOWNERS rules when present.
- Ownership warnings.
- Copy reviewer and summary actions.

Example warnings:

- `Only one active maintainer detected`
- `No recent contributors in the last 90 days`
- `Ownership concentration risk`
- `No Git contributors found for this target`

## How Analysis Works

TraceOwners uses the local Git CLI. It does not send code or history to any cloud service.

TraceOwners also reads `.github/CODEOWNERS`, `CODEOWNERS`, or `docs/CODEOWNERS` when present and displays matching ownership rules beside Git-derived ownership.

For file-level ownership, it analyzes:

- `git log --max-count=500 --since="2 years ago" --follow --numstat`

For method/class/function-level ownership, it also uses:

- `git blame --line-porcelain`
- `git log --max-count=150 --since="2 years ago" -L`

This lets the plugin combine historical edits with current line ownership for the selected source range.

For responsiveness, method/class/function targets analyze the selected line range first and only fall back to whole-file history if range-level analysis finds no contributors.

## Expertise Score

Expertise score is weighted. It does not rely only on commit count.

Signals include:

- commit frequency
- recent activity
- lines modified
- repeated contribution days
- long-term ownership duration

The final score is normalized to `0-100` and sorted from strongest ownership signal to weakest.

## Reviewer Suggestions

Suggested reviewers are selected from contributors with strong ownership signals, prioritizing:

- high expertise score
- recent activity
- repeated contributions
- commit frequency

Active maintainers are contributors with recent activity and meaningful ownership strength.

## Performance

The MVP is designed to avoid blocking the IDE:

- analysis runs in background coroutines
- Git commands run off the UI thread
- results are cached per target
- Fast, Balanced, and Deep modes let users trade accuracy depth for speed
- the `Current File` action analyzes the active editor file on demand
- refresh invalidates the local cache for the current target
- bounded Git history avoids full-repository scans on common paths
- Git command timeouts protect the IDE from long-running processes

## Install

Use the current installable plugin zip:

```text
W:\7.Unsync Project\TraceOwners\build\distributions\TraceOwners-0.1.0.zip
```

In IntelliJ IDEA:

1. Open `File -> Settings -> Plugins`.
2. Click the gear icon.
3. Choose `Install Plugin from Disk...`.
4. Select the zip above.
5. Restart IntelliJ IDEA.

This build declares compatibility with:

- IntelliJ build range: `241` through `261.*`
- Kotlin plugin K2 mode

## Build

This project uses Gradle Kotlin DSL and the IntelliJ Platform Gradle Plugin.

On this machine, Gradle is installed at:

```powershell
C:\Gradle\gradle-9.5.1\bin\gradle.bat
```

Build the plugin:

```powershell
& 'C:\Gradle\gradle-9.5.1\bin\gradle.bat' buildPlugin --no-daemon --stacktrace
```

The Gradle-built artifact is written to:

```text
build\distributions\
```

## Architecture

The code is separated into small ownership-focused packages:

- `actions` - IntelliJ context menu action and PSI target resolution
- `git` - Git repository resolution and Git CLI history parsing
- `expertise` - weighted expertise scoring
- `reviewer` - reviewer and active maintainer selection
- `risks` - ownership warning detection
- `cache` - local target analysis cache
- `ownership` - project service and async analysis orchestration
- `ui` - native IntelliJ tool window UI
- `model` - contributor and analysis data models

## MVP Non-Goals

TraceOwners intentionally does not include:

- AI summaries
- semantic expertise detection
- Slack or Teams integration
- ownership graphs
- heatmaps
- cloud sync
- ML models
- distributed indexing
- team dashboards
- pull request integrations

The MVP stays focused on fast, local Git-based ownership intelligence inside IntelliJ IDEA.
