#!/usr/bin/env python3
"""Generates the item patterns of AxAuctions' categories.yml from the expected classification
(material<TAB>category, written by the axauctions-category-rules e2e scenario).

Each category gets the fewest readable patterns (AxAuctions syntax: NAME, PREFIX*, *SUFFIX) that match exactly its
own items among every current item type; leftovers are listed by name. Usage: gencategories.py <tsv> > fragment.yml
"""
import sys
from collections import OrderedDict

ORDER = ["blocks", "tools", "combat", "food", "potions", "books", "spawners", "redstone", "misc"]

rows = [line.rstrip("\n").split("\t") for line in open(sys.argv[1]) if line.strip()]
category_of = {name: cat for name, cat in rows}
items = sorted(category_of)


def matches(pattern, value):
    if pattern == "*":
        return True
    start = pattern.startswith("*")
    end = pattern.endswith("*")
    core = pattern[1 if start else 0: len(pattern) - (1 if end else 0)]
    if start and end:
        return core in value
    if start:
        return value.endswith(core)
    if end:
        return value.startswith(core)
    return value == core


def candidates(name):
    out = set()
    parts = name.split("_")
    for i in range(1, len(parts)):
        tail = "_".join(parts[i:])
        out.add("*_" + tail)
        head = "_".join(parts[:i])
        out.add(head + "_*")
    # whole-word suffix without the underscore, e.g. *BUCKET also covers BUCKET itself
    out.add("*" + parts[-1])
    return out


result = OrderedDict()
for cat in ORDER:
    target = {n for n in items if category_of[n] == cat}
    pool = set()
    for n in target:
        pool |= candidates(n)
    valid = {}
    for p in pool:
        hit = {n for n in items if matches(p, n)}
        if hit and hit <= target:
            valid[p] = hit
    chosen = []
    uncovered = set(target)
    while True:
        best = None
        for p, hit in valid.items():
            gain = len(hit & uncovered)
            if gain < 2:
                continue
            key = (gain, -len(p), p)
            if best is None or key > best[0]:
                best = (key, p)
        if best is None:
            break
        p = best[1]
        chosen.append(p)
        uncovered -= valid[p]
    exact = sorted(uncovered)
    result[cat] = sorted(chosen, key=lambda p: (p.lstrip("*"), p)) + exact
    # every item of the category is matched, nothing else is
    for n in items:
        assert any(matches(p, n) for p in result[cat]) == (n in target), (cat, n)

for cat, patterns in result.items():
    sys.stderr.write("%s: %d items, %d patterns\n" % (cat, sum(1 for n in items if category_of[n] == cat), len(patterns)))
    print(cat)
    for p in patterns:
        print("  " + p)
