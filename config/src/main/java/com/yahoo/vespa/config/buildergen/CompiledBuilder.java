// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.buildergen;

import com.yahoo.config.ConfigInstance;

/**
 * Represents a builder that can be instantiated.
 *
 * @author lulf
 * @since 5.2
 */
public interface CompiledBuilder {
    <BUILDER extends ConfigInstance.Builder> BUILDER newInstance();
}
