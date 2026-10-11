package cn.ares.jvm.jdi.eval;

import com.sun.jdi.Method;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks the overload an eval call means from the methods a name resolves to.
 */
final class MethodSelector {

    private MethodSelector() {
    }

    static Method findNoArgMethod(List<Method> methodList) {
        for (Method m : methodList) {
            if (m.argumentTypeNames().isEmpty()) {
                return m;
            }
        }
        return null;
    }

    /**
     * Pick the overload matching the argument count. Ambiguity is reported rather than guessed:
     * argument types cannot be inferred from bare literals, so picking one silently can invoke
     * the wrong overload.
     */
    static Method findMethodByArgCount(List<Method> methodList, int argCount) {
        List<Method> matchList = new ArrayList<>();
        for (Method m : methodList) {
            if (m.argumentTypeNames().size() == argCount) {
                matchList.add(m);
            }
        }
        if (matchList.size() == 1) {
            return matchList.get(0);
        }
        if (matchList.size() > 1) {
            throw new IllegalArgumentException("Ambiguous overload for '" + matchList.get(0).name()
                    + "' with " + argCount + " args: " + describeCandidates(matchList));
        }
        return null;
    }

    static String describeCandidates(List<Method> methodList) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < methodList.size(); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(methodList.get(i).name()).append(methodList.get(i).signature());
        }
        return sb.toString();
    }
}
