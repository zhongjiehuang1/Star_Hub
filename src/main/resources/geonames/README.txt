GeoNames city gazetteer (local derived index)

Source: https://download.geonames.org/export/dump/cities500.zip
Source archive SHA-256: 750455a31c0690d643455a64f5c812c35cf9d3ad083eb497a22113b64e5eb2c9
Snapshot: 2026-11-17. cities500 includes populated places over 500 people and seats
of administrative divisions. It does not provide administrative boundaries.
Administrative names: https://download.geonames.org/export/dump/admin1CodesASCII.txt

Data: GeoNames, https://www.geonames.org/, licensed under Creative Commons
Attribution 4.0 International, https://creativecommons.org/licenses/by/4.0/ .
These files derive and sort a subset of the GeoNames fields and names; names are
limited to Chinese and Latin spellings, and queries are exact-match only.
GeoNames data is supplied without warranty of accuracy or completeness.

To update, download both source files, run scripts/build-geonames-index.py,
replace admin1-codes.txt, update CityLookup.INDEX_SIZE and RECORDS_SIZE from the printed sizes,
update this snapshot and source SHA-256, and run CityLookupTest and the
restricted-heap CityLookupMemoryHarness before deployment.
