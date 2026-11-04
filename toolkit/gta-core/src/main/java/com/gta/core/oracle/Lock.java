package com.gta.core.oracle;

import java.util.ArrayList;
import java.util.List;

/**
 * The expected values locked for one item (plan §5.4): the THEN tokens, the assertion statements that hold them, and a
 * hash of the watched inputs.
 */
public class Lock {
    public List<String> tokens = new ArrayList<>();
    public List<String> statements = new ArrayList<>();
    public String fingerprint;
    public String watched;
    public String reachedInRun;
}
