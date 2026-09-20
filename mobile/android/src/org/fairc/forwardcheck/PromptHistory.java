package org.fairc.forwardcheck;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** Remembers explicit choices by message fingerprint, never by a recycled UI node. */
public final class PromptHistory {
    private final int capacity;
    private final LinkedHashSet<String> acknowledged = new LinkedHashSet<>();

    public PromptHistory(int capacity) { this.capacity = Math.max(1, capacity); }

    public boolean shouldOffer(String messageFingerprint, boolean checkWorthy) {
        return checkWorthy && !acknowledged.contains(messageFingerprint);
    }

    public void acknowledge(String messageFingerprint) {
        if (messageFingerprint == null || messageFingerprint.isEmpty()) return;
        acknowledged.remove(messageFingerprint);
        acknowledged.add(messageFingerprint);
        while (acknowledged.size() > capacity) acknowledged.remove(acknowledged.iterator().next());
    }

    public void restore(Collection<String> fingerprints) {
        acknowledged.clear();
        if (fingerprints != null) for (String fingerprint : fingerprints) acknowledge(fingerprint);
    }

    public Set<String> snapshot() { return new LinkedHashSet<>(acknowledged); }
    public int size() { return acknowledged.size(); }
    public void clear() { acknowledged.clear(); }
}
