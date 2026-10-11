package cn.ares.jvm.jdi.breakpoint;

import java.util.List;
import java.util.Map;

/**
 * Outcome of a set-breakpoint: the id handed out, whether it is still waiting for its class, or
 * why it could not be installed, plus every location it was armed on.
 */
public class BreakpointResult {

    String bpId;
    boolean deferred;
    String error;
    int installedCount;
    List<Map<String, Object>> candidateList;

    public String getBpId() {
        return bpId;
    }

    public boolean isDeferred() {
        return deferred;
    }

    public String getError() {
        return error;
    }

    public int getInstalledCount() {
        return installedCount;
    }

    public List<Map<String, Object>> getCandidateList() {
        return candidateList;
    }
}
