# Parsers and Streams

The Parser and Stream APIs provide a convenient way to write programs that

* determine whether a string matches an expected pattern, and if so extract elements of
  it (as might be done with a regular expression in other programming languages); or
* decode a structured file format (text, binary, or some combination).

As with regular expression libraries, to use the APIs the program first constructs a
value that represents the expected structure of the string or file (the *parser*),
and then calls a function that attempts to match the parser against the next segment of
the string or file.  Unlike regular expression libraries, standard Retrospect
expressions are used to describe the structure, rather than a separate language encoded
as a string.

## Streams

In order to handle different use cases, a parser can be matched against

* a sequence of characters (usually from a string), or
* a sequence of bytes interpreted as UTF-8 encoded characters, or
* a sequence of uninterpreted bytes (i.e. integers in the range 0 to 255).

A sequence of uninterpreted bytes is treated as a restricted sequence of characters,
only capable of representing the first 256 Unicode code points.  A parser that only matches
characters outside of this range (e.g. "⭐") will error if applied to an uninterpreted
byte sequence.

A *stream* represents one of these sequences and a position in that sequence; streams can
be created from strings or files:

* `stream(string)` returns a stream of the characters in the given string
* `streamUtf8(file)` returns a stream of the characters in a UTF-8 encoded file
* `streamBytes(file)` returns a stream of the bytes in an uninterpreted file

Newly-created streams are positioned at the beginning of the input; most parsing operations
advance the stream's position to immediately after the characters that were matched.

## Parsers

A parser determines which sequences of characters are acceptable, and the array
of output values that are returned from an acceptable input.  The simplest parsers
return no outputs (i.e. an empty array):

* a string (e.g. `"abc"`): matches only that sequence of characters
* `anyChar`: matches any single character
* `oneOf(s)`: matches a single character, which must be one of the characters in
  the string `s`
* `noneOf(s)`: matches a single character, which must *not* be one of the characters
  in the string `s`
* `atEnd`: matches only if there are no more characters to be matched
* `empty`: equivalent to `""`, i.e. always matches and consumes no characters
* `oneOfRange(firstChar, lastChar)`: matches a single character which must be in the
  specified range
* `digit`, `blank`, `lineEnd`, `alpha`, `word`: match a single character of the specified class
  (equivalent to `oneOfRange("0", "9")`, `oneOf(" \t")`, `oneOf("\n\r\u0085\u2028\u2029")`,
  `choose(oneOfRange("A", "Z"), oneOfRange("a", "z"))` and `choose([alpha, digit, "_"])`
  respectively)
* `newLine`: matches `"\r\n"` or a single line separator character (equivalent to
  `choose("\r\n", lineEnd)`)

Other parsers return a single output:

* `parseInt`: matches one or more digits, optionally preceded by a sign; returns the number
  represented by those characters
* `parseNumber`: matches one or more characters that represent a number in decimal
  notation (with an optional decimal exponent); returns the number

More complex parsers may be constructed from other parsers:

* `parser1 + parser2`: matches `parser1` followed by `parser2`; returns the concatenation
  of their outputs.  For example, `oneOf("xy") + digit` matches `"x"` or `"y"` followed by
  a single digit (and returns no outputs).
* `matched(parser)`: matches the same inputs as `parser`; returns the same outputs
  as `parser` followed by the input string that it matched.  For example,
  `matched(oneOf("xy") + digit)` would return `["x3"]` if the input began with `"x3"`.
* `number * parser`: matches `parser` the specified number of times (`number` must be a
  non-negative integer) and returns the concatenation of its outputs.  For example,
  `matched(6 * digit)` matches six consecutive digits and returns a single output
  (e.g. ["314159"]), while `6 * matched(digit)` matches the same inputs but returns six
  outputs (e.g. ["3", "1", "4", "1", "5", "9"]).
* `range * parser`: matches `parser` as many times as possible within the given range.
  For example, `4..6 * digit` matches between four and six digits, while `.. * blank`
  matches any number of horizontal whitespace characters.
* `choose(parser1, parser2)`: if `parser1` matches, returns its outputs; otherwise matches
  if `parser2` matches, and returns its outputs.  For example, `choose(parseInt, matched("None"))`
  either matches a string of digits (and returns a number) or matches the sequence `"None"`
  (and returns that string).
* `choose(parsers)`: `parsers` is an array of parsers; tries them in order and returns the
  output of the first that matches, or fails if none do.  `choose([parser1, parser2, ...])`
  is equivalent to `choose(parser1, choose([parser2, ...]))`.
* `peek(parser)`: matches if `parser` matches (and returns its outputs), but does not consume
  any input; a subsequent parser will be matched starting from the same point this one was.
  For example, `peek(digit) + parseInt` matches an unsigned sequence of digits and returns
  the corresponding number.
* `peekNot(parser)`: matches only if `parser` does not match.  Returns no outputs, and
  consumes no input.  For example, `peekNot("0") + parseInt` matches an integer
  that does not begin with a leading zero.
* `group(parser)`: matches if `parser` matches; return a single output, which is the
  array of all outputs produced by parser.  For example, `group(1.. * matched(digit))`
  would return `[["3", "1", "4"]]` if the input began "314" (followed by a non-digit or
  the end of the input).

## Backtracking and Guards

Unlike most regular expression libraries, parsers are designed for mostly-deterministic
parsing with limited backtracking.  For example, the parser `.. * digit + matched(digit)`
can never match; the `.. * digit` part will only stop when it encounters a non-digit (or the
end of the input), so the following `matched(digit)` will always fail.  In contrast the
regular expression `"\d*(\d)"` matches one or more digits and returns the last digit
matched.

There are only a few situations in which parsing backtracks, i.e. where the failure of a
nested parser to match does not lead to the entire operation failing:

* `choose(parser1, parser2)`: if `parser1` does not match, `parser2` is attempted (on the
  same input)
* `min..max * parser`: if `parser` has matched at least `min` but fewer than `max` times
  it will be tried again, but if it fails to match the operation will proceed as if that
  attempt had not been made
* `peekNot(parser)`: if parser does not match, the rest of the operation proceeds with the
  same input that was provided to `parser`

In some cases it may be desirable to further restrict backtracking using a *guard*,
a prefix of a pattern that disables backtracking if it succeeds.  For example, suppose the
pattern `choose("$$" + digit, anyChar)` was intended to match either `$$` and a following digit,
or any other character (including an isolated `$`).  It has a bug: if it encounters a `$$` that
is not followed by a digit it should fail, but instead the second pattern will just match the
first `$`.

This bug can be fixed by using a guard on the first choice: `choose("$$" & digit, anyChar)`.
Note that

* A guarded parser is written as `guard & rest`, where both `guard` and `rest` are parsers.
* It matches the same inputs as `guard + rest`, and returns the same outputs.
* Guarded parsers can only be used in situations where backtracking may occur if the parser
  does not match, i.e. as the first argument to `choose()`, as a pattern multiplied by a range,
  or as the argument to `peekNot()`.
* If the guard matches but the rest does not match, the enclosing operation will fail rather
  than backtracking.

(When `choose()` is called with an array of parsers, all except the last element of the array
may be guarded.)

## Parsing

The most general way to match a parser against a stream is the `parse(parser, stream=)`
function, which either

* returns an array of outputs and advances `stream` (if the parser matches), or
* returns `None` and leaves `stream` unchanged (if the parser does not match).

For example:
```
// If `s` contains zero or more integers separated by spaces or tabs, returns them as an
// array of numbers; otherwise returns `None`.
function parseInts(s) {
  stream = stream(s)
  parser = .. * choose(blank, parseInt) + atEnd
  return parse(parser, stream=)
}
```

If it is an error for the parser to not match, it is usually preferable to instead call
`match(parser, stream=)`.  Not only does this save an `assert result is not None` check,
but also the error message when the parser fails to match will include more information
about where in the stream and where in the parser the problem was detected.

If the only information you need is whether the parser matched or not (not its outputs,
and not the ending stream position) then the `peek(parser, stream)` function just returns
`True` or `False`, e.g.
```
// True if all characters in `s` are whitespace.
function isAllWhitespace(s) = peek(.. * choose(blank, lineEnd) + atEnd, stream(s))
```
