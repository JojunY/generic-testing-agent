package com.gta.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Pins a confirmed product defect (plan §5.3 item 7) with strict-xfail semantics:
 * <ul>
 *   <li>fails with the same fingerprint → reported as aborted, the build stays green;</li>
 *   <li>passes → the test FAILS with "defect fixed, remove @KnownDefect";</li>
 *   <li>fails for another reason → the test FAILS.</li>
 * </ul>
 * Only gtaw adds this annotation, after a human confirmed the defect.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
@ExtendWith(KnownDefectExtension.class)
public @interface KnownDefect {
    /** The finding or scenario id, e.g. {@code order.create/f02}. */
    String id();

    /** Failure fingerprint printed by gtaw ({@link Fingerprint#of}); empty accepts any failure. */
    String failure() default "";
}
