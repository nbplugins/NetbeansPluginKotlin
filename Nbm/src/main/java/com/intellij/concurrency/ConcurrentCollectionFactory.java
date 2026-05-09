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
        return ContainerUtil.createConcurrentLongObjectMap();
    }
    public static <V> ConcurrentLongObjectMap<V> createConcurrentLongObjectMap(int initialCapacity) {
        return ContainerUtil.createConcurrentLongObjectMap();
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
