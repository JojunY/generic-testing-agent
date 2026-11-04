package com.gta.core.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/** YAML reading (capability registry, platform mappings, frontmatter). gta never writes YAML through Jackson. */
public final class Yaml {
    public static final ObjectMapper MAPPER =
            new ObjectMapper(new YAMLFactory()).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Yaml() {}
}
