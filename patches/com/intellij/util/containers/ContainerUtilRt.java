// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.containers;

import com.intellij.util.ArrayUtilRt;
import com.intellij.util.Function;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Stripped-down version of {@link ContainerUtil}.
 * Intended to use by external (out-of-IDE-process) runners and helpers so it should not contain any library dependencies.
 * @deprecated Use collection methods instead
 */
@Deprecated
public class ContainerUtilRt {

  @SafeVarargs
  @NotNull
  public static <T> ArrayList<T> newArrayList(T @NotNull ... elements) {
    ArrayList<T> list = new ArrayList<>(elements.length);
    Collections.addAll(list, elements);
    return list;
  }

  @NotNull
  public static <T> ArrayList<T> newArrayList(@NotNull Iterable<? extends T> elements) {
    return copy(new ArrayList<>(), elements);
  }

  /** Added: missing from kotlin-compiler-1.3.72 stub, called by formatter JARs */
  @NotNull
  public static <T> ArrayList<T> newArrayList() {
    return new ArrayList<>();
  }

  @SafeVarargs
  @NotNull
  public static <T> HashSet<T> newHashSet(T @NotNull ... elements) {
    return new HashSet<>(Arrays.asList(elements));
  }

  @NotNull
  public static <T> LinkedHashSet<T> newLinkedHashSet(T @NotNull ... elements) {
    return new LinkedHashSet<>(Arrays.asList(elements));
  }

  @NotNull
  public static <T, V> List<V> map2List(@NotNull Collection<? extends T> collection,
                                         @NotNull Function<? super T, ? extends V> mapping) {
    List<V> result = new ArrayList<>(collection.size());
    for (T t : collection) {
      result.add(mapping.fun(t));
    }
    return result;
  }

  public static <K, V> void putValue(K key, V value, @NotNull Map<K, List<V>> map) {
    List<V> list = map.get(key);
    if (list == null) {
      list = new ArrayList<>();
      map.put(key, list);
    }
    list.add(value);
  }

  protected static <T, C extends Collection<? super T>> C copy(@NotNull C collection,
                                                                 @NotNull Iterable<? extends T> elements) {
    for (T element : elements) {
      collection.add(element);
    }
    return collection;
  }

  private static final class EmptyList<T> extends AbstractList<T>
      implements RandomAccess, Serializable {
    private static final long serialVersionUID = 1L;
    private static final EmptyList<?> INSTANCE = new EmptyList<>();

    @Override public int size() { return 0; }
    @Override public boolean contains(Object obj) { return false; }
    @Override public T get(int index) { throw new IndexOutOfBoundsException("Index: " + index); }
    @Override public Object @NotNull [] toArray() { return ArrayUtilRt.EMPTY_OBJECT_ARRAY; }
    @Override public <E> E @NotNull [] toArray(E @NotNull [] a) { if (a.length != 0) a[0] = null; return a; }
    @Override public @NotNull Iterator<T> iterator() { return Collections.emptyIterator(); }
    @Override public @NotNull ListIterator<T> listIterator() { return Collections.emptyListIterator(); }
    @Override public boolean containsAll(@NotNull Collection<?> c) { return c.isEmpty(); }
    @Override public boolean isEmpty() { return true; }
    @Override public boolean equals(Object o) { return o instanceof List && ((List<?>)o).isEmpty(); }
    @Override public int hashCode() { return 1; }
    @Override public void clear() { throw new UnsupportedOperationException(); }
    @Override public boolean remove(Object o) { throw new UnsupportedOperationException(); }
    @Override public boolean removeAll(@NotNull Collection<?> c) { throw new UnsupportedOperationException(); }
    @Override public boolean removeIf(@NotNull Predicate<? super T> filter) { throw new UnsupportedOperationException(); }
    @Override public boolean retainAll(@NotNull Collection<?> c) { throw new UnsupportedOperationException(); }
    @Override public boolean addAll(int index, Collection<? extends T> c) { throw new UnsupportedOperationException(); }
    @Override public boolean addAll(@NotNull Collection<? extends T> c) { throw new UnsupportedOperationException(); }
    @Override public void sort(@SuppressWarnings("NullableProblems") Comparator<? super T> c) { throw new UnsupportedOperationException(); }
    @Override public void replaceAll(@NotNull UnaryOperator<T> operator) { throw new UnsupportedOperationException(); }
  }

  @NotNull
  public static <T> List<T> emptyList() {
    //noinspection unchecked
    return (List<T>)EmptyList.INSTANCE;
  }
}
