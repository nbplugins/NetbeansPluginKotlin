package com.intellij.util.concurrency;

import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public final class AtomicFieldUpdater<ContainingClass, FieldType> {
  private final VarHandle myHandle;

  private static final Unsafe ourUnsafe = findUnsafe();

  private static Unsafe findUnsafe() {
    try {
      Field f = Unsafe.class.getDeclaredField("theUnsafe");
      f.setAccessible(true);
      return (Unsafe)f.get(null);
    }
    catch (Throwable t) {
      return null;
    }
  }

  public static Unsafe getUnsafe() {
    return ourUnsafe;
  }

  public static <T, V> AtomicFieldUpdater<T, V> forFieldOfType(Class<T> ownerClass, Class<V> fieldType) {
    return new AtomicFieldUpdater<>(ownerClass, fieldType);
  }

  public static <T> AtomicFieldUpdater<T, Long> forLongFieldIn(Class<T> ownerClass) {
    return new AtomicFieldUpdater<>(ownerClass, long.class);
  }

  public static <T> AtomicFieldUpdater<T, Integer> forIntFieldIn(Class<T> ownerClass) {
    return new AtomicFieldUpdater<>(ownerClass, int.class);
  }

  private AtomicFieldUpdater(Class<ContainingClass> ownerClass, Class<FieldType> fieldType) {
    this(getTheOnlyVolatileFieldOfClass(ownerClass, fieldType));
  }

  private AtomicFieldUpdater(Field field) {
    field.setAccessible(true);
    try {
      myHandle = MethodHandles
        .privateLookupIn(field.getDeclaringClass(), MethodHandles.lookup())
        .findVarHandle(field.getDeclaringClass(), field.getName(), field.getType());
    }
    catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  private static <T, V> Field getTheOnlyVolatileFieldOfClass(Class<T> ownerClass, Class<V> fieldType) {
    Field[] fields = ownerClass.getDeclaredFields();
    Field found = null;
    for (Field field : fields) {
      int mod = field.getModifiers();
      if (Modifier.isStatic(mod) || !Modifier.isVolatile(mod)) continue;
      if (fieldType.isAssignableFrom(field.getType())) {
        if (found == null) found = field;
        else throw new IllegalArgumentException("Two fields of " + fieldType + " found in " + ownerClass);
      }
    }
    if (found == null) throw new IllegalArgumentException("No volatile instance field of " + fieldType + " in " + ownerClass);
    return found;
  }

  public boolean compareAndSet(ContainingClass owner, FieldType expected, FieldType newValue) {
    return (boolean)myHandle.compareAndSet(owner, expected, newValue);
  }

  public boolean compareAndSetLong(ContainingClass owner, long expected, long newValue) {
    return (boolean)myHandle.compareAndSet(owner, expected, newValue);
  }

  public boolean compareAndSetInt(ContainingClass owner, int expected, int newValue) {
    return (boolean)myHandle.compareAndSet(owner, expected, newValue);
  }

  public void set(ContainingClass owner, FieldType newValue) {
    myHandle.setVolatile(owner, newValue);
  }

  public FieldType get(ContainingClass owner) {
    //noinspection unchecked
    return (FieldType)myHandle.getVolatile(owner);
  }
}
