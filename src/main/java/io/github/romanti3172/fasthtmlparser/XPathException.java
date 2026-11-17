package io.github.romanti3172.fasthtmlparser;

/**
 * Thrown by {@link XPathQuery#compile(String)} (and the methods that take an expression) for an invalid XPath
 * expression, and by the methods that return tags for an expression that gives a number, string or boolean.
 */
public class XPathException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /** An invalid or unusable expression with a message that names it. */
    public XPathException(String message) {
        super(message);
    }
}
