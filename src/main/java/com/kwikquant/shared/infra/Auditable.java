package com.kwikquant.shared.infra;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {
    String action();

    String targetType();

    /** SpEL expression resolved against method arguments, e.g. {@code "#orderId"} or {@code "#order.id()"}. */
    String targetId() default "";
}
