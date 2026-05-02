import org.objectweb.asm.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches kotlin-compiler.jar: adds missing ContainerUtil/ContainerUtilRt methods called by bundled formatter JARs.
 *
 * Problem: idea-formatter-1.0.jar and openapi-formatter-1.0.jar were compiled against intellij-core's
 * ContainerUtil/ContainerUtilRt which had many overloads. kotlin-compiler's ContainerUtil/ContainerUtilRt
 * dropped these overloads. After stripping intellij-core's ContainerUtil (conflict resolution),
 * the formatter calls fail with NoSuchMethodError.
 */
public class PatchContainerUtilAddMissing {
    static final String CONTAINER_UTIL    = "com/intellij/util/containers/ContainerUtil";
    static final String CONTAINER_UTIL_RT = "com/intellij/util/containers/ContainerUtilRt";
    static final String HASH_SET          = "java/util/HashSet";
    static final String HASH_MAP          = "java/util/HashMap";
    static final String ARRAY_LIST        = "java/util/ArrayList";
    static final String ARRAYS            = "java/util/Arrays";
    static final String COLLECTIONS       = "java/util/Collections";
    static final String CONCURRENT_HM     = "java/util/concurrent/ConcurrentHashMap";
    static final String SMART_LIST        = "com/intellij/util/SmartList";
    static final String T_HASH_MAP        = "gnu/trove/THashMap";
    static final String CONDITION         = "com/intellij/openapi/util/Condition";
    static final String FUNCTION          = "com/intellij/util/Function";
    static final String ITERATOR          = "java/util/Iterator";
    static final String ITERABLE          = "java/lang/Iterable";
    static final String COLLECTION        = "java/util/Collection";
    static final String LIST              = "java/util/List";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: PatchContainerUtilAddMissing <input.jar> <output.jar>");
            System.exit(1);
        }

        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(args[0]));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(args[1]))) {

            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] bytes = zin.readAllBytes();

                if (name.equals(CONTAINER_UTIL + ".class")) {
                    System.out.println("Patching ContainerUtil (adding missing methods)...");
                    bytes = patchContainerUtil(bytes);
                } else if (name.equals(CONTAINER_UTIL_RT + ".class")) {
                    System.out.println("Patching ContainerUtilRt (adding missing methods)...");
                    bytes = patchContainerUtilRt(bytes);
                }

                ZipEntry outEntry = new ZipEntry(name);
                zout.putNextEntry(outEntry);
                zout.write(bytes);
                zout.closeEntry();
            }
        }
        System.out.println("Done.");
    }

    static byte[] patchContainerUtil(byte[] original) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);

        // Collect existing methods to avoid duplicates
        Set<String> existing = new HashSet<>();
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                existing.add(name + descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public void visitEnd() {
                // public static <T> HashSet<T> newHashSet()
                if (!existing.contains("newHashSet()Ljava/util/HashSet;")) {
                    addNewHashSet(cv);
                }
                // public static <T> HashSet<T> newHashSet(T... items)
                if (!existing.contains("newHashSet([Ljava/lang/Object;)Ljava/util/HashSet;")) {
                    addNewHashSetArray(cv);
                }
                // public static <T> HashSet<T> newHashSet(Iterable<? extends T> items)
                if (!existing.contains("newHashSet(Ljava/lang/Iterable;)Ljava/util/HashSet;")) {
                    addNewHashSetIterable(cv);
                }
                // public static <K, V> HashMap<K, V> newHashMap()
                if (!existing.contains("newHashMap()Ljava/util/HashMap;")) {
                    addNewHashMap(cv);
                }
                // public static <T> ArrayList<T> newArrayList()
                if (!existing.contains("newArrayList()Ljava/util/ArrayList;")) {
                    addNewArrayList(cv, CONTAINER_UTIL);
                }
                // public static <T> Set<T> newConcurrentSet()
                if (!existing.contains("newConcurrentSet()Ljava/util/Set;")) {
                    addNewConcurrentSet(cv);
                }
                // public static <T> List<T> newSmartList(T element)
                if (!existing.contains("newSmartList(Ljava/lang/Object;)Ljava/util/List;")) {
                    addNewSmartList(cv);
                }
                // public static <K, V> THashMap<K, V> newTroveMap()
                if (!existing.contains("newTroveMap()Lgnu/trove/THashMap;")) {
                    addNewTroveMap(cv);
                }
                // public static <T> boolean isEmpty(Collection<T> collection)
                if (!existing.contains("isEmpty(Ljava/util/Collection;)Z")) {
                    addIsEmpty(cv);
                }
                // public static <T, V> List<V> map(Collection<? extends T> collection, Function<T,V> mapping)
                if (!existing.contains("map(Ljava/util/Collection;Lcom/intellij/util/Function;)Ljava/util/List;")) {
                    addMap(cv);
                }
                // public static <T> List<T> filter(Collection<? extends T> collection, Condition<? super T> condition)
                if (!existing.contains("filter(Ljava/util/Collection;Lcom/intellij/openapi/util/Condition;)Ljava/util/List;")) {
                    addFilter(cv);
                }
                // public static <T> T[] toArray(Collection<T> c, T[] a)
                if (!existing.contains("toArray(Ljava/util/Collection;[Ljava/lang/Object;)[Ljava/lang/Object;")) {
                    addToArrayCollection(cv);
                }
                // public static <T> T[] toArray(List<T> l, T[] a)
                if (!existing.contains("toArray(Ljava/util/List;[Ljava/lang/Object;)[Ljava/lang/Object;")) {
                    addToArrayList(cv);
                }
                super.visitEnd();
            }
        }, 0);

        return cw.toByteArray();
    }

    static byte[] patchContainerUtilRt(byte[] original) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);

        Set<String> existing = new HashSet<>();
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                existing.add(name + descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public void visitEnd() {
                if (!existing.contains("newHashSet([Ljava/lang/Object;)Ljava/util/HashSet;")) {
                    addNewHashSetArray(cv);
                    System.out.println("  Added ContainerUtilRt.newHashSet(Object[])");
                }
                if (!existing.contains("newArrayList()Ljava/util/ArrayList;")) {
                    addNewArrayList(cv, CONTAINER_UTIL_RT);
                    System.out.println("  Added ContainerUtilRt.newArrayList()");
                }
                super.visitEnd();
            }
        }, 0);

        return cw.toByteArray();
    }

    // --- ContainerUtil helpers ---

    static void addNewHashSet(ClassVisitor cv) {
        // public static HashSet newHashSet() { return new HashSet<>(); }
        MethodVisitor mv = cv.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "newHashSet", "()Ljava/util/HashSet;",
            "<T:Ljava/lang/Object;>()Ljava/util/HashSet<TT;>;", null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, HASH_SET);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, HASH_SET, "<init>", "()V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        System.out.println("  Added ContainerUtil.newHashSet()");
    }

    static void addNewHashSetArray(ClassVisitor cv) {
        // public static HashSet newHashSet(Object[] items) { return new HashSet<>(Arrays.asList(items)); }
        MethodVisitor mv = cv.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_VARARGS,
            "newHashSet", "([Ljava/lang/Object;)Ljava/util/HashSet;",
            "<T:Ljava/lang/Object;>([TT;)Ljava/util/HashSet<TT;>;", null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, HASH_SET);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, ARRAYS, "asList",
            "([Ljava/lang/Object;)Ljava/util/List;", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, HASH_SET, "<init>",
            "(Ljava/util/Collection;)V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(3, 1);
        mv.visitEnd();
        System.out.println("  Added newHashSet(Object[])");
    }

    static void addNewHashSetIterable(ClassVisitor cv) {
        // public static HashSet newHashSet(Iterable items) {
        //   HashSet s = new HashSet<>(); for (Object o : items) s.add(o); return s; }
        MethodVisitor mv = cv.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "newHashSet", "(Ljava/lang/Iterable;)Ljava/util/HashSet;",
            "<T:Ljava/lang/Object;>(Ljava/lang/Iterable<+TT;>;)Ljava/util/HashSet<TT;>;", null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, HASH_SET);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, HASH_SET, "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, ITERABLE, "iterator",
            "()Ljava/util/Iterator;", true);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        Label loop = new Label(), end = new Label();
        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, ITERATOR, "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, end);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, ITERATOR, "next",
            "()Ljava/lang/Object;", true);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HASH_SET, "add",
            "(Ljava/lang/Object;)Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, loop);
        mv.visitLabel(end);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
        System.out.println("  Added newHashSet(Iterable)");
    }

    static void addNewHashMap(ClassVisitor cv) {
        // public static HashMap newHashMap() { return new HashMap<>(); }
        MethodVisitor mv = cv.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "newHashMap", "()Ljava/util/HashMap;",
            "<K:Ljava/lang/Object;V:Ljava/lang/Object;>()Ljava/util/HashMap<TK;TV;>;", null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, HASH_MAP);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, HASH_MAP, "<init>", "()V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        System.out.println("  Added newHashMap()");
    }

    static void addNewArrayList(ClassVisitor cv, String owner) {
        // public static ArrayList newArrayList() { return new ArrayList<>(); }
        MethodVisitor mv = cv.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "newArrayList", "()Ljava/util/ArrayList;",
            "<T:Ljava/lang/Object;>()Ljava/util/ArrayList<TT;>;", null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, ARRAY_LIST);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, ARRAY_LIST, "<init>", "()V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        System.out.println("  Added " + owner.substring(owner.lastIndexOf('/') + 1) + ".newArrayList()");
    }

    static void addNewConcurrentSet(ClassVisitor cv) {
        // public static Set newConcurrentSet() { return ConcurrentHashMap.newKeySet(); }
        MethodVisitor mv = cv.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "newConcurrentSet", "()Ljava/util/Set;",
            "<T:Ljava/lang/Object;>()Ljava/util/Set<TT;>;", null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONCURRENT_HM, "newKeySet",
            "()Ljava/util/concurrent/ConcurrentHashMap$KeySetView;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        System.out.println("  Added newConcurrentSet()");
    }

    static void addNewSmartList(ClassVisitor cv) {
        // public static List newSmartList(Object element) { return new SmartList<>(element); }
        MethodVisitor mv = cv.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "newSmartList", "(Ljava/lang/Object;)Ljava/util/List;",
            "<T:Ljava/lang/Object;>(TT;)Ljava/util/List<TT;>;", null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, SMART_LIST);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SMART_LIST, "<init>",
            "(Ljava/lang/Object;)V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(3, 1);
        mv.visitEnd();
        System.out.println("  Added newSmartList(Object)");
    }

    static void addNewTroveMap(ClassVisitor cv) {
        // public static THashMap newTroveMap() { return new THashMap<>(); }
        MethodVisitor mv = cv.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "newTroveMap", "()Lgnu/trove/THashMap;",
            "<K:Ljava/lang/Object;V:Ljava/lang/Object;>()Lgnu/trove/THashMap<TK;TV;>;", null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, T_HASH_MAP);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, T_HASH_MAP, "<init>", "()V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        System.out.println("  Added newTroveMap()");
    }

    static void addIsEmpty(ClassVisitor cv) {
        // public static boolean isEmpty(Collection c) { return c == null || c.isEmpty(); }
        MethodVisitor mv = cv.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "isEmpty", "(Ljava/util/Collection;)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        Label notNull = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, notNull);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(notNull);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, COLLECTION, "isEmpty", "()Z", true);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        System.out.println("  Added isEmpty(Collection)");
    }

    static void addMap(ClassVisitor cv) {
        // public static List map(Collection c, Function f) {
        //   ArrayList r = new ArrayList<>(); for (Object o : c) r.add(f.fun(o)); return r; }
        MethodVisitor mv = cv.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "map",
            "(Ljava/util/Collection;Lcom/intellij/util/Function;)Ljava/util/List;",
            "<T:Ljava/lang/Object;V:Ljava/lang/Object;>(Ljava/util/Collection<+TT;>;Lcom/intellij/util/Function<TT;TV;>;)Ljava/util/List<TV;>;",
            null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, ARRAY_LIST);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, ARRAY_LIST, "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, COLLECTION, "iterator",
            "()Ljava/util/Iterator;", true);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        Label loop = new Label(), end = new Label();
        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, ITERATOR, "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, end);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, ITERATOR, "next",
            "()Ljava/lang/Object;", true);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FUNCTION, "fun",
            "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ARRAY_LIST, "add",
            "(Ljava/lang/Object;)Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, loop);
        mv.visitLabel(end);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(4, 4);
        mv.visitEnd();
        System.out.println("  Added map(Collection, Function)");
    }

    static void addFilter(ClassVisitor cv) {
        // public static List filter(Collection c, Condition cond) {
        //   ArrayList r = new ArrayList<>(); for (Object o : c) if (cond.value(o)) r.add(o); return r; }
        MethodVisitor mv = cv.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "filter",
            "(Ljava/util/Collection;Lcom/intellij/openapi/util/Condition;)Ljava/util/List;",
            "<T:Ljava/lang/Object;>(Ljava/util/Collection<+TT;>;Lcom/intellij/openapi/util/Condition<-TT;>;)Ljava/util/List<TT;>;",
            null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, ARRAY_LIST);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, ARRAY_LIST, "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, COLLECTION, "iterator",
            "()Ljava/util/Iterator;", true);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        Label loop = new Label(), end = new Label(), skip = new Label();
        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, ITERATOR, "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, end);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, ITERATOR, "next",
            "()Ljava/lang/Object;", true);
        mv.visitVarInsn(Opcodes.ASTORE, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CONDITION, "value",
            "(Ljava/lang/Object;)Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, skip);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ARRAY_LIST, "add",
            "(Ljava/lang/Object;)Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(skip);
        mv.visitJumpInsn(Opcodes.GOTO, loop);
        mv.visitLabel(end);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(3, 5);
        mv.visitEnd();
        System.out.println("  Added filter(Collection, Condition)");
    }

    static void addToArrayCollection(ClassVisitor cv) {
        // public static Object[] toArray(Collection c, Object[] a) { return c.toArray(a); }
        MethodVisitor mv = cv.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "toArray",
            "(Ljava/util/Collection;[Ljava/lang/Object;)[Ljava/lang/Object;",
            "<T:Ljava/lang/Object;>(Ljava/util/Collection<TT;>;[TT;)[TT;", null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, COLLECTION, "toArray",
            "([Ljava/lang/Object;)[Ljava/lang/Object;", true);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
        System.out.println("  Added toArray(Collection, Object[])");
    }

    static void addToArrayList(ClassVisitor cv) {
        // public static Object[] toArray(List l, Object[] a) { return l.toArray(a); }
        MethodVisitor mv = cv.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "toArray",
            "(Ljava/util/List;[Ljava/lang/Object;)[Ljava/lang/Object;",
            "<T:Ljava/lang/Object;>(Ljava/util/List<TT;>;[TT;)[TT;", null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST, "toArray",
            "([Ljava/lang/Object;)[Ljava/lang/Object;", true);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
        System.out.println("  Added toArray(List, Object[])");
    }
}
