package io.github.romanti3172.fasthtmlparser;

import java.util.ArrayList;
import java.util.List;

/** Helpers for HTML fragments and lists of tags. */
public final class Html {
    private Html() {
    }

    /** The visible text of an HTML fragment, as {@link HtmlNode#text()} gives it for a tag's content. */
    public static String toText(String html) {
        return Text.clearTags(html);
    }

    /** The text of an HTML fragment with the visible line structure kept, as {@link HtmlNode#textLineBreaks()}. */
    public static String toTextLineBreaks(String html) {
        return TextLines.clearTagsLineBreaks(html);
    }

    /** Decodes the HTML character references in text ({@code &amp;amp;}, {@code &amp;#169;}, ...); CR LF and a lone CR become LF. */
    public static String decode(String text) {
        return Decode.decode(text, false);
    }

    /** The tags of the list at the level of the first one (the outermost tags of a findAll result). */
    public static List<HtmlNode> firstLevel(List<HtmlNode> nodes) {
        return level(nodes, null);
    }

    /** The tags of the list with the name, at the level of the first tag of the list. */
    public static List<HtmlNode> firstLevelByName(List<HtmlNode> nodes, Tag name) {
        return level(nodes, Query.by(name));
    }

    /** The tags of the list with the name, at the level of the first tag of the list. */
    public static List<HtmlNode> firstLevelByName(List<HtmlNode> nodes, String name) {
        return level(nodes, Query.by(name));
    }

    /** The tags of the list with the name and the class, at the level of the first tag of the list. */
    public static List<HtmlNode> firstLevelByNameClass(List<HtmlNode> nodes, Tag name, String cls) {
        return level(nodes, Query.by(name).hasClass(cls));
    }

    /** The tags of the list with the name and the class, at the level of the first tag of the list. */
    public static List<HtmlNode> firstLevelByNameClass(List<HtmlNode> nodes, String name, String cls) {
        return level(nodes, Query.by(name).hasClass(cls));
    }

    private static List<HtmlNode> level(List<HtmlNode> nodes, Query q) {
        List<HtmlNode> out = new ArrayList<>();
        if (nodes.isEmpty()) return out;
        int depth = nodes.get(0).depth();
        for (HtmlNode n : nodes) if (n.depth() == depth && (q == null || q.matches(n))) out.add(n);
        return out;
    }
}
