#!/usr/bin/env python3
"""Illustrative research costs, not an estimate of Internet size or feed coverage.

No API calls, dependencies, model training, or changes to the live checker.
Rates: official pricing/catalog checked 2026-09-19. Provider prices may change.
"""
import argparse
import json
from decimal import Decimal as D

RATES = {'deepseek-v3.2': (D('0.269'), D('0.40')), 'sonnet-4.6': (D('3'), D('15'))}


def nonnegative(value):
    number = int(value)
    if number < 0:
        raise argparse.ArgumentTypeError('Must be nonnegative.')
    return number


def share(value):
    number = D(value)
    if not number.is_finite() or not D(0) <= number <= D(1):
        raise argparse.ArgumentTypeError('A fraction from 0 to 1 is required.')
    return number


def estimate(initial=10000, daily=1000, initial_x_reads=0, daily_x_reads=0,
             searches=6, input_tokens=20000, output_tokens=3000,
             paid_search_share=D('0.2'), paid_model_share=D('0.05')):
    results = {}
    for name, (input_rate, output_rate) in RATES.items():
        tokens = (D(input_tokens) * input_rate + D(output_tokens) * output_rate) / D(1000000)
        search = D(searches) * D('0.005')
        attempt = (tokens * D(paid_model_share) + search * D(paid_search_share)) * D('1.25')
        results[name] = {
            'research_attempt_usd': float(attempt),
            'initial_research_subtotal_usd': float(D(initial) * attempt),
            'daily_research_subtotal_usd': float(D(daily) * attempt),
            'initial_x_read_subtotal_usd': float(D(initial_x_reads) * D('0.005')),
            'daily_x_read_subtotal_usd': float(D(daily_x_reads) * D('0.005')),
            'initial_research_plus_x_subtotal_usd': float(D(initial) * attempt + D(initial_x_reads) * D('0.005')),
            'daily_research_plus_x_subtotal_usd': float(D(daily) * attempt + D(daily_x_reads) * D('0.005')),
        }
    return {'illustrative_only': True, 'all_topic_size_established': False, 'coverage_established': False,
            'paid_search_share_assumption': float(paid_search_share),
            'paid_model_share_assumption': float(paid_model_share),
            'costs_exclude': ['data/content licenses', 'local energy/hardware', 'hosting/storage/delivery',
                             'media verification', 'human review', 'development/operations', 'tax/account fees'],
            'x_reads_are_reference_pricing_not_enterprise_quote': True,
            'x_30_day_reads_above_self_serve_limit': daily_x_reads * 30 > 3000000,
            'x_31_day_reads_above_self_serve_limit': daily_x_reads * 31 > 3000000,
            'models': results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--initial', type=nonnegative, default=10000)
    parser.add_argument('--daily', type=nonnegative, default=1000)
    parser.add_argument('--initial-x-reads', type=nonnegative, default=0)
    parser.add_argument('--daily-x-reads', type=nonnegative, default=0)
    parser.add_argument('--searches', type=nonnegative, default=6)
    parser.add_argument('--input-tokens', type=nonnegative, default=20000)
    parser.add_argument('--output-tokens', type=nonnegative, default=3000,
                        help='Total billed output, including reasoning and all review calls.')
    parser.add_argument('--paid-search-share', type=share, default=D('0.2'),
                        help='Assumed fraction requiring six paid searches; not a measured rate.')
    parser.add_argument('--paid-model-share', type=share, default=D('0.05'),
                        help='Assumed fraction requiring a paid review after local processing.')
    print(json.dumps(estimate(**vars(parser.parse_args())), indent=2))


if __name__ == '__main__':
    main()
