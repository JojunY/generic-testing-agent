package com.gta.core.spec;

import java.util.Optional;

/**
 * A code reference in a spec ({@code method:com.acme.order.api.OrderController#create}, {@code bean:com.acme.Clock},
 * {@code config:order.max-items}), parsed in one place.
 *
 * @param kind   {@code method}, {@code bean}, {@code config} or another prefix
 * @param target the class name for {@code method}/{@code bean}, the key for {@code config}
 * @param member the part after {@code #}, or null
 */
public record Ref(String kind, String target, String member) {

    /** Empty when the text has no {@code kind:} prefix or no target. */
    public static Optional<Ref> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        int colon = text.indexOf(':');
        if (colon <= 0 || colon == text.length() - 1) {
            return Optional.empty();
        }
        String kind = text.substring(0, colon);
        String rest = text.substring(colon + 1);
        int hash = rest.indexOf('#');
        String target = hash < 0 ? rest : rest.substring(0, hash);
        String member = hash < 0 || hash == rest.length() - 1 ? null : rest.substring(hash + 1);
        return target.isEmpty() ? Optional.empty() : Optional.of(new Ref(kind, target, member));
    }

    /** Whether the target is a Java class ({@code method:} and {@code bean:} refs). */
    public boolean isCode() {
        return "method".equals(kind) || "bean".equals(kind);
    }

    /** Package of the target class; empty for the default package. */
    public String packageName() {
        int dot = target.lastIndexOf('.');
        return dot < 0 ? "" : target.substring(0, dot);
    }

    public String simpleName() {
        return target.substring(target.lastIndexOf('.') + 1);
    }

    /** Source file of the target class, relative to a source root ({@code com/acme/Order.java}). */
    public String sourcePath() {
        return target.replace('.', '/') + ".java";
    }

    /** {@code fqcn#member}, as {@code JavaIndex} keys methods. */
    public String classAndMember() {
        return member == null ? target : target + "#" + member;
    }
}
