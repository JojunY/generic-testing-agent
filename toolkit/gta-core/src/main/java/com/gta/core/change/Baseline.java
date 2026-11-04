package com.gta.core.change;

import java.util.LinkedHashMap;
import java.util.Map;

/** What the repository looked like when a brief was issued: HEAD and the content hash of every file already dirty. */
public class Baseline {
    public String head;
    public Map<String, String> dirty = new LinkedHashMap<>();
}
