package cn.ares.jvm.jdi.eval;

import cn.ares.jvm.jdi.session.ClassLoaderResolver;
import cn.ares.jvm.jdi.session.JvmSession;
import com.sun.jdi.ClassLoaderReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.VirtualMachine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the loaded class a leading run of expression tokens names, and picks one copy of it when
 * several class loaders have defined it.
 */
class ClassResolver {

  private final JvmSession session;

  private final ClassLoaderResolver loaderResolver;

  ClassResolver(JvmSession session, ClassLoaderResolver loaderResolver) {
    this.session = session;
    this.loaderResolver = loaderResolver;
  }

  /**
   * Try to resolve a class name from progressively longer token prefixes. E.g., for
   * ["cn","dotfashion","soa","Tracer","getCurrentContext()"], tries "cn", "cn.dotfashion", ...
   * until a loaded class is found. Also handles simple names like "Thread" → "java.lang.Thread".
   */
  ClassResolution resolveClassName(List<String> partList, StackFrame frame, String loaderKey) {
    VirtualMachine vm = session.getVm();

    // Try progressively longer prefixes (skip last part which should be the method/field)
    StringBuilder className = new StringBuilder();
    for (int i = 0; i < partList.size() - 1; i++) {
      String token = partList.get(i);
      // Stop if token looks like a method call (has parentheses)
      if (token.contains("(")) {
        break;
      }

      if (className.length() > 0) {
        className.append('.');
      }
      className.append(token);

      List<ReferenceType> classList = vm.classesByName(className.toString());
      if (!classList.isEmpty()) {
        ClassResolution res = new ClassResolution();
        res.type = pickType(className.toString(), classList, frame, loaderKey);
        res.consumedParts = i + 1;
        return res;
      }
    }

    // Try common java.lang.* prefix for simple names like "Thread", "System", "Math"
    String simpleName = partList.get(0);
    if (simpleName.contains("(")) {
      return null;
    }
    String javaLangName = "java.lang." + simpleName;
    List<ReferenceType> classList = vm.classesByName(javaLangName);
    if (classList.isEmpty()) {
      return null;
    }
    ClassResolution res = new ClassResolution();
    res.type = pickType(javaLangName, classList, frame, loaderKey);
    res.consumedParts = 1;
    return res;
  }

  /**
   * Choose one of the loaded copies of a class. Taking the first would silently run a static call
   * against whichever copy JDI lists first, e.g. a stale hot-redeployed one. Order: the explicit
   * loader, the only copy, the only copy visible from the frame's class; otherwise fail with the
   * candidates so the caller can pass class_loader_id.
   */
  private ReferenceType pickType(String className, List<ReferenceType> typeList, StackFrame frame, String loaderKey) {
    if (null != loaderKey) {
      for (ReferenceType type : typeList) {
        if (loaderKey.equals(String.valueOf(loaderResolver.loaderIdOf(type)))) {
          return type;
        }
      }
      throw new ClassChoiceException("ClassLoaderMismatch", "No copy of " + className
          + " is defined by class loader " + loaderKey, describeTypes(typeList));
    }
    if (typeList.size() == 1) {
      return typeList.get(0);
    }
    List<ReferenceType> visibleList = visibleFrom(frame, typeList);
    if (visibleList.size() == 1) {
      return visibleList.get(0);
    }
    throw new ClassChoiceException("AmbiguousClass", typeList.size() + " copies of " + className
        + " are loaded and the current frame does not single one out."
        + " Retry with class_loader_id set to one of the candidates", describeTypes(typeList));
  }

  /**
   * The copies the code of this frame would resolve the name to. visibleClasses() lists only
   * classes the loader has already resolved, so a copy the frame never touched is missed and the
   * caller falls back to class_loader_id.
   */
  private List<ReferenceType> visibleFrom(StackFrame frame, List<ReferenceType> typeList) {
    ClassLoaderReference loader = loaderResolver.loaderOf(frame.location().declaringType());
    List<ReferenceType> visibleList = new ArrayList<>();
    if (null == loader) {
      for (ReferenceType type : typeList) {
        if (null == loaderResolver.loaderOf(type)) {
          visibleList.add(type);
        }
      }
      return visibleList;
    }
    Set<ReferenceType> visibleSet = new HashSet<>(loader.visibleClasses());
    for (ReferenceType type : typeList) {
      if (visibleSet.contains(type)) {
        visibleList.add(type);
      }
    }
    return visibleList;
  }

  private List<Map<String, Object>> describeTypes(List<ReferenceType> typeList) {
    List<Map<String, Object>> candidateList = new ArrayList<>();
    for (ReferenceType type : typeList) {
      Map<String, Object> info = new LinkedHashMap<>();
      info.put("class", type.name());
      loaderResolver.putClassLoader(info, type);
      candidateList.add(info);
    }
    return candidateList;
  }

  static class ClassResolution {

    ReferenceType type;

    /**
     * Number of tokens consumed as class name
     */
    int consumedParts;
  }
}
