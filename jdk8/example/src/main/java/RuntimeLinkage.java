import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.jar.*;

/**
 * Resolve every base runtime class and its public/private member types on an actual Java 8 JVM.
 */
public final class RuntimeLinkage {

    public static void main(String[] args) throws Exception {
        if (!"1.8".equals(System.getProperty("java.specification.version")))
            throw new IllegalStateException("Java 8 required");
        int count = 0, optional = 0;
        List<String> failures = new ArrayList<String>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.endsWith(".jar"))
                continue;
            try (JarFile jar = new JarFile(entry)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.equals("module-info.class"))
                        continue;
                    name = name.substring(0, name.length() - 6).replace('/', '.');
                    // OkHttp conditionally loads these two providers only when their optional platform is installed.
                    if (name.equals("okhttp3.internal.platform.ConscryptPlatform$DisabledHostnameVerifier") || name.equals("okhttp3.internal.platform.android.AndroidCertificateChainCleaner")) {
                        optional++;
                        continue;
                    }
                    try {
                        Class<?> c = Class.forName(name, false, RuntimeLinkage.class.getClassLoader());
                        c.getDeclaredMethods();
                        c.getDeclaredConstructors();
                        c.getDeclaredFields();
                        count++;
                    } catch (LinkageError | ClassNotFoundException error) {
                        failures.add(name + ": " + error);
                    }
                }
            }
        }
        if (!failures.isEmpty())
            throw new AssertionError(failures.toString());
        System.out.println("Java 8 runtime linkage passed: " + count + " base classes and their member types resolved; " + optional + " optional Android/Conscrypt provider classes excluded.");
    }
}
