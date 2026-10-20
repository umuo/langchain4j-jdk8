package dev.langchain4j.internal;

import dev.langchain4j.Internal;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Invoke an interface default method without recursively invoking the proxy handler. */
@Internal
public final class DefaultMethodInvoker {
    private DefaultMethodInvoker() {}

    public static Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
        if (!method.isDefault()) throw new IllegalArgumentException("Not a default method: " + method);
        Class<?> owner = method.getDeclaringClass();
        MethodHandles.Lookup lookup;
        try {
            // Use the supported lookup API on Java 9+, discovered without Java 9 linkage.
            Method privateLookupIn = MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
            lookup = (MethodHandles.Lookup) privateLookupIn.invoke(null, owner, MethodHandles.lookup());
        } catch (NoSuchMethodException java8) {
            Constructor<MethodHandles.Lookup> constructor = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
            constructor.setAccessible(true);
            lookup = constructor.newInstance(owner, MethodHandles.Lookup.PUBLIC | MethodHandles.Lookup.PRIVATE | MethodHandles.Lookup.PROTECTED | MethodHandles.Lookup.PACKAGE);
        } catch (InvocationTargetException lookupFailure) {
            throw lookupFailure.getCause();
        }
        MethodHandle handle = lookup.unreflectSpecial(method, owner).bindTo(proxy);
        return handle.invokeWithArguments(arguments == null ? new Object[0] : arguments);
    }
}
