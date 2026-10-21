package dev.langchain4j.agentic.observability;

import dev.langchain4j.agentic.planner.AgentArgument;
import dev.langchain4j.agentic.planner.AgentCondition;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.planner.AgenticSystemTopology;
import dev.langchain4j.agentic.workflow.ConditionalAgent;
import dev.langchain4j.agentic.workflow.ConditionalAgentInstance;
import dev.langchain4j.agentic.workflow.LoopAgentInstance;
import dev.langchain4j.service.tool.ToolExecution;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Generates HTML reports for agent executions.
 * This class has been vibe-coded and is not expected to be maintainable manually by a human without LLM's help.
 */
public final class HtmlReportGenerator {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static void generateTopology(Object rootAgent, Path path) {
        try {
            dev.langchain4j.agentic.internal.AgenticJdk8.writeString(path, generateTopology(rootAgent));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String generateTopology(Object rootAgent) {
        return new HtmlReportGenerator(null, (AgentInstance) rootAgent, null).generateReport();
    }

    public static void generateReport(AgentMonitor monitor, Path path) {
        try {
            dev.langchain4j.agentic.internal.AgenticJdk8.writeString(path, generateReport(monitor));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String generateReport(AgentMonitor monitor) {
        return new HtmlReportGenerator(monitor, monitor.rootAgent(), null).generateReport();
    }

    public static void generateReport(AgentMonitor monitor, Object memoryId, Path path) {
        try {
            dev.langchain4j.agentic.internal.AgenticJdk8.writeString(path, generateReport(monitor, memoryId));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String generateReport(AgentMonitor monitor, Object memoryId) {
        return new HtmlReportGenerator(monitor, monitor.rootAgent(), memoryId).generateReport();
    }

    public static void generateExecution(AgentMonitor monitor, Path path) {
        try {
            dev.langchain4j.agentic.internal.AgenticJdk8.writeString(path, generateExecution(monitor));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String generateExecution(AgentMonitor monitor) {
        return new HtmlReportGenerator(monitor, monitor.rootAgent(), null).generateExecutionHtml();
    }

    public static void generateExecution(AgentMonitor monitor, Object memoryId, Path path) {
        try {
            dev.langchain4j.agentic.internal.AgenticJdk8.writeString(path, generateExecution(monitor, memoryId));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String generateExecution(AgentMonitor monitor, Object memoryId) {
        return new HtmlReportGenerator(monitor, monitor.rootAgent(), memoryId).generateExecutionHtml();
    }

    private String generateReport() {
        StringBuilder html = new StringBuilder(16384);
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n");
        appendHead(html);
        html.append("<body>\n");
        appendNavbar(html);
        html.append("<main class=\"container\">\n");
        appendTopologySection(html);
        if (monitor != null) {
            appendExecutionsSection(html);
        }
        appendFooter(html);
        html.append("</main>\n");
        appendScript(html);
        html.append("</body>\n</html>");
        return html.toString();
    }

    private String generateExecutionHtml() {
        String title = "LangChain4j Agentic System Execution";
        StringBuilder html = new StringBuilder(16384);
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n");
        appendHead(html, title);
        html.append("<body>\n");
        appendNavbar(html, title);
        html.append("<main class=\"container\">\n");
        appendExecutionsSection(html);
        appendFooter(html);
        html.append("</main>\n");
        appendScript(html);
        html.append("</body>\n</html>");
        return html.toString();
    }

    private String name() {
        return monitor != null ? "LangChain4j Agentic System Report" : "LangChain4j Agentic System Topology";
    }

    private void appendHead(StringBuilder html) {
        html.append("<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("<title>").append(esc(name())).append("</title>\n");
        appendStyles(html);
        html.append("</head>\n");
    }

    private void appendHead(StringBuilder html, String title) {
        html.append("<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("<title>").append(esc(title)).append("</title>\n");
        appendStyles(html);
        html.append("</head>\n");
    }

    private void appendStyles(StringBuilder html) {
        html.append("<style>\n");
        html.append(CSS);
        html.append("</style>\n");
    }

    private void appendNavbar(StringBuilder html) {
        html.append("<nav class=\"navbar\">\n");
        html.append("  <div class=\"navbar-logo\">").append(LOGO_SVG).append("</div>\n");
        html.append("  <div class=\"navbar-title\">").append(esc(name())).append("</div>\n");
        html.append("  <div class=\"navbar-subtitle\">LangChain4j Agentic System</div>\n");
        html.append("</nav>\n");
    }

    private void appendNavbar(StringBuilder html, String title) {
        html.append("<nav class=\"navbar\">\n");
        html.append("  <div class=\"navbar-logo\">").append(LOGO_SVG).append("</div>\n");
        html.append("  <div class=\"navbar-title\">").append(esc(title)).append("</div>\n");
        html.append("  <div class=\"navbar-subtitle\">LangChain4j Agentic System</div>\n");
        html.append("</nav>\n");
    }

    private void appendTopologySection(StringBuilder html) {
        Map<String, String> keyColors = collectStateKeyColors(rootAgent);
        html.append("<section class=\"section\">\n");
        html.append("<div class=\"section-header\">\n");
        html.append("  <div class=\"section-icon\">").append(ICON_TOPOLOGY).append("</div>\n");
        html.append("  <h2 class=\"section-title\">System Topology</h2>\n");
        html.append("</div>\n");
        appendLegend(html);
        if (!keyColors.isEmpty()) {
            appendDataFlowLegend(html, keyColors);
        }
        html.append("<div class=\"topology-scroll\">\n");
        html.append("<div class=\"org-tree\" style=\"position:relative\">\n");
        html.append("<svg id=\"df-svg\" class=\"df-svg\"><defs>");
        for (Map.Entry<String, String> kc : keyColors.entrySet()) {
            String id = "ah-" + Math.abs(kc.getKey().hashCode());
            html.append("<marker id=\"").append(id).append("\" viewBox=\"0 0 10 10\" refX=\"10\" refY=\"5\"");
            html.append(" markerWidth=\"5\" markerHeight=\"5\" orient=\"auto\">");
            html.append("<path d=\"M0 0 L10 5 L0 10 z\" fill=\"").append(kc.getValue()).append("\"/>");
            html.append("</marker>");
        }
        html.append("</defs></svg>\n");
        html.append("<ul>\n");
        appendTopologyNode(html, rootAgent, null, keyColors);
        html.append("</ul></div>\n");
        html.append("</div>\n");
        html.append("</section>\n");
    }

    private void appendDataFlowLegend(StringBuilder html, Map<String, String> keyColors) {
        html.append("<div class=\"legend df-legend\">\n");
        html.append("<span class=\"df-legend-title\">Data Flow</span>");
        for (Map.Entry<String, String> e : keyColors.entrySet()) {
            html.append("<div class=\"legend-item\">");
            html.append("<div class=\"legend-dot\" style=\"background:").append(e.getValue()).append("\"></div>");
            html.append("<span>").append(esc(e.getKey())).append("</span>");
            html.append("</div>\n");
        }
        html.append("<button class=\"df-toggle\" onclick=\"toggleDataFlow()\">Toggle Edges</button>");
        html.append("</div>\n");
    }

    private void appendLegend(StringBuilder html) {
        Set<AgenticSystemTopology> present = new LinkedHashSet<>();
        collectTopologies(rootAgent, present);
        html.append("<div class=\"legend\">\n");
        for (AgenticSystemTopology t : present) {
            html.append("<div class=\"legend-item\">");
            html.append("<div class=\"legend-dot\" style=\"background:").append(color(t)).append("\"></div>");
            html.append("<span>").append(label(t)).append("</span>");
            html.append("</div>\n");
        }
        html.append("</div>\n");
    }

    private static void collectTopologies(AgentInstance agent, Set<AgenticSystemTopology> topologies) {
        topologies.add(agent.topology());
        if (agent.subagents() != null) {
            for (AgentInstance child : agent.subagents()) {
                collectTopologies(child, topologies);
            }
        }
    }

    private void appendTopologyNode(StringBuilder html, AgentInstance agent, String condition, Map<String, String> keyColors) {
        html.append("<li>\n");
        if (condition != null && !condition.isEmpty()) {
            html.append("<div class=\"condition-label\">when: ").append(esc(condition)).append("</div>\n");
        }
        String css = cssCls(agent.topology());
        html.append("<div class=\"node-card node-border-").append(css).append("\"");
        // Data attributes for data-flow edge drawing
        html.append(" data-agent-id=\"").append(esc(agent.agentId())).append("\"");
        if (agent.outputKey() != null && !agent.outputKey().isEmpty()) {
            html.append(" data-output-key=\"").append(esc(agent.outputKey())).append("\"");
            html.append(" data-output-color=\"").append(keyColors.getOrDefault(agent.outputKey(), "#999")).append("\"");
        }
        if (agent.arguments() != null && !agent.arguments().isEmpty()) {
            String inputKeys = agent.arguments().stream().map(AgentArgument::name).reduce((a, b) -> a + "," + b).orElse("");
            html.append(" data-input-keys=\"").append(esc(inputKeys)).append("\"");
        }
        html.append(">\n");
        // Header
        html.append("<div class=\"node-card-header\">");
        html.append("<span class=\"topology-badge ").append(css).append("\">");
        html.append(label(agent.topology())).append("</span>");
        html.append("<span class=\"agent-name\">").append(esc(agent.name())).append("</span>");
        if (agent.async()) {
            html.append("<span class=\"async-badge\">async</span>");
        }
        html.append("</div>\n");
        // Details
        html.append("<div class=\"node-details\">\n");
        if (agent.type() != null) {
            String typeName = agent.type().getSimpleName();
            if (!typeName.equals(agent.name())) {
                detail(html, "Type", typeName);
            }
        }
        if (agent.description() != null && !agent.description().isEmpty()) {
            detail(html, "Desc", truncate(agent.description(), 60));
        }
        if (agent.outputType() != null) {
            detail(html, "Returns", simpleTypeName(agent.outputType()));
        }
        if (agent.plannerType() != null) {
            detail(html, "Planner", agent.plannerType().getSimpleName());
        }
        appendLoopInfo(html, agent);
        appendActivationCondition(html, agent);
        // node-details
        html.append("</div>\n");
        // Data-flow key badges (colored pills showing what this agent reads/writes)
        appendKeyBadges(html, agent, keyColors);
        // node-card
        html.append("</div>\n");
        // Children
        List<AgentInstance> children = agent.subagents();
        if (children != null && !children.isEmpty()) {
            Map<String, String> conds = conditionsOf(agent);
            html.append("<ul>\n");
            for (AgentInstance child : children) {
                appendTopologyNode(html, child, conds.get(child.agentId()), keyColors);
            }
            html.append("</ul>\n");
        }
        html.append("</li>\n");
    }

    private void appendKeyBadges(StringBuilder html, AgentInstance agent, Map<String, String> keyColors) {
        boolean hasInputs = agent.arguments() != null && !agent.arguments().isEmpty();
        boolean hasOutput = agent.outputKey() != null && !agent.outputKey().isEmpty();
        if (!hasInputs && !hasOutput)
            return;
        html.append("<div class=\"key-flow\">");
        if (hasInputs) {
            for (AgentArgument arg : agent.arguments()) {
                String c = keyColors.getOrDefault(arg.name(), "#999");
                html.append("<span class=\"key-pill key-in\" style=\"--kc:").append(c).append("\">");
                html.append("&#8592; ").append(esc(arg.name()));
                html.append("</span>");
            }
        }
        if (hasOutput) {
            String c = keyColors.getOrDefault(agent.outputKey(), "#999");
            html.append("<span class=\"key-pill key-out\" style=\"--kc:").append(c).append("\">");
            html.append("&#8594; ").append(esc(agent.outputKey()));
            html.append("</span>");
        }
        html.append("</div>\n");
    }

    private void appendLoopInfo(StringBuilder html, AgentInstance agent) {
        if (agent.topology() != AgenticSystemTopology.LOOP) {
            return;
        }
        LoopAgentInstance loop = agent.as(LoopAgentInstance.class);
        html.append("<div class=\"loop-info\">");
        html.append("<span class=\"loop-tag\">max ").append(loop.maxIterations()).append("</span>");
        if (loop.exitCondition() != null && !loop.exitCondition().isEmpty()) {
            html.append("<span class=\"loop-tag\">exit: ").append(esc(truncate(loop.exitCondition(), 40))).append("</span>");
        }
        html.append("<span class=\"loop-tag\">").append(loop.testExitAtLoopEnd() ? "test at end" : "test at start").append("</span>");
        html.append("</div>\n");
    }

    private void appendActivationCondition(StringBuilder html, AgentInstance agent) {
        AgentCondition condition = agent.activationCondition();
        if (condition == null) {
            return;
        }
        html.append("<div class=\"activation-info\">");
        html.append("<span class=\"activation-tag\" title=\"").append(esc(condition.description())).append("\">");
        html.append("if: ").append(esc(truncate(condition.description(), 40))).append("</span>");
        html.append("</div>\n");
    }

    private Map<String, String> conditionsOf(AgentInstance agent) {
        if (agent.topology() != AgenticSystemTopology.ROUTER) {
            return dev.langchain4j.internal.Jdk8.map();
        }
        ConditionalAgentInstance router;
        try {
            router = agent.as(ConditionalAgentInstance.class);
        } catch (ClassCastException e) {
            // a router built on a custom planner, with no activation conditions to show
            return dev.langchain4j.internal.Jdk8.map();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (ConditionalAgent ca : router.conditionalSubagents()) {
            if (ca.condition() != null) {
                for (AgentInstance child : ca.agentInstances()) {
                    map.put(child.agentId(), ca.condition());
                }
            }
        }
        return map;
    }

    private void appendExecutionsSection(StringBuilder html) {
        Set<Object> memoryIds;
        if (memoryId != null) {
            memoryIds = monitor.allMemoryIds().contains(memoryId) ? dev.langchain4j.internal.Jdk8.set(memoryId) : dev.langchain4j.internal.Jdk8.set();
        } else {
            memoryIds = monitor.allMemoryIds();
        }
        html.append("<section class=\"section\">\n");
        html.append("<div class=\"section-header\">\n");
        html.append("  <div class=\"section-icon\">").append(ICON_EXEC).append("</div>\n");
        html.append("  <h2 class=\"section-title\">Execution History</h2>\n");
        html.append("  <span class=\"section-count\">").append(memoryIds.size()).append(" session(s)</span>\n");
        html.append("  <button class=\"tool-toggle\" onclick=\"toggleTools()\">Toggle Tools</button>\n");
        html.append("</div>\n");
        if (memoryIds.isEmpty()) {
            html.append("<div class=\"no-data\">No executions recorded.</div>\n");
        } else {
            int idx = 0;
            for (Object mid : memoryIds) {
                appendExecutionGroup(html, mid, idx++);
            }
        }
        html.append("</section>\n");
    }

    private void appendExecutionGroup(StringBuilder html, Object memoryId, int idx) {
        List<MonitoredExecution> all = monitor.allExecutionsFor(memoryId);
        String gid = "g" + idx;
        boolean collapsed = idx > 0;
        long successCount = all.stream().filter(e -> statusOf(e).equals("ok")).count();
        long failCount = all.stream().filter(e -> statusOf(e).equals("fail")).count();
        long runCount = all.stream().filter(e -> statusOf(e).equals("run")).count();
        html.append("<div class=\"execution-group\">\n");
        // Header
        html.append("<div class=\"execution-header");
        if (collapsed)
            html.append(" collapsed");
        html.append("\" onclick=\"toggle('").append(gid).append("')\">\n");
        html.append("  <span class=\"chevron\">&#9660;</span>");
        html.append("  <span class=\"execution-memory-id\">Session: ").append(esc(String.valueOf(memoryId))).append("</span>");
        html.append("  <div class=\"execution-meta\">");
        html.append("    <span class=\"section-count\">").append(all.size()).append(" run(s)</span>");
        if (successCount > 0)
            html.append("<span class=\"status-badge st-ok\">").append(successCount).append(" success</span>");
        if (failCount > 0)
            html.append("<span class=\"status-badge st-fail\">").append(failCount).append(" failed</span>");
        if (runCount > 0)
            html.append("<span class=\"status-badge st-run\">in progress</span>");
        html.append("  </div>\n");
        html.append("</div>\n");
        // Body
        html.append("<div id=\"").append(gid).append("\" class=\"execution-body");
        if (collapsed)
            html.append(" collapsed");
        html.append("\">\n");
        for (MonitoredExecution e : all) {
            appendExecution(html, e);
        }
        html.append("</div>\n");
        html.append("</div>\n");
    }

    private static String statusOf(MonitoredExecution exec) {
        if (exec.hasError())
            return "fail";
        if (exec.done())
            return "ok";
        return "run";
    }

    private void appendExecution(StringBuilder html, MonitoredExecution exec) {
        String status = statusOf(exec);
        AgentInvocation top = exec.topLevelInvocations();
        html.append("<div class=\"single-exec\">\n");
        // Summary line
        html.append("<div class=\"exec-summary\">");
        html.append("<span class=\"status-dot st-dot-").append(status).append("\"></span>");
        html.append("<span class=\"exec-agent\">").append(esc(top.agent().name())).append("</span>");
        if (top.done()) {
            html.append("<span class=\"dur-badge\">").append(fmtDur(top.duration())).append("</span>");
            html.append("<span class=\"exec-time\">").append(top.startTime().format(TIME_FMT));
            html.append(" &#8594; ").append(top.finishTime().format(TIME_FMT)).append("</span>");
        } else {
            html.append("<span class=\"dur-badge\">running...</span>");
        }
        html.append("</div>\n");
        // Error
        if (exec.hasError()) {
            html.append("<div class=\"error-box\">");
            html.append("<strong>Error in ").append(esc(exec.error().agentName())).append(":</strong> ");
            html.append("<code>").append(esc(String.valueOf(exec.error().error().getMessage()))).append("</code>");
            html.append("</div>\n");
        }
        // Waterfall
        if (top.done() || !top.nestedInvocations().isEmpty() || !top.toolExecutions().isEmpty()) {
            LocalDateTime base = top.startTime();
            long totalMs = top.done() ? Math.max(1, top.duration().toMillis()) : Math.max(1, Duration.between(base, LocalDateTime.now()).toMillis());
            html.append("<table class=\"wf-table\">\n");
            html.append("<thead><tr><th>Agent</th><th>Duration</th><th>Tokens</th><th class=\"wf-timeline-col\">Timeline</th><th>Input</th><th>Output</th></tr></thead>\n");
            html.append("<tbody>\n");
            int[] rowCounter = { 0 };
            appendWfRow(html, top, 0, base, totalMs, rowCounter, -1);
            html.append("</tbody></table>\n");
        }
        html.append("</div>\n");
    }

    private void appendWfRow(StringBuilder html, AgentInvocation inv, int depth, LocalDateTime base, long totalMs, int[] rowCounter, int parentRowId) {
        AgentInstance ag = inv.agent();
        String css = cssCls(ag.topology());
        int myRowId = rowCounter[0]++;
        boolean hasChildren = !inv.nestedInvocations().isEmpty() || !inv.toolExecutions().isEmpty();
        long offMs = Duration.between(base, inv.startTime()).toMillis();
        long durMs = inv.done() ? inv.duration().toMillis() : Duration.between(inv.startTime(), LocalDateTime.now()).toMillis();
        double leftPct = Math.max(0, (double) offMs / totalMs * 100.0);
        double widthPct = Math.max(0.4, (double) durMs / totalMs * 100.0);
        html.append("<tr");
        if (inv.skipped()) {
            html.append(" class=\"skipped-row\"");
        }
        html.append(" data-row-id=\"").append(myRowId).append("\"");
        if (parentRowId >= 0) {
            html.append(" data-parent-row=\"").append(parentRowId).append("\"");
        }
        html.append(">");
        // Agent column
        html.append("<td><div class=\"wf-agent\">");
        for (int i = 0; i < depth; i++) html.append("<span class=\"wf-indent\"></span>");
        if (depth > 0)
            html.append("<span class=\"wf-connector\">&#x2514;</span>");
        if (hasChildren) {
            html.append("<span class=\"row-toggle\" onclick=\"toggleRow(").append(myRowId).append(")\">&#9660;</span>");
        }
        html.append("<span class=\"topology-badge sm ").append(css).append("\">").append(label(ag.topology())).append("</span>");
        html.append(" ").append(esc(ag.name()));
        if (inv.iterationIndex() >= 0) {
            html.append(" <span class=\"iter-tag\">iter ").append(inv.iterationIndex()).append("</span>");
        }
        if (inv.skipped()) {
            html.append(" <span class=\"skip-tag\" title=\"").append(esc(inv.skipReason())).append("\">skipped</span>");
        }
        html.append("</div></td>");
        // Duration column
        html.append("<td class=\"wf-dur\">");
        if (!inv.skipped()) {
            html.append(inv.done() ? fmtDur(inv.duration()) : "<em>...</em>");
        }
        html.append("</td>");
        // Tokens column
        html.append("<td class=\"wf-dur\">");
        if (inv.done() && inv.totalTokenCount() > 0) {
            html.append(fmtTokens(inv.totalTokenCount()));
        }
        html.append("</td>");
        // Timeline bar column, empty for a skipped agent
        html.append("<td><div class=\"wf-bar-track\">");
        if (!inv.skipped()) {
            html.append("<div class=\"wf-bar bar-").append(css).append("\" style=\"left:").append(fmt(leftPct)).append("%;width:").append(fmt(widthPct)).append("%;\"");
            html.append(" title=\"").append(esc(ag.name())).append(": ").append(inv.done() ? fmtDur(inv.duration()) : "running").append("\">");
            html.append("</div>");
        }
        html.append("</div></td>");
        // Input column
        html.append("<td class=\"wf-io\">");
        if (inv.inputs() != null && !inv.inputs().isEmpty()) {
            appendTruncatedWithTooltip(html, mapToString(inv.inputs()), 80);
        }
        html.append("</td>");
        // Output column, with the reason of the skip for a skipped agent
        html.append("<td class=\"wf-io\">");
        if (inv.skipped()) {
            appendTruncatedWithTooltip(html, inv.skipReason(), 80);
        } else if (inv.done() && inv.output() != null) {
            appendTruncatedWithTooltip(html, String.valueOf(inv.output()), 80);
        }
        html.append("</td>");
        html.append("</tr>\n");
        for (ToolExecution toolExec : inv.toolExecutions()) {
            appendToolWfRow(html, toolExec, depth + 1, base, totalMs, rowCounter, myRowId);
        }
        for (AgentInvocation nested : inv.nestedInvocations()) {
            appendWfRow(html, nested, depth + 1, base, totalMs, rowCounter, myRowId);
        }
    }

    private void appendToolWfRow(StringBuilder html, ToolExecution toolExec, int depth, LocalDateTime base, long totalMs, int[] rowCounter, int parentRowId) {
        int myRowId = rowCounter[0]++;
        Duration dur = toolExec.duration();
        boolean hasTiming = toolExec.startTime() != null && dur != null;
        double leftPct = 0;
        double widthPct = 0.4;
        if (hasTiming) {
            long offMs = Duration.between(base, toolExec.startTime()).toMillis();
            long durMs = dur.toMillis();
            leftPct = Math.max(0, (double) offMs / totalMs * 100.0);
            widthPct = Math.max(0.4, (double) durMs / totalMs * 100.0);
        }
        html.append("<tr class=\"tool-row\" data-row-id=\"").append(myRowId).append("\"");
        if (parentRowId >= 0) {
            html.append(" data-parent-row=\"").append(parentRowId).append("\"");
        }
        html.append(">");
        // Tool name column
        html.append("<td><div class=\"wf-agent\">");
        for (int i = 0; i < depth; i++) html.append("<span class=\"wf-indent\"></span>");
        html.append("<span class=\"wf-connector\">&#x2514;</span>");
        html.append("<span class=\"topology-badge sm tool\">Tool</span>");
        html.append(" ").append(esc(toolExec.request().name()));
        if (toolExec.hasFailed()) {
            html.append(" <span class=\"iter-tag\" style=\"background:#fee2e2;color:#991b1b;border-color:#fca5a5\">failed</span>");
        }
        html.append("</div></td>");
        // Duration column
        html.append("<td class=\"wf-dur\">");
        if (hasTiming) {
            html.append(fmtDur(dur));
        }
        html.append("</td>");
        // Tokens column (empty for tools)
        html.append("<td></td>");
        // Timeline bar column
        html.append("<td><div class=\"wf-bar-track\">");
        if (hasTiming) {
            html.append("<div class=\"wf-bar bar-tool\" style=\"left:").append(fmt(leftPct)).append("%;width:").append(fmt(widthPct)).append("%;\"");
            html.append(" title=\"").append(esc(toolExec.request().name())).append(": ").append(fmtDur(dur)).append("\">");
            html.append("</div>");
        }
        html.append("</div></td>");
        // Input column (tool arguments)
        html.append("<td class=\"wf-io\">");
        if (toolExec.request().arguments() != null && !toolExec.request().arguments().isEmpty()) {
            appendTruncatedWithTooltip(html, toolExec.request().arguments(), 80);
        }
        html.append("</td>");
        // Output column (tool result)
        html.append("<td class=\"wf-io\">");
        if (toolExec.result() != null) {
            appendTruncatedWithTooltip(html, toolExec.result(), 80);
        }
        html.append("</td>");
        html.append("</tr>\n");
    }

    private void appendTruncatedWithTooltip(StringBuilder html, String text, int max) {
        String display = truncate(text, max);
        html.append("<span class=\"tt-trigger\">");
        html.append(esc(display));
        if (text.length() > max) {
            html.append("<span class=\"tt-content\">").append(esc(text)).append("</span>");
        }
        html.append("</span>");
    }

    private void appendFooter(StringBuilder html) {
        html.append("<footer class=\"footer\">");
        html.append("Generated by LangChain4j at ");
        html.append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        html.append("</footer>\n");
    }

    private void appendScript(StringBuilder html) {
        html.append("<script>\nfunction toggle(id) {\n    var body = document.getElementById(id);\n    body.classList.toggle('collapsed');\n    body.previousElementSibling.classList.toggle('collapsed');\n}\n\nfunction toggleTools() {\n    document.querySelectorAll('.tool-row').forEach(function(row) {\n        row.classList.toggle('tool-hidden');\n    });\n}\n\nfunction toggleRow(rowId) {\n    var trigger = document.querySelector('[data-row-id=\"' + rowId + '\"]');\n    var arrow = trigger ? trigger.querySelector('.row-toggle') : null;\n    var collapsing = arrow && !arrow.classList.contains('collapsed');\n    if (arrow) arrow.classList.toggle('collapsed');\n    var children = document.querySelectorAll('[data-parent-row=\"' + rowId + '\"]');\n    children.forEach(function(row) {\n        if (collapsing) {\n            row.classList.add('row-hidden');\n            hideDescendants(row.getAttribute('data-row-id'));\n        } else {\n            row.classList.remove('row-hidden');\n        }\n    });\n}\n\nfunction hideDescendants(rowId) {\n    document.querySelectorAll('[data-parent-row=\"' + rowId + '\"]').forEach(function(row) {\n        row.classList.add('row-hidden');\n        hideDescendants(row.getAttribute('data-row-id'));\n    });\n}\n\nfunction toggleDataFlow() {\n    var svg = document.getElementById('df-svg');\n    if (!svg) return;\n    svg.classList.toggle('df-hidden');\n}\n\nfunction drawDataFlow() {\n    var svg = document.getElementById('df-svg');\n    var tree = svg ? svg.parentElement : null;\n    if (!svg || !tree) return;\n\n    // Size SVG to match tree\n    svg.style.width = tree.scrollWidth + 'px';\n    svg.style.height = tree.scrollHeight + 'px';\n\n    // Remove old paths\n    svg.querySelectorAll('.df-path').forEach(function(p) { p.remove(); });\n\n    var treeRect = tree.getBoundingClientRect();\n\n    // Build producer map: outputKey -> [node, ...]\n    var prodMap = {};\n    tree.querySelectorAll('.node-card[data-output-key]').forEach(function(n) {\n        var k = n.getAttribute('data-output-key');\n        if (!prodMap[k]) prodMap[k] = [];\n        prodMap[k].push(n);\n    });\n\n    // For each consumer node, draw edges from matching producers\n    tree.querySelectorAll('.node-card[data-input-keys]').forEach(function(consumer) {\n        var keys = consumer.getAttribute('data-input-keys').split(',');\n        var cRect = consumer.getBoundingClientRect();\n        var cx = cRect.left + cRect.width / 2 - treeRect.left;\n        var cy = cRect.top - treeRect.top;\n\n        keys.forEach(function(key) {\n            var producers = prodMap[key];\n            if (!producers) return;\n            producers.forEach(function(producer) {\n                if (producer === consumer) return;\n                // Skip if producer is an ancestor or descendant (structural edge)\n                if (producer.contains(consumer) || consumer.contains(producer)) return;\n\n                var color = producer.getAttribute('data-output-color') || '#999';\n                var pRect = producer.getBoundingClientRect();\n                var px = pRect.left + pRect.width / 2 - treeRect.left;\n                var py = pRect.bottom - treeRect.top;\n\n                var markerId = 'ah-' + Math.abs(hashCode(key));\n                drawEdge(svg, px, py, cx, cy, color, markerId);\n            });\n        });\n    });\n}\n\nfunction drawEdge(svg, x1, y1, x2, y2, color, markerId) {\n    var dx = x2 - x1;\n    var dy = y2 - y1;\n    var dist = Math.sqrt(dx*dx + dy*dy);\n    // Control point offset perpendicular to the line\n    var bend = Math.min(60, dist * 0.3);\n    // Offset to the right of the direction vector\n    var nx = -dy / (dist || 1) * bend;\n    var ny =  dx / (dist || 1) * bend;\n    var cpx = (x1 + x2) / 2 + nx;\n    var cpy = (y1 + y2) / 2 + ny;\n\n    var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');\n    path.setAttribute('d', 'M' + x1 + ',' + y1 + ' Q' + cpx + ',' + cpy + ' ' + x2 + ',' + y2);\n    path.setAttribute('stroke', color);\n    path.setAttribute('stroke-width', '1.8');\n    path.setAttribute('stroke-dasharray', '6,4');\n    path.setAttribute('fill', 'none');\n    path.setAttribute('opacity', '0.55');\n    path.setAttribute('class', 'df-path');\n    path.setAttribute('marker-end', 'url(#' + markerId + ')');\n    svg.appendChild(path);\n}\n\nfunction hashCode(s) {\n    var h = 0;\n    for (var i = 0; i < s.length; i++) {\n        h = ((h << 5) - h) + s.charCodeAt(i);\n        h |= 0;\n    }\n    return h;\n}\n\ndocument.addEventListener('DOMContentLoaded', drawDataFlow);\nwindow.addEventListener('resize', drawDataFlow);\n</script>\n");
    }

    private static void detail(StringBuilder html, String label, String value) {
        html.append("<div class=\"node-detail\">");
        html.append("<span class=\"nd-label\">").append(label).append("</span> ");
        html.append("<span class=\"nd-value\">").append(esc(value)).append("</span>");
        html.append("</div>\n");
    }

    private static String esc(String s) {
        if (s == null)
            return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String truncate(String s, int max) {
        if (s == null)
            return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String fmtDur(Duration d) {
        long ms = d.toMillis();
        if (ms < 1000)
            return ms + "ms";
        if (ms < 60_000)
            return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
        return String.format(Locale.ROOT, "%dm %ds", ms / 60_000, (ms % 60_000) / 1000);
    }

    private static String fmtTokens(int tokens) {
        if (tokens < 1000)
            return String.valueOf(tokens);
        return String.format(Locale.ROOT, "%.1fk", tokens / 1000.0);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String simpleTypeName(Type type) {
        if (type instanceof Class<?>)
            return ((Class<?>) type).getSimpleName();
        String n = type.getTypeName();
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(dot + 1) : n;
    }

    private static String mapToString(Map<String, Object> map) {
        if (map == null || map.isEmpty())
            return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first)
                sb.append(", ");
            first = false;
            sb.append(e.getKey()).append("=").append(truncate(String.valueOf(e.getValue()), 60));
        }
        return sb.append("}").toString();
    }

    private static String cssCls(AgenticSystemTopology t) {
        switch(t) {
            case AI_AGENT:
                return "ai";
            case NON_AI_AGENT:
                return "nonai";
            case HUMAN_IN_THE_LOOP:
                return "human";
            case SEQUENCE:
                return "seq";
            case PARALLEL:
                return "par";
            case LOOP:
                return "loop";
            case ROUTER:
                return "rtr";
            case STAR:
                return "star";
            default:
                throw new IllegalStateException("Unexpected enum value");
        }
    }

    private static String label(AgenticSystemTopology t) {
        switch(t) {
            case AI_AGENT:
                return "AI";
            case NON_AI_AGENT:
                return "Action";
            case HUMAN_IN_THE_LOOP:
                return "Human";
            case SEQUENCE:
                return "Sequence";
            case PARALLEL:
                return "Parallel";
            case LOOP:
                return "Loop";
            case ROUTER:
                return "Router";
            case STAR:
                return "Star";
            default:
                throw new IllegalStateException("Unexpected enum value");
        }
    }

    private static String color(AgenticSystemTopology t) {
        switch(t) {
            case AI_AGENT:
                return "#2e8555";
            case NON_AI_AGENT:
                return "#6b7280";
            case HUMAN_IN_THE_LOOP:
                return "#d97706";
            case SEQUENCE:
                return "#0891b2";
            case PARALLEL:
                return "#3b82f6";
            case LOOP:
                return "#7c3aed";
            case ROUTER:
                return "#dc2626";
            case STAR:
                return "#ca8a04";
            default:
                throw new IllegalStateException("Unexpected enum value");
        }
    }

    private static final String[] KEY_PALETTE = { "#e63946", "#457b9d", "#2a9d8f", "#e9c46a", "#7b2d8e", "#f4a261", "#264653", "#d62828", "#6a994e", "#bc6c25" };

    private Map<String, String> collectStateKeyColors(AgentInstance root) {
        Set<String> keys = new LinkedHashSet<>();
        collectKeys(root, keys);
        Map<String, String> colors = new LinkedHashMap<>();
        int i = 0;
        for (String key : keys) {
            colors.put(key, KEY_PALETTE[i % KEY_PALETTE.length]);
            i++;
        }
        return colors;
    }

    private void collectKeys(AgentInstance agent, Set<String> keys) {
        if (agent.outputKey() != null && !agent.outputKey().isEmpty()) {
            keys.add(agent.outputKey());
        }
        if (agent.arguments() != null) {
            for (AgentArgument arg : agent.arguments()) {
                if (arg.name() != null) {
                    keys.add(arg.name());
                }
            }
        }
        if (agent.subagents() != null) {
            for (AgentInstance child : agent.subagents()) {
                collectKeys(child, keys);
            }
        }
    }

    private static final String LOGO_SVG = "<svg width=\"28\" height=\"28\" viewBox=\"0 0 28 28\" fill=\"none\">" + "<rect width=\"28\" height=\"28\" rx=\"6\" fill=\"white\" fill-opacity=\"0.2\"/>" + "<path d=\"M7 8h14M7 14h14M7 20h10\" stroke=\"white\" stroke-width=\"2\" stroke-linecap=\"round\"/>" + "</svg>";

    private static final String ICON_TOPOLOGY = "<svg width=\"16\" height=\"16\" viewBox=\"0 0 16 16\" fill=\"none\">" + "<circle cx=\"8\" cy=\"3\" r=\"2\" fill=\"white\"/>" + "<circle cx=\"4\" cy=\"13\" r=\"2\" fill=\"white\"/>" + "<circle cx=\"12\" cy=\"13\" r=\"2\" fill=\"white\"/>" + "<line x1=\"8\" y1=\"5\" x2=\"4\" y2=\"11\" stroke=\"white\" stroke-width=\"1.5\"/>" + "<line x1=\"8\" y1=\"5\" x2=\"12\" y2=\"11\" stroke=\"white\" stroke-width=\"1.5\"/>" + "</svg>";

    private static final String ICON_EXEC = "<svg width=\"16\" height=\"16\" viewBox=\"0 0 16 16\" fill=\"none\">" + "<rect x=\"1\" y=\"3\" width=\"14\" height=\"2\" rx=\"1\" fill=\"white\"/>" + "<rect x=\"1\" y=\"7\" width=\"10\" height=\"2\" rx=\"1\" fill=\"white\"/>" + "<rect x=\"1\" y=\"11\" width=\"12\" height=\"2\" rx=\"1\" fill=\"white\"/>" + "</svg>";

    private static final String CSS = ":root {\n    --green: #2e8555;\n    --green-dk: #205d3b;\n    --green-lt: #3cad6e;\n    --teal: #25c2a0;\n    --c-ai: #2e8555;\n    --c-nonai: #6b7280;\n    --c-human: #d97706;\n    --c-seq: #0891b2;\n    --c-par: #3b82f6;\n    --c-loop: #7c3aed;\n    --c-rtr: #dc2626;\n    --c-star: #ca8a04;\n    --c-tool: #e74694;\n    --bg: #f8faf9;\n    --bg2: #f0f4f2;\n    --fg: #1a1a2e;\n    --fg2: #4a5568;\n    --fg3: #718096;\n    --brd: #e2e8f0;\n    --conn: #cbd5e1;\n    --rad: 8px;\n}\n* { margin:0; padding:0; box-sizing:border-box; }\nbody { font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;\n       background:var(--bg); color:var(--fg); line-height:1.6; }\n\n/* ---- Navbar ---- */\n.navbar { background:linear-gradient(135deg,var(--green-dk),var(--green));\n          color:#fff; padding:14px 32px; display:flex; align-items:center; gap:14px;\n          box-shadow:0 2px 8px rgba(0,0,0,.15); position:sticky; top:0; z-index:100; }\n.navbar-logo { display:flex; align-items:center; justify-content:center; }\n.navbar-title { font-size:20px; font-weight:600; letter-spacing:-.3px; }\n.navbar-subtitle { font-size:13px; opacity:.75; margin-left:auto; }\n\n/* ---- Container ---- */\n.container { width:100%; margin:0 auto; padding:32px; }\n\n/* ---- Section ---- */\n.section { margin-bottom:40px; }\n.section-header { display:flex; align-items:center; gap:12px; margin-bottom:16px;\n                  padding-bottom:10px; border-bottom:2px solid var(--green); }\n.section-icon { width:30px; height:30px; background:var(--green); border-radius:var(--rad);\n                display:flex; align-items:center; justify-content:center; flex-shrink:0; }\n.section-title { font-size:21px; font-weight:600; }\n.section-count { font-size:12px; color:var(--fg3); background:var(--bg2);\n                 padding:2px 10px; border-radius:12px; }\n\n/* ---- Legend ---- */\n.legend { display:flex; flex-wrap:wrap; gap:14px; margin-bottom:18px;\n          padding:10px 16px; background:#fff; border:1px solid var(--brd); border-radius:var(--rad); }\n.legend-item { display:flex; align-items:center; gap:5px; font-size:12px; color:var(--fg2); }\n.legend-dot { width:12px; height:12px; border-radius:3px; }\n\n/* ---- Org tree (top-down CSS connector chart) ---- */\n.topology-scroll { overflow-x:auto; padding:16px 0; }\n.org-tree { text-align:center; }\n.org-tree ul { display:flex; justify-content:center; padding-top:24px;\n               position:relative; list-style:none; }\n.org-tree > ul { padding-top:0; }\n.org-tree li { display:flex; flex-direction:column; align-items:center;\n               position:relative; padding:24px 10px 0; }\n.org-tree > ul > li { padding-top:0; }\n\n/* vertical line from parent ul down to horizontal bar */\n.org-tree ul::before { content:''; position:absolute; top:0; left:50%;\n                       border-left:2px solid var(--conn); height:24px; }\n.org-tree > ul::before { display:none; }\n\n/* horizontal bar + vertical drop per child */\n.org-tree li::before, .org-tree li::after {\n    content:''; position:absolute; top:0; width:50%; height:24px;\n    border-top:2px solid var(--conn); }\n.org-tree li::before { right:50%; }\n.org-tree li::after  { left:50%; border-left:2px solid var(--conn); }\n.org-tree > ul > li::before, .org-tree > ul > li::after { display:none; }\n.org-tree li:first-child::before { border-top:none; }\n.org-tree li:last-child::after   { border-top:none; }\n.org-tree li:only-child::before  { display:none; }\n.org-tree li:only-child::after   { border-top:none; }\n\n/* ---- Node card ---- */\n.node-card { position:relative; background:#fff; border:1px solid var(--brd);\n             border-radius:var(--rad); padding:10px 14px; min-width:140px; max-width:260px;\n             box-shadow:0 1px 3px rgba(0,0,0,.06); transition:box-shadow .2s,transform .2s; text-align:left; }\n.node-card:hover { box-shadow:0 4px 14px rgba(0,0,0,.1); transform:translateY(-2px); }\n.node-card-header { display:flex; align-items:center; gap:6px; margin-bottom:4px; flex-wrap:wrap; }\n.agent-name { font-size:14px; font-weight:600; }\n\n.topology-badge { display:inline-block; padding:1px 7px; border-radius:4px;\n                  font-size:10px; font-weight:700; text-transform:uppercase;\n                  letter-spacing:.4px; color:#fff; }\n.topology-badge.sm { font-size:9px; padding:0 5px; }\n.topology-badge.ai    { background:var(--c-ai); }\n.topology-badge.nonai { background:var(--c-nonai); }\n.topology-badge.human { background:var(--c-human); }\n.topology-badge.seq   { background:var(--c-seq); }\n.topology-badge.par   { background:var(--c-par); }\n.topology-badge.loop  { background:var(--c-loop); }\n.topology-badge.rtr   { background:var(--c-rtr); }\n.topology-badge.star  { background:var(--c-star); }\n.topology-badge.tool  { background:var(--c-tool); }\n\n.node-border-ai    { border-left:3px solid var(--c-ai); }\n.node-border-nonai { border-left:3px solid var(--c-nonai); }\n.node-border-human { border-left:3px solid var(--c-human); }\n.node-border-seq   { border-left:3px solid var(--c-seq); }\n.node-border-par   { border-left:3px solid var(--c-par); }\n.node-border-loop  { border-left:3px solid var(--c-loop); }\n.node-border-rtr   { border-left:3px solid var(--c-rtr); }\n.node-border-star  { border-left:3px solid var(--c-star); }\n\n.async-badge { font-size:9px; background:#fef3c7; color:#92400e; padding:0 5px;\n               border-radius:3px; border:1px solid #fcd34d; }\n\n.node-details { font-size:11px; }\n.node-detail { display:flex; gap:4px; margin-top:1px; }\n.nd-label { color:var(--fg3); font-weight:500; }\n.nd-value { color:var(--fg2); }\n\n.condition-label { font-size:9px; background:#fef3c7; color:#92400e;\n                   padding:1px 8px; border-radius:3px; border:1px solid #fcd34d;\n                   margin-bottom:4px; white-space:nowrap; }\n\n.loop-info { display:flex; gap:6px; margin-top:4px; flex-wrap:wrap; }\n.activation-info { display:flex; margin-top:4px; }\n.activation-tag { font-size:10px; background:#fef3c7; color:#92400e;\n                  padding:0 6px; border-radius:3px; border:1px solid #fcd34d; white-space:nowrap; }\n.loop-tag { font-size:10px; background:#ede9fe; color:#5b21b6;\n            padding:0 6px; border-radius:3px; border:1px solid #c4b5fd; }\n\n/* ---- Data-flow key badges ---- */\n.key-flow { display:flex; flex-wrap:wrap; gap:3px; margin-top:5px;\n            padding-top:5px; border-top:1px dashed var(--brd); }\n.key-pill { font-size:9px; font-weight:600; padding:0 6px; border-radius:3px;\n            border:1px solid var(--kc);\n            background:#fff; color:var(--kc); white-space:nowrap;\n            opacity:.85; }\n\n/* ---- Tool toggle ---- */\n.tool-toggle { font-size:11px; padding:3px 12px; border-radius:4px;\n               border:1px solid var(--brd); background:var(--bg2); color:var(--fg2);\n               cursor:pointer; transition:background .15s; margin-left:auto; }\n.tool-toggle:hover { background:var(--brd); }\n.tool-row.tool-hidden { display:none; }\n.row-toggle { cursor:pointer; font-size:10px; margin-right:4px; display:inline-block;\n              transition:transform .2s; color:var(--fg3); user-select:none; }\n.row-toggle.collapsed { transform:rotate(-90deg); }\n.row-hidden { display:none; }\n\n/* ---- Data-flow SVG overlay ---- */\n.df-svg { position:absolute; top:0; left:0; pointer-events:none; z-index:2; overflow:visible; }\n.df-svg.df-hidden { display:none; }\n\n/* ---- Data-flow legend ---- */\n.df-legend { align-items:center; }\n.df-legend-title { font-size:12px; font-weight:600; color:var(--fg); margin-right:4px; }\n.df-toggle { margin-left:auto; font-size:11px; padding:3px 12px; border-radius:4px;\n             border:1px solid var(--brd); background:var(--bg2); color:var(--fg2);\n             cursor:pointer; transition:background .15s; }\n.df-toggle:hover { background:var(--brd); }\n\n/* ---- Execution groups ---- */\n.execution-group { margin-bottom:12px; }\n.execution-header { display:flex; align-items:center; gap:10px; padding:10px 16px;\n                    background:#fff; border:1px solid var(--brd);\n                    border-radius:var(--rad) var(--rad) 0 0;\n                    cursor:pointer; user-select:none; transition:background .15s; }\n.execution-header:hover { background:var(--bg2); }\n.execution-header.collapsed { border-radius:var(--rad); }\n.chevron { transition:transform .2s; color:var(--fg3); font-size:11px; }\n.execution-header.collapsed .chevron { transform:rotate(-90deg); }\n.execution-memory-id { font-weight:600; font-size:14px; }\n.execution-meta { margin-left:auto; display:flex; gap:8px; align-items:center; }\n\n.status-badge { padding:2px 10px; border-radius:12px; font-size:11px; font-weight:600; }\n.st-ok   { background:#d1fae5; color:#065f46; }\n.st-fail { background:#fee2e2; color:#991b1b; }\n.st-run  { background:#dbeafe; color:#1e40af; }\n\n.execution-body { border:1px solid var(--brd); border-top:none;\n                  border-radius:0 0 var(--rad) var(--rad); background:#fff; overflow:hidden; }\n.execution-body.collapsed { display:none; }\n\n/* ---- Single execution ---- */\n.single-exec { padding:12px 16px; border-bottom:1px solid var(--brd); }\n.single-exec:last-child { border-bottom:none; }\n.exec-summary { display:flex; align-items:center; gap:10px; margin-bottom:8px; flex-wrap:wrap; }\n.status-dot { width:8px; height:8px; border-radius:50%; flex-shrink:0; }\n.st-dot-ok   { background:#10b981; }\n.st-dot-fail { background:#ef4444; }\n.st-dot-run  { background:#3b82f6; animation:pulse 1.5s infinite; }\n@keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.4} }\n.exec-agent { font-weight:600; font-size:14px; }\n.dur-badge { font-size:12px; color:var(--fg3); font-family:'SF Mono','Fira Code',monospace; }\n.exec-time { font-size:11px; color:var(--fg3); }\n\n.error-box { background:#fef2f2; border:1px solid #fecaca; border-radius:var(--rad);\n             padding:8px 12px; margin-bottom:8px; font-size:12px; color:#991b1b; }\n.error-box code { font-size:11px; word-break:break-all; }\n\n/* ---- Waterfall table ---- */\n.wf-table { width:100%; border-collapse:collapse; font-size:13px; }\n.wf-table th { text-align:left; padding:6px 10px; background:var(--bg2); color:var(--fg3);\n               font-size:10px; font-weight:600; text-transform:uppercase;\n               letter-spacing:.5px; border-bottom:1px solid var(--brd); }\n.wf-table td { padding:5px 10px; border-bottom:1px solid var(--brd); vertical-align:middle; }\n.wf-table tr:last-child td { border-bottom:none; }\n.wf-table tr:hover { background:var(--bg); }\n.wf-timeline-col { width:35%; }\n\n.wf-agent { display:flex; align-items:center; gap:4px; white-space:nowrap; }\n.wf-indent { display:inline-block; width:18px; }\n.wf-connector { color:var(--conn); font-family:monospace; margin-right:2px; font-size:12px; }\n.wf-dur { font-family:'SF Mono','Fira Code',monospace; font-size:12px; color:var(--fg2); white-space:nowrap; }\n\n.wf-bar-track { position:relative; height:18px; background:var(--bg2); border-radius:3px; min-width:120px; }\n.wf-bar { position:absolute; top:2px; height:14px; border-radius:3px; opacity:.8; min-width:2px;\n          transition:opacity .15s; }\n.wf-bar:hover { opacity:1; }\n.bar-ai    { background:var(--c-ai); }\n.bar-nonai { background:var(--c-nonai); }\n.bar-human { background:var(--c-human); }\n.bar-seq   { background:var(--c-seq); }\n.bar-par   { background:var(--c-par); }\n.bar-loop  { background:var(--c-loop); }\n.bar-rtr   { background:var(--c-rtr); }\n.bar-star  { background:var(--c-star); }\n.bar-tool  { background:var(--c-tool); }\n\n.wf-io { font-size:11px; color:var(--fg3); white-space:nowrap; }\n\n.iter-tag { font-size:9px; background:#ede9fe; color:#5b21b6;\n            padding:0 5px; border-radius:3px; border:1px solid #c4b5fd;\n            font-weight:600; white-space:nowrap; }\n.skip-tag { font-size:9px; background:var(--bg2); color:var(--fg3);\n            padding:0 5px; border-radius:3px; border:1px solid var(--brd);\n            font-weight:600; white-space:nowrap; }\n.skipped-row { opacity:.65; }\n\n/* ---- Tooltip ---- */\n.tt-trigger { position:relative; cursor:help; }\n.tt-trigger .tt-content { display:none; position:absolute; bottom:calc(100% + 4px); left:0;\n                          background:var(--fg); color:#fff; padding:6px 10px; border-radius:6px;\n                          font-size:11px; white-space:pre-wrap; max-width:400px; z-index:10;\n                          box-shadow:0 4px 12px rgba(0,0,0,.2); word-break:break-all; }\n.tt-trigger:hover .tt-content { display:block; }\n\n/* ---- Footer ---- */\n.footer { text-align:center; padding:24px 0 8px; font-size:12px; color:var(--fg3);\n          border-top:1px solid var(--brd); margin-top:20px; }\n";

    private final AgentMonitor monitor;

    public AgentMonitor monitor() {
        return monitor;
    }

    private final AgentInstance rootAgent;

    public AgentInstance rootAgent() {
        return rootAgent;
    }

    private final Object memoryId;

    public Object memoryId() {
        return memoryId;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public HtmlReportGenerator(@com.fasterxml.jackson.annotation.JsonProperty("monitor") AgentMonitor monitor, @com.fasterxml.jackson.annotation.JsonProperty("rootAgent") AgentInstance rootAgent, @com.fasterxml.jackson.annotation.JsonProperty("memoryId") Object memoryId) {
        this.monitor = monitor;
        this.rootAgent = rootAgent;
        this.memoryId = memoryId;
    }

    public final boolean equals(Object obj) {
        return obj == this || (obj != null && obj.getClass() == getClass() && java.util.Objects.equals(monitor, ((HtmlReportGenerator) obj).monitor) && java.util.Objects.equals(rootAgent, ((HtmlReportGenerator) obj).rootAgent) && java.util.Objects.equals(memoryId, ((HtmlReportGenerator) obj).memoryId));
    }

    public final int hashCode() {
        return (31 * (31 * (31 * 0 + java.util.Objects.hashCode(monitor)) + java.util.Objects.hashCode(rootAgent)) + java.util.Objects.hashCode(memoryId));
    }

    public final String toString() {
        return "HtmlReportGenerator[" + "monitor=" + monitor + ", rootAgent=" + rootAgent + ", memoryId=" + memoryId + "]";
    }
}
