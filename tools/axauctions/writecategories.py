#!/usr/bin/env python3
"""Writes AxAuctions' categories.yml from the generated pattern fragment. Usage: writecategories.py <fragment> <out>"""
import sys

NAMES = {
    "blocks": "blocks", "tools": "tools", "combat": "combat", "food": "food", "potions": "potions",
    "books": "books", "spawners": "spawners", "redstone": "redstone", "misc": "everything else",
}

HEADER = """# DOCUMENTATION: https://docs.artillex-studios.com/axauctions.html
#
# some supported regex: (optional)
# - 'name' #  only 'name' is disallowed
# - 'name*' #  disallows text starting with 'name'
# - '*name' #  disallows text ending with 'name'
# - '*name*' #  disallows text containing 'name'
#
# you can use some sections from our item builder: (https://docs.artillex-studios.com/item-builder.html)
#  > name
#  > material / type
#  > custom-model-data (only the old variant)
#
# note: category items are always matched with the "OR" logic gate, which means that if any of the "items" sections match the item, it will be part of the category
#
# you should not include color codes as the plugin removes them before checking for matches,
# instead you should put names like this: "name: '*Name*'" meaning that if the item name contains the name, it will match it
# and of course, every field is case-sensitive!
#
# SiftVanilla: the categories of SiftCore's own auction house (blocks, tools, combat, food, potions, books,
# spawners, everything else) plus redstone. Every sellable item is in exactly one of them. The patterns are
# GENERATED for the 26.2 item list and checked by the axauctions-category-rules e2e scenario (tools/e2e); after a
# Minecraft update, run that scenario and regenerate them (docs/features/auction.md explains how).
# The names are lower case because the menus show them mid-sentence ("Showing blocks").

# should the category system be enabled?
# note: the category selector item is disabled by default, you need to uncomment the section as well (in the main-gui.yml and my-items.yml) to make it work!
# restarting the server is recommended after changing this
enabled: true

# the "all" category just matches all materials
all:
  # formatted name of the category, can contain colors
  name: "all items"
  items:
    - material: "*"
"""

fragment = open(sys.argv[1]).read().splitlines()
out = [HEADER]
current = None
for line in fragment:
    if not line.startswith("  "):
        current = line.strip()
        out.append("\n%s:\n  name: \"%s\"\n  items:\n" % (current, NAMES[current]))
    else:
        out.append("    - material: \"%s\"\n" % line.strip())
out.append("\n# do not change this\nversion: 1\n")
open(sys.argv[2], "w").write("".join(out))
