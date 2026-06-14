"""Compliance Buddy -- Plain-English Explainer PDF with architecture diagram."""
from xhtml2pdf import pisa
import os, sys, base64, tempfile

OUT     = os.path.join(os.path.dirname(__file__), "CB_Explainer.pdf")
SVG_SRC = os.path.join(os.path.dirname(__file__), "architecture_v2.svg")


def svg_file_to_img_tag(svg_path, width_pt=530):
    """Render SVG file to PNG and return an <img> tag with base64 data URI."""
    try:
        from svglib.svglib import svg2rlg
        from reportlab.graphics import renderPM
        drawing = svg2rlg(svg_path)
        if drawing is None:
            raise RuntimeError("svg2rlg returned None")
        scale = width_pt / drawing.width
        drawing.width  = int(drawing.width  * scale)
        drawing.height = int(drawing.height * scale)
        drawing.transform = (scale, 0, 0, scale, 0, 0)
        tmp = svg_path + ".tmp.png"
        renderPM.drawToFile(drawing, tmp, fmt="PNG", dpi=150)
        with open(tmp, "rb") as f:
            b64 = base64.b64encode(f.read()).decode()
        os.unlink(tmp)
        return f'<img src="data:image/png;base64,{b64}" width="{width_pt}"/>'
    except Exception as e:
        return f'<p style="color:#dc2626;">[Architecture diagram unavailable: {e}]</p>'


ARCH_IMG = svg_file_to_img_tag(SVG_SRC)

HTML = """<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8"/>
<title>Compliance Buddy -- Plain English Guide</title>
<style>
  @page { size: A4; margin: 22mm 18mm; }

  body {
    font-family: Arial, Helvetica, sans-serif;
    font-size: 10.5pt;
    line-height: 1.65;
    color: #1a1a2e;
    background: #fff;
  }

  /* ── headings ── */
  h1 {
    font-size: 20pt;
    font-weight: bold;
    color: #1e3a8a;
    border-bottom: 3px solid #1a56db;
    padding-bottom: 6px;
    margin-top: 28px;
    margin-bottom: 12px;
    page-break-before: always;
  }
  h1.first { page-break-before: avoid; }
  h2 {
    font-size: 14pt;
    font-weight: bold;
    color: #1e3a8a;
    margin-top: 20px;
    margin-bottom: 8px;
  }
  h3 {
    font-size: 11.5pt;
    font-weight: bold;
    color: #374151;
    margin-top: 16px;
    margin-bottom: 6px;
  }
  p  { margin-bottom: 9px; }
  ul { margin: 6px 0 12px 20px; }
  li { margin-bottom: 4px; }

  /* ── callout boxes ── */
  .box {
    border-left: 5px solid;
    padding: 10px 14px;
    margin: 12px 0;
    border-radius: 0 5px 5px 0;
    page-break-inside: avoid;
  }
  .box-blue   { border-color: #1a56db; background: #dbeafe; }
  .box-green  { border-color: #16a34a; background: #dcfce7; }
  .box-purple { border-color: #7c3aed; background: #ede9fe; }
  .box-yellow { border-color: #d97706; background: #fef3c7; }
  .box-orange { border-color: #ea580c; background: #ffedd5; }
  .box-gray   { border-color: #6b7280; background: #f9fafb; }

  .box-title {
    font-weight: bold;
    font-size: 10.5pt;
    display: block;
    margin-bottom: 4px;
  }

  /* ── emoji number steps ── */
  .step {
    margin: 10px 0;
    padding: 10px 14px;
    background: #f8fafc;
    border-radius: 6px;
    border: 1px solid #e2e8f0;
    page-break-inside: avoid;
  }
  .step-num {
    font-size: 15pt;
    font-weight: bold;
    color: #1a56db;
    margin-right: 6px;
  }
  .step-label {
    font-weight: bold;
    color: #1e3a8a;
  }

  /* ── analogy cards ── */
  .analogy {
    background: #fffbeb;
    border: 1px solid #fde68a;
    border-radius: 6px;
    padding: 10px 14px;
    margin: 10px 0;
    page-break-inside: avoid;
  }
  .analogy-title {
    font-weight: bold;
    color: #92400e;
    font-size: 10pt;
  }

  /* ── feature card ── */
  .feature {
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    padding: 12px 14px;
    margin: 14px 0;
    page-break-inside: avoid;
  }
  .feature-header {
    font-size: 11pt;
    font-weight: bold;
    color: #1e3a8a;
    margin-bottom: 6px;
  }
  .feature-before {
    background: #fee2e2;
    border-radius: 4px;
    padding: 4px 10px;
    margin: 6px 0 4px;
    font-size: 9.5pt;
    color: #991b1b;
  }
  .feature-after {
    background: #dcfce7;
    border-radius: 4px;
    padding: 4px 10px;
    margin: 4px 0 6px;
    font-size: 9.5pt;
    color: #166534;
  }

  /* ── cover ── */
  .cover { page-break-after: always; padding: 20px 0; }
  .cover-badge {
    display: inline-block;
    background: #1a56db;
    color: #fff;
    font-size: 9pt;
    font-weight: bold;
    letter-spacing: 1px;
    padding: 4px 12px;
    border-radius: 4px;
    margin-bottom: 20px;
  }
  .cover-title {
    font-size: 34pt;
    font-weight: bold;
    color: #1e3a8a;
    line-height: 1.15;
    margin-bottom: 6px;
  }
  .cover-sub {
    font-size: 15pt;
    color: #6b7280;
    margin-bottom: 10px;
  }
  .cover-tagline {
    font-size: 11pt;
    color: #374151;
    margin-bottom: 28px;
    font-style: italic;
  }
  .divider {
    height: 4px;
    width: 60px;
    background: #1a56db;
    border-radius: 2px;
    margin-bottom: 28px;
  }

  /* ── table ── */
  table { width: 100%; border-collapse: collapse; margin: 10px 0 14px; font-size: 10pt; page-break-inside: avoid; }
  th { background: #1e3a8a; color: #fff; padding: 7px 11px; font-weight: bold; text-align: left; }
  td { padding: 6px 11px; border-bottom: 1px solid #e5e7eb; vertical-align: top; }
  tr.alt td { background: #f9fafb; }

  /* ── diagram box ── */
  .diagram-wrap {
    border: 2px solid #bfdbfe;
    border-radius: 6px;
    background: #f0f9ff;
    padding: 8px;
    margin: 14px 0;
    page-break-inside: avoid;
  }
  .diagram-caption {
    text-align: center;
    font-size: 9pt;
    color: #1e40af;
    font-style: italic;
    margin-top: 6px;
  }

  .highlight { background: #fef3c7; padding: 1px 4px; border-radius: 2px; }
  .new-tag   { color: #7c3aed; font-weight: bold; }
  .bold      { font-weight: bold; }

  .qa {
    margin: 10px 0;
    padding: 8px 14px;
    border-radius: 5px;
    background: #f8fafc;
    border: 1px solid #e2e8f0;
    page-break-inside: avoid;
  }
  .qa-q { font-weight: bold; color: #1e3a8a; margin-bottom: 3px; }
  .qa-a { color: #374151; }
  .section-break { page-break-before: always; }
</style>
</head>
<body>

<!-- ═══════════════ COVER ═══════════════ -->
<div class="cover">
  <div class="cover-badge">PLAIN ENGLISH GUIDE</div>
  <div class="cover-title">Compliance<br/>Buddy</div>
  <div class="cover-sub">Your Automated Robot Security Engineer</div>
  <div class="cover-tagline">"It finds security holes in your code, fixes them, and submits the fix for your approval — all by itself."</div>
  <div class="divider"></div>

  <p style="font-size:10.5pt; color:#374151; margin-bottom:6px;">
    This document explains how Compliance Buddy works in plain, everyday language.
    No programming background needed. If you can understand how an airport security check works,
    you can understand this.
  </p>

  <table style="width:auto; font-size:10pt; margin-top:20px;">
    <tr><td style="border:none; font-weight:bold; color:#111827; width:160px; padding:4px 20px 4px 0;">What it does</td><td style="border:none; color:#374151;">Automatically finds and fixes security weaknesses in Java code</td></tr>
    <tr><td style="border:none; font-weight:bold; color:#111827; padding:4px 20px 4px 0;">Who uses it</td><td style="border:none; color:#374151;">Development teams who want security issues fixed without manual effort</td></tr>
    <tr><td style="border:none; font-weight:bold; color:#111827; padding:4px 20px 4px 0;">How fast</td><td style="border:none; color:#374151;">From bug found to fix ready in under 10 minutes</td></tr>
    <tr><td style="border:none; font-weight:bold; color:#111827; padding:4px 20px 4px 0;">Human effort needed</td><td style="border:none; color:#374151;">One click: Approve or Reject the suggested fix</td></tr>
    <tr><td style="border:none; font-weight:bold; color:#111827; padding:4px 20px 4px 0;">AI used</td><td style="border:none; color:#374151;">GPT-4o (OpenAI) as primary, Claude Sonnet (Anthropic) as backup</td></tr>
    <tr><td style="border:none; font-weight:bold; color:#111827; padding:4px 20px 4px 0;">Version</td><td style="border:none; color:#374151;">v2 — June 2026 (9 components, 4 major new features)</td></tr>
  </table>
</div>


<!-- ═══════════════ SECTION 1 ═══════════════ -->
<h1 class="first">Section 1 — What Problem Does It Solve?</h1>

<h2>The Old Way (Without Compliance Buddy)</h2>
<p>
Imagine your codebase is like a large building. A security inspector (SonarQube) walks through it
every day and leaves Post-it notes wherever it finds problems — an unlocked door, a broken window,
a missing fire extinguisher.
</p>
<p>
In the old way, a developer has to:
</p>
<ul>
  <li>Read every Post-it note manually</li>
  <li>Research how to fix each type of problem</li>
  <li>Write the fix themselves</li>
  <li>Test that the fix doesn't break anything</li>
  <li>Submit the fix for a team review</li>
  <li>Wait for approval</li>
  <li>Repeat for every new problem found</li>
</ul>
<p>
This process can take hours or days per issue, and security bugs pile up faster than teams can fix them.
</p>

<h2>The New Way (With Compliance Buddy)</h2>
<div class="box box-green">
  <span class="box-title">The short version</span>
  Compliance Buddy reads those Post-it notes, figures out the fix, applies it, tests it,
  and hands it to a human for a single yes/no approval — automatically, 24 hours a day.
</div>

<div class="analogy">
  <div class="analogy-title">Real-world analogy</div>
  Think of Compliance Buddy like a robot auto-mechanic at a dealership.
  The car's onboard computer flags a fault. The robot mechanic reads the error code,
  looks up the right fix in its repair manual, orders the part, installs it, test-drives the car,
  then parks it in the "ready for final inspection" bay. The human service manager does one
  final check and signs off. That's it.
</div>


<!-- ═══════════════ SECTION 2 ═══════════════ -->
<h1>Section 2 — The Big Picture: Architecture Diagram</h1>

<p>
The diagram below shows all the pieces of Compliance Buddy and how they connect.
Don't worry about the technical names — the sections that follow will explain each part in plain language.
</p>

<div class="diagram-wrap">
###ARCH_IMG###
<div class="diagram-caption">
  Figure 1 — Compliance Buddy v2 Complete Architecture.
  Green boxes = the main fixing pipeline. Blue box = the AI brain. Purple = new features.
  Orange arrows = activity tracking. Grey arrows = users talking to the system.
</div>
</div>

<div class="box box-blue">
  <span class="box-title">How to read the diagram</span>
  <ul style="margin-bottom:0;">
    <li><span class="bold">Top row (blue band)</span> — outside systems CB talks to: the code scanner, the AI models, GitHub, and external tools</li>
    <li><span class="bold">Green band (middle)</span> — CB's core pipeline: scanner → AI brain → patcher → PR creator</li>
    <li><span class="bold">Purple band</span> — helper services: the API gateway, email alerts, and the new tool server</li>
    <li><span class="bold">Grey band (bottom)</span> — the filing cabinets and record-keepers: databases and the tracking system</li>
    <li><span class="bold">Arrows with dashes</span> — optional or background connections</li>
  </ul>
</div>


<!-- ═══════════════ SECTION 3 ═══════════════ -->
<h1>Section 3 — How It Works, Step by Step</h1>

<p>Here is the full journey of a security bug, from discovery to fix, explained like a story.</p>

<div class="step">
  <span class="step-num">①</span>
  <span class="step-label">The Scout goes on patrol (every 5 minutes)</span><br/>
  A component called <strong>cb-scanner</strong> wakes up every 5 minutes and asks SonarQube:
  "Found anything new?" SonarQube is the code scanner — it reads all the Java source code
  looking for security weaknesses like SQL injection, cross-site scripting, and hardcoded passwords.
  When cb-scanner finds a new problem, it writes it down in the database and stamps it <em>DETECTED</em>.
</div>

<div class="analogy">
  <div class="analogy-title">Think of it like...</div>
  A neighbourhood watch volunteer who walks the streets every 5 minutes and logs any new graffiti
  or broken windows in a shared notebook that the whole team can see.
</div>

<div class="step">
  <span class="step-num">②</span>
  <span class="step-label">The AI Brain wakes up and starts thinking (30 seconds later)</span><br/>
  Another component, <strong>cb-agent</strong>, checks the notebook every 30 seconds.
  When it sees a new DETECTED entry, it picks it up and calls in the AI (GPT-4o — the same
  model that powers ChatGPT's advanced version). The AI doesn't just guess a fix — it first
  <em>asks itself questions</em>: "Have I seen this type of bug before? What worked last time?"
  Only after gathering that context does it write the actual code fix.
</div>

<div class="analogy">
  <div class="analogy-title">Think of it like...</div>
  A doctor who, before prescribing medicine, first looks up your medical history and checks
  the hospital database for similar cases. Then they write the prescription based on what worked
  for others, not just a guess.
</div>

<div class="step">
  <span class="step-num">③</span>
  <span class="step-label">The Handyman applies the fix and tests it</span><br/>
  <strong>cb-patcher</strong> receives the AI's proposed fix. It carefully applies the changes
  to the actual code files (like a surgeon making precise incisions — only changing the exact
  lines that need changing), then runs the full test suite to confirm the code still compiles
  and builds correctly. If it builds, the fix is stamped <em>BUILD_VALIDATED</em>.
  If the build breaks, the fix is rejected and the AI tries again.
</div>

<div class="step">
  <span class="step-num">④</span>
  <span class="step-label">The fix gets filed and stored in memory</span><br/>
  Once a fix passes the build test, it's stored in two places:
  <ul>
    <li>The regular database (MongoDB) — the full details of what was fixed</li>
    <li>The AI memory (Qdrant — see Section 4) — a "smart" searchable version so future fixes can learn from it</li>
  </ul>
</div>

<div class="step">
  <span class="step-num">⑤</span>
  <span class="step-label">The Secretary creates a Pull Request on GitHub</span><br/>
  <strong>cb-pr</strong> takes the validated fix, creates a new branch in your GitHub repository,
  commits the changed files, and opens a Pull Request — just like a human developer would.
  The PR title and description are automatically written to explain what was fixed and why.
</div>

<div class="step">
  <span class="step-num">⑥</span>
  <span class="step-label">Three reviewers check the fix</span><br/>
  Three reviewers look at the PR simultaneously:
  <ul>
    <li><strong>GitHub Copilot AI</strong> — reviews the code diff and adds comments automatically</li>
    <li><strong>CB itself (GPT-4o)</strong> — does a second pass, posting numbered findings with an ACCEPT or REJECT checkbox</li>
    <li><strong>You (the human)</strong> — looks at the PR on GitHub and ticks the checkbox: Accept or Reject</li>
  </ul>
</div>

<div class="step">
  <span class="step-num">⑦</span>
  <span class="step-label">Approved? Done. Rejected? Try again.</span><br/>
  If you tick <strong>Accept</strong>: the vulnerability is marked RESOLVED and the team gets an email.
  Done. <br/>
  If you tick <strong>Reject</strong> (with a reason): CB closes the PR, reads your feedback,
  and tells the AI to try again — this time with your rejection reason baked into the new prompt.
  CB learns from the rejection to avoid the same mistake.
</div>

<div class="step">
  <span class="step-num">⑧</span>
  <span class="step-label">Alerts go out</span><br/>
  <strong>cb-notifier</strong> sends email alerts to the team when a vulnerability is escalated
  (tried 3 times without success) or when a PR is merged. It also pushes metrics to Datadog
  so dashboards stay up to date.
</div>


<!-- ═══════════════ SECTION 4 ═══════════════ -->
<h1>Section 4 — The 4 New Features (v2 Upgrades)</h1>

<p>Version 2 added four major improvements. Here's what changed and why it matters.</p>

<!-- FEATURE 1 -->
<div class="feature">
  <div class="feature-header">&#10024; New Feature 1 — AI Memory (Qdrant Vector Store)</div>
  <div class="feature-before">&#10060; Before (v1): The AI was "amnesiac" — it started fresh every time with no knowledge of past fixes</div>
  <div class="feature-after">&#9989; After (v2): The AI has a searchable memory of every past fix it successfully made</div>
  <p>
    Every time a fix is approved and merged, CB stores it in a special database called <strong>Qdrant</strong>.
    Qdrant isn't like a normal database where you search by exact words. Instead, it understands <em>meaning</em>.
    When the next similar bug appears, CB asks: "Have I fixed something like this before?" and Qdrant
    searches by concept — finding related fixes even if the exact wording is different.
  </p>
  <div class="analogy">
    <div class="analogy-title">Think of it like...</div>
    A chef's recipe book that not only stores exact recipes but can also recommend "similar dishes"
    when you tell it what ingredients you have. Ask for "something spicy with chicken" and it surfaces
    the 5 most relevant recipes — even if you don't remember the dish name.
  </div>
  <p><strong>Practical result:</strong> Over time, CB gets faster and more accurate because it doesn't reinvent
  the wheel for the same class of bug. A SQL injection fix it learned in Month 1 helps it write
  better fixes for SQL injections in Month 6.</p>
</div>

<!-- FEATURE 2 -->
<div class="feature">
  <div class="feature-header">&#10024; New Feature 2 — Smart AI Agent (Agentic Architecture)</div>
  <div class="feature-before">&#10060; Before (v1): The AI received one fixed prompt and gave one answer — no reasoning, no context-gathering</div>
  <div class="feature-after">&#9989; After (v2): The AI reasons through the problem step-by-step, using tools to gather context before answering</div>
  <p>
    In v1, the AI was handed a problem and told to answer in one shot — like asking someone to solve a maths
    problem without letting them use a calculator or look anything up.
  </p>
  <p>
    In v2, the AI is given <strong>tools it can call</strong>. Before writing the fix, it first runs:
  </p>
  <ul>
    <li><strong>Memory check:</strong> "Show me the 5 most similar bugs fixed in the past" (searches Qdrant)</li>
    <li><strong>Guideline lookup:</strong> "What's the standard approach for this type of vulnerability?" (checks a built-in knowledge base)</li>
    <li>Only then does it write the actual fix, using both sources of information</li>
  </ul>
  <div class="analogy">
    <div class="analogy-title">Think of it like...</div>
    The difference between a junior employee who just guesses an answer versus a senior consultant
    who first pulls the client file, checks industry best practices, and then gives a considered recommendation.
    Same intelligence, completely different quality of output.
  </div>
  <p>
    <strong>Safety net:</strong> If the AI tools fail, CB automatically falls back to the simpler approach
    (like v1) — so the system always produces <em>some</em> fix rather than crashing.
  </p>
</div>

<!-- FEATURE 3 -->
<div class="feature">
  <div class="feature-header">&#10024; New Feature 3 — Tool Server for Other AI Agents (MCP Server)</div>
  <div class="feature-before">&#10060; Before (v1): CB was a closed system — no other AI tool could interact with it</div>
  <div class="feature-after">&#9989; After (v2): External AI assistants like Claude Desktop or GitHub Copilot can directly use CB's capabilities</div>
  <p>
    CB now runs a small server called <strong>cb-mcp-server</strong> that speaks a standard language
    called MCP (Model Context Protocol). Other AI assistants that also speak MCP can connect to CB
    and call its functions like they were built-in tools.
  </p>
  <p>The 5 tools external AI agents can call:</p>
  <table>
    <tr><th>Tool name</th><th>What it does in plain English</th></tr>
    <tr><td>findPreviousFixes</td><td>Look up every fix CB has ever made for a specific type of bug</td></tr>
    <tr class="alt"><td>getSonarIssue</td><td>Get full details about a specific security warning by its ID</td></tr>
    <tr><td>getPRDiff</td><td>Show exactly what code changed in a given Pull Request</td></tr>
    <tr class="alt"><td>buildProject</td><td>Kick off a build of the code on a specific branch and report pass/fail</td></tr>
    <tr><td>createPR</td><td>Create a Pull Request on GitHub with specified title, description, and branches</td></tr>
  </table>
  <div class="analogy">
    <div class="analogy-title">Think of it like...</div>
    Instead of CB being a locked room that only CB staff can enter, it now has a reception window
    where other AI assistants can walk up and say "I need to know about past SQL injection fixes"
    and CB hands them the information directly.
    <br/><br/>
    Concretely: a developer could open Claude Desktop, type "What SQL injection fixes did CB make
    last month?" — and Claude would call CB's MCP server behind the scenes and return the answer.
    No web browser, no logging into a portal.
  </div>
</div>

<!-- FEATURE 4 -->
<div class="feature">
  <div class="feature-header">&#10024; New Feature 4 — Activity Tracking Dashboard (OpenTelemetry + Jaeger)</div>
  <div class="feature-before">&#10060; Before (v1): If something went wrong inside CB, we could only guess where by reading hundreds of log lines</div>
  <div class="feature-after">&#9989; After (v2): Every action across all 9 components is traced and visualised in a timeline dashboard</div>
  <p>
    CB now uses a standard called <strong>OpenTelemetry</strong> to record timing information for
    every step it takes. This data flows into <strong>Jaeger</strong>, a dashboard that shows a visual
    timeline of everything that happened — which component did what, in what order, and how long each step took.
  </p>
  <div class="analogy">
    <div class="analogy-title">Think of it like...</div>
    Imagine a parcel delivery system. OpenTelemetry is the barcode scanner at every depot.
    Jaeger is the tracking website where you can see "Parcel left warehouse at 09:02,
    arrived at sorting centre at 09:15, out for delivery at 10:30" — but for every single
    action CB performs across all its components.
  </div>
  <ul>
    <li>If a fix takes unusually long, the dashboard shows exactly which step is slow</li>
    <li>If a step fails, the dashboard shows the exact moment and what it was trying to do</li>
    <li>All 9 CB components contribute their timing data to one unified view</li>
  </ul>
</div>


<!-- ═══════════════ SECTION 5 ═══════════════ -->
<h1>Section 5 — Who's Who: The 9 Components</h1>

<p>CB is made up of 9 separate programs that each have one job, like specialists in a hospital.</p>

<table>
  <tr><th>Component</th><th>Job Title Analogy</th><th>What it actually does</th></tr>
  <tr>
    <td><strong>cb-scanner</strong></td>
    <td>Security Inspector</td>
    <td>Checks SonarQube every 5 min, logs new security problems to the shared database</td>
  </tr>
  <tr class="alt">
    <td><strong>cb-agent</strong></td>
    <td>AI Doctor + Researcher</td>
    <td>Picks up detected problems, consults its memory + guidelines, and generates a code fix using GPT-4o</td>
  </tr>
  <tr>
    <td><strong>cb-patcher</strong></td>
    <td>Surgical Nurse</td>
    <td>Applies the AI's fix to the real code files and runs the build to verify nothing broke</td>
  </tr>
  <tr class="alt">
    <td><strong>cb-pr</strong></td>
    <td>Executive Secretary</td>
    <td>Commits the fix to a new branch, opens a GitHub Pull Request, invites reviewers, and polls for your decision</td>
  </tr>
  <tr>
    <td><strong>cb-api</strong></td>
    <td>Front Desk Receptionist</td>
    <td>The only door to CB from the outside — accepts REST API calls, checks identity (API key), routes requests</td>
  </tr>
  <tr class="alt">
    <td><strong>cb-notifier</strong></td>
    <td>Team Communicator</td>
    <td>Sends email alerts when a bug can't be fixed after 3 tries, or when a PR is merged</td>
  </tr>
  <tr>
    <td><strong>cb-mcp-server &#10024;</strong></td>
    <td>External Relations Officer</td>
    <td>Lets other AI tools (Claude Desktop, GitHub Copilot) call CB's capabilities through the MCP standard</td>
  </tr>
  <tr class="alt">
    <td><strong>Qdrant &#10024;</strong></td>
    <td>Institutional Memory / Library</td>
    <td>Stores every past fix in a searchable, meaning-aware format so the AI can find similar past solutions</td>
  </tr>
  <tr>
    <td><strong>Jaeger &#10024;</strong></td>
    <td>CCTV Control Room</td>
    <td>Records and displays a visual timeline of every action across all 9 components in real time</td>
  </tr>
</table>


<!-- ═══════════════ SECTION 6 ═══════════════ -->
<h1>Section 6 — The Database: Where Everything Is Stored</h1>

<p>CB uses two types of storage, each for a different purpose.</p>

<h3>Storage Type 1 — MongoDB (the Filing Cabinet)</h3>
<p>
MongoDB stores the structured facts: which vulnerabilities were found, what fixes were attempted,
the PR URLs, build results, review decisions, audit logs. You can think of it as a very organised
spreadsheet with multiple tabs. Every CB component reads from and writes to this shared filing cabinet.
</p>
<table>
  <tr><th>Drawer (Collection)</th><th>What's stored there</th></tr>
  <tr><td>cb_vulnerabilities</td><td>Every security problem ever found, with its current status (DETECTED, FIXED, RESOLVED...)</td></tr>
  <tr class="alt"><td>cb_fixes</td><td>Every fix attempt: the code changes, confidence score, which AI model made it, build result, PR link</td></tr>
  <tr><td>cb_review_sessions</td><td>The GitHub PR review details, Copilot findings, and whether you ticked Accept or Reject</td></tr>
  <tr class="alt"><td>cb_audit_events</td><td>A full history log of every action — who did what and when</td></tr>
</table>

<h3>Storage Type 2 — Qdrant (the Smart Memory)</h3>
<p>
Qdrant is a completely different kind of storage. Instead of storing facts as rows and columns,
it stores the <em>meaning</em> of text as a list of 1,536 numbers (called an "embedding vector").
Each number represents a shade of meaning. Fixes about the same type of bug end up with
very similar number lists — so Qdrant can find them even if the exact words differ.
</p>
<div class="box box-purple">
  <span class="box-title">Why two databases?</span>
  MongoDB is fast for exact lookups: "give me fix with ID 12345". Qdrant is fast for
  fuzzy, conceptual searches: "give me fixes that are semantically similar to this SQL injection
  in a Spring Boot repository controller". They do completely different jobs.
</div>


<!-- ═══════════════ SECTION 7 ═══════════════ -->
<h1>Section 7 — Security and Safety Nets</h1>

<p>CB handles code that goes into production. Several safety nets are in place.</p>

<h3>1. Nothing gets merged without human approval</h3>
<p>
Every single fix must pass through the 3-reviewer step and receive a human tick before it can be
merged. CB can create PRs, but it cannot merge them. The human is always the final decision-maker.
</p>

<h3>2. Fixes are tested before you ever see them</h3>
<p>
cb-patcher runs the full Gradle build on every fix before a PR is even opened.
If the build fails — even for an unrelated reason — the fix is discarded and the AI tries again.
You never see a fix that doesn't compile.
</p>

<h3>3. Rejection feedback improves the next attempt</h3>
<p>
When you reject a fix and write a reason ("This changes too much", "Use a different approach"),
CB reads your reason and feeds it directly into the AI's next attempt. Over time CB learns
what your team accepts and what it doesn't.
</p>

<h3>4. Maximum 3 attempts per bug</h3>
<p>
If CB tries 3 times and all 3 are rejected (or fail the build), it stops trying and sends
an escalation alert to the team: "This bug needs human attention — CB couldn't fix it."
No infinite loops, no blocking the pipeline.
</p>

<h3>5. Backup AI model</h3>
<p>
If the primary AI (GPT-4o) is unavailable, CB automatically switches to Claude Sonnet (Anthropic's model)
as a backup. A "circuit breaker" mechanism prevents repeated failed calls from overwhelming the AI service.
</p>

<h3>6. All credentials are locked away</h3>
<p>
API keys for OpenAI, Anthropic, GitHub, and SonarQube are stored in Kubernetes Secrets —
never in the code, never in a configuration file that could be accidentally shared.
</p>


<!-- ═══════════════ SECTION 8 ═══════════════ -->
<h1>Section 8 — A Day in the Life of a Bug</h1>

<p>Here is a realistic example of how CB handles a SQL injection vulnerability from start to finish.</p>

<div class="box box-yellow">
  <span class="box-title">The scenario</span>
  A developer commits code that builds a database query by gluing strings together instead of using safe parameterised queries.
  This is called SQL injection — one of the most common and dangerous security vulnerabilities.
</div>

<div class="step"><span class="step-num">09:00</span> <span class="step-label">SonarQube finds the bug.</span><br/>
  SonarQube's scanner runs and flags: "CWE-89: SQL injection in UserRepository.java, line 47."
  The issue gets an ID: sqube-issue-abc123.
</div>

<div class="step"><span class="step-num">09:05</span> <span class="step-label">cb-scanner picks it up.</span><br/>
  On its next 5-minute patrol, cb-scanner fetches new SonarQube issues, sees sqube-issue-abc123,
  and saves it to MongoDB as a new Vulnerability with status DETECTED.
</div>

<div class="step"><span class="step-num">09:05:30</span> <span class="step-label">cb-agent starts working.</span><br/>
  cb-agent's 30-second check fires. It picks up the DETECTED vulnerability and calls the AI.
  The AI first searches Qdrant: "Show me the 5 most similar SQL injection fixes from the past."
  Qdrant finds 3 relevant past fixes — all using PreparedStatement. The AI also looks up the CWE-89
  guideline: "Always use parameterised queries." Now it writes the fix.
</div>

<div class="step"><span class="step-num">09:06</span> <span class="step-label">Fix is generated.</span><br/>
  The AI produces a precise code change: replace the string concatenation in UserRepository.java
  with a PreparedStatement. The fix is saved to MongoDB (status: FIX_GENERATED).
</div>

<div class="step"><span class="step-num">09:06:30</span> <span class="step-label">cb-patcher tests the fix.</span><br/>
  cb-patcher picks up the FIX_GENERATED record, applies the code change to the actual file,
  and runs <code>./gradlew build</code>. Build passes in 4 minutes. Status → BUILD_VALIDATED.
  The fix is also stored in Qdrant for future memory.
</div>

<div class="step"><span class="step-num">09:11</span> <span class="step-label">GitHub PR is opened.</span><br/>
  cb-pr creates branch <code>cb-fix/cwe89-abc123</code>, commits the change, and opens PR #247
  "Security fix: CWE-89 SQL Injection in UserRepository.java". GitHub Copilot is requested
  as a reviewer. CB's own AI review is kicked off in parallel.
</div>

<div class="step"><span class="step-num">09:12</span> <span class="step-label">Reviews come in.</span><br/>
  GitHub Copilot posts 2 inline comments about the code. CB posts a structured review:
  "No security findings. Fix correctly uses PreparedStatement. Recommend ACCEPT." with a checkbox.
</div>

<div class="step"><span class="step-num">09:30</span> <span class="step-label">You click Accept.</span><br/>
  You glance at the PR on GitHub, read the change, and tick the Accept checkbox in CB's review comment.
  Within 60 seconds, cb-pr detects your tick and updates MongoDB: vulnerability → RESOLVED.
  The team receives an email: "Security issue CWE-89 in UserRepository.java resolved via PR #247."
</div>

<div class="box box-green">
  <span class="box-title">Total elapsed time: ~30 minutes (mostly waiting for your review)</span>
  Of that 30 minutes, CB's active processing time was under 10 minutes.
  Your effort: roughly 2 minutes of reading and one click.
</div>


<!-- ═══════════════ SECTION 9 ═══════════════ -->
<h1>Section 9 — Frequently Asked Questions</h1>

<div class="qa">
  <div class="qa-q">Q: Can CB accidentally break my codebase?</div>
  <div class="qa-a">No. CB never merges code automatically. It creates Pull Requests that a human must approve first.
  Additionally, every fix is built and tested before it even appears as a PR. If the build fails, you never see the fix.</div>
</div>

<div class="qa">
  <div class="qa-q">Q: What happens if the AI makes a bad fix?</div>
  <div class="qa-a">You reject it. CB reads your rejection reason, learns from it, and tries again with improved instructions.
  After 3 failed attempts, it stops and alerts the team that the bug needs manual attention.</div>
</div>

<div class="qa">
  <div class="qa-q">Q: Does CB fix ALL types of security bugs?</div>
  <div class="qa-a">Currently CB focuses on the most common Java security vulnerabilities flagged by SonarQube
  (SQL injection, XSS, insecure deserialization, etc.). It improves over time as its fix memory grows.</div>
</div>

<div class="qa">
  <div class="qa-q">Q: Can I see what CB is doing right now?</div>
  <div class="qa-a">Yes. The Jaeger dashboard (new in v2) shows a real-time visual timeline of every action.
  You can also call the REST API at /api/v1/metrics/dashboard to get a summary of vulnerability counts by status.</div>
</div>

<div class="qa">
  <div class="qa-q">Q: Is my code sent to OpenAI or Anthropic?</div>
  <div class="qa-a">CB sends the vulnerability context and the relevant code snippet to the AI model, not your entire codebase.
  Only the specific lines flagged by SonarQube are included in the AI prompt.</div>
</div>

<div class="qa">
  <div class="qa-q">Q: What is the MCP server for?</div>
  <div class="qa-a">It lets other AI tools (like Claude Desktop or GitHub Copilot Chat) call CB's functions directly.
  For example, a developer could ask their AI assistant: "What fixes did CB make this week?" and the AI would
  use the MCP server to get a live answer from CB — no web browser needed.</div>
</div>

<div class="qa">
  <div class="qa-q">Q: What if GPT-4o is down?</div>
  <div class="qa-a">CB automatically switches to Claude Sonnet (Anthropic) as a backup. If both are unavailable,
  the circuit breaker kicks in to prevent repeated failed calls, and the fix generation is queued for retry.</div>
</div>

<div class="qa">
  <div class="qa-q">Q: How does CB improve over time?</div>
  <div class="qa-a">Every approved fix is stored in Qdrant memory. Next time a similar bug appears, CB searches
  this memory before writing the fix. The more fixes CB makes, the more context-aware and accurate it becomes.
  It's like training a new employee who gets better with every project they complete.</div>
</div>


<!-- ═══════════════ SECTION 10 ═══════════════ -->
<h1>Section 10 — Quick Reference Card</h1>

<h3>Status labels you'll see</h3>
<table>
  <tr><th>Status</th><th>Meaning in plain English</th></tr>
  <tr><td>DETECTED</td><td>SonarQube found a bug; CB knows about it but hasn't started fixing it yet</td></tr>
  <tr class="alt"><td>IN_PROGRESS</td><td>The AI is currently working on a fix</td></tr>
  <tr><td>FIX_GENERATED</td><td>The AI has written a proposed fix; waiting for build validation</td></tr>
  <tr class="alt"><td>FIX_VALIDATED</td><td>The fix passed the build test; waiting for a PR to be opened</td></tr>
  <tr><td>PR_RAISED</td><td>A Pull Request is open on GitHub; waiting for your review decision</td></tr>
  <tr class="alt"><td>RESOLVED</td><td>You approved the fix; done</td></tr>
  <tr><td>FAILED</td><td>3 attempts were made and all failed or were rejected; needs human attention</td></tr>
  <tr class="alt"><td>ESCALATED</td><td>CB has notified the team by email; human action required</td></tr>
</table>

<h3>Key numbers</h3>
<table>
  <tr><th>What</th><th>How often / How many</th></tr>
  <tr><td>SonarQube check</td><td>Every 5 minutes</td></tr>
  <tr class="alt"><td>AI fix check</td><td>Every 30 seconds</td></tr>
  <tr><td>PR review polling</td><td>Every 60 seconds</td></tr>
  <tr class="alt"><td>Memory indexing (Qdrant)</td><td>Every 5 minutes</td></tr>
  <tr><td>Max fix attempts per bug</td><td>3 attempts before escalation</td></tr>
  <tr class="alt"><td>AI models used</td><td>2 — GPT-4o (primary) + Claude Sonnet (backup)</td></tr>
  <tr><td>Total CB components</td><td>9 (7 services + Qdrant + Jaeger)</td></tr>
  <tr class="alt"><td>Tools exposed via MCP</td><td>5 tools for external AI agents</td></tr>
  <tr><td>Time from bug found to PR open</td><td>Under 10 minutes (typical)</td></tr>
</table>

<h3>Who is responsible for what</h3>
<table>
  <tr><th>Task</th><th>Who does it</th></tr>
  <tr><td>Finding bugs</td><td>SonarQube (automated scanner)</td></tr>
  <tr class="alt"><td>Writing the fix</td><td>GPT-4o / Claude Sonnet AI</td></tr>
  <tr><td>Testing the fix</td><td>cb-patcher (Gradle build)</td></tr>
  <tr class="alt"><td>Creating the PR</td><td>cb-pr (automated)</td></tr>
  <tr><td>Code reviewing the fix</td><td>GitHub Copilot AI + CB's own AI review</td></tr>
  <tr class="alt"><td>Final approval or rejection</td><td>YOU — the human developer</td></tr>
  <tr><td>Merging the PR</td><td>YOU — always a human decision</td></tr>
</table>

<div class="box box-blue" style="margin-top:20px;">
  <span class="box-title">Bottom line</span>
  Compliance Buddy does 95% of the security fix work automatically. Your job is the final 5%:
  read the PR, decide if the fix looks right, click Accept or Reject. That's it.
</div>

</body>
</html>""".replace("###ARCH_IMG###", ARCH_IMG)


def convert(html, out_path):
    with open(out_path, "wb") as f:
        result = pisa.CreatePDF(html, dest=f, encoding="utf-8")
    if result.err:
        print(f"ERROR: {result.err}")
        return False
    print(f"PDF created: {out_path}  ({os.path.getsize(out_path):,} bytes)")
    return True


if __name__ == "__main__":
    sys.exit(0 if convert(HTML, OUT) else 1)
