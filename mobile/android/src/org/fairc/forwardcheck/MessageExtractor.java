package org.fairc.forwardcheck;

import java.util.*;
import java.util.regex.Pattern;

/** Adds incoming text in visibly unsaved one-to-one chats to forwarded bubbles.
 * Contact text is reduced to flags before the snapshot; names/numbers are not retained.
 * Unknown layouts and ambiguous direction are skipped, never treated as scams.
 */
final class MessageExtractor {
    static final int UNSAVED = 1, GROUP = 2;
    private static final Pattern PHONE = Pattern.compile("\\+?[\\p{Nd} ()\\-]{8,24}");
    private static final Pattern UNSAVED_TEXT = Pattern.compile("(?i)(?:not (?:in|a|on) your contacts|not a contact|add to contacts|save (?:this )?contact|संपर्कों में नहीं|संपर्क में जोड़ें|कॉन्टैक्ट में जोड़ें)");

    static int senderCues(String id, CharSequence text, CharSequence description) {
        String leaf = leaf(id);
        String t = text == null ? "" : text.toString().replaceAll("[\\p{Cf}\\u00a0]", " ").trim();
        String d = description == null ? "" : description.toString();
        if (leaf.equals("conversation_contact_name") && PHONE.matcher(t).matches() && t.replaceAll("\\D", "").length() >= 8) return UNSAVED;
        // Only dedicated contact controls/banners count; message content never does.
        if ((leaf.contains("contact") || leaf.contains("unknown_sender") || leaf.contains("conversation_header")) &&
                !leaf.contains("name") && UNSAVED_TEXT.matcher(t + " " + d).find()) return UNSAVED;
        if (leaf.contains("group_participant") || leaf.equals("sender_name") || leaf.equals("participant_name") ||
                leaf.equals("group_sender_name") || leaf.equals("group_info") || leaf.equals("conversation_group_photo")) return GROUP;
        if (leaf.equals("conversation_contact_status") && (t.contains(",") || t.matches("(?i).*\\d+ (?:members|participants).*"))) return GROUP;
        return 0;
    }

    static List<ForwardExtractor.Candidate> extract(ForwardExtractor.Node root) {
        List<ForwardExtractor.Candidate> out = new ArrayList<>(ForwardExtractor.extract(root));
        Scan scan = scan(root);
        if (!scan.eligible()) return out;
        Set<String> seen = new HashSet<>();
        for (ForwardExtractor.Candidate c : out) seen.add(c.instanceId + "\n" + c.text);
        for (ForwardExtractor.Node bubble : scan.bubbles) {
            int leftGap = bubble.left() - scan.conversation.left();
            int rightGap = scan.conversation.right() - bubble.right();
            int width = scan.conversation.right() - scan.conversation.left();
            if (width <= 0 || bubble.right() <= bubble.left() || leftGap < 0 || rightGap < 0 ||
                    leftGap > width / 8 || rightGap - leftGap < width / 30) continue;
            List<ForwardExtractor.Node> bodies = new ArrayList<>();
            boolean[] outgoing = {false};
            bodies(bubble, bodies, outgoing, 0);
            if (outgoing[0] || bodies.size() != 1) continue;
            ForwardExtractor.Node body = bodies.get(0);
            String text = body.text() == null ? "" : body.text().toString().replace('\u00a0', ' ').trim().replaceAll("\\s+", " ");
            if (text.length() < 3 || text.length() > 4000) continue;
            if (seen.add(body.instanceId() + "\n" + text)) out.add(new ForwardExtractor.Candidate(text, "", body.viewId(), body.instanceId(), "unsaved"));
        }
        return out;
    }

    static boolean visiblyUnsaved(ForwardExtractor.Node root) { return scan(root).eligible(); }

    private static Scan scan(ForwardExtractor.Node root) {
        Scan scan = new Scan(); walk(root, scan, 0); return scan;
    }
    private static void walk(ForwardExtractor.Node node, Scan scan, int depth) {
        if (node == null || !node.visible()) return;
        if (depth > 18 || ++scan.nodes >= 600) { scan.clipped = true; return; }
        scan.cues |= node.senderCues();
        String id = leaf(node.viewId());
        if (id.equals("conversation_root_layout")) scan.conversation = node;
        if (id.equals("main_layout")) scan.bubbles.add(node);
        for (int i = 0; i < Math.min(80, node.childCount()); i++) walk(node.childAt(i), scan, depth + 1);
    }
    private static void bodies(ForwardExtractor.Node node, List<ForwardExtractor.Node> out, boolean[] outgoing, int depth) {
        if (node == null || !node.visible() || depth > 6) return;
        String id = leaf(node.viewId());
        if (id.equals("status") || id.equals("message_status") || id.equals("msg_status") || id.contains("outgoing")) outgoing[0] = true;
        if (id.contains("quoted") || id.contains("reply_preview")) return;
        if (id.equals("message_text") || id.equals("caption")) out.add(node);
        for (int i = 0; i < Math.min(40, node.childCount()); i++) bodies(node.childAt(i), out, outgoing, depth + 1);
    }
    private static String leaf(String id) { return id == null ? "" : id.substring(id.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT); }
    private static final class Scan {
        int nodes, cues; boolean clipped;
        ForwardExtractor.Node conversation;
        final List<ForwardExtractor.Node> bubbles = new ArrayList<>();
        boolean eligible() { return !clipped && conversation != null && (cues & UNSAVED) != 0 && (cues & GROUP) == 0; }
    }
}
