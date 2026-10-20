package dev.langchain4j.internal;

import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Java 8 equivalents of newer JDK operations. Not a public extension API.
 */
@dev.langchain4j.Internal
public final class Jdk8 {

    private Jdk8() {
    }

    @SafeVarargs
    public static <T> List<T> list(T... values) {
        List<T> copy = new ArrayList<T>(values.length);
        for (T value : values) copy.add(Objects.requireNonNull(value));
        return Collections.unmodifiableList(copy);
    }

    @SafeVarargs
    public static <T> Set<T> set(T... values) {
        Set<T> copy = new LinkedHashSet<T>();
        for (T value : values) if (!copy.add(Objects.requireNonNull(value)))
            throw new IllegalArgumentException("duplicate element: " + value);
        return Collections.unmodifiableSet(copy);
    }

    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> map(Object... pairs) {
        if (pairs.length % 2 != 0)
            throw new IllegalArgumentException("key/value pairs required");
        Map<K, V> copy = new LinkedHashMap<K, V>();
        for (int i = 0; i < pairs.length; i += 2) {
            K key = (K) Objects.requireNonNull(pairs[i]);
            V value = (V) Objects.requireNonNull(pairs[i + 1]);
            if (copy.containsKey(key))
                throw new IllegalArgumentException("duplicate key: " + key);
            copy.put(key, value);
        }
        return Collections.unmodifiableMap(copy);
    }

    public static <T> List<T> copyList(Collection<? extends T> values) {
        List<T> copy = new ArrayList<T>();
        for (T value : values) copy.add(Objects.requireNonNull(value));
        return Collections.unmodifiableList(copy);
    }

    public static <T> Set<T> copySet(Collection<? extends T> values) {
        Set<T> copy = new LinkedHashSet<T>();
        for (T value : values) copy.add(Objects.requireNonNull(value));
        return Collections.unmodifiableSet(copy);
    }

    public static <K, V> Map<K, V> copyMap(Map<? extends K, ? extends V> values) {
        Map<K, V> copy = new LinkedHashMap<K, V>();
        values.forEach((k, v) -> copy.put(Objects.requireNonNull(k), Objects.requireNonNull(v)));
        return Collections.unmodifiableMap(copy);
    }

    public static <T> CompletableFuture<T> failedFuture(Throwable error) {
        CompletableFuture<T> f = new CompletableFuture<T>();
        f.completeExceptionally(error);
        return f;
    }

    public static boolean isBlank(String s) {
        return s.codePoints().allMatch(Character::isWhitespace);
    }

    public static String strip(String s) {
        int start = 0, end = s.length();
        while (start < end && Character.isWhitespace(s.codePointAt(start))) start += Character.charCount(s.codePointAt(start));
        while (end > start && Character.isWhitespace(s.codePointBefore(end))) end -= Character.charCount(s.codePointBefore(end));
        return s.substring(start, end);
    }

    public static String repeat(String s, int count) {
        if (count < 0)
            throw new IllegalArgumentException("negative count");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < count; i++) b.append(s);
        return b.toString();
    }

    public static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    public static boolean isRecord(Class<?> type) {
        return booleanReflection(type, "isRecord");
    }

    public static boolean isSealed(Class<?> type) {
        return booleanReflection(type, "isSealed");
    }

    private static boolean booleanReflection(Class<?> type, String method) {
        try {
            return (Boolean) Class.class.getMethod(method).invoke(type);
        } catch (NoSuchMethodException e) {
            return false;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Class<?>[] getPermittedSubclasses(Class<?> type) {
        try {
            return (Class<?>[]) Class.class.getMethod("getPermittedSubclasses").invoke(type);
        } catch (NoSuchMethodException e) {
            return new Class<?>[0];
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray(), out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = digits[(bytes[i] & 255) >>> 4];
            out[i * 2 + 1] = digits[bytes[i] & 15];
        }
        return new String(out);
    }

    public static <T> java.util.stream.Stream<T> optionalStream(Optional<T> optional) {
        return optional.map(java.util.stream.Stream::of).orElseGet(java.util.stream.Stream::empty);
    }

    public static <T> Optional<T> firstService(ServiceLoader<T> loader) {
        Iterator<T> it = loader.iterator();
        return it.hasNext() ? Optional.of(it.next()) : Optional.empty();
    }

    private static final java.util.concurrent.ScheduledExecutorService DELAYER = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "langchain4j-jdk8-delay");
        t.setDaemon(true);
        return t;
    });

    public static java.util.concurrent.Executor delayedExecutor(long delay, java.util.concurrent.TimeUnit unit, java.util.concurrent.Executor executor) {
        return task -> DELAYER.schedule(() -> executor.execute(task), delay, unit);
    }

    public static <T> CompletableFuture<T> exceptionallyCompose(CompletableFuture<T> source, java.util.function.Function<Throwable, ? extends java.util.concurrent.CompletionStage<T>> fallback) {
        return source.handle((value, error) -> error == null ? CompletableFuture.completedFuture(value) : fallback.apply(error)).thenCompose(stage -> stage);
    }

    public static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Reflective view of Java 16 record metadata; absent on Java 8.
     */
    public static RecordComponent[] getRecordComponents(Class<?> type) {
        try {
            Object[] values = (Object[]) Class.class.getMethod("getRecordComponents").invoke(type);
            if (values == null)
                return new RecordComponent[0];
            RecordComponent[] result = new RecordComponent[values.length];
            for (int i = 0; i < values.length; i++) result[i] = new RecordComponent(values[i]);
            return result;
        } catch (NoSuchMethodException unavailable) {
            return new RecordComponent[0];
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static final class RecordComponent {

        private final Object value;

        private RecordComponent(Object value) {
            this.value = value;
        }

        private Object invoke(String name) {
            try {
                return value.getClass().getMethod(name).invoke(value);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }

        public String getName() {
            return (String) invoke("getName");
        }

        public Class<?> getType() {
            return (Class<?>) invoke("getType");
        }

        public java.lang.reflect.Type getGenericType() {
            return (java.lang.reflect.Type) invoke("getGenericType");
        }
    }
}
