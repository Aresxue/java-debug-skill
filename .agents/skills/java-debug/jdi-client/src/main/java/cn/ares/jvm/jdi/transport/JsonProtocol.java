package cn.ares.jvm.jdi.transport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser/serializer for the JDI Client protocol. Supports: string, number(int/long),
 * boolean, null, object, array. No external dependencies.
 */
public class JsonProtocol {

  /**
   * Nesting cap for the recursive parser. Commands are at most two levels deep, and a deeply nested
   * body would otherwise end in StackOverflowError, which no handler catches
   */
  private static final int MAX_DEPTH = 64;

  // ==================== Parsing ====================

  public Map<String, Object> parse(String json) {
    if (json == null || json.trim().isEmpty()) {
      return Collections.emptyMap();
    }
    int[] pos = {0};
    skipWhitespace(json, pos);
    Object result = parseValue(json, pos, 0);
    skipWhitespace(json, pos);
    if (pos[0] < json.length()) {
      throw new IllegalArgumentException("Unexpected trailing content at pos " + pos[0]);
    }
    if (result instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> map = (Map<String, Object>) result;
      return map;
    }
    throw new IllegalArgumentException("Expected JSON object at root");
  }

  private Object parseValue(String json, int[] pos, int depth) {
    skipWhitespace(json, pos);
    if (pos[0] >= json.length()) {
      throw new IllegalArgumentException("Unexpected end of JSON");
    }
    char c = json.charAt(pos[0]);
    switch (c) {
      case '{':
        return parseObject(json, pos, nextDepth(depth));
      case '[':
        return parseArray(json, pos, nextDepth(depth));
      case '"':
        return parseString(json, pos);
      case 't':
        return parseLiteral(json, pos, "true", Boolean.TRUE);
      case 'f':
        return parseLiteral(json, pos, "false", Boolean.FALSE);
      case 'n':
        return parseLiteral(json, pos, "null", null);
      default:
        if (c == '-' || (c >= '0' && c <= '9')) {
          return parseNumber(json, pos);
        }
        throw new IllegalArgumentException("Unexpected char '" + c + "' at pos " + pos[0]);
    }
  }

  private int nextDepth(int depth) {
    if (depth >= MAX_DEPTH) {
      throw new IllegalArgumentException("JSON nested deeper than " + MAX_DEPTH + " levels");
    }
    return depth + 1;
  }

  private Map<String, Object> parseObject(String json, int[] pos, int depth) {
    Map<String, Object> map = new LinkedHashMap<>();
    pos[0]++; // skip '{'
    skipWhitespace(json, pos);
    if (charAtOrFail(json, pos) == '}') {
      pos[0]++;
      return map;
    }
    while (true) {
      skipWhitespace(json, pos);
      String key = parseString(json, pos);
      skipWhitespace(json, pos);
      expect(json, pos, ':');
      Object value = parseValue(json, pos, depth);
      map.put(key, value);
      skipWhitespace(json, pos);
      char c = charAtOrFail(json, pos);
      if (c == ',') {
        pos[0]++;
      } else if (c == '}') {
        pos[0]++;
        break;
      } else {
        throw new IllegalArgumentException("Expected ',' or '}' at pos " + pos[0]);
      }
    }
    return map;
  }

  private List<Object> parseArray(String json, int[] pos, int depth) {
    List<Object> list = new ArrayList<>();
    pos[0]++; // skip '['
    skipWhitespace(json, pos);
    if (charAtOrFail(json, pos) == ']') {
      pos[0]++;
      return list;
    }
    while (true) {
      Object value = parseValue(json, pos, depth);
      list.add(value);
      skipWhitespace(json, pos);
      char c = charAtOrFail(json, pos);
      if (c == ',') {
        pos[0]++;
      } else if (c == ']') {
        pos[0]++;
        break;
      } else {
        throw new IllegalArgumentException("Expected ',' or ']' at pos " + pos[0]);
      }
    }
    return list;
  }

  private String parseString(String json, int[] pos) {
    expect(json, pos, '"');
    StringBuilder sb = new StringBuilder();
    while (pos[0] < json.length()) {
      char c = json.charAt(pos[0]++);
      if (c == '"') {
        return sb.toString();
      }
      if (c == '\\') {
          if (pos[0] >= json.length()) {
              break;
          }
        char esc = json.charAt(pos[0]++);
        switch (esc) {
          case '"':
            sb.append('"');
            break;
          case '\\':
            sb.append('\\');
            break;
          case '/':
            sb.append('/');
            break;
          case 'b':
            sb.append('\b');
            break;
          case 'f':
            sb.append('\f');
            break;
          case 'n':
            sb.append('\n');
            break;
          case 'r':
            sb.append('\r');
            break;
          case 't':
            sb.append('\t');
            break;
          case 'u':
            sb.append(parseUnicodeEscape(json, pos));
            break;
          default:
            // Accepting it as the bare character would turn "thr\eads" into a valid command
            throw new IllegalArgumentException(
                "Invalid escape '\\" + esc + "' at pos " + (pos[0] - 1));
        }
      } else if (c < 0x20) {
        throw new IllegalArgumentException("Unescaped control character 0x"
            + Integer.toHexString(c) + " at pos " + (pos[0] - 1));
      } else {
        sb.append(c);
      }
    }
    throw new IllegalArgumentException("Unterminated string");
  }

  /**
   * Exactly four ASCII hex digits. Integer.parseInt alone would also take a sign, reading the
   * digits "-001" as U+FFFF, and Character.digit alone accepts non-ASCII digits.
   */
  private char parseUnicodeEscape(String json, int[] pos) {
    if (pos[0] + 4 > json.length()) {
      throw new IllegalArgumentException("Truncated \\u escape at pos " + pos[0]);
    }
    int code = 0;
    for (int i = 0; i < 4; i++) {
      char ch = json.charAt(pos[0] + i);
      int digit = ch < 0x80 ? Character.digit(ch, 16) : -1;
      if (digit < 0) {
        throw new IllegalArgumentException("Invalid \\u escape at pos " + pos[0]);
      }
      code = code * 16 + digit;
    }
    pos[0] += 4;
    return (char) code;
  }

  private Number parseNumber(String json, int[] pos) {
    int start = pos[0];
      if (json.charAt(pos[0]) == '-') {
          pos[0]++;
      }
    while (pos[0] < json.length() && json.charAt(pos[0]) >= '0' && json.charAt(pos[0]) <= '9') {
      pos[0]++;
    }
    boolean isFloat = false;
    if (pos[0] < json.length() && json.charAt(pos[0]) == '.') {
      isFloat = true;
      do {
        pos[0]++;
      } while (pos[0] < json.length() && json.charAt(pos[0]) >= '0' && json.charAt(pos[0]) <= '9');
    }
    if (pos[0] < json.length() && (json.charAt(pos[0]) == 'e' || json.charAt(pos[0]) == 'E')) {
      isFloat = true;
      pos[0]++;
        if (pos[0] < json.length() && (json.charAt(pos[0]) == '+' || json.charAt(pos[0]) == '-')) {
            pos[0]++;
        }
      while (pos[0] < json.length() && json.charAt(pos[0]) >= '0' && json.charAt(pos[0]) <= '9') {
        pos[0]++;
      }
    }
    String numStr = json.substring(start, pos[0]);
    if (isFloat) {
      double parsed = Double.parseDouble(numStr);
      // Reject Infinity/NaN here rather than at serialization time: they cannot be
      // represented in JSON, and letting them through produces an illegal response body
      if (!Double.isFinite(parsed)) {
        throw new IllegalArgumentException("Number out of range: " + numStr);
      }
      return parsed;
    }
    long val = Long.parseLong(numStr);
    if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
      return (int) val;
    }
    return val;
  }

  private Object parseLiteral(String json, int[] pos, String literal, Object value) {
    if (json.startsWith(literal, pos[0])) {
      pos[0] += literal.length();
      return value;
    }
    throw new IllegalArgumentException("Expected '" + literal + "' at pos " + pos[0]);
  }

  private void skipWhitespace(String json, int[] pos) {
    while (pos[0] < json.length() && Character.isWhitespace(json.charAt(pos[0]))) {
      pos[0]++;
    }
  }

  /**
   * Read the current char, failing with a readable message instead of
   * StringIndexOutOfBoundsException when the input is truncated.
   */
  private char charAtOrFail(String json, int[] pos) {
    if (pos[0] >= json.length()) {
      throw new IllegalArgumentException("Unexpected end of JSON at pos " + pos[0]);
    }
    return json.charAt(pos[0]);
  }

  private void expect(String json, int[] pos, char expected) {
    if (pos[0] >= json.length() || json.charAt(pos[0]) != expected) {
      throw new IllegalArgumentException("Expected '" + expected + "' at pos " + pos[0]);
    }
    pos[0]++;
  }

  // ==================== Serialization ====================

  public String serialize(Map<String, Object> map) {
    StringBuilder sb = new StringBuilder();
    serializeMap(sb, map);
    return sb.toString();
  }

  private void serializeValue(StringBuilder stringBuilder, Object value) {
    if (value == null) {
      stringBuilder.append("null");
    } else if (value instanceof String) {
      serializeString(stringBuilder, (String) value);
    } else if (value instanceof Number) {
      if (value instanceof Double) {
        double d = (Double) value;
        if (d == Math.floor(d) && !Double.isInfinite(d)) {
          stringBuilder.append((long) d);
        } else if (!Double.isFinite(d)) {
          // Infinity/NaN are not valid JSON numbers; emit them as strings so the
          // response stays parseable by the caller
          serializeString(stringBuilder, String.valueOf(d));
        } else {
          stringBuilder.append(d);
        }
      } else {
        stringBuilder.append(value);
      }
    } else if (value instanceof Boolean) {
      stringBuilder.append(value);
    } else if (value instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> map = (Map<String, Object>) value;
      serializeMap(stringBuilder, map);
    } else if (value instanceof List) {
      @SuppressWarnings("unchecked")
      List<Object> list = (List<Object>) value;
      serializeList(stringBuilder, list);
    } else {
      serializeString(stringBuilder, value.toString());
    }
  }

  private void serializeString(StringBuilder stringBuilder, String str) {
    stringBuilder.append('"');
    for (int i = 0; i < str.length(); i++) {
      char c = str.charAt(i);
      switch (c) {
        case '"':
          stringBuilder.append("\\\"");
          break;
        case '\\':
          stringBuilder.append("\\\\");
          break;
        case '\b':
          stringBuilder.append("\\b");
          break;
        case '\f':
          stringBuilder.append("\\f");
          break;
        case '\n':
          stringBuilder.append("\\n");
          break;
        case '\r':
          stringBuilder.append("\\r");
          break;
        case '\t':
          stringBuilder.append("\\t");
          break;
        default:
          if (c < 0x20) {
            stringBuilder.append(String.format("\\u%04x", (int) c));
          } else {
            stringBuilder.append(c);
          }
      }
    }
    stringBuilder.append('"');
  }

  private void serializeMap(StringBuilder stringBuilder, Map<String, Object> map) {
    stringBuilder.append('{');
    boolean first = true;
    for (Map.Entry<String, Object> entry : map.entrySet()) {
        if (!first) {
            stringBuilder.append(',');
        }
      first = false;
      serializeString(stringBuilder, entry.getKey());
      stringBuilder.append(':');
      serializeValue(stringBuilder, entry.getValue());
    }
    stringBuilder.append('}');
  }

  private void serializeList(StringBuilder stringBuilder, List<Object> list) {
    stringBuilder.append('[');
    boolean first = true;
    for (Object item : list) {
        if (!first) {
            stringBuilder.append(',');
        }
      first = false;
      serializeValue(stringBuilder, item);
    }
    stringBuilder.append(']');
  }
}
