package cn.ares.jvm.jdi.eval;

import cn.ares.jvm.jdi.session.ClassLoaderResolver;
import cn.ares.jvm.jdi.session.JvmSession;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ClassLoaderReference;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.InterfaceType;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static invokes for eval. The ordinary route is the JDWP one, and on a JDK 9+ target the agent
 * leaks the declaring class there: invoker.c keeps the jclass GetMethodDeclaringClass hands it in a
 * local reference it never frees, and the command thread never returns to Java, so the class, and
 * with it its loader, stays reachable until the debugger disconnects. A class that has a loader to
 * lose therefore goes through a method handle from the JDK's trusted lookup instead: every command
 * that sends is then an instance invoke on a bootstrap class, which leaks nothing. Bootstrap
 * classes, classes of the JDK's own app and platform loaders, and JDK 8 targets keep the ordinary
 * route, so what eval can reach does not depend on the target version.
 *
 * <p>The handle comes from MethodHandles.Lookup.IMPL_LOOKUP, a static field read as a field and
 * therefore with no access check, and its findStatic is the one lookup the JDK grants everything:
 * it skips the module check a setAccessible would fail and the security manager check an ordinary
 * lookup would fail, both of which a debugger is subject to no more than it is to JDWP's own
 * invoke. A caller-sensitive method, the one kind a trusted handle would bind to the wrong caller,
 * is only ever declared by the JDK's own loaders, which never take this route.
 *
 * <p>The result is captured before it can be collected: the handle's return type is widened to
 * Object and a store into an Object[1] held until the answer is read is filtered onto it, so the
 * target writes the return value into memory the client already owns during the invoke itself. The
 * alternative, reading the invoke's reply and then asking for the value, leaves the value
 * unreferenced in the window between those two commands, where a GC may take it.
 */
class StaticInvoker {

    /**
     * The primitive parameter types eval can box, each with the java.lang wrapper the target's own
     * valueOf makes for its arguments, and with the JVM descriptor character an array type name is
     * built from
     */
    private enum Primitive {
        BOOLEAN("boolean", "java.lang.Boolean", 'Z'),
        BYTE("byte", "java.lang.Byte", 'B'),
        SHORT("short", "java.lang.Short", 'S'),
        CHAR("char", "java.lang.Character", 'C'),
        INT("int", "java.lang.Integer", 'I'),
        LONG("long", "java.lang.Long", 'J'),
        FLOAT("float", "java.lang.Float", 'F'),
        DOUBLE("double", "java.lang.Double", 'D');

        private final String typeName;
        private final String wrapperClassName;
        private final char descriptor;

        Primitive(String typeName, String wrapperClassName, char descriptor) {
            this.typeName = typeName;
            this.wrapperClassName = wrapperClassName;
            this.descriptor = descriptor;
        }
    }

    private static final Map<String, Primitive> PRIMITIVE_MAP = new HashMap<>();

    static {
        for (Primitive primitive : Primitive.values()) {
            PRIMITIVE_MAP.put(primitive.typeName, primitive);
        }
    }

    private static final String CLASS_CLASS_NAME = "java.lang.Class";
    private static final String STRING_CLASS_NAME = "java.lang.String";
    private static final String OBJECT_CLASS_NAME = "java.lang.Object";
    private static final String ARRAY_CLASS_NAME = "java.lang.reflect.Array";
    private static final String NEW_INSTANCE = "newInstance";
    private static final String NEW_INSTANCE_SIGNATURE = "(Ljava/lang/Class;I)Ljava/lang/Object;";
    private static final String SET = "set";
    private static final String SET_SIGNATURE = "(Ljava/lang/Object;ILjava/lang/Object;)V";
    private static final String ARRAY_SUFFIX = "[]";
    private static final String VALUE_FIELD = "value";
    private static final String TYPE_FIELD = "TYPE";
    private static final String FOR_NAME = "forName";
    private static final String VALUE_OF = "valueOf";
    private static final String RETURN_TYPE_VOID = "void";
    private static final String OBJECT_DESCRIPTOR = "Ljava/lang/Object;";
    private static final String LOOKUP_CLASS_NAME = "java.lang.invoke.MethodHandles$Lookup";
    private static final String IMPL_LOOKUP_FIELD = "IMPL_LOOKUP";
    private static final String FIND_STATIC = "findStatic";
  private static final String METHOD_HANDLES_CLASS_NAME = "java.lang.invoke.MethodHandles";
    private static final String METHOD_TYPE_CLASS_NAME = "java.lang.invoke.MethodType";
    private static final String FROM_METHOD_DESCRIPTOR = "fromMethodDescriptorString";
    private static final String FROM_METHOD_DESCRIPTOR_SIGNATURE = "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;";
    private static final String AS_TYPE = "asType";
    private static final String FILTER_RETURN_VALUE = "filterReturnValue";
    private static final String FILTER_RETURN_VALUE_SIGNATURE = "(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/MethodHandle;";
    private static final String ARRAY_ELEMENT_SETTER = "arrayElementSetter";
    private static final String ARRAY_ELEMENT_SETTER_SIGNATURE = "(Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;";
    private static final String INSERT_ARGUMENTS = "insertArguments";
    private static final String INSERT_ARGUMENTS_SIGNATURE = "(Ljava/lang/invoke/MethodHandle;I[Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;";
    private static final String AS_FIXED_ARITY = "asFixedArity";
    private static final String INVOKE_WITH_ARGUMENTS = "invokeWithArguments";
    private static final String INVOKE_WITH_ARGUMENTS_SIGNATURE = "([Ljava/lang/Object;)Ljava/lang/Object;";

    /**
     * Loaders the JDK keeps reachable from static fields for the life of the VM, so a class they
     * define is never unloaded and the ordinary route pins nothing that was not pinned already
     */
    private static final Set<String> BUILTIN_LOADER_SET = new HashSet<>(Arrays.asList(
            "jdk.internal.loader.ClassLoaders$AppClassLoader",
            "jdk.internal.loader.ClassLoaders$PlatformClassLoader"));

    /**
     * How often the helper objects are built again after the target collected one before it could
     * be held. Each loss needs a collection inside the round trip between creating and holding it
     */
    private static final int MAX_ATTEMPTS = 3;

    private final JvmSession session;

    private final ClassLoaderResolver loaderResolver;

    StaticInvoker(JvmSession session, ClassLoaderResolver loaderResolver) {
        this.session = session;
        this.loaderResolver = loaderResolver;
    }

    /**
     * Invoke a static method on a suspended thread, leaving the declaring class's loader
     * collectable on the targets that would otherwise leak it.
     */
    Value invoke(ReferenceType type, Method method, List<Value> args, ThreadReference thread,
                 EvaluationScope scope) throws Exception {
        ClassLoaderReference loader = loaderToProtect(method);
        if (null == loader) {
            // The result is what eval goes on to format, so it has to outlive this call
            return scope.hold(invokeDirectly(type, method, args, thread));
        }
        // The caller's string arguments wait through every helper command of the route, where a
        // direct invoke sends them at once. One already collected fails here the way the direct
        // invoke would fail on it, and nothing has been sent that could pin the class
        List<ObjectReference> argHeldList = new ArrayList<>();
        try {
            for (Value arg : args) {
                if (arg instanceof ObjectReference) {
                    hold((ObjectReference) arg, argHeldList);
                }
            }
            HandleTarget target = prepare(method, args, thread);
            if (null != target) {
                return scope.hold(invokeHandle(target, method, thread, scope));
            }
            return scope.hold(invokeDirectly(type, method, args, thread));
        } finally {
            release(argHeldList);
        }
    }

    /**
     * The declaring type's loader when the ordinary route would leave it reachable, null when there
     * is nothing to protect: a bootstrap class or one of the JDK's own loaders cannot unload, and a
     * JDK 8 target leaks nothing. Those loaders can define hidden classes that do unload, but eval
     * names a class to call by its binary name, which a hidden class does not have
     */
    private ClassLoaderReference loaderToProtect(Method method) {
        if (!session.isJdk9OrLater()) {
            return null;
        }
        ClassLoaderReference loader = loaderResolver.loaderOf(method.declaringType());
        if (null == loader || isBuiltin(loader)) {
            return null;
        }
        return loader;
    }

    /**
     * Whether the loader is one of the JDK's own. The name alone is not proof, the defining loader
     * of the loader's class is: only the bootstrap loader defines the real ones. Both answers come
     * from caches after the first ask
     */
    private boolean isBuiltin(ClassLoaderReference loader) {
        ReferenceType loaderType = loader.referenceType();
        return BUILTIN_LOADER_SET.contains(loaderType.name())
                && ClassLoaderResolver.BOOTSTRAP_LOADER.equals(loaderResolver.loaderIdOf(loaderType));
    }

    private Value invokeDirectly(ReferenceType type, Method method, List<Value> args, ThreadReference thread)
            throws Exception {
        loaderResolver.primeForInvoke(method);
        if (type instanceof ClassType) {
            return ((ClassType) type).invokeMethod(thread, method, args, ObjectReference.INVOKE_SINGLE_THREADED);
        }
        if (type instanceof InterfaceType) {
            return ((InterfaceType) type).invokeMethod(thread, method, args, ObjectReference.INVOKE_SINGLE_THREADED);
        }
        throw new IllegalArgumentException("Cannot invoke static method on " + type.getClass().getSimpleName());
    }

    /**
     * Everything a handle invoke needs, or null when this call is not one the route can carry and
     * the ordinary route has to answer it. Only helper commands are sent here: the method eval asked
     * for is never invoked before the caller holds the result of this, so a fallback cannot run it
     * twice
     */
    private HandleTarget prepare(Method method, List<Value> args, ThreadReference thread) {
        ObjectCollectedException lastCollected = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            List<ObjectReference> heldList = new ArrayList<>();
            try {
                return prepareOnce(method, args, heldList, thread);
            } catch (ObjectCollectedException e) {
                // A helper object went before it was held. Only helpers have run, so building
                // them again runs nothing twice
                release(heldList);
                lastCollected = e;
            } catch (DoesNotFit ignored) {
                release(heldList);
                return null;
            } catch (VMDisconnectedException e) {
                // Not a fallback case: the session is gone and the ordinary route reports it
                throw e;
            } catch (Exception e) {
                // A helper that refuses, a target quirk: the caller's eval must not be lost over
                // the detour, so the ordinary route answers it the way it always did
                release(heldList);
                warnFallback(method, e);
                return null;
            }
        }
        warnFallback(method, lastCollected);
        return null;
    }

    /**
     * Worth a line in the log, because unlike a call that does not fit this one leaves the class
     * pinned
     */
    private static void warnFallback(Method method, Exception e) {
        System.err.println("warning: cannot route the static call to " + method.name()
                + " through a method handle (" + e + "), the target keeps its class's loader until disconnect");
    }

    private HandleTarget prepareOnce(Method method, List<Value> args, List<ObjectReference> heldList,
                                     ThreadReference thread) throws Exception {
        ReferenceType objectClass = findType(OBJECT_CLASS_NAME, thread);
        ObjectReference argumentArray = newArray(objectClass.classObject(),
                buildArguments(args, method, heldList, thread), heldList, thread);
        // A void method answers nothing, so there is no value to capture and eval reports VoidValue
        boolean captures = !RETURN_TYPE_VOID.equals(method.returnTypeName());
        ObjectReference holder = captures
                ? newArray(objectClass.classObject(),
                        Collections.singletonList((Value) null), heldList, thread)
                : null;
        ObjectReference handle = buildHandle(method, heldList, holder, thread);
        return new HandleTarget(handle, argumentArray, holder, heldList);
    }

    /**
     * The handle eval's call goes through. It is asked of the lookup the JDK keeps for itself, which
     * checks no access at all: it is a static field, read as a field and not through anything that
     * checks access, and its findStatic is granted what JDWP itself is granted, so neither a module
     * that opens its package to nobody nor a security manager that refuses reflection stops it.
     * Caller-sensitive methods, the one kind a trusted handle would bind to the wrong caller, are
     * only ever declared by the JDK's own loaders, which never take this route. The asking is by name
     * and descriptor, so it costs the same whatever the declaring type declares
     *
     * <p>When there is a value to keep, the handle's return type is widened to Object and a store
     * into the held array is filtered onto it: the target then writes the value into memory the
     * client already owns during the invoke itself, where reading the invoke's reply and asking for
     * the value afterwards would leave it unreferenced in between
     */
    private ObjectReference buildHandle(Method method, List<ObjectReference> heldList,
                                        ObjectReference holder, ThreadReference thread) throws Exception {
        ClassLoaderReference loader = loaderResolver.loaderOf(method.declaringType());
        ObjectReference methodType = buildMethodType(method.signature(), loader, heldList, thread);
        ObjectReference handle = findStatic(method, methodType, heldList, thread);
        if (method.isVarArgs()) {
            // A varargs method comes back as a collector, which would pack an already packed array
            // into another one
            handle = hold((ObjectReference) invokeOn(handle, AS_FIXED_ARITY, 0, Collections.emptyList(), thread),
                    heldList);
        }
        if (null == holder) {
            return handle;
        }
        ReferenceType methodHandlesClass = findType(METHOD_HANDLES_CLASS_NAME, thread);
        ObjectReference wideType = buildMethodType(widenedSignature(method.signature()), loader, heldList, thread);
        handle = hold((ObjectReference) invokeOn(handle, AS_TYPE, 1,
                Collections.singletonList((Value) wideType), thread), heldList);
        return hold((ObjectReference) invokeDirectly(methodHandlesClass,
                findBySignature(methodHandlesClass, FILTER_RETURN_VALUE, FILTER_RETURN_VALUE_SIGNATURE),
                Arrays.<Value>asList(handle, buildStore(holder, heldList, thread)), thread), heldList);
    }

    private ObjectReference findStatic(Method method, ObjectReference methodType,
                                       List<ObjectReference> heldList, ThreadReference thread) throws Exception {
        ReferenceType lookupType = findType(LOOKUP_CLASS_NAME, thread);
        Field field = lookupType.fieldByName(IMPL_LOOKUP_FIELD);
        if (null == field || !(lookupType instanceof ClassType)) {
            throw new DoesNotFit();
        }
        ObjectReference lookup = (ObjectReference) ((ClassType) lookupType).getValue(field);
        return hold((ObjectReference) invokeOn(lookup, FIND_STATIC, 3,
                Arrays.<Value>asList(method.declaringType().classObject(), session.getVm().mirrorOf(method.name()),
                        methodType), thread), heldList);
    }

    /**
     * A MethodType the target builds from the descriptor, so no parameter Class has to be resolved
     * one command at a time and no type the declaring type merely mentions has to be loaded first
     */
    private ObjectReference buildMethodType(String signature, ClassLoaderReference loader,
                                            List<ObjectReference> heldList, ThreadReference thread) throws Exception {
        ReferenceType methodTypeClass = findType(METHOD_TYPE_CLASS_NAME, thread);
        return hold((ObjectReference) invokeDirectly(methodTypeClass, findBySignature(methodTypeClass,
                FROM_METHOD_DESCRIPTOR, FROM_METHOD_DESCRIPTOR_SIGNATURE),
                Arrays.<Value>asList(session.getVm().mirrorOf(signature), loader), thread), heldList);
    }

    /**
     * The function that puts a return value into the array held for it: MethodHandles.arrayElementSetter
     * over Object[] with the array and the index already fixed, which leaves the one Object argument
     * filterReturnValue accepts. The values insertArguments fixes on are one explicit Object[], passed
     * as JDI passes one: the sole argument, which it hands over as the whole varargs array
     */
    private ObjectReference buildStore(ObjectReference holder, List<ObjectReference> heldList,
                                       ThreadReference thread) throws Exception {
        ReferenceType methodHandlesClass = findType(METHOD_HANDLES_CLASS_NAME, thread);
        ReferenceType objectClass = findType(OBJECT_CLASS_NAME, thread);
        ObjectReference objectArrayClass = arrayClass(objectClass, thread);
        // The values insertArguments binds are an Object[], so the index it binds is the boxed
        // Integer: a raw int there is an Object parameter JDI will not widen
        ObjectReference fixedValues = newArray(objectClass.classObject(),
                Arrays.<Value>asList(holder, box(session.getVm().mirrorOf(0), Primitive.INT.typeName,
                        heldList, thread)), heldList, thread);
        ObjectReference setter = hold((ObjectReference) invokeDirectly(methodHandlesClass,
                findBySignature(methodHandlesClass, ARRAY_ELEMENT_SETTER, ARRAY_ELEMENT_SETTER_SIGNATURE),
                Collections.singletonList((Value) objectArrayClass), thread), heldList);
        return hold((ObjectReference) invokeDirectly(methodHandlesClass,
                findBySignature(methodHandlesClass, INSERT_ARGUMENTS, INSERT_ARGUMENTS_SIGNATURE),
                Arrays.<Value>asList(setter, session.getVm().mirrorOf(0), fixedValues), thread), heldList);
    }

    /**
     * The Class object of an array of the component type, which is what arrayElementSetter is asked
     * for. The array type is named the source way, which is the way classesByName takes
     */
    private ObjectReference arrayClass(ReferenceType componentClass, ThreadReference thread) throws Exception {
        return findType(componentClass.name() + ARRAY_SUFFIX, thread).classObject();
    }

    /**
     * The descriptor with its return type replaced by Object, which is what a handle whose return is
     * filtered has to have
     */
    private static String widenedSignature(String signature) {
        return signature.substring(0, signature.lastIndexOf(')') + 1) + OBJECT_DESCRIPTOR;
    }

    /**
     * The Class object of a parameter type, resolved by name through the loader the declaring type
     * came from, which is what covers arrays and classes the target has not loaded yet. A primitive
     * is its wrapper's TYPE field, read rather than invoked
     */
    private ObjectReference findParamClass(String typeName, ClassLoaderReference loader, ThreadReference thread)
            throws Exception {
        Primitive primitive = PRIMITIVE_MAP.get(typeName);
        if (null != primitive) {
            ReferenceType wrapper = findType(primitive.wrapperClassName, thread);
            Field field = wrapper.fieldByName(TYPE_FIELD);
            if (null == field || !(wrapper instanceof ClassType)) {
                throw new DoesNotFit();
            }
            // The wrapper's own static field, read off the wrapper type rather than its Class object
            return (ObjectReference) ((ClassType) wrapper).getValue(field);
        }
        ReferenceType classType = findType(CLASS_CLASS_NAME, thread);
        Method forName = findMethod(classType, FOR_NAME, 3, STRING_CLASS_NAME);
        if (null == forName) {
            throw new DoesNotFit();
        }
        InvocationGuard.checkInvocable(forName);
        List<Value> args = new ArrayList<>(3);
        args.add(session.getVm().mirrorOf(classForNameName(typeName)));
        args.add(session.getVm().mirrorOf(false));
        args.add(loader);
        return (ObjectReference) invokeDirectly(classType, forName, args, thread);
    }

    /**
     * The arguments the Object[] invokeWithArguments takes, in the form the handle expects: the caller
     * has matched one argument to each parameter, and a varargs method gets its trailing one packed
     * the way a direct invoke packs it
     */
    private List<Value> buildArguments(List<Value> args, Method method, List<ObjectReference> heldList,
                                       ThreadReference thread) throws Exception {
        List<String> typeNames = method.argumentTypeNames();
        int varArgIndex = method.isVarArgs() ? typeNames.size() - 1 : -1;
        List<Value> boxedList = new ArrayList<>(args.size());
        ClassLoaderReference loader = null;
        for (int i = 0; i < args.size(); i++) {
            Value arg = args.get(i);
            String typeName = typeNames.get(i);
            // A null there stays null, which a direct invoke too passes as the array itself
            boolean packed = varArgIndex == i && null != arg;
            if (packed) {
                if (null == loader) {
                    loader = loaderResolver.loaderOf(method.declaringType());
                }
                boxedList.add(packVarArg(arg, typeName, loader, heldList, thread));
            } else {
                boxedList.add(box(arg, typeName, heldList, thread));
            }
        }
        return boxedList;
    }

    /**
     * The trailing argument of a varargs call as an array of one, the component type resolved
     * through the declaring type's loader like any parameter type
     */
    private ObjectReference packVarArg(Value arg, String arrayTypeName, ClassLoaderReference loader,
                                       List<ObjectReference> heldList, ThreadReference thread) throws Exception {
        String componentName = arrayTypeName.substring(0, arrayTypeName.length() - ARRAY_SUFFIX.length());
        Value element = box(arg, componentName, heldList, thread);
        ObjectReference componentClass = findParamClass(componentName, loader, thread);
        return newArray(componentClass, Collections.singletonList(element), heldList, thread);
    }

    /**
     * An array the target creates and fills through java.lang.reflect.Array, every step a static
     * call on a bootstrap class. Nothing in the target refers to it until the invoke it is built
     * for, so it is held against collection until release
     */
    private ObjectReference newArray(ObjectReference componentClass, List<Value> elementList,
                                     List<ObjectReference> heldList, ThreadReference thread) throws Exception {
        VirtualMachine vm = session.getVm();
        ReferenceType arrayClass = findType(ARRAY_CLASS_NAME, thread);
        Method newInstance = findBySignature(arrayClass, NEW_INSTANCE, NEW_INSTANCE_SIGNATURE);
        Method set = findBySignature(arrayClass, SET, SET_SIGNATURE);
        ObjectReference array = hold((ObjectReference) invokeDirectly(arrayClass, newInstance,
                Arrays.<Value>asList(componentClass, vm.mirrorOf(elementList.size())), thread), heldList);
        for (int i = 0; i < elementList.size(); i++) {
            invokeDirectly(arrayClass, set, Arrays.<Value>asList(array, vm.mirrorOf(i), elementList.get(i)), thread);
        }
        return array;
    }

    /**
     * Keep an object the target does not refer to alive until release. JDI leaves every object it
     * hands out collectable, and this route carries its objects across several commands
     */
    private static ObjectReference hold(ObjectReference object, List<ObjectReference> heldList) {
        object.disableCollection();
        heldList.add(object);
        return object;
    }

    private static void release(List<ObjectReference> heldList) {
        for (ObjectReference held : heldList) {
            try {
                held.enableCollection();
            } catch (RuntimeException ignored) {
                // Collected or gone with the session: either way nothing is held any more
            }
        }
    }

    /**
     * One argument in the form the handle accepts: a literal only fits a primitive parameter and a
     * string only a parameter type String is assignable to. One that fits neither is left to the
     * ordinary route, which refuses it as it always did, so boxed behind an object parameter or
     * narrowed into a smaller primitive is not a promise eval makes here
     */
    private Value box(Value arg, String typeName, List<ObjectReference> heldList, ThreadReference thread)
            throws Exception {
        Primitive primitive = PRIMITIVE_MAP.get(typeName);
        if (null == arg) {
            // A null literal fits a reference parameter, and a primitive one refuses it the way the
            // ordinary route does, rather than let the target fail while the handle unboxes it
            if (null != primitive) {
                throw new DoesNotFit();
            }
            return null;
        }
        if (arg instanceof StringReference) {
            if (null != primitive || !acceptsString((StringReference) arg, typeName)) {
                throw new DoesNotFit();
            }
            return arg;
        }
        if (!(arg instanceof PrimitiveValue) || null == primitive) {
            throw new DoesNotFit();
        }
        PrimitiveValue literal = mirrorOf(primitive, (PrimitiveValue) arg);
        ReferenceType wrapper = findType(primitive.wrapperClassName, thread);
        Method valueOf = findMethod(wrapper, VALUE_OF, 1, primitive.typeName);
        if (null == valueOf) {
            throw new DoesNotFit();
        }
        InvocationGuard.checkInvocable(valueOf);
        // A new wrapper unless valueOf had it cached, and nothing refers to it until Array.set
        return hold((ObjectReference) invokeDirectly(wrapper, valueOf,
                Collections.singletonList((Value) literal), thread), heldList);
    }

    /**
     * Whether a string can be assigned to the parameter type, the check the ordinary route makes
     * before it sends anything, where the target would only refuse once the call is under way.
     * String's supertypes are all bootstrap classes, so comparing names is comparing types
     */
    private static boolean acceptsString(StringReference arg, String typeName) {
        ClassType stringType = (ClassType) arg.referenceType();
        for (ClassType type = stringType; null != type; type = type.superclass()) {
            if (typeName.equals(type.name())) {
                return true;
            }
        }
        for (InterfaceType type : stringType.allInterfaces()) {
            if (typeName.equals(type.name())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The literal in the parameter's own type: a handle neither widens nor narrows, and one that
     * does not fit is left to the ordinary route, which reports the overflow
     */
    private PrimitiveValue mirrorOf(Primitive primitive, PrimitiveValue literal) {
        VirtualMachine vm = session.getVm();
        if (Primitive.BOOLEAN == primitive) {
            if (!(literal instanceof BooleanValue)) {
                throw new DoesNotFit();
            }
            return vm.mirrorOf(((BooleanValue) literal).value());
        }
        long value = literal.longValue();
        switch (primitive) {
            case BYTE:
                return vm.mirrorOf((byte) inRange(value, Byte.MIN_VALUE, Byte.MAX_VALUE));
            case SHORT:
                return vm.mirrorOf((short) inRange(value, Short.MIN_VALUE, Short.MAX_VALUE));
            case CHAR:
                return vm.mirrorOf((char) inRange(value, Character.MIN_VALUE, Character.MAX_VALUE));
            case INT:
                return vm.mirrorOf((int) inRange(value, Integer.MIN_VALUE, Integer.MAX_VALUE));
            case LONG:
                return vm.mirrorOf(value);
            case FLOAT:
                return vm.mirrorOf((float) value);
            default:
                return vm.mirrorOf((double) value);
        }
    }

    private static long inRange(long value, long min, long max) {
        if (value < min || value > max) {
            throw new DoesNotFit();
        }
        return value;
    }

    /**
     * Run the call through its handle and read back what the target wrote into the held array, then
     * let every helper go. A void method answers VoidValue, and a primitive is unboxed, so eval
     * reports the same value and the same type the ordinary route would
     */
    private Value invokeHandle(HandleTarget target, Method method, ThreadReference thread,
                               EvaluationScope scope) throws Exception {
        try {
            // The Object[] is passed as a single argument, which is the whole varargs array: JDI
            // collects the one argument for an invoke whose parameter count matches its argument
            // count, so the handle receives the array itself and not an array of one array
            target.handle.invokeMethod(thread,
                    findBySignature(target.handle.referenceType(), INVOKE_WITH_ARGUMENTS,
                            INVOKE_WITH_ARGUMENTS_SIGNATURE),
                    Collections.singletonList((Value) target.argumentArray),
                    ObjectReference.INVOKE_SINGLE_THREADED);
            if (null == target.holder) {
                return session.getVm().mirrorOfVoid();
            }
            // Read while the array still holds everything, and hold what comes out before the
            // finally below lets the array go: the value the target just built has no other
            // reference, so the moment the array is collectable the value is too
            return unbox(scope.hold(((com.sun.jdi.ArrayReference) target.holder).getValue(0)), method);
        } finally {
            release(target.heldList);
        }
    }

    /**
     * A primitive return comes back boxed, where a direct invoke answers with the primitive itself,
     * so eval reports the same type either way
     */
    private Value unbox(Value result, Method method) {
        if (RETURN_TYPE_VOID.equals(method.returnTypeName())) {
            return session.getVm().mirrorOfVoid();
        }
        Primitive primitive = PRIMITIVE_MAP.get(method.returnTypeName());
        if (null == primitive || !(result instanceof ObjectReference)) {
            return result;
        }
        ObjectReference wrapper = (ObjectReference) result;
        Field field = wrapper.referenceType().fieldByName(VALUE_FIELD);
        if (null == field || !primitive.typeName.equals(field.typeName())) {
            return result;
        }
        return wrapper.getValue(field);
    }

    /**
     * Call a method one of the bootstrap classes here declares, which is what every step of the
     * handle route is
     */
    private Value invokeOn(ObjectReference receiver, String name, int argCount, List<Value> args, ThreadReference thread)
            throws Exception {
        Method method = findMethod(receiver.referenceType(), name, argCount, null);
        if (null == method) {
            throw new DoesNotFit();
        }
        InvocationGuard.checkInvocable(method);
        return receiver.invokeMethod(thread, method, args, ObjectReference.INVOKE_SINGLE_THREADED);
    }

    /**
     * The one visible method of that name and arity. methodsByName reaches what the type inherits
     * and leaves out what an interface declares twice, so getParameterCount is found on Executable
     * and setAccessible on AccessibleObject. More or less than one candidate means null, and the
     * caller falls back
     */
    private static Method findMethod(ReferenceType type, String name, int argCount, String paramTypeName) {
        List<Method> candidates = new ArrayList<>();
        for (Method method : type.methodsByName(name)) {
            List<String> typeNames = method.argumentTypeNames();
            if (argCount != typeNames.size()) {
                continue;
            }
            if (null == paramTypeName || paramTypeName.equals(typeNames.get(0))) {
                candidates.add(method);
            }
        }
        return 1 == candidates.size() ? candidates.get(0) : null;
    }

    /**
     * A bootstrap method picked by its exact signature, for names an overload shares an arity with
     */
    private static Method findBySignature(ReferenceType type, String name, String signature) {
        List<Method> candidates = type.methodsByName(name, signature);
        if (1 != candidates.size()) {
            throw new DoesNotFit();
        }
        Method method = candidates.get(0);
        InvocationGuard.checkInvocable(method);
        return method;
    }

    /**
     * The type for a class name, having the target load the class where nothing has yet: an array or
     * a wrapper is missing from a target that never needed one. Class.forName is a bootstrap static
     * call, which leaks nothing that could be unloaded. The name is the source form classesByName
     * wants, which is not the form Class.forName takes
     */
    private ReferenceType findType(String className, ThreadReference thread) throws Exception {
        List<ReferenceType> types = session.getVm().classesByName(className);
        if (types.isEmpty()) {
            List<ReferenceType> classTypes = session.getVm().classesByName(CLASS_CLASS_NAME);
            Method forName = classTypes.isEmpty() ? null : findMethod(classTypes.get(0), FOR_NAME, 1, STRING_CLASS_NAME);
            if (null != forName) {
                InvocationGuard.checkInvocable(forName);
                invokeDirectly(classTypes.get(0), forName,
                        Collections.singletonList((Value) session.getVm().mirrorOf(classForNameName(className))),
                        thread);
                types = session.getVm().classesByName(className);
            }
        }
        if (types.isEmpty()) {
            throw new DoesNotFit();
        }
        return types.get(0);
    }

    /**
     * The name Class.forName takes for a type, which differs from the source form classesByName
     * wants once an array is involved: "[Ljava.lang.Object;" for "java.lang.Object[]"
     */
    private static String classForNameName(String typeName) {
        int firstBracket = typeName.indexOf('[');
        if (firstBracket < 0) {
            return typeName;
        }
        String componentName = typeName.substring(0, firstBracket);
        StringBuilder sb = new StringBuilder();
        // Each dimension is the two characters "[]" in source form and one '[' in this one
        int dimensions = (typeName.length() - firstBracket) / ARRAY_SUFFIX.length();
        for (int i = 0; i < dimensions; i++) {
            sb.append('[');
        }
        Primitive primitive = PRIMITIVE_MAP.get(componentName);
        if (null != primitive) {
            return sb.append(primitive.descriptor).toString();
        }
        return sb.append('L').append(componentName).append(';').toString();
    }

    /**
     * A static call the handle route can carry: the handle to invoke, the Object[] for its arguments,
     * the Object[1] its return value is written into (null when the method is void), and the objects
     * built for it that are held until the invoke returns
     */
    private static class HandleTarget {

        private final ObjectReference handle;

        private final ObjectReference argumentArray;

        private final ObjectReference holder;

        private final List<ObjectReference> heldList;

        private HandleTarget(ObjectReference handle, ObjectReference argumentArray,
                             ObjectReference holder, List<ObjectReference> heldList) {
            this.handle = handle;
            this.argumentArray = argumentArray;
            this.holder = holder;
            this.heldList = heldList;
        }
    }

    /**
     * Control flow, never reported to the user: this call is not one the handle route can carry, so
     * it goes out the ordinary way instead
     */
    private static class DoesNotFit extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
