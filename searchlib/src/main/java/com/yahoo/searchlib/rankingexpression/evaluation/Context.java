// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchlib.rankingexpression.evaluation;

import com.yahoo.searchlib.rankingexpression.rule.Arguments;
import com.yahoo.searchlib.rankingexpression.rule.ExpressionNode;
import com.yahoo.tensor.evaluation.EvaluationContext;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * The context providing value bindings for an expression evaluation.
 *
 * @author bratseth
 */
public abstract class Context implements EvaluationContext {

    /**
     * <p>Returns the value of a simple variable name.</p>
     *
     * @param name The name of the variable whose value to return.
     * @return The value of the named variable.
     */
    public abstract Value get(String name);

    /**
     * <p>Returns the value of a <i>structured variable</i> on the form
     * <code>name(argument*)(.output)?</code>, where <i>argument</i> is any
     * string.  This may be used to implement more advanced variables whose
     * values are calculated at runtime from arguments.  Supporting this in a
     * context is optional. 
     * 
     * <p>This default implementation generates a name on the form
     * <code>name(argument1, argument2, ...argumentN).output</code>.
     * If there are no arguments the parenthesis are omitted.
     * If there is no output, the dot is omitted.</p>
     *
     * @param name      The name of this variable.
     * @param arguments The parsed arguments as given in the textual expression.
     * @param output    The name of the value to output (to enable one named
     *                  calculation to output several), or null to output the
     *                  "main" (or only) value.
     */
    public Value get(String name, Arguments arguments, String output) {
        if (arguments != null && arguments.expressions().size() > 0)
            name = name + "(" + arguments.expressions().stream().map(ExpressionNode::toString).collect(Collectors.joining(",")) + ")";
        if (output !=null)
            name = name + "." + output;
        return get(name);
    }

    /**
     * <p>Lookup by index rather than name. This is supported by some optimized
     * context subclasses.  This default implementation throws
     * UnsupportedOperationException.</p>
     *
     * @param index The index of the variable whose value to return.
     * @return The value of the indexed variable.
     */
    public Value get(int index) {
        throw new UnsupportedOperationException(this + " does not support variable lookup by index");
    }

    /**
     * <p>Lookup by index rather than name directly to a double. This is supported by some optimized
     * context subclasses.  This default implementation throws
     * UnsupportedOperationException.</p>
     *
     * @param index The index of the variable whose value to return.
     * @return The value of the indexed variable.
     */
    public double getDouble(int index) {
        throw new UnsupportedOperationException(this + " does not support variable lookup by index");
    }

    /**
     * Same as put(name,DoubleValue.frozen(value))
     */
    public final void put(String name, double value) {
        put(name, DoubleValue.frozen(value));
    }

    /**
     * <p>Sets a value to this, or throws an UnsupportedOperationException if
     * this is not supported. This default implementation does the latter.</p>     *
     *
     * @param name  The name of the variable to set.
     * @param value the value to set. Ownership of this value is transferred to this - if it is mutable
     *              (not frozen) it may be modified during execution
     * @since 5.1.5
     */
    public void put(String name, Value value) {
        throw new UnsupportedOperationException(this + " does not support variable assignment");
    }

    /**
     * <p>Returns all the names available in this, or throws an
     * UnsupportedOperationException if this operation is not supported. This
     * default implementation does the latter.</p>
     *
     * @return The set of all variable names.
     */
    public Set<String> names() {
        throw new UnsupportedOperationException(this + " does not support return a list of its names");
    }

}
