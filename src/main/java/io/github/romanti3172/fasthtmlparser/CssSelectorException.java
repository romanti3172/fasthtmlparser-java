package io.github.romanti3172.fasthtmlparser;

/** Thrown by {@link CssQuery#compile(String)} (and the methods that take a selector) for an invalid CSS selector. */
public class CssSelectorException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /** An invalid selector with a message that names it and the position of the problem. */
    public CssSelectorException(String message) {
        super(message);
    }
}
