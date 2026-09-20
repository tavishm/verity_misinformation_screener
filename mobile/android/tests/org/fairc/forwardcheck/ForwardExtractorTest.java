package org.fairc.forwardcheck;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Run with plain javac/java; intentionally has no Android or JUnit dependency. */
public final class ForwardExtractorTest {
    private static Node node(String id, String text, Node... children) {
        return new Node(id, text, true, children);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) throw new AssertionError(message + ": " + actual);
    }

    private static void empty(List<?> value, String message) {
        if (!value.isEmpty()) throw new AssertionError(message + ": " + value.size());
    }

    public static void main(String[] args) {
        markerAssociatesOnlyInsideItsBubble();
        multipleBubblesStayDistinct();
        identicalTextInNewBubblesStaysDistinct();
        quotedMarkerWordIsNotMetadata();
        missingMarkerIsIgnored();
        ambiguousContainerIsIgnored();
        hindiMarkerWorks();
        privatePersonalTextPassesThroughUnchangedForLocalClassification();
        asynchronousLifecycleGateFailsClosed();
        actualWhatsAppShapeAssociatesMarkerWithSiblingBody();
        System.out.println("PASS ForwardExtractorTest");
    }

    private static void markerAssociatesOnlyInsideItsBubble() {
        Node bubble = node("message_row", "", node("forwarded", "Forwarded"),
                node("message_text", "The city council approved the budget."));
        Node root = node("conversation", "", bubble,
                node("message_row", "", node("message_text", "A private message nearby.")));
        List<ForwardExtractor.Candidate> found = ForwardExtractor.extract(root);
        equal(1, found.size(), "one forwarded bubble");
        equal("The city council approved the budget.", found.get(0).text, "exact bubble text");
    }

    private static void multipleBubblesStayDistinct() {
        Node root = node("conversation", "",
                node("message_row", "", node("meta", "Forwarded"), node("message_text", "Claim number one is here.")),
                node("message_row", "", node("meta", "Forwarded many times"), node("message_text", "Claim number two is here.")));
        List<ForwardExtractor.Candidate> found = ForwardExtractor.extract(root);
        equal(2, found.size(), "two independent bubbles");
        equal("Claim number one is here.", found.get(0).text, "first text");
        equal("Claim number two is here.", found.get(1).text, "second text");
    }

    private static void quotedMarkerWordIsNotMetadata() {
        Node root = node("conversation", "", node("message_row", "",
                node("message_text", "She wrote “Forwarded” in her reply, but this is ordinary text.")));
        empty(ForwardExtractor.extract(root), "marker embedded in content");
    }

    private static void identicalTextInNewBubblesStaysDistinct() {
        Node root = node("conversation", "",
                node("message_row", "", node("meta", "Forwarded"), node("message_text", "The same forwarded factual statement.")),
                node("message_row", "", node("meta", "Forwarded"), node("message_text", "The same forwarded factual statement.")));
        List<ForwardExtractor.Candidate> found = ForwardExtractor.extract(root);
        equal(2, found.size(), "new copies must not disappear behind text-only deduplication");
        if (found.get(0).instanceId.equals(found.get(1).instanceId)) throw new AssertionError("separate visible message identity");
    }

    private static void missingMarkerIsIgnored() {
        empty(ForwardExtractor.extract(node("conversation", "", node("message_row", "",
                node("message_text", "The council approved the budget.")))), "ordinary message");
    }

    private static void ambiguousContainerIsIgnored() {
        Node root = node("conversation", "", node("message_row", "",
                node("meta", "Forwarded"), node("message_text", "First possible body."),
                node("caption", "Second possible body.")));
        empty(ForwardExtractor.extract(root), "two bodies in one group");
    }

    private static void hindiMarkerWorks() {
        List<ForwardExtractor.Candidate> found = ForwardExtractor.extract(node("conversation", "",
                node("message_row", "", node("meta", "कई बार अग्रेषित"),
                        node("message_text", "सरकार ने आज नई योजना की घोषणा की।"))));
        equal(1, found.size(), "Hindi marker");
    }

    private static void privatePersonalTextPassesThroughUnchangedForLocalClassification() {
        String personal = "Please bring my medicine home tonight.";
        List<ForwardExtractor.Candidate> found = ForwardExtractor.extract(node("conversation", "",
                node("message_row", "", node("meta", "Forwarded"), node("message_text", personal))));
        equal(1, found.size(), "extractor does not guess factuality");
        equal(personal, found.get(0).text, "classifier receives exact visible text locally");
    }

    private static void asynchronousLifecycleGateFailsClosed() {
        if (!ForwardExtractor.shouldPresent(false, 7, 7, false, true, true))
            throw new AssertionError("current visible offer should present");
        if (ForwardExtractor.shouldPresent(true, 7, 7, false, true, true))
            throw new AssertionError("destroyed service callback");
        if (ForwardExtractor.shouldPresent(false, 6, 7, false, true, true))
            throw new AssertionError("stale generation callback");
        if (ForwardExtractor.shouldPresent(false, 7, 7, true, true, true))
            throw new AssertionError("duplicate overlay");
        if (ForwardExtractor.shouldPresent(false, 7, 7, false, false, true))
            throw new AssertionError("cached personal/opinion skip");
        if (ForwardExtractor.shouldPresent(false, 7, 7, false, true, false))
            throw new AssertionError("candidate left visible chat");
    }

    private static void actualWhatsAppShapeAssociatesMarkerWithSiblingBody() {
        Node markerParent = node("marker_parent", "",
                node("conversation_row_top_text_attribute", "\u00a0Forwarded\u00a0"));
        Node textRow = node("conversation_text_row", "",
                node("message_text", "A synthetic health claim says a remedy cures an illness."));
        Node date = node("date_wrapper", "", node("date", "10:42 PM"));
        Node main = node("main_layout", "", markerParent, textRow, date);
        Node conversation = node("conversation_row_text", "", main);
        List<ForwardExtractor.Candidate> found = ForwardExtractor.extract(node("root", "", conversation));
        equal(1, found.size(), "observed WhatsApp sibling structure");
        equal("A synthetic health claim says a remedy cures an illness.", found.get(0).text,
                "only the sibling bubble body is extracted");
    }

    private static final class Node implements ForwardExtractor.Node {
        final String id, text;
        final boolean visible;
        final List<Node> children;
        Node(String id, String text, boolean visible, Node... children) {
            this.id = id; this.text = text; this.visible = visible;
            this.children = children == null ? Collections.emptyList() : Arrays.asList(children);
        }
        @Override public CharSequence text() { return text; }
        @Override public String viewId() { return id; }
        @Override public String className() { return "android.widget.TextView"; }
        @Override public String instanceId() { return Integer.toString(System.identityHashCode(this)); }
        @Override public boolean visible() { return visible; }
        @Override public int childCount() { return children.size(); }
        @Override public ForwardExtractor.Node childAt(int index) { return children.get(index); }
    }
}
