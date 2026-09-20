package org.fairc.forwardcheck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Conservative forwarded-message extraction from an already-visible UI tree.
 *
 * <p>The extractor intentionally knows very little about WhatsApp internals. A
 * marker and exactly one plausible message must share a small local container.
 * Unknown or flattened layouts are skipped instead of treating the chat window
 * as one message.</p>
 */
public final class ForwardExtractor {
    private static final int MAX_TREE_DEPTH = 18;
    private static final int MAX_NODES = 600;
    private static final int MAX_BUBBLE_DEPTH = 4;
    private static final int MAX_TEXT = 4000;
    private static final Set<String> MARKERS;

    static {
        Set<String> values = new HashSet<>();
        Collections.addAll(values,
                "forwarded", "forwarded many times",
                "अग्रेषित", "फॉरवर्ड किया गया", "कई बार अग्रेषित",
                "कई बार फॉरवर्ड किया गया");
        MARKERS = Collections.unmodifiableSet(values);
    }

    private ForwardExtractor() {}

    /** Minimal adapter so extraction tests have no Android dependency. */
    public interface Node {
        CharSequence text();
        String viewId();
        String className();
        boolean visible();
        int childCount();
        Node childAt(int index);
        default String instanceId() { return viewId(); }
        default int senderCues() { return 0; }
        default int left() { return 0; }
        default int right() { return 0; }
    }

    public static final class Candidate {
        public final String text;
        public final String marker;
        public final String messageViewId;
        public final String instanceId;
        public final String source;

        Candidate(String text, String marker, String messageViewId, String instanceId) {
            this(text, marker, messageViewId, instanceId, "forwarded");
        }
        Candidate(String text, String marker, String messageViewId, String instanceId, String source) {
            this.text = text;
            this.marker = marker;
            this.messageViewId = messageViewId;
            this.instanceId = instanceId;
            this.source = source;
        }
    }

    public static List<Candidate> extract(Node root) {
        if (root == null || !root.visible()) return Collections.emptyList();
        List<Node> path = new ArrayList<>();
        List<MarkerPath> markers = new ArrayList<>();
        int[] visited = {0};
        collectMarkers(root, path, markers, visited, 0);
        if (visited[0] >= MAX_NODES) return Collections.emptyList();

        List<Candidate> result = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        for (MarkerPath marker : markers) {
            Candidate candidate = associate(marker);
            if (candidate != null) {
                String identity = candidate.instanceId + "\n" + candidate.text;
                if (emitted.add(identity)) result.add(candidate);
            }
        }
        return result;
    }

    private static void collectMarkers(Node node, List<Node> path, List<MarkerPath> output,
                                       int[] visited, int depth) {
        if (node == null || !node.visible() || depth > MAX_TREE_DEPTH || visited[0]++ >= MAX_NODES) return;
        path.add(node);
        String value = clean(node.text());
        if (isMarker(value)) output.add(new MarkerPath(new ArrayList<>(path), value));
        int children = Math.min(node.childCount(), 80);
        for (int index = 0; index < children; index++) {
            collectMarkers(node.childAt(index), path, output, visited, depth + 1);
            if (visited[0] >= MAX_NODES) break;
        }
        path.remove(path.size() - 1);
    }

    private static Candidate associate(MarkerPath marker) {
        // The marker itself is the final path node. Search only small ancestors;
        // the first unambiguous local group wins.
        for (int distance = 1; distance <= 3; distance++) {
            int position = marker.path.size() - 1 - distance;
            if (position < 0) break;
            Node container = marker.path.get(position);
            List<Node> textNodes = new ArrayList<>();
            collectMessageLeaves(container, textNodes, 0, marker.value);
            if (textNodes.size() != 1) continue;
            Node message = textNodes.get(0);
            String text = clean(message.text());
            // A large generic/root container is not a bubble, even when only
            // one message happens to be visible in it.
            if (distance == 3 && !hasBubbleHint(container, message)) continue;
            return new Candidate(text, marker.value, safe(message.viewId()), safe(message.instanceId()));
        }
        return null;
    }

    private static void collectMessageLeaves(Node node, List<Node> output, int depth, String marker) {
        if (node == null || !node.visible() || depth > MAX_BUBBLE_DEPTH || output.size() > 1) return;
        String text = clean(node.text());
        if (!text.isEmpty() && !text.equals(marker) && plausibleMessage(node, text)) output.add(node);
        int children = Math.min(node.childCount(), 30);
        for (int index = 0; index < children; index++) {
            collectMessageLeaves(node.childAt(index), output, depth + 1, marker);
            if (output.size() > 1) return;
        }
    }

    private static boolean plausibleMessage(Node node, String text) {
        if (text.length() < 8 || text.length() > MAX_TEXT || isMarker(text)) return false;
        if (text.matches("(?i)^\\d{1,2}:\\d{2}(?:\\s?[ap]m)?(?:\\s?[✓✔]+)?$")) return false;
        String id = safe(node.viewId()).toLowerCase(Locale.ROOT);
        String type = safe(node.className()).toLowerCase(Locale.ROOT);
        if (id.contains("date") || id.contains("time") || id.contains("status") || id.contains("sender")) return false;
        if (type.contains("button") || type.contains("edittext")) return false;
        return node.childCount() == 0 || id.contains("message") || id.contains("text") || id.contains("caption");
    }

    private static boolean hasBubbleHint(Node container, Node message) {
        String ids = (safe(container.viewId()) + " " + safe(message.viewId())).toLowerCase(Locale.ROOT);
        return ids.contains("message") || ids.contains("bubble") || ids.contains("row") ||
                ids.contains("text") || ids.contains("caption");
    }

    static boolean isMarker(String value) {
        return MARKERS.contains(clean(value).toLowerCase(Locale.ROOT));
    }

    /** Pure lifecycle gate used after asynchronous on-device classification. */
    static boolean shouldPresent(boolean destroyed, int requestedGeneration, int currentGeneration,
                                 boolean overlayShowing, boolean offer, boolean candidateVisible) {
        return !destroyed && requestedGeneration == currentGeneration && !overlayShowing &&
                offer && candidateVisible;
    }

    private static String clean(CharSequence value) {
        if (value == null) return "";
        return value.toString().replace('\u00a0', ' ').trim().replaceAll("\\s+", " ");
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static final class MarkerPath {
        final List<Node> path;
        final String value;
        MarkerPath(List<Node> path, String value) { this.path = path; this.value = value; }
    }
}
