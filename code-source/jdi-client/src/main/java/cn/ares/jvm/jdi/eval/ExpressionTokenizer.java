package cn.ares.jvm.jdi.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits eval's expression text: the chain on '.', a call's arguments on ','. Both respect
 * parentheses and string literals.
 */
final class ExpressionTokenizer {

    private ExpressionTokenizer() {
    }

    /**
     * Split expression by '.' but respect parentheses and string literals.
     * e.g. "request.getHeader(\"foo\")" → ["request", "getHeader(\"foo\")"]
     */
    static List<String> tokenize(String expr) {
        List<String> partList = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parenDepth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);

            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }

            if ('\\' == c) {
                current.append(c);
                escaped = true;
                continue;
            }

            if ('"' == c) {
                inString = !inString;
                current.append(c);
                continue;
            }

            if (inString) {
                current.append(c);
                continue;
            }

            if ('(' == c) {
                parenDepth++;
                current.append(c);
            } else if (')' == c) {
                parenDepth--;
                current.append(c);
            } else if ('.' == c && 0 == parenDepth) {
                if (current.length() > 0) {
                    partList.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            partList.add(current.toString());
        }
        return partList;
    }

    /**
     * Split args by comma, respecting strings and parens.
     */
    static List<String> splitArgs(String argsStr) {
        List<String> resultList = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parenDepth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < argsStr.length(); i++) {
            char c = argsStr.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if ('\\' == c) {
                current.append(c);
                escaped = true;
                continue;
            }
            if ('"' == c) {
                inString = !inString;
                current.append(c);
                continue;
            }
            if (inString) {
                current.append(c);
                continue;
            }
            if ('(' == c) {
                parenDepth++;
                current.append(c);
            } else if (')' == c) {
                parenDepth--;
                current.append(c);
            } else if (',' == c && 0 == parenDepth) {
                resultList.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            resultList.add(current.toString());
        }
        return resultList;
    }
}
