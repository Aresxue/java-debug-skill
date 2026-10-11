package cn.ares.jvm.jdi.eval;

import java.util.List;
import java.util.Map;

/**
 * The class an expression names cannot be tied to one loaded copy.
 */
class ClassChoiceException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  final String errorCode;
  final transient List<Map<String, Object>> candidateList;

  ClassChoiceException(String errorCode, String message, List<Map<String, Object>> candidateList) {
    super(message);
    this.errorCode = errorCode;
    this.candidateList = candidateList;
  }
}
