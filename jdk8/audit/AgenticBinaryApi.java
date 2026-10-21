import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** Compare the pinned Java 17 Agentic API with the Java 8 candidate, including bridge methods. */
public final class AgenticBinaryApi {
    static boolean visible(Class<?> type) {
        if (!Modifier.isPublic(type.getModifiers()) && !Modifier.isProtected(type.getModifiers())) return false;
        return type.getEnclosingClass() == null || visible(type.getEnclosingClass());
    }

    static List<String> typeParameters(Class<?> type) {
        return Arrays.stream(type.getTypeParameters()).map(p -> p.getName() + Arrays.toString(p.getBounds())).toList();
    }

    static Set<String> members(Class<?> type, boolean inherited) {
        Set<String> result = new TreeSet<>();
        Method[] methods = inherited ? type.getMethods() : type.getDeclaredMethods();
        for (Method method : methods) {
            if (Modifier.isPublic(method.getModifiers()) || Modifier.isProtected(method.getModifiers()))
                result.add(method.toGenericString());
        }
        if (!inherited) {
            for (Constructor<?> ctor : type.getDeclaredConstructors()) {
                if (Modifier.isPublic(ctor.getModifiers()) || Modifier.isProtected(ctor.getModifiers()))
                    result.add(ctor.toGenericString());
            }
        }
        Field[] fields = inherited ? type.getFields() : type.getDeclaredFields();
        for (Field field : fields) {
            if (Modifier.isPublic(field.getModifiers()) || Modifier.isProtected(field.getModifiers()))
                result.add(field.toGenericString());
        }
        return result;
    }

    static URLClassLoader loader(Path artifact, String classpath) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(artifact.toUri().toURL());
        for (String entry : classpath.split(java.io.File.pathSeparator))
            urls.add(Path.of(entry).toUri().toURL());
        return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
    }

    public static void main(String[] args) throws Exception {
        Path original = Path.of(args[0]);
        String classpath = Files.readString(Path.of(args[2])).trim();
        int types = 0, declared = 0, inherited = 0, records = 0;
        List<String> lost = new ArrayList<>(), added = new ArrayList<>();
        try (var before = loader(original, classpath); var after = loader(Path.of(args[1]), classpath);
             var files = Files.walk(original)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                String name = original.relativize(file).toString().replace(java.io.File.separatorChar, '.').replaceAll("\\.class$", "");
                Class<?> oldType = Class.forName(name, false, before);
                if (!visible(oldType)) continue;
                Class<?> newType = Class.forName(name, false, after);
                types++;
                if (oldType.isRecord()) {
                    records++;
                    if (newType.isRecord() || newType.getSuperclass() != Object.class)
                        lost.add(name + ": expected POJO record replacement");
                } else if (!Objects.equals(oldType.getGenericSuperclass() == null ? null : oldType.getGenericSuperclass().getTypeName(), newType.getGenericSuperclass() == null ? null : newType.getGenericSuperclass().getTypeName())) {
                    lost.add(name + ": superclass changed");
                }
                if (!typeParameters(oldType).equals(typeParameters(newType))) lost.add(name + ": type parameter bounds changed");
                if (oldType.getModifiers() != newType.getModifiers()) lost.add(name + ": type modifiers changed");
                if (!Arrays.toString(oldType.getGenericInterfaces()).equals(Arrays.toString(newType.getGenericInterfaces()))) lost.add(name + ": interfaces changed");
                for (boolean includeInherited : new boolean[]{false, true}) {
                    Set<String> oldMembers = members(oldType, includeInherited);
                    Set<String> newMembers = members(newType, includeInherited);
                    if (includeInherited) inherited += oldMembers.size(); else declared += oldMembers.size();
                    for (String member : oldMembers) if (!newMembers.contains(member)) lost.add(member);
                    if (!includeInherited) for (String member : newMembers) if (!oldMembers.contains(member)) added.add(member);
                }
            }
        }
        System.out.printf("API comparison: %d visible types, %d declared members, %d inherited/public entries, %d public record replacements%n", types, declared, inherited, records);
        for (String value : added) System.out.println("ADDED " + value);
        if (!lost.isEmpty()) throw new AssertionError("Missing/changed API: " + lost);
        System.out.println("Pinned " + (args.length > 3 ? args[3] : "main Agentic") + " API preserved (Record superclass/metadata intentionally replaced).");
    }
}
