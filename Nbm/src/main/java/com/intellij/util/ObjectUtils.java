package com.intellij.util;

import com.intellij.openapi.util.NotNullFactory;
import com.intellij.util.containers.Convertor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import com.intellij.util.IntIntFunction;

/**
 * Extends 193-era ObjectUtils with binarySearch added in 241-era,
 * required by code-style-impl:241 (ProbablyIncreasingLowerboundAlgorithm).
 * Placed in Nbm/src/main/java so the main module JAR takes classloader priority over ext/util.jar.
 * Source: submodules/IntellijCommunity/platform/util/src/com/intellij/util/ObjectUtils.java
 * Changes vs submodule source:
 *   - sentinel(String,Class) uses Proxy.newProxyInstance directly (ReflectionUtil.proxy absent in 193)
 *   - assertAllElementsNotNull uses manual loop (ArrayUtil.indexOfIdentity absent in 193)
 *   - nullizeIfDefaultValue added (present in util:241 binary, absent in submodule/193)
 */
public final class ObjectUtils {
    private ObjectUtils() {}

    public static final Object NULL = sentinel("ObjectUtils.NULL");

    private static final class Sentinel {
        private final String myName;
        Sentinel(@NotNull String name) { myName = name; }
        @Override public String toString() { return myName; }
    }

    public static @NotNull Object sentinel(@NotNull String name) {
        return new Sentinel(name);
    }

    @SuppressWarnings("unchecked")
    public static @NotNull <T> T sentinel(@NotNull String name, @NotNull Class<T> ofInterface) {
        if (!ofInterface.isInterface()) throw new IllegalArgumentException("Expected interface but got: " + ofInterface);
        InvocationHandler handler = (proxy, method, args) -> {
            if ("toString".equals(method.getName()) && (args == null || args.length == 0)) return name;
            throw new AbstractMethodError();
        };
        return (T) Proxy.newProxyInstance(ofInterface.getClassLoader(), new Class<?>[]{ofInterface}, handler);
    }

    @Deprecated
    public static @NotNull <T> T assertNotNull(@Nullable T t) {
        if (t == null) throw new NullPointerException();
        return t;
    }

    public static <T> void assertAllElementsNotNull(@NotNull T[] array) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == null) throw new NullPointerException("Element [" + i + "] is null");
        }
    }

    @Contract(value = "!null, _ -> !null; _, !null -> !null; null, null -> null", pure = true)
    public static <T> T chooseNotNull(@Nullable T t1, @Nullable T t2) {
        return t1 == null ? t2 : t1;
    }

    @Contract(value = "!null, _ -> !null; _, !null -> !null; null, null -> null", pure = true)
    public static <T> T coalesce(@Nullable T t1, @Nullable T t2) {
        return t1 == null ? t2 : t1;
    }

    @Contract(value = "!null, _, _ -> !null; _, !null, _ -> !null; _, _, !null -> !null; null,null,null -> null", pure = true)
    public static <T> T coalesce(@Nullable T t1, @Nullable T t2, @Nullable T t3) {
        return t1 != null ? t1 : t2 != null ? t2 : t3;
    }

    public static @Nullable <T> T coalesce(@NotNull Iterable<? extends T> o) {
        for (T t : o) { if (t != null) return t; }
        return null;
    }

    @Deprecated
    public static <T> @NotNull T notNull(@Nullable T value) {
        if (value == null) throw new NullPointerException();
        return value;
    }

    @Contract(value = "null, _ -> param2; !null, _ -> param1", pure = true)
    public static @NotNull <T> T notNull(@Nullable T value, @NotNull T defaultValue) {
        return value == null ? defaultValue : value;
    }

    public static @NotNull <T> T notNull(@Nullable T value, @NotNull NotNullFactory<? extends T> defaultValue) {
        return value == null ? defaultValue.create() : value;
    }

    @Contract(value = "null, _ -> null", pure = true)
    public static @Nullable <T> T tryCast(@Nullable Object obj, @NotNull Class<T> clazz) {
        return clazz.isInstance(obj) ? clazz.cast(obj) : null;
    }

    public static @Nullable <T, S> S doIfCast(@Nullable Object obj, @NotNull Class<T> clazz,
                                               @NotNull Convertor<? super T, ? extends S> convertor) {
        //noinspection unchecked
        return clazz.isInstance(obj) ? convertor.convert((T) obj) : null;
    }

    @Contract("null, _ -> null")
    public static @Nullable <T, S> S doIfNotNull(@Nullable T obj, @NotNull Function<? super T, ? extends S> function) {
        return obj == null ? null : function.fun(obj);
    }

    @Deprecated
    public static <T> void consumeIfNotNull(@Nullable T obj, @NotNull Consumer<? super T> consumer) {
        if (obj != null) consumer.consume(obj);
    }

    @Deprecated
    public static <T> void consumeIfCast(@Nullable Object obj, @NotNull Class<T> clazz,
                                          @NotNull Consumer<? super T> consumer) {
        if (clazz.isInstance(obj)) {
            //noinspection unchecked
            consumer.consume((T) obj);
        }
    }

    @Contract("null, _ -> null")
    public static @Nullable <T> T nullizeByCondition(@Nullable T obj, @NotNull Predicate<? super T> condition) {
        return condition.test(obj) ? null : obj;
    }

    public static @Nullable <T> T nullizeIfDefaultValue(@Nullable T value, @Nullable T defaultValue) {
        return java.util.Objects.equals(value, defaultValue) ? null : value;
    }

    /**
     * Binary search on range [fromIndex, toIndex). Required by KotlinCompilerIntellijPlatform (MarkerProduction).
     */
    public static int binarySearch(int fromIndex, int toIndex, @NotNull IntIntFunction indexComparator) {
        int low = fromIndex;
        int high = toIndex - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int cmp = indexComparator.fun(mid);
            if (cmp < 0) low = mid + 1;
            else if (cmp > 0) high = mid - 1;
            else return mid;
        }
        return -(low + 1);
    }

    /**
     * Binary search on range [fromIndex, toIndex). Added in 241-era.
     * Required by code-style-impl:241 ProbablyIncreasingLowerboundAlgorithm.
     */
    public static int binarySearch(int fromIndex, int toIndex, @NotNull IntUnaryOperator indexComparator) {
        int low = fromIndex;
        int high = toIndex - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int cmp = indexComparator.applyAsInt(mid);
            if (cmp < 0) low = mid + 1;
            else if (cmp > 0) high = mid - 1;
            else return mid;
        }
        return -(low + 1);
    }

    public static @NotNull String objectInfo(@Nullable Object o) {
        return o != null ? o + " (" + o.getClass().getName() + ")" : "null";
    }
}
