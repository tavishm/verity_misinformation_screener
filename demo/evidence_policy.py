"""Conservative scope for official documents; this gate only abstains.

Official publication establishes an official act or attributed wording. It does
not independently verify every factual assertion in political rhetoric.
"""
import re


def official_claim_type(text, evidence):
    if text.startswith('The document titled "') and '" states: "' in text:
        return 'attribution_only'
    if not evidence or not all(str(s.get('source_kind','')).startswith('official_') for s in evidence):
        return 'other_sources'
    attributed = re.search(r'\baccording to\b|\b(?:document|notice|rule|determination|department|president|proclamation|report)\b.{0,350}?\b(?:says|said|states|stated|reports|reported|announced)\b',text,re.I)
    official_act = re.search(r'\b(?:notice|order|rule|determination|department|president|congress|proclamation)\b.{0,100}?\b(?:signed|issued|amend\w*|publish\w*|identif\w*|list\w*|continu\w*|enact\w*|designat\w*|remov\w*|add\w*|declar\w*|requires?|authoriz\w*|establish\w*|rescind\w*|extend\w*)\b',text,re.I)
    judgment = re.search(r'\b(?:chaos|best|worst|public interests?|better serve|would|caused|causes|because|vow|solemn)\b',text,re.I)
    if official_act and not judgment:
        return 'official_act'
    if attributed:
        return 'attribution_only'
    return 'outside_scope'


def official_scope_gap(text, evidence):
    if official_claim_type(text,evidence)!='outside_scope':
        return None
    return 'This official document alone establishes its stated position, not independent proof of this broader assertion. An attributed claim or additional evidence is needed.'


def contradiction_scope_gap(text, evidence):
    if not evidence or not all(str(s.get('source_kind','')).startswith('official_') for s in evidence):
        return None
    identified=re.search(r'\b(?:19|20)\d{2}\b|\b(?:order|bill|proclamation|case|resolution)\s+(?:[A-Z.]+\s*)?\d+\b',text,re.I)
    if identified or text.startswith('The document titled "'):
        return None
    return 'A different action in one official document does not refute this unanchored claim. Its event, date or document needs to be identified first.'
