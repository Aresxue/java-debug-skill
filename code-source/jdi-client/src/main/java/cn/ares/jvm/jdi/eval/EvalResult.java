package cn.ares.jvm.jdi.eval;

import java.util.List;
import java.util.Map;

/**
 * Outcome of one eval: the formatted value and its type, or the error that stopped it.
 */
public class EvalResult {

  String value;
  String type;
  String error;

  /**
   * Error code when it is more specific than EvalError
   */
  String errorCode;
  List<Map<String, Object>> candidateList;

  public String getValue() {
    return value;
  }

  public String getType() {
    return type;
  }

  public String getError() {
    return error;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public List<Map<String, Object>> getCandidateList() {
    return candidateList;
  }
}
