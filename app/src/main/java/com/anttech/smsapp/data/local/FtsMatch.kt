package com.anttech.smsapp.data.local

/**
 * The runs of letters and digits in a query. Those are the only parts of it that
 * name something the index holds; everything else is punctuation, which is a
 * separator to the tokenizer and so cannot be searched for anyway.
 */
private val FTS_TOKEN = Regex("[\\p{L}\\p{N}]+")

/**
 * Builds the FTS4 MATCH expression for a raw search query, or null when the
 * query contains nothing searchable.
 *
 * The raw text cannot go to MATCH as it stands. FTS parses its argument as a
 * little query language in which `"` and `-` are syntax and a bare uppercase
 * `AND`/`OR`/`NOT` is an operator, so `MATCH 'OR'` is a syntax error rather than
 * a search for the word "or": passing the user's keystrokes straight through
 * would turn an ordinary query into a thrown exception. The query is therefore
 * reduced to runs of letters and digits, and every run except the last is
 * wrapped in double quotes, which makes FTS read it as a literal phrase and
 * keeps a token like `AND` from being taken as an operator.
 *
 * The last run keeps a trailing `*` so it matches as a prefix, which is what
 * lets results narrow while a word is still being typed, and the `*` also stops
 * that run from being read as an operator, so the final term needs no quoting.
 * Space between terms is FTS's implicit AND, so every word has to be present
 * somewhere in the body - not necessarily adjacent, which the whole-query LIKE
 * this replaces did require.
 */
internal fun ftsMatchExpression(query: String): String? {
    val tokens = FTS_TOKEN.findAll(query).map { it.value }.toList()
    if (tokens.isEmpty()) return null
    return tokens.mapIndexed { index, token ->
        if (index == tokens.lastIndex) "$token*" else "\"$token\""
    }.joinToString(" ")
}
