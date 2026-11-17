every scraper returns the same records

Java 21.0.13 (OpenJDK 64-Bit Server VM); median time per call

Parse a page:

| Page | fasthtmlparser | jsoup | HtmlCleaner | validator.nu (DOM) | Jericho |
|---|---:|---:|---:|---:|---:|
| quotes, 11 KB | 20 μs | 132 μs | 940 μs | 1.03 ms | 105 μs |
| laptops, 38 KB | 62 μs | 474 μs | 3.00 ms | 3.11 ms | 419 μs |
| books, 51 KB | 73 μs | 496 μs | 3.83 ms | 3.88 ms | 387 μs |
| books x10, 510 KB | 677 μs | 5.22 ms | 36.08 ms | 37.01 ms | 3.90 ms |
| books without </li> </p> | 69 μs | 466 μs | 4.03 ms | 3.74 ms | 372 μs |

Scrape a page (parse + extract the records):

| Task | Time |
|---|---:|
| books, fasthtmlparser find | 96 μs |
| books, fasthtmlparser CSS | 107 μs |
| books, fasthtmlparser XPath | 118 μs |
| books, jsoup CSS | 707 μs |
| books, jsoup XPath | 49.10 ms |
| books, jsoup + javax.xml XPath | 11.90 ms |
| books, HtmlCleaner | 3.88 ms |
| quotes, fasthtmlparser find | 33 μs |
| quotes, fasthtmlparser CSS | 35 μs |
| quotes, jsoup CSS | 195 μs |
