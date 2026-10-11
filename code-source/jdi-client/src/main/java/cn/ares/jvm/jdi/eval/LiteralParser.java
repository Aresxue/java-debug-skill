package cn.ares.jvm.jdi.eval;

import cn.ares.jvm.jdi.session.JvmSession;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the literal text of eval's call arguments and string roots into values in the target.
 */
class LiteralParser {

    /**
     * How often a literal mirror is built again after the target collected it before it could be
     * held. A loss needs a collection inside the one round trip between creating and holding it
     */
    private static final int MAX_MIRROR_ATTEMPTS = 3;

    private final JvmSession session;

    LiteralParser(JvmSession session) {
        this.session = session;
    }

    List<Value> parseArgValues(String argsStr, EvaluationScope scope) {
        List<Value> valueList = new ArrayList<>();
        if (argsStr.isEmpty()) {
            return valueList;
        }

        List<String> argTokenList = ExpressionTokenizer.splitArgs(argsStr);
        for (String arg : argTokenList) {
            valueList.add(parseArgLiteral(arg.trim(), scope));
        }
        return valueList;
    }

    /**
     * Mirror a quoted string literal token, its quotes still on, and hold it.
     */
    Value parseString(String quotedToken, EvaluationScope scope) {
        String s = quotedToken.substring(1, quotedToken.length() - 1);
        return holdFreshString(decodeStringLiteral(s), scope);
    }

    private Value parseArgLiteral(String arg, EvaluationScope scope) {
        VirtualMachine vm = session.getVm();
        // String literal
        if (arg.startsWith("\"") && arg.endsWith("\"")) {
            return parseString(arg, scope);
        }
        // Numeric
        if (arg.matches("-?\\d+")) {
            long val = Long.parseLong(arg);
            if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
                return vm.mirrorOf((int) val);
            }
            return vm.mirrorOf(val);
        }
        // Boolean
      switch (arg) {
        case "true":
          return vm.mirrorOf(true);
        case "false":
          return vm.mirrorOf(false);
        case "null":
          return null;
      }

      // No implicit fallback to a string literal: silently mirroring an unresolvable token
        // as its own name makes eval return a plausible wrong value instead of failing,
        // e.g. map.get(someKeyVar) would look up the literal "someKeyVar" and answer null
        throw new IllegalArgumentException("Cannot parse argument as literal: " + arg
                + ". Only string/number/boolean/null literals are supported");
    }

    /**
     * Decode the escapes inside a string literal, shared by the expression root and the method
     * arguments so the same text means the same string either way. One pass, so the two-character
     * sequence backslash-backslash is read as one backslash and cannot then be re-read as the start
     * of another escape: a chain of replace calls decodes them in whatever order it is written, and
     * the common order turns a literal backslash-n into a newline. The escapes are the ones a Java
     * literal has; anything else is refused rather than passed through, because a silently kept
     * backslash would let eval look up a different value than the caller wrote
     */
    private static String decodeStringLiteral(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!escaped) {
                if ('\\' == ch) {
                    escaped = true;
                } else {
                    result.append(ch);
                }
                continue;
            }
            switch (ch) {
                case '"':
                    result.append('"');
                    break;
                case '\\':
                    result.append('\\');
                    break;
                case 'n':
                    result.append('\n');
                    break;
                case 't':
                    result.append('\t');
                    break;
                case 'r':
                    result.append('\r');
                    break;
                case 'b':
                    result.append('\b');
                    break;
                case 'f':
                    result.append('\f');
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported string escape: \\" + ch);
            }
            escaped = false;
        }
        if (escaped) {
            throw new IllegalArgumentException("Trailing backslash in string literal");
        }
        return result.toString();
    }

    /**
     * Mirror a string and hold it. The mirror is one CreateString command and the hold is another,
     * and nothing in the target refers to the string in between, so a collection there loses it
     * before it can be held. Nothing has been invoked yet at this point, so building it again runs
     * nothing twice and the attempt can simply be repeated
     */
    private Value holdFreshString(String s, EvaluationScope scope) {
        ObjectCollectedException lastCollected = null;
        for (int attempt = 0; attempt < MAX_MIRROR_ATTEMPTS; attempt++) {
            try {
                return scope.hold(session.getVm().mirrorOf(s));
            } catch (ObjectCollectedException ignored) {
            }
        }
        throw lastCollected;
    }
}
