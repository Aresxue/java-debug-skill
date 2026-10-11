package cn.ares.jvm.jdi.eval;

import cn.ares.jvm.jdi.eval.ClassResolver.ClassResolution;
import cn.ares.jvm.jdi.inspect.ValueFormatter;
import cn.ares.jvm.jdi.session.ClassLoaderResolver;
import cn.ares.jvm.jdi.session.JvmSession;
import cn.ares.jvm.jdi.session.ThreadRegistry;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.InvocationException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Evaluates eval's expression on a suspended thread: a root (literal, local, this, or class name)
 * followed by a chain of field reads, getters and method calls.
 */
public class ExpressionEvaluator {

  private final JvmSession session;

  private final ThreadRegistry threadRegistry;

  private final ClassLoaderResolver loaderResolver;

  private final StaticInvoker staticInvoker;

  private final LiteralParser literalParser;

  private final ClassResolver classResolver;

  public ExpressionEvaluator(JvmSession session, ThreadRegistry threadRegistry, ClassLoaderResolver loaderResolver) {
    this.session = session;
    this.threadRegistry = threadRegistry;
    this.loaderResolver = loaderResolver;
    this.staticInvoker = new StaticInvoker(session, loaderResolver);
    this.literalParser = new LiteralParser(session);
    this.classResolver = new ClassResolver(session, loaderResolver);
  }

  /**
   * Evaluate an expression on the specified thread and frame. loaderKey picks the copy of a class
   * named in the expression (a class_loader_id as a string, or "bootstrap"); null lets the frame's
   * visibility decide. It does not affect chains that start from a local variable.
   */
  public EvalResult evaluate(long threadId, int frameDepth, String expression, String loaderKey) {
    EvalResult result = new EvalResult();
    ThreadReference thread = threadRegistry.findThread(threadId);
    if (null == thread) {
      result.error = "Thread not found: " + threadId;
      return result;
    }
    if (!thread.isSuspended()) {
      result.error = "Thread not suspended: " + threadId;
      return result;
    }
    // The objects this evaluation must keep alive are held until they have been formatted: a
    // value the target no longer refers to can be collected between any two commands otherwise.
    // What is held is the string literals, the root value, the chain intermediates and the
    // invoke results. The field values and array elements a batch read returns are not: a
    // disableCollection is itself a command, so holding a whole batch adds windows while
    // closing one, and that was measured to make collection failures much more frequent
    EvaluationScope scope = new EvaluationScope();

    try {
      StackFrame frame = thread.frame(frameDepth);
      Value value = resolveExpression(frame, thread, expression.trim(), loaderKey, scope);
      result.value = ValueFormatter.format(value, 2);
      result.type = (null != value) ? value.type().name() : "null";
    } catch (IncompatibleThreadStateException incompatibleThreadStateException) {
      result.error = "Thread not in valid state: " + incompatibleThreadStateException.getMessage();
    } catch (IndexOutOfBoundsException indexOutOfBoundsException) {
      result.error = "Invalid frame depth: " + frameDepth;
    } catch (InvocationException invocationException) {
      // Runs before the finally below, so the scope still holds what this reads
      result.error = "Method invocation failed: " + ValueFormatter.format(scope.hold(invocationException.exception()), 2);
    } catch (ClassChoiceException classChoiceException) {
      result.errorCode = classChoiceException.errorCode;
      result.error = classChoiceException.getMessage();
      result.candidateList = classChoiceException.candidateList;
    } catch (VMDisconnectedException vmDisconnectedException) {
      // Not an evaluation failure: let dispatch report it as VmDisconnected
      throw vmDisconnectedException;
    } catch (Exception exception) {
      result.error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
    } finally {
      scope.releaseAll();
    }
    return result;
  }

  private Value resolveExpression(StackFrame frame, ThreadReference thread, String expr, String loaderKey, EvaluationScope scope) throws Exception {
    // Split by '.' respecting parentheses and strings
    List<String> partList = ExpressionTokenizer.tokenize(expr);

    if (partList.isEmpty()) {
      throw new IllegalArgumentException("Empty expression");
    }

    // Try resolving first part as local/this/literal
    Value current;
    int chainStart = 1;
    try {
      current = resolveFirst(frame, thread, partList.get(0), scope);
    } catch (IllegalArgumentException illegalArgumentException) {
      // First token not resolvable as local/this — try as class name
      ClassResolution classRes = classResolver.resolveClassName(partList, frame, loaderKey);
      if (null == classRes) {
        // Re-throw original error
        throw illegalArgumentException;
      }
      current = resolveStaticMember(classRes.type, thread, partList.get(classRes.consumedParts), scope);
      chainStart = classRes.consumedParts + 1;
    }

    // Resolve chain
    for (int i = chainStart; i < partList.size(); i++) {
      if (null == current) {
        throw new NullPointerException("Cannot dereference null at: " + partList.get(i));
      }
      if (!(current instanceof ObjectReference)) {
        throw new IllegalArgumentException("Cannot access member on primitive: " + partList.get(i));
      }
      current = scope.hold(resolveMember((ObjectReference) current, thread, partList.get(i), scope));
    }

    return current;
  }

  /**
   * Resolve a static method call or static field on a class.
   */
  private Value resolveStaticMember(ReferenceType type, ThreadReference thread, String token, EvaluationScope scope) throws Exception {
    // Check if it's a method call: methodName(args)
    int parenIdx = token.indexOf('(');
    if (parenIdx > 0 && token.endsWith(")")) {
      String methodName = token.substring(0, parenIdx);
      String argsStr = token.substring(parenIdx + 1, token.length() - 1).trim();
      return invokeStaticMethod(type, thread, methodName, argsStr, scope);
    }

    // Static field access
    Field field = type.fieldByName(token);
    if (null != field && field.isStatic()) {
      return type.getValue(field);
    }

    throw new IllegalArgumentException("Cannot resolve static member: " + token + " on " + type.name());
  }

  private Value invokeStaticMethod(ReferenceType type, ThreadReference thread, String methodName, String argsStr, EvaluationScope scope) throws Exception {
    List<Value> argList = literalParser.parseArgValues(argsStr, scope);
    List<Method> methodList = type.methodsByName(methodName);

    if (methodList.isEmpty()) {
      throw new IllegalArgumentException("Static method not found: " + methodName + " on " + type.name());
    }

    // Find the static overload matching the argument count; report ambiguity instead of
    // picking one, since literal arguments carry no type information to disambiguate with
    List<Method> staticList = new ArrayList<>();
    for (Method m : methodList) {
      if (m.isStatic() && m.argumentTypeNames().size() == argList.size()) {
        staticList.add(m);
      }
    }
    if (staticList.isEmpty()) {
      throw new IllegalArgumentException("No static method " + methodName + " with " + argList.size() + " args on " + type.name());
    }
    if (staticList.size() > 1) {
      throw new IllegalArgumentException("Ambiguous static overload for '" + methodName
          + "' with " + argList.size() + " args: " + MethodSelector.describeCandidates(staticList));
    }
    Method method = staticList.get(0);
    InvocationGuard.checkInvocable(method);
    return staticInvoker.invoke(type, method, argList, thread, scope);
  }

  private Value resolveFirst(StackFrame frame, ThreadReference thread, String token, EvaluationScope scope) throws Exception {
    VirtualMachine vm = session.getVm();

    // Check for string literal
    if (token.startsWith("\"") && token.endsWith("\"")) {
      return literalParser.parseString(token, scope);
    }

    // Check for numeric literal
    if (token.matches("-?\\d+")) {
      long val = Long.parseLong(token);
      if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
        return vm.mirrorOf((int) val);
      }
      return vm.mirrorOf(val);
    }

    // Check for boolean
    switch (token) {
      case "true":
        return vm.mirrorOf(true);
      case "false":
        return vm.mirrorOf(false);
      case "null":
        return null;

      // Check for 'this'
      case "this":
        return scope.hold(frame.thisObject());
    }

    // Check for method call on implicit this: methodName(args)
    if (token.contains("(")) {
      ObjectReference thisObj = frame.thisObject();
      if (null != thisObj) {
        scope.hold(thisObj);
        return scope.hold(resolveMember(thisObj, thread, token, scope));
      }
      // Static context - try local variable
    }

    // Try local variable
    try {
      LocalVariable var = frame.visibleVariableByName(token);
      if (null != var) {
        return scope.hold(frame.getValue(var));
      }
    } catch (AbsentInformationException e) {
      // no debug info
    }

    // Try 'this' fields
    ObjectReference thisObj = frame.thisObject();
    if (null != thisObj) {
      scope.hold(thisObj);
      Field field = thisObj.referenceType().fieldByName(token);
      if (null != field) {
        return scope.hold(thisObj.getValue(field));
      }
    }

    throw new IllegalArgumentException("Cannot resolve: " + token);
  }

  private Value resolveMember(ObjectReference obj, ThreadReference thread, String token, EvaluationScope scope) throws Exception {
    // Check if it's a method call: methodName(args)
    int parenIdx = token.indexOf('(');
    if (parenIdx > 0 && token.endsWith(")")) {
      String methodName = token.substring(0, parenIdx);
      String argsStr = token.substring(parenIdx + 1, token.length() - 1).trim();
      return invokeMethod(obj, thread, methodName, argsStr, scope);
    }

    // Field access
    ReferenceType type = obj.referenceType();
    Field field = type.fieldByName(token);
    if (null != field) {
      return obj.getValue(field);
    }

    // Try as getter: token → getToken()
    String getterName = "get" + Character.toUpperCase(token.charAt(0)) + token.substring(1);
    List<Method> getterList = type.methodsByName(getterName);
    if (getterList.isEmpty()) {
      throw new IllegalArgumentException("Cannot resolve member: " + token + " on " + type.name());
    }
    Method getter = MethodSelector.findNoArgMethod(getterList);
    if (null == getter) {
      throw new IllegalArgumentException("Cannot resolve member: " + token + " on " + type.name());
    }
    InvocationGuard.checkInvocable(getter);
    return obj.invokeMethod(thread, getter, Collections.emptyList(), ObjectReference.INVOKE_SINGLE_THREADED);
  }

  private Value invokeMethod(ObjectReference obj, ThreadReference thread, String methodName, String argsStr, EvaluationScope scope) throws Exception {
    List<Value> argList = literalParser.parseArgValues(argsStr, scope);
    ReferenceType type = obj.referenceType();
    List<Method> methodList = type.methodsByName(methodName);

    if (methodList.isEmpty()) {
      throw new IllegalArgumentException("Method not found: " + methodName + " on " + type.name());
    }

    // Find method with matching arg count
    Method method = MethodSelector.findMethodByArgCount(methodList, argList.size());
    if (null == method) {
      throw new IllegalArgumentException("No method " + methodName + " with " + argList.size() + " args");
    }
    InvocationGuard.checkInvocable(method);
    loaderResolver.primeForInvoke(method);

    return obj.invokeMethod(thread, method, argList, ObjectReference.INVOKE_SINGLE_THREADED);
  }
}
