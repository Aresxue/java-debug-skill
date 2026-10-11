package cn.ares.jvm.jdi.session;

import com.sun.jdi.ClassLoaderReference;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.ClassObjectReference;
import com.sun.jdi.Field;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.Type;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class loader lookups for one connection that keep the target's loaders collectable: every loader
 * question goes through here instead of the pinning JDI calls (see loaderOf).
 */
public class ClassLoaderResolver {

  /**
   * class_loader_id of classes defined by the bootstrap loader, which has no mirror object
   */
  public static final String BOOTSTRAP_LOADER = "bootstrap";

  /**
   * ReferenceTypeImpl's private loader cache (value and flag, same names in JDK 8 through 25),
   * filled by primeForInvoke. Null when JDI is not the stock implementation or, on JDK 9+, when
   * jdk.jdi does not open the package to us: the jar's Add-Opens manifest entry only applies to a
   * java -jar launch. java.lang.reflect.Field is spelled out because com.sun.jdi.Field owns the
   * short name here
   */
  private static final java.lang.reflect.Field JDI_LOADER_FIELD;
  private static final java.lang.reflect.Field JDI_LOADER_CACHED_FIELD;

  static {
    java.lang.reflect.Field loaderField;
    java.lang.reflect.Field cachedField;
    try {
      Class<?> implClass = Class.forName("com.sun.tools.jdi.ReferenceTypeImpl");
      loaderField = implClass.getDeclaredField("classLoader");
      cachedField = implClass.getDeclaredField("isClassLoaderCached");
      loaderField.setAccessible(true);
      cachedField.setAccessible(true);
    } catch (ReflectiveOperationException | RuntimeException e) {
      // RuntimeException: InaccessibleObjectException, the package is not opened to us
      loaderField = null;
      cachedField = null;
      System.err.println("warning: cannot open JDI's class loader cache (" + e + "), eval calls"
          + " with string arguments will keep the called class's loader reachable in the target until disconnect");
    }
    JDI_LOADER_FIELD = loaderField;
    JDI_LOADER_CACHED_FIELD = cachedField;
  }

  /**
   * Type -> class_loader_id, so a stack of N frames costs field reads only for types not seen
   * before. Holds ids, not loader mirrors; the keys are mirrors JDI caches anyway
   */
  private final Map<ReferenceType, Object> loaderIdMap = new ConcurrentHashMap<>();

  /**
   * java.lang.Class.classLoader, looked up on first use
   */
  private volatile Field classLoaderField;

  /**
   * The loader that defined a type, or null for bootstrap. Always go through here instead of
   * ReferenceType.classLoader(): the JDWP agent's ReferenceType.ClassLoader handler (JDK 8 through
   * current) never frees the JNI local reference it gets from GetClassLoader, and its command
   * thread never returns to Java, so every loader asked about that way stays strongly reachable
   * until the debugger disconnects. Every redeployed webapp that ever showed up in a stack, a
   * candidate list or a failure would be kept in the target's heap and metaspace. Reading the
   * Class.classLoader field goes through GetValues, which runs in a local frame.
   * ReferenceType.module() (JDK 9+) leaks the same way through GetModule, Module holding its
   * loader: do not call it either. JDI asks classLoader() on its own during some invokes, see
   * primeForInvoke, and a static invoke leaks the declaring class the same way on JDK 9+ targets
   * (invoker.c check_methodClass), which StaticInvoker routes around.
   */
  public ClassLoaderReference loaderOf(ReferenceType type) {
    ClassObjectReference classObject = type.classObject();
    Field field = classLoaderField;
    if (null == field) {
      field = classObject.referenceType().fieldByName("classLoader");
      if (null == field) {
        // A JVM whose Class has no such field: correct but pinning, nothing better exists
        return type.classLoader();
      }
      classLoaderField = field;
    }
    return (ClassLoaderReference) classObject.getValue(field);
  }

  /**
   * Identity of the loader that defined a type: its object id, or "bootstrap".
   */
  public Object loaderIdOf(ReferenceType type) {
    return loaderIdMap.computeIfAbsent(type, key -> {
      ClassLoaderReference loader = loaderOf(key);
      return null == loader ? BOOTSTRAP_LOADER : (Object) loader.uniqueID();
    });
  }

  /**
   * Add class_loader_id and the loader's class name, enough to pick one copy of a class.
   */
  public void putClassLoader(Map<String, Object> map, ReferenceType type) {
    ClassLoaderReference loader = loaderOf(type);
    if (null == loader) {
      map.put("class_loader_id", BOOTSTRAP_LOADER);
      return;
    }
    map.put("class_loader_id", loader.uniqueID());
    try {
      map.put("class_loader", loader.referenceType().name());
    } catch (ObjectCollectedException ignored) {
      // Not pinned (see loaderOf), so an unreachable loader can go at any moment; the id is enough
    }
  }

  /**
   * Answer in advance the loader questions JDI asks by itself during an invoke: an object argument
   * makes it resolve the parameter types through the declaring type's classLoader(), and a varargs
   * method also asks the varargs array type. Each would otherwise go out as the pinning command
   * loaderOf avoids. Written on the invoking thread right before the invoke, because JDI reads its
   * unsynchronized cache on that same thread.
   */
  public void primeForInvoke(Method method) {
    primeLoader(method.declaringType());
    if (!method.isVarArgs()) {
      return;
    }
    try {
      List<Type> typeList = method.argumentTypes();
      primeLoader((ReferenceType) typeList.get(typeList.size() - 1));
    } catch (ClassNotLoadedException ignored) {
      // The invoke resolves the same types and reports this itself
    }
  }

  private void primeLoader(ReferenceType type) {
    if (null == JDI_LOADER_FIELD || !JDI_LOADER_FIELD.getDeclaringClass().isInstance(type)) {
      return;
    }
    ClassLoaderReference loader = loaderOf(type);
    try {
      // Same order as ReferenceTypeImpl.classLoader() itself: the value, then the flag
      JDI_LOADER_FIELD.set(type, loader);
      JDI_LOADER_CACHED_FIELD.setBoolean(type, true);
    } catch (IllegalAccessException ignored) {
      // setAccessible succeeded, so this does not happen
    }
  }
}
