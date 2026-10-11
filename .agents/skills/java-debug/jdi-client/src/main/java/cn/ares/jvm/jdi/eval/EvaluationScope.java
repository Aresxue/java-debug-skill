package cn.ares.jvm.jdi.eval;

import com.sun.jdi.ObjectReference;
import com.sun.jdi.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * The objects one evaluation has to keep alive until it has finished reading them. JDI leaves every
 * object it hands out collectable, and a suspended thread only stops its own execution: a value an
 * invoke produced, a string literal eval mirrored, or an object a chain is still stepping through
 * need not be reachable in the target at all, so the target's GC may take any of them between two
 * commands. The string literals, the root value, the chain intermediates and the invoke results are
 * held here from the moment eval first sees them until the whole expression has been formatted,
 * which is what closes the windows between those commands.
 *
 * <p>What is deliberately not held is the field values and array elements a batch read returns,
 * and
 * a static field read. A disableCollection is itself a command, so holding a whole batch trades one
 * window for many and was measured to make collection failures far more frequent under heavy GC;
 * those values are read once and formatted in place instead.
 *
 * <p>disableCollection is counted per object by JDI itself, so the same object may be held more
 * than once as long as it is released as often; releaseAll does exactly that.
 */
class EvaluationScope {

  private final List<ObjectReference> heldList = new ArrayList<>();

  /**
   * Keep the value, when it is an object, until releaseAll. A primitive cannot be collected and is
   * returned untouched
   */
  Value hold(Value value) {
    if (value instanceof ObjectReference) {
      ObjectReference object = (ObjectReference) value;
      object.disableCollection();
      heldList.add(object);
    }
    return value;
  }

  void releaseAll() {
    for (ObjectReference held : heldList) {
      try {
        held.enableCollection();
      } catch (RuntimeException ignored) {
        // Collected or gone with the session: either way nothing is held any more
      }
    }
    heldList.clear();
  }
}
