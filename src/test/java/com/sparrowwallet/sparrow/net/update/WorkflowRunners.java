package com.sparrowwallet.sparrow.net.update;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads a GitHub Actions workflow far enough to find every runner it names: the values under a job's runs-on (a name, a flow or block list, or a
 * mapping with labels: and group:) and every value under a job's strategy.matrix (which runs-on usually takes through ${{ matrix.x }}). It follows
 * YAML's structure by indentation (block mappings and sequences, "- key: value" items, flow lists that may span lines, quoted scalars, comments,
 * and | or > block scalars, whose text is skipped), so a key of the same name elsewhere, such as os: under env:, is not taken for a runner.
 * Anchors, aliases and flow mappings are not used in these workflows and are not read.
 */
final class WorkflowRunners {
    private static final Pattern LATEST = Pattern.compile("(?i)(?<![\\w.-])(macos|ubuntu|windows)-latest(?:-[a-z0-9]+)*(?![\\w.])");

    private record Frame(int indent, String key) {}

    private WorkflowRunners() {}

    /** Whether the workflow names a -latest runner (any case, any -latest-xyz size) where a runner is chosen */
    static boolean namesLatestRunner(String workflow) {
        return runnerValues(workflow).stream().anyMatch(value -> LATEST.matcher(value).find());
    }

    /** Every scalar under a job's runs-on or strategy.matrix */
    static List<String> runnerValues(String workflow) {
        List<String> found = new ArrayList<>();
        List<Frame> path = new ArrayList<>();
        String[] lines = workflow.replace("\r\n", "\n").split("\n", -1);
        for(int i = 0; i < lines.length; i++) {
            String raw = stripComment(lines[i]);
            if(raw.isBlank()) {
                continue;
            }
            int indent = indentOf(raw);
            String content = raw.trim();
            //"- " items: the item's own content sits further in, at the column after the dash
            boolean item = false;
            while(content.startsWith("- ") || content.equals("-")) {
                item = true;
                int dash = raw.indexOf('-', indent);
                popTo(path, indent);
                content = content.substring(1).trim();
                indent = dash + 1 + (raw.length() > dash + 1 ? indentOf(raw.substring(dash + 1)) : 0);
                if(content.isEmpty()) {
                    break;
                }
                raw = " ".repeat(indent) + content;
            }
            if(!item) {
                popTo(path, indent);
            }
            int colon = keyColon(content);
            String key = colon < 0 ? null : unquote(content.substring(0, colon).trim());
            String value = colon < 0 ? content : content.substring(colon + 1).trim();
            if(key != null) {
                path.add(new Frame(indent, key));
            }
            //A flow list that goes on over the next lines
            if(value.startsWith("[") && !value.contains("]")) {
                StringBuilder flow = new StringBuilder(value);
                while(i + 1 < lines.length && !flow.toString().contains("]")) {
                    flow.append(' ').append(stripComment(lines[++i]).trim());
                }
                value = flow.toString();
            }
            //A block scalar: its lines are text (a run: script), not structure
            if(value.matches("[|>][+-]?\\d*")) {
                while(i + 1 < lines.length && (stripComment(lines[i + 1]).isBlank() || indentOf(lines[i + 1]) > indent)) {
                    i++;
                }
                if(key != null) {
                    path.remove(path.size() - 1);
                }
                continue;
            }
            if(!value.isEmpty() && inRunnerPlace(path)) {
                for(String scalar : scalars(value)) {
                    found.add(scalar);
                }
            }
            if(key != null && !value.isEmpty()) {
                //A key with its value on the same line opens nothing below it
                path.remove(path.size() - 1);
            }
        }
        return found;
    }

    /** Under jobs.<job>.runs-on, or jobs.<job>.strategy.matrix */
    private static boolean inRunnerPlace(List<Frame> path) {
        if(path.size() < 3 || !path.get(0).key().equals("jobs")) {
            return false;
        }
        String third = path.get(2).key();
        if(third.equals("runs-on")) {
            return true;
        }
        return third.equals("strategy") && path.size() >= 4 && path.get(3).key().equals("matrix");
    }

    private static void popTo(List<Frame> path, int indent) {
        while(!path.isEmpty() && path.get(path.size() - 1).indent() >= indent) {
            path.remove(path.size() - 1);
        }
    }

    /** The values in a scalar or a flow list, unquoted */
    private static List<String> scalars(String value) {
        List<String> out = new ArrayList<>();
        String v = value.trim();
        if(v.startsWith("[")) {
            v = v.substring(1, v.lastIndexOf(']') < 0 ? v.length() : v.lastIndexOf(']'));
            for(String part : v.split(",")) {
                if(!part.isBlank()) {
                    out.add(unquote(part.trim()));
                }
            }
        } else {
            out.add(unquote(v));
        }
        return out;
    }

    private static String unquote(String s) {
        if(s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** The colon that ends a mapping key: outside quotes, followed by a space or the end, and not inside ${{ }} or a flow list */
    private static int keyColon(String content) {
        if(content.startsWith("[") || content.startsWith("{") || content.startsWith("\"") && content.indexOf('"', 1) < 0) {
            return -1;
        }
        char quote = 0;
        for(int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if(quote != 0) {
                if(c == quote) {
                    quote = 0;
                }
            } else if(c == '"' || c == '\'') {
                if(i == 0) {
                    quote = c;
                }
            } else if(c == '$' || c == '[' || c == '{') {
                return -1;
            } else if(c == ':' && (i + 1 == content.length() || content.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    /** The line without a # comment outside quotes */
    private static String stripComment(String line) {
        char quote = 0;
        for(int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if(quote != 0) {
                if(c == quote) {
                    quote = 0;
                }
            } else if((c == '"' || c == '\'') && (i == 0 || " :[,{-".indexOf(line.charAt(i - 1)) >= 0)) {
                //Only a quote that opens a scalar; an apostrophe inside plain text (eigenwallet's) is just a character
                quote = c;
            } else if(c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int indentOf(String line) {
        int n = 0;
        while(n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }
}
