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

    /** Where a value sits: a job's runs-on, one of its matrix keys, or one of its reusable-workflow inputs (with:) */
    private enum Place { RUNS_ON, MATRIX, WITH }

    private record Value(String job, Place place, String key, String text) {}

    private static final Pattern MATRIX_REFERENCE = Pattern.compile("\\$\\{\\{\\s*matrix\\.([A-Za-z0-9_-]+)\\s*}}");

    /**
     * Every runner a workflow names: the values of a job's runs-on, the values of its matrix keys that runs-on refers to
     * (runs-on: ${{ matrix.os }}), and the values of its with: inputs, which a reusable workflow may use as its runner. YAML aliases
     * (*name) are replaced by what their anchor (&name) holds.
     */
    static List<String> runnerValues(String workflow) {
        List<Value> values = new ArrayList<>();
        java.util.Map<String, List<String>> anchors = new java.util.HashMap<>();
        //Anchors being filled from a block below their key: name, and the indent of that key
        List<java.util.Map.Entry<String, Integer>> filling = new ArrayList<>();
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
            //An anchored block ends at the first line that is not indented further than its key
            final int rawIndent = indentOf(lines[i]);
            filling.removeIf(open -> rawIndent <= open.getValue());
            int colon = keyColon(content);
            String key = colon < 0 ? null : unquote(content.substring(0, colon).trim());
            String value = colon < 0 ? content : content.substring(colon + 1).trim();
            if(key != null) {
                path.add(new Frame(indent, key));
            }
            //An anchor: on its own, it names the block below; before a value, it names that value
            java.util.regex.Matcher anchor = Pattern.compile("^&([A-Za-z0-9_-]+)\\s*(.*)$").matcher(value);
            if(anchor.matches()) {
                value = anchor.group(2).trim();
                if(value.isEmpty()) {
                    anchors.put(anchor.group(1), new ArrayList<>());
                    filling.add(java.util.Map.entry(anchor.group(1), indentOf(lines[i])));
                } else {
                    String full = value;
                    if(full.startsWith("[") && !full.contains("]")) {
                        StringBuilder flow = new StringBuilder(full);
                        for(int k = i + 1; k < lines.length && !flow.toString().contains("]"); k++) {
                            flow.append(' ').append(stripComment(lines[k]).trim());
                        }
                        full = flow.toString();
                    }
                    anchors.put(anchor.group(1), scalars(full));
                }
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
            if(!value.isEmpty()) {
                List<String> texts = value.startsWith("*") ? anchors.getOrDefault(value.substring(1).trim(), List.of()) : scalars(value);
                for(java.util.Map.Entry<String, Integer> open : filling) {
                    if(!value.startsWith("*")) {
                        anchors.get(open.getKey()).addAll(texts);
                    }
                }
                Value place = place(path, "");
                if(place != null) {
                    for(String text : texts) {
                        values.add(new Value(place.job(), place.place(), place.key(), text));
                    }
                }
            }
            if(key != null && !value.isEmpty()) {
                //A key with its value on the same line opens nothing below it
                path.remove(path.size() - 1);
            }
        }

        //The matrix keys each job's runs-on refers to
        java.util.Map<String, java.util.Set<String>> referenced = new java.util.HashMap<>();
        for(Value value : values) {
            if(value.place() == Place.RUNS_ON) {
                java.util.regex.Matcher reference = MATRIX_REFERENCE.matcher(value.text());
                while(reference.find()) {
                    referenced.computeIfAbsent(value.job(), job -> new java.util.HashSet<>()).add(reference.group(1));
                }
            }
        }
        List<String> found = new ArrayList<>();
        for(Value value : values) {
            boolean runner = switch(value.place()) {
                case RUNS_ON, WITH -> true;
                case MATRIX -> referenced.getOrDefault(value.job(), java.util.Set.of()).contains(value.key());
            };
            if(runner) {
                found.add(value.text());
            }
        }
        return found;
    }

    /** Which job, place and key a value at this path belongs to, or null when it is not where a runner is named */
    private static Value place(List<Frame> path, String text) {
        if(path.size() < 3 || !path.get(0).key().equals("jobs")) {
            return null;
        }
        String job = path.get(1).key();
        String third = path.get(2).key();
        if(third.equals("runs-on")) {
            return new Value(job, Place.RUNS_ON, null, text);
        }
        if(third.equals("with") && path.size() >= 4) {
            return new Value(job, Place.WITH, path.get(3).key(), text);
        }
        if(third.equals("strategy") && path.size() >= 5 && path.get(3).key().equals("matrix")) {
            //matrix.<key>, or matrix.include/exclude entries' <key>
            String key = path.get(4).key();
            if((key.equals("include") || key.equals("exclude")) && path.size() >= 6) {
                key = path.get(5).key();
            }
            return new Value(job, Place.MATRIX, key, text);
        }
        return null;
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
