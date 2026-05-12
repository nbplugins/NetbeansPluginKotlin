package com.intellij.concurrency;

import com.intellij.util.containers.ConcurrentIntObjectMap;
import com.intellij.util.containers.ConcurrentLongObjectMap;
import com.intellij.util.containers.ContainerUtil;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

// Stub for concurrency:241 ConcurrentCollectionFactory used by code-style-impl:241. Switched
// from ContainerUtil.newConcurrentMap (193 method, removed in 232 ContainerUtil) to direct
// java.util.concurrent.ConcurrentHashMap. Identity / strategy variants degrade to plain hash
// (no identity semantics) — acceptable: code-style-impl uses these for caching where collisions
// only mean missed cache entries.
public final class ConcurrentCollectionFactory {
    public static <K, V> ConcurrentMap<K, V> createConcurrentIdentityMap() {
        return new ConcurrentHashMap<>();
    }
    public static <T, V> ConcurrentMap<T, V> createConcurrentMap() {
        return new ConcurrentHashMap<>();
    }
    public static <T, V> ConcurrentMap<T, V> createConcurrentMap(Object strategy) {
        return new ConcurrentHashMap<>();
    }
    public static <T, V> ConcurrentMap<T, V> createConcurrentMap(int initialCapacity, float loadFactor, int concurrencyLevel, Object strategy) {
        return new ConcurrentHashMap<>(initialCapacity, loadFactor, concurrencyLevel);
    }
    public static <T> Set<T> createConcurrentSet() {
        return Collections.newSetFromMap(new ConcurrentHashMap<>());
    }
    public static <T> Set<T> createConcurrentIdentitySet() {
        return Collections.newSetFromMap(new ConcurrentHashMap<>());
    }
    public static <T> Set<T> createConcurrentIdentitySet(int initialCapacity) {
        return Collections.newSetFromMap(new ConcurrentHashMap<>(initialCapacity));
    }
    public static <T> Set<T> createConcurrentSet(Object strategy) {
        return Collections.newSetFromMap(new ConcurrentHashMap<>());
    }
    public static <T> Set<T> createConcurrentSet(int initialCapacity, Object strategy) {
        return Collections.newSetFromMap(new ConcurrentHashMap<>(initialCapacity));
    }
    public static <T> Set<T> createConcurrentSet(int initialCapacity, float loadFactor, int concurrencyLevel, Object strategy) {
        return Collections.newSetFromMap(new ConcurrentHashMap<>(initialCapacity, loadFactor, concurrencyLevel));
    }
    public static <V> ConcurrentLongObjectMap<V> createConcurrentLongObjectMap() {
        return new SimpleConcurrentLongObjectMap<>();
    }
    public static <V> ConcurrentLongObjectMap<V> createConcurrentLongObjectMap(int initialCapacity) {
        return new SimpleConcurrentLongObjectMap<>();
    }

    private static final class SimpleConcurrentLongObjectMap<V> implements ConcurrentLongObjectMap<V> {
        private final ConcurrentHashMap<Long, V> map = new ConcurrentHashMap<>();
        @Override public V put(long key, V value) { return map.put(key, value); }
        @Override public V get(long key) { return map.get(key); }
        @Override public V remove(long key) { return map.remove(key); }
        @Override public V putIfAbsent(long key, V value) { return map.putIfAbsent(key, value); }
        @Override public Iterable<ConcurrentLongObjectMap.LongEntry<V>> entries() {
            java.util.List<ConcurrentLongObjectMap.LongEntry<V>> result = new java.util.ArrayList<>();
            for (java.util.Map.Entry<Long, V> e : map.entrySet()) {
                final long k = e.getKey(); final V v = e.getValue();
                result.add(new ConcurrentLongObjectMap.LongEntry<V>() {
                    @Override public long getKey() { return k; }
                    @Override public V getValue() { return v; }
                });
            }
            return result;
        }
    }
    public static <V> ConcurrentIntObjectMap<V> createConcurrentIntObjectMap() {
        return ContainerUtil.createConcurrentIntObjectMap();
    }
    public static <V> ConcurrentIntObjectMap<V> createConcurrentIntObjectMap(int initialCapacity, float loadFactor, int concurrencyLevel) {
        return ContainerUtil.createConcurrentIntObjectMap();
    }
    public static <V> ConcurrentIntObjectMap<V> createConcurrentIntObjectSoftValueMap() {
        return ContainerUtil.createConcurrentIntObjectMap();
    }
    public static <V> ConcurrentIntObjectMap<V> createConcurrentIntObjectWeakValueMap() {
        return ContainerUtil.createConcurrentIntObjectMap();
    }
}
