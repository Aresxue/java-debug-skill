package cn.ares.jvm.jdi.inspect;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.Field;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;
import com.sun.jdi.VoidValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Format a JDI value as a display string. Shared by locals collection and expression evaluation so
 * the same value reads identically on both paths.
 *
 * <p>The values read out of a field map or an array are not held here: those reads happen in one
 * batch, and holding each afterwards costs a disableCollection command per value, which adds the
 * very window it would close. An evaluation holds the objects it reached itself, see
 * EvaluationScope; on the locals path the mirrors come from a stopped frame, where nothing else
 * competes for the window between reading one field and reading the next.
 */
public final class ValueFormatter {

  private static final int MAX_STRING_LENGTH = 500;
  private static final int MAX_COLLECTION_SIZE = 10;
  private static final int MAX_FIELDS = 20;

  private ValueFormatter() {
  }

  public static String format(Value value, int maxDepth) {
    if (null == value) {
      return "null";
    }
    if (value instanceof VoidValue) {
      return "void";
    }
    if (value instanceof StringReference) {
      String s = ((StringReference) value).value();
      int totalLength = s.length();
      if (totalLength > MAX_STRING_LENGTH) {
        s = s.substring(0, MAX_STRING_LENGTH) + "...(truncated, total " + totalLength + ")";
      }
      return "\"" + s + "\"";
    }
    if (value instanceof PrimitiveValue) {
      return value.toString();
    }
    if (value instanceof ArrayReference) {
      return formatArray((ArrayReference) value, maxDepth);
    }
    if (value instanceof ObjectReference) {
      return formatObject((ObjectReference) value, maxDepth);
    }
    return value.toString();
  }

  private static String formatArray(ArrayReference arrayReference, int maxDepth) {
    int length = arrayReference.length();
    if (maxDepth <= 0) {
      return arrayReference.type().name() + "[" + length + "]";
    }
    int show = Math.min(length, MAX_COLLECTION_SIZE);
    List<Value> valueList = arrayReference.getValues(0, show);
    StringBuilder sb = new StringBuilder();
    sb.append('[');
    for (int i = 0; i < valueList.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(format(valueList.get(i), maxDepth - 1));
    }
    if (length > show) {
      sb.append(", ... (").append(length).append(" total)");
    }
    sb.append(']');
    return sb.toString();
  }

  /**
   * Expand an object as a field tree, one batch round trip per object. Fields are read directly
   * rather than calling size()/toString() on the target: invoking methods here would run real code
   * on every breakpoint hit (lazy-loading collections issue queries, a toString() that takes a lock
   * blocks the event loop, and JDI invokeMethod has no timeout), which contradicts the skill's own
   * rule against evaluating side-effecting methods. Callers who want toString() can ask for it
   * explicitly via eval.
   */
  private static String formatObject(ObjectReference objectReference, int maxDepth) {
    if (maxDepth <= 0) {
      return objectReference.type().name() + "@" + objectReference.uniqueID();
    }
    try {
      ReferenceType type = objectReference.referenceType();
      List<Field> fieldList = new ArrayList<>();
      for (Field field : type.allFields()) {
        if (field.isStatic() || field.isSynthetic()) {
          continue;
        }
        fieldList.add(field);
      }
      if (fieldList.isEmpty()) {
        return type.name() + "@" + objectReference.uniqueID();
      }
      int show = Math.min(fieldList.size(), MAX_FIELDS);
      List<Field> shownList = fieldList.subList(0, show);
      Map<Field, Value> valueMap = objectReference.getValues(shownList);
      StringBuilder sb = new StringBuilder();
      sb.append(type.name()).append('{');
      for (int i = 0; i < show; i++) {
        Field field = shownList.get(i);
        if (i > 0) {
          sb.append(", ");
        }
        sb.append(field.name()).append('=')
            .append(format(valueMap.get(field), maxDepth - 1));
      }
      if (fieldList.size() > show) {
        sb.append(", ... (").append(fieldList.size()).append(" fields total)");
      }
      sb.append('}');
      return sb.toString();
    } catch (Exception exception) {
      return objectReference.type().name() + "@" + objectReference.uniqueID();
    }
  }
}
