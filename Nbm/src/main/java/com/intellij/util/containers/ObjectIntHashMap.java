package com.intellij.util.containers;

import gnu.trove.TObjectIntHashMap;
import gnu.trove.TObjectHashingStrategy;
import gnu.trove.TObjectIntIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// Replacement for 193/241-era ObjectIntHashMap that explicitly implements ObjectIntMap,
// allowing code-style-impl:241 to cast ObjectIntHashMap to ObjectIntMap.
public class ObjectIntHashMap<K> extends TObjectIntHashMap<K> implements ObjectIntMap<K> {
    public ObjectIntHashMap() { super(); }
    public ObjectIntHashMap(int initialCapacity) { super(initialCapacity); }
    public ObjectIntHashMap(TObjectHashingStrategy<K> strategy) { super(strategy); }
    public ObjectIntHashMap(int initialCapacity, TObjectHashingStrategy<K> strategy) { super(initialCapacity, strategy); }

    @Override public int get(K key) { return super.get(key); }
    @Override public int put(K key, int value) { return super.put(key, value); }
    @Override public int remove(K key) { return super.remove(key); }
    @Override public boolean containsKey(Object key) { return super.containsKey((K) key); }
    @Override public void clear() { super.clear(); }
    @SuppressWarnings("unchecked") @Override public Set<K> keySet() { return super._set == null ? java.util.Collections.emptySet() : new java.util.HashSet<>((java.util.Collection<K>) java.util.Arrays.asList(super._set)); }
    @Override public int size() { return super.size(); }
    @Override public boolean isEmpty() { return super.isEmpty(); }

    @Override
    public int[] values() {
        int[] result = new int[size()];
        TObjectIntIterator<K> it = iterator();
        for (int i = 0; it.hasNext(); i++) {
            it.advance();
            result[i] = it.value();
        }
        return result;
    }

    @Override
    public boolean containsValue(int value) {
        return super.containsValue(value);
    }

    @Override
    public Iterable<ObjectIntMap.Entry<K>> entries() {
        List<ObjectIntMap.Entry<K>> list = new ArrayList<>(size());
        TObjectIntIterator<K> it = iterator();
        while (it.hasNext()) {
            it.advance();
            final K k = it.key();
            final int v = it.value();
            list.add(new ObjectIntMap.Entry<K>() {
                @Override public K getKey() { return k; }
                @Override public int getValue() { return v; }
            });
        }
        return list;
    }
}
