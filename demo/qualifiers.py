"""Conservative support vetoes; matching qualifiers never proves entailment.

These guards only downgrade a model's supported verdict. They cannot publish a
true/false verdict and do not replace source assessment or language evaluation.
"""
from datetime import date
from decimal import Decimal, InvalidOperation
import re
import unicodedata

MONTHS = [
    ('january', 'jan', 'जनवरी'), ('february', 'feb', 'फ़रवरी', 'फरवरी'),
    ('march', 'mar', 'मार्च'), ('april', 'apr', 'अप्रैल'), ('may', 'मई'),
    ('june', 'jun', 'जून'), ('july', 'jul', 'जुलाई'), ('august', 'aug', 'अगस्त'),
    ('september', 'sept', 'sep', 'सितंबर', 'सितम्बर'),
    ('october', 'oct', 'अक्टूबर'), ('november', 'nov', 'नवंबर', 'नवम्बर'),
    ('december', 'dec', 'दिसंबर', 'दिसम्बर'),
]


def normalize(text):
    output = []
    for char in unicodedata.normalize('NFKC', text).casefold():
        try:
            output.append(str(unicodedata.decimal(char)))
        except (ValueError, TypeError):
            output.append(char)
    return ''.join(output)


def numbers(text):
    values = set()
    for raw in re.findall(r'(?<!\w)\d[\d,]*(?:\.\d+)?', normalize(text)):
        try:
            values.add(Decimal(raw.replace(',', '')))
        except InvalidOperation:
            pass
    return values


def dates(text):
    value = normalize(text)
    found = set()
    for month, names in enumerate(MONTHS, 1):
        words = '|'.join(re.escape(normalize(name)) for name in names)
        patterns = [
            (rf'(?<!\d)(\d{{1,2}})(?:st|nd|rd|th)?\s+(?:{words})\.?[,]?\s+(\d{{4}})(?!\d)', False),
            (rf'(?<!\w)(?:{words})\.?\s+(\d{{1,2}})(?:st|nd|rd|th)?[,]?\s+(\d{{4}})(?!\d)', False),
        ]
        for pattern, _ in patterns:
            for day, year in re.findall(pattern, value):
                try:
                    found.add(date(int(year), month, int(day)))
                except ValueError:
                    pass
    for year, month, day in re.findall(r'(?<!\d)(\d{4})-(\d{1,2})-(\d{1,2})(?!\d)', value):
        try:
            found.add(date(int(year), int(month), int(day)))
        except ValueError:
            pass
    return found


def support_gap(claim, quotes):
    """Return an abstention reason if a literal qualifier lacks quoted support.

    Strict matching intentionally abstains on numerical derivations and alternate
    unit/date encodings it cannot establish. A calculator/evidence route can later
    handle these; this demo must not invent support to increase coverage.
    """
    evidence = '\n'.join(quotes)
    if quantities(claim) - quantities(evidence):
        return 'The cited passages do not establish every amount with its stated unit or duration.'
    if dates(claim) - dates(evidence):
        return 'The cited passages do not establish every explicit date in this claim.'
    if numbers(claim) - numbers(evidence):
        return 'The cited passages do not establish every explicit number in this claim.'
    return None


def quantities(text):
    """Literal number+unit pairs, including simple spelled-out English amounts.

    This only rejects missing matches; it cannot establish truth. It deliberately
    does not derive amounts or infer which event a matching number refers to.
    """
    words = dict(zip('zero one two three four five six seven eight nine ten eleven twelve'.split(),range(13)))
    value = normalize(text)
    value = re.sub(r'\b('+'|'.join(words)+r')\b',lambda m:str(words[m.group()]),value)
    found = set()
    pattern = r'(?<!\w)(\d[\d,]*(?:\.\d+)?)\s*[-–]?\s*(years?|months?|weeks?|days?|hours?|minutes?|seconds?|dollars?|rupees?|percent|percentage|%)\b'
    for amount,unit in re.findall(pattern,value):
        try:
            found.add((Decimal(amount.replace(',','')),unit.rstrip('s')))
        except InvalidOperation:
            pass
    return found


def needs_author_context(text):
    if text.startswith('The document titled "') and '" states: "' in text:
        return False  # Explicit source attribution; exact quotes have a separate verifier.
    return bool(re.search(r'\b(?:I|we|my|our|mine|ours)\b|^\s*(?:He|She|They|His|Her|Their)\b',text,re.I))


def sentence_claims(post):
    """Keep original assertions instead of trusting a generated paraphrase.

    Sentence boundaries are a conservative initial unit. Long/complex statements
    still need deeper decomposition; this does not certify claim completeness.
    """
    # Abbreviations such as U.S. and Mr. are not independent assertions. Keep
    # offsets into the original text instead of normalizing the checked text.
    spans, start = [], 0
    for boundary in re.finditer(r'(?<=[.!?।॥])\s+', post.strip()):
        prefix = post.strip()[start:boundary.start()]
        if re.search(r'(?:\b(?:[A-Za-z]\.){2,}|\b(?:Mr|Mrs|Ms|Dr|Sen|Rep|Gov|Jr|Sr|vs|No)\.)$', prefix):
            continue
        spans.append(prefix.strip())
        start = boundary.end()
    tail = post.strip()[start:]
    if tail:
        spans.append(tail)
    return spans
