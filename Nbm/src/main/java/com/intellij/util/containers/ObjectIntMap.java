package com.intellij.util.containers;

// Stub: extends kotlin-compiler:1.9.25's ObjectIntMap with containsKey(Object)
// absent from the shaded version but needed by code-style-impl:241's ObjectIntMap users.
public interface ObjectIntMap<K> {
    int get(K key);
    int put(K key, int value);
    boolean containsKey(Object key);
}
