package com.intellij.util.containers;

import gnu.trove.TObjectIntHashMap;
import gnu.trove.TObjectHashingStrategy;
import gnu.trove.TObjectIntIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// Replacement: extends TObjectIntHashMap AND implements ObjectIntMap so code-style-impl:241 can cast to it.
// ObjectIntMap in kotlin-compiler:1.9.25 only declares get(K) and put(K,int).
public class ObjectIntHashMap<K> extends TObjectIntHashMap<K> implements ObjectIntMap<K> {
    public ObjectIntHashMap() { super(); }
    public ObjectIntHashMap(int initialCapacity) { super(initialCapacity); }
    public ObjectIntHashMap(TObjectHashingStrategy<K> strategy) { super(strategy); }
    public ObjectIntHashMap(int initialCapacity, TObjectHashingStrategy<K> strategy) { super(initialCapacity, strategy); }

    @Override public int get(K key) { return super.get(key); }
    @Override public int put(K key, int value) { return super.put(key, value); }
    public int remove(K key) { return super.remove(key); }
    @SuppressWarnings("unchecked") public boolean containsKey(Object key) { return super.containsKey((K) key); }
    @SuppressWarnings("unchecked") public Set<K> keySet() { return super._set == null ? java.util.Collections.emptySet() : new java.util.HashSet<>((java.util.Collection<K>) java.util.Arrays.asList(super._set)); }

    public int[] values() {
        int[] result = new int[size()];
        TObjectIntIterator<K> it = iterator();
        for (int i = 0; it.hasNext(); i++) {
            it.advance();
            result[i] = it.value();
        }
        return result;
    }
}
