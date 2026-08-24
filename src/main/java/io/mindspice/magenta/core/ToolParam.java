package io.mindspice.magenta.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Describes a Magenta tool method parameter. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {
    /**
     * JSON property name for this parameter. Required when the declaring class
     * was compiled without {@code -parameters}.
     */
    String name() default "";

    String description() default "";
    boolean required() default true;
}
