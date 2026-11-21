# fasthtmlparser for Java

[![CI](https://github.com/romanti3172/fasthtmlparser-java/actions/workflows/ci.yml/badge.svg)](https://github.com/romanti3172/fasthtmlparser-java/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.romanti3172/fasthtmlparser)](https://central.sonatype.com/artifact/io.github.romanti3172/fasthtmlparser)

**Website:** [fasthtmlparser.com](https://fasthtmlparser.com): try the parser on any page, documentation and every language.

Java port of [z3l.FastHtmlParser](https://github.com/romanti3172/z3l.FastHtmlParser): a fast HTML / XML parser for
scraping, with **XPath 1.0** and **CSS selectors**. It is built for extracting data, not for building a DOM: a page
becomes a flat list of tags that hold only positions in the source. Text, attributes and entities are read when you
ask for them, and the values are substrings of the page.

The whole API is ported: the parser, attributes, HTML5 entities, visible text and text with line breaks,
`find` / `findAll`, navigation, links, XPath 1.0, CSS selectors and the one-call shortcuts. There are also ports for
[Go](https://github.com/romanti3172/fasthtmlparser), [JavaScript](https://github.com/romanti3172/fasthtmlparser-js),
[Rust](https://github.com/romanti3172/fasthtmlparser-rs) and [Python](https://github.com/romanti3172/fasthtmlparser-py).

```xml
<dependency>
  <groupId>io.github.romanti3172</groupId>
  <artifactId>fasthtmlparser</artifactId>
  <version>0.1.2</version>
</dependency>
```

```kotlin
implementation("io.github.romanti3172:fasthtmlparser:0.1.2")
```

No dependencies. Java 17 or later. Module name `io.github.romanti3172.fasthtmlparser`.

```java
import io.github.romanti3172.fasthtmlparser.*;

HtmlDocument doc = HtmlDocument.parse(page, "https://example.com/catalog/"); // or parse(page); byte[] is read as UTF-8

// CSS selectors, as querySelectorAll / querySelector / matches
for (HtmlNode pod : doc.css("article.product_pod")) {
    String title = pod.cssFirst("h3 a").attr("title");
    String price = pod.cssFirst("p.price_color").text();
}
doc.cssFirst("ul#menu > li:last-child a").attr("href");
doc.css("tr:has(> td.price)");
card.css("> span"); // the children of card

// XPath 1.0
doc.xpath("//div[@class='card']//span[@class='price']");
doc.xpathValues("//a[contains(@href, '/product/')]/@href");
doc.xpathValue("//dt[.='Price']/following-sibling::dd[1]");
doc.xpathValue("count(//table//tr)"); // "12"

// compile once, reuse on any number of pages and threads
XPathQuery titles = XPathQuery.compile("//article//h3/a/@title");
titles.values(doc);

// search by name, class, id, attribute, text
for (HtmlNode pod : doc.findAll(Query.by(Tag.ARTICLE).hasClass("product_pod"))) {
    pod.find(Query.by(Tag.P).hasClass("price_color")).text();
}

// one-call shortcuts (findByName*, findAllByName*, isName*, parentByName, nextSiblingByName, attribute*, ...)
HtmlNode pod = doc.findByNameClass(Tag.ARTICLE, "product_pod");
pod.findByNameItemprop(Tag.SPAN, "price").text();
doc.findByNameText(Tag.DT, "Price").nextSiblingByName(Tag.DD).text();
doc.findByNameClass("my-widget", "x"); // any name works as a string

// reading a tag
tag.text();             // visible text, entities decoded
tag.textLineBreaks();   // <br> and block elements → new line, <p> and headings → paragraph
tag.attr(Attr.DATA_SRC); // decoded value; attrRaw(): as written; hasAttr(), attrs()
tag.href();             // absolute URL, resolved against the page URL; src() for src
tag.hrefMailTo();       // the address of a mailto: link; hrefTelephone() for tel:
tag.source();           // the opening tag without '<' and '>'
tag.name(); tag.tag(); tag.depth(); tag.index(); tag.start(); tag.end();

// navigation
tag.parent(); tag.children(); tag.nextSibling(); tag.previousSibling(); tag.next(); tag.previous();
tag.parentMatching(Query.by(Tag.TABLE));
tag.textAfterUntil(Query.by(Tag.BR)); // the source between the tag and the next <br>
```

Queries: `Query.by(Tag.DIV)`, `Query.by("custom-tag")` or `Query.any()`, then `.hasClass`, `.id`, `.attr`, `.text`,
`.idStartsWith`, `.textStartsWith`, `.body`, `.bodyStartsWith`, `.source`. Queries are immutable. `Tag` (an enum) and
`Attr` (string constants) cover about 250 tag and 230 attribute names (HTML, SVG, MathML, RSS, Atom, sitemap); any
other name works as a string. Every method that takes a tag name takes a `Tag` or a `String`.

A missing tag is `null`, as in jsoup; a missing value is `""`. The string methods (`css`, `xpath`, ...) compile and
cache the query and throw `CssSelectorException` / `XPathException` (both `IllegalArgumentException`s) on an invalid
one; `xpath()` on an expression that gives a number or string throws an `XPathException` (use `xpathValue`).
`Html.toText`, `Html.toTextLineBreaks` and `Html.decode` work on HTML fragments.

Documents are read-only and can be shared between threads; parsing on many threads at once is safe (the parser keeps
its buffers per thread). `CssQuery` and `XPathQuery` are immutable.

CSS: type, `*`, `#id`, `.class`, attribute selectors with all operators and the `i` / `s` flags, the four combinators,
selector lists, `:first-child`, `:last-child`, `:only-child`, `:*-of-type`, `:nth-child(An+B [of S])` and its
relatives, `:not()`, `:is()`, `:where()`, `:has()`, `:empty`, `:root`, `:scope`, `:link`, `:checked`, `:disabled`,
`:enabled`, `:required`, `:optional`, `:contains("text")`. XPath: all axes except namespace, predicates, `|`, the
operators and all XPath 1.0 functions, plus `ends-with()`, `lower-case()`, `upper-case()`. HTML rules apply: names are
case-insensitive, the string-value of an element is its visible text, attribute values and text are decoded.

Parsing follows what a browser makes of the page where it matters for scraping: HTML5 optional end tags (`<li>`, `<p>`,
`<td>`, `<tr>`, `<option>` without end tags give the same elements as a browser), raw text in `<script>`, `<style>`,
`<textarea>`, `<title>`, comments as HTML5 reads them, void elements, nesting flattened past 512 levels, all 2125
named entities, CR LF read as LF. XML documents (RSS, Atom, sitemaps) are parsed by XML rules. Not done: full HTML5
tree construction (no implied `<tbody>` or `<html>`), and documents are read-only.

## Same results as the .NET version

The conformance tests compare the port with z3l.FastHtmlParser 1.4.1 (.NET) on 121 pages: the test fixtures, the
broken-markup snippets of the .NET cross-check tests, extra edge cases (entities, raw text, comments, XML, custom
elements, 600-deep nesting, Unicode whitespace, links of every kind, line breaks), and every fixture with
`</li> </p> </td> </tr>` left out, plus three copies of each with random end tags dropped, names upper-cased and the
page cut off, and copies saved with CR LF and with a lone CR.

- `ConformanceTest`, every tag (over 15,000): names, parents, depths, positions, visible text, text with line
  breaks, 10 attribute values, the opening tag source, next / previous tag and sibling, children, the resolved href
  and src, mailto: and tel: values, and the text after the tag must be identical.
- The same class, 569 queries: every string of the .NET XPath and CSS tests plus extra ones for the axes, functions and
  selectors. Whether each one compiles must be the same (119 are invalid); the 228 XPath and 222 CSS queries that
  compile are run on every page, on the document and from three tags of the page, and every CSS selector is also
  matched against every tag. Selected tags and values must be identical.

All pass, with one documented exception: on the page nested 600 levels deep, where the .NET result depends on the
order of an unstable sort.

Positions (`start()`, `end()`) are UTF-16 indexes into the source, as in Java strings and in .NET. Intended
differences: `textLineBreaks()` joins lines with `"\n"` on every OS (.NET uses `Environment.NewLine`), and a missing
value is `""` where .NET returns null. Links resolved against the page are written as .NET's `System.Uri` writes them
(host in lower case, no default port, readable escapes); absolute links are returned as written.

The expected output in `testdata/conformance` is produced by `tools/conformance`, a small .NET program that references
the z3l.FastHtmlParser package; it is shared with the other ports.

## Benchmarks

`cd bench && mvn -q compile exec:java` (Java 21, AMD Ryzen 7 3800X, Windows; median time per call after a one-second
warm-up). validator.nu builds a W3C DOM with the HTML5 tree construction algorithm. Before timing, the benchmark
checks that every library extracts exactly the same records.

Parse a page:

| Page | fasthtmlparser | jsoup | Jericho | HtmlCleaner | validator.nu |
|---|---:|---:|---:|---:|---:|
| quotes, 11 KB | **20 μs** | 132 μs | 105 μs | 940 μs | 1.03 ms |
| laptops, 38 KB | **62 μs** | 474 μs | 419 μs | 3.00 ms | 3.11 ms |
| books, 51 KB | **73 μs** | 496 μs | 387 μs | 3.83 ms | 3.88 ms |
| books x10, 510 KB | **677 μs** | 5.22 ms | 3.90 ms | 36.08 ms | 37.01 ms |
| books without `</li>` `</p>` | **69 μs** | 466 μs | 372 μs | 4.03 ms | 3.74 ms |

Scrape a page: parse and extract the records. Books: 20 × title, price, rating; quotes: 10 × text, author, tags.

| Task | fasthtmlparser `find` | CSS | XPath | jsoup CSS | jsoup `selectXpath` | jsoup + javax.xml XPath | HtmlCleaner |
|---|---:|---:|---:|---:|---:|---:|---:|
| books | **96 μs** | 107 μs | 118 μs | 707 μs | 49.10 ms | 11.90 ms | 3.88 ms |
| quotes | **33 μs** | 35 μs | | 195 μs | | | |

The full output is in `bench-results.md`.

## Development

```
mvn verify                                               # tests, jar, sources and javadoc
java tools/Gen.java ../z3l.FastHtmlParser/src/z3l.FastHtmlParser   # tag, attribute and entity tables, shortcuts
cd bench && mvn -q compile exec:java                     # benchmarks (after mvn install -DskipTests)
```

Releases: bump `version` in `pom.xml`, commit, and push a tag `v<version>`. The `Publish` workflow checks that the
version is new, runs the tests again, signs the artifacts and publishes them to Maven Central.

- `Parser.java`: the scanner and the HTML5 optional-end-tag rules (port of `ParsingCore.cs`)
- `Attrs.java`, `Decode.java`, `Text.java`, `TextLines.java`: attributes, character references, visible text, text with line breaks
- `HtmlDocument.java`, `HtmlNode.java`, `Query.java`: search, navigation and the generated shortcuts; `Links.java`: href / src
- `CssQuery.java`: CSS selectors; `XPathQuery.java`: XPath 1.0
- `Tag.java`, `Attr.java`, `Entities.java`: generated by `tools/Gen.java` from the C# sources
- `bench/`: benchmarks in their own Maven project, so the library has no dependencies

## License

Source-available, not OSI open source: free for individuals and companies with annual revenue below USD 1,000,000
and fewer than 100 people; a one-time commercial license above that. See [LICENSE.md](LICENSE.md).
